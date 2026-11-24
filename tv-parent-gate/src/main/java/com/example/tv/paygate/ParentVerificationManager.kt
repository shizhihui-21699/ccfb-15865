package com.example.tv.paygate

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** 确认流程的对外结果，UI 只需要按状态渲染，不参与安全判断。 */
sealed class VerifyResult {
    data class Approved(
        val approvalToken: String,
        val trustSession: TrustSession?,
    ) : VerifyResult()

    data class Rejected(val message: String) : VerifyResult()
    data class Locked(val lockSeconds: Long) : VerifyResult()
    data object Cancelled : VerifyResult()
    data class Error(val message: String) : VerifyResult()
}

/**
 * 确认流程编排：风险判定 → 建 challenge → 因素交互 → 轮询/提交 → 颁发信任窗口。
 * UI（Fragment）通过本类发起，所有节流、轮询节奏、token 处理都收敛在这里。
 */
class ParentVerificationManager(
    private val api: PayGateApi,
    private val riskEngine: RiskEngine,
    private val now: () -> Long,
    private val deviceId: String,
) {
    private val throttle = AttemptThrottle()

    /** 第一步：风险判定。BLOCK/ALLOW 不需要弹确认框。 */
    fun decide(payment: PaymentContext): GateDecision = riskEngine.evaluate(payment)

    /**
     * 第二步：对需要确认的支付发起挑战。
     * @param factor 用户在确认页选择的因素，必须在 decision.acceptedFactors 内，否则拒绝。
     */
    suspend fun startChallenge(
        payment: PaymentContext,
        decision: GateDecision,
        factor: VerifyFactor,
        paymentId: String,
    ): ChallengeDto {
        require(factor in decision.acceptedFactors) {
            "因素 $factor 不在本次支付允许列表中（高强度场景禁止降级为短信/语音）"
        }
        return api.createChallenge(
            CreateChallengeReq(paymentId, factor, deviceId),
        )
    }

    /** PHONE_PUSH / QR_SCAN：TV 端只负责轮询，秘密完全不经过电视。 */
    suspend fun awaitExternalApproval(
        challengeId: String,
        ttlSeconds: Int,
        profileId: String,
        onTick: (remainingSeconds: Int) -> Unit,
    ): VerifyResult {
        val deadline = now() + ttlSeconds * 1000L
        while (true) {
            val remaining = ((deadline - now()) / 1000L).toInt()
            if (remaining <= 0) return VerifyResult.Rejected("确认超时，请重新发起")
            onTick(remaining)

            val status = runCatching { api.getStatus(challengeId) }
                .getOrElse { delay(POLL_INTERVAL); continue }
            when (status.state) {
                ChallengeState.APPROVED ->
                    return VerifyResult.Approved(
                        status.approvalToken.orEmpty(),
                        newTrustSession(profileId),
                    )
                ChallengeState.REJECTED ->
                    return VerifyResult.Rejected("家长在手机上拒绝了本次付费")
                ChallengeState.EXPIRED ->
                    return VerifyResult.Rejected("二维码/请求已过期，请重新发起")
                ChallengeState.FAILED_RATE_LIMITED ->
                    return VerifyResult.Locked(0)
                ChallengeState.PENDING -> delay(POLL_INTERVAL)
            }
        }
    }

    /** SMS_OTP / VOICE_CHALLENGE：提交型因素，带本地节流（服务端还有一份权威计数）。 */
    suspend fun submit(
        challengeId: String,
        profileId: String,
        submit: suspend () -> ChallengeStatusDto,
    ): VerifyResult {
        val state = throttle.current(now())
        if (state.locked(now())) {
            return VerifyResult.Locked(state.remainingLockSeconds(now()))
        }
        val status = try {
            submit()
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            return VerifyResult.Error("网络异常，请重试：${e.message}")
        }
        return when (status.state) {
            ChallengeState.APPROVED -> {
                throttle.onSuccess()
                VerifyResult.Approved(status.approvalToken.orEmpty(), newTrustSession(profileId))
            }
            ChallengeState.FAILED_RATE_LIMITED -> {
                val locked = throttle.onFailure(now())
                VerifyResult.Locked(locked.remainingLockSeconds(now()))
            }
            else -> {
                val locked = throttle.onFailure(now())
                if (locked.locked(now())) {
                    VerifyResult.Locked(locked.remainingLockSeconds(now()))
                } else {
                    val remainingAttempts = maxOf(0, 3 - locked.consecutiveFailures % 3)
                    VerifyResult.Rejected(
                        status.failHint ?: "验证失败，还可尝试 $remainingAttempts 次",
                    )
                }
            }
        }
    }

    suspend fun submitOtp(challengeId: String, profileId: String, code: String): VerifyResult =
        submit(challengeId, profileId) { api.submitOtp(challengeId, SubmitOtpReq(code)) }

    suspend fun submitVoice(
        challengeId: String,
        profileId: String,
        audio: okhttp3.MultipartBody.Part,
    ): VerifyResult =
        submit(challengeId, profileId) { api.submitVoice(challengeId, audio) }

    /** 确认成功后落地短时信任窗口；游戏充值不下发（RiskEngine 已排除，双保险）。 */
    private fun newTrustSession(profileId: String): TrustSession =
        TrustSession(now(), PayGateLimits.TRUST_MAX_AMOUNT_FEN, profileId)

    companion object {
        private const val POLL_INTERVAL = 2_000L
    }

    /** 页面退出时作废未完成的 challenge，防止悬挂的确认请求被他人事后批准。 */
    suspend fun cancel(challengeId: String?) {
        if (challengeId != null) runCatching { api.cancelChallenge(challengeId) }
    }
}
