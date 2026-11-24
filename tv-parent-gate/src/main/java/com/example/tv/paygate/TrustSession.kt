package com.example.tv.paygate

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 一次家长确认通过后颁发的短时信任窗口。
 * 仅对小额、非游戏充值生效；窗口和金额上限在 [PayGateLimits] 集中管理。
 */
data class TrustSession(
    val issuedAtMillis: Long,
    val maxAmountFen: Long,
    val scopeProfileId: String,
) {
    fun validFor(nowMillis: Long, amountFen: Long): Boolean =
        nowMillis - issuedAtMillis <= PayGateLimits.TRUST_WINDOW_MILLIS &&
            amountFen <= maxAmountFen

    fun expired(nowMillis: Long) =
        nowMillis - issuedAtMillis > PayGateLimits.TRUST_WINDOW_MILLIS
}

/**
 * 信任会话的本地持久化。用 EncryptedSharedPreferences（AndroidX Security），
 * 防止 root 之外的本地篡改；真正的消费统计与额度永远在服务端，本地只缓存"这次会话"。
 */
class TrustStore(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "paygate_trust",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun save(session: TrustSession, profileId: String) {
        prefs.edit()
            .putLong("${profileId}_issued_at", session.issuedAtMillis)
            .putLong("${profileId}_max_amount", session.maxAmountFen)
            .apply()
    }

    fun load(profileId: String, nowMillis: Long): TrustSession? {
        val issuedAt = prefs.getLong("${profileId}_issued_at", 0L)
        if (issuedAt == 0L) return null
        val session = TrustSession(
            issuedAtMillis = issuedAt,
            maxAmountFen = prefs.getLong("${profileId}_max_amount", 0L),
            scopeProfileId = profileId,
        )
        if (session.expired(nowMillis)) {
            clear(profileId)
            return null
        }
        return session
    }

    fun clear(profileId: String) {
        prefs.edit().remove("${profileId}_issued_at").remove("${profileId}_max_amount").apply()
    }
}

/**
 * 失败退避：防止孩子在电视端对 OTP/语音等可交互因素做暴力枚举。
 * 计数以服务端为准（换设备、清数据都绕不过），本地这份只做即时 UI 反馈。
 */
class AttemptThrottle {

    data class State(val consecutiveFailures: Int, val lockedUntilMillis: Long) {
        fun locked(nowMillis: Long) = nowMillis < lockedUntilMillis
        fun remainingLockSeconds(nowMillis: Long): Long =
            if (locked(nowMillis)) (lockedUntilMillis - nowMillis + 999) / 1000 else 0L
    }

    private var state = State(0, 0L)

    /** 服务端下发的阶梯：15s → 60s → 5min → 30min → 24h；连续 10 次失败触发人工风控。 */
    fun onFailure(nowMillis: Long): State {
        val fails = state.consecutiveFailures + 1
        val lockMillis = when {
            fails >= 10 -> 24 * 3_600_000L
            fails >= 7 -> 30 * 60_000L
            fails >= 5 -> 5 * 60_000L
            fails >= 3 -> 60_000L
            else -> 15_000L
        }
        state = State(fails, nowMillis + lockMillis)
        return state
    }

    fun onSuccess() {
        state = State(0, 0L)
    }

    /** 锁定期结束后保留失败计数（继续尝试会进下一档），只有成功才清零。 */
    fun current(nowMillis: Long): State =
        if (state.lockedUntilMillis != 0L && nowMillis >= state.lockedUntilMillis) {
            state.copy(lockedUntilMillis = 0L).also { state = it }
        } else {
            state
        }
}
