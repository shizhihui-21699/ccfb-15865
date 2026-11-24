package com.example.tv.paygate

import okhttp3.MultipartBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path

/**
 * 家长确认闸门的服务端契约。TV 端永远是"发起方/展示方"，
 * 真正的鉴权、OTP 校验、语音比对、额度统计全部在服务端完成。
 *
 * 安全要点：
 * - 每次确认对应一个一次性、短 TTL（建议 120s）的 challengeId。
 * - 确认通过后服务端返回一次性 approvalToken，TV 端凭它调下单接口；
 *   token 与 paymentId、设备、账号绑定且只能用一次，重放无效。
 * - OTP 只走服务端短信通道，TV 端不接触发送逻辑，也拿不到完整手机号（只回掩码）。
 */
interface PayGateApi {

    @POST("paygate/challenges")
    suspend fun createChallenge(@Body req: CreateChallengeReq): ChallengeDto

    @GET("paygate/challenges/{id}/status")
    suspend fun getStatus(@Path("id") challengeId: String): ChallengeStatusDto

    @POST("paygate/challenges/{id}/otp")
    suspend fun submitOtp(
        @Path("id") challengeId: String,
        @Body req: SubmitOtpReq,
    ): ChallengeStatusDto

    @Multipart
    @POST("paygate/challenges/{id}/voice")
    suspend fun submitVoice(
        @Path("id") challengeId: String,
        @Part audio: MultipartBody.Part,
    ): ChallengeStatusDto

    @POST("paygate/challenges/{id}/cancel")
    suspend fun cancelChallenge(@Path("id") challengeId: String)
}

data class CreateChallengeReq(
    val paymentId: String,
    val factor: VerifyFactor,
    val deviceId: String,
)

data class ChallengeDto(
    val challengeId: String,
    val factor: VerifyFactor,
    val ttlSeconds: Int,
    /** PHONE_PUSH / SMS_OTP：脱敏手机号，如 138****6688，用于让家人确认是哪位家长的手机 */
    val guardianPhoneMasked: String?,
    /** QR_SCAN：小程序/ App 的深链内容，TV 端用 ZXing 编码成二维码展示 */
    val qrPayload: String?,
    /** VOICE_CHALLENGE：TTS 要朗读的问题文本（如"请说出你上小学时的班主任姓氏"），问题由服务端随机抽 */
    val voicePrompt: String?,
)

enum class ChallengeState { PENDING, APPROVED, REJECTED, EXPIRED, FAILED_RATE_LIMITED }

data class ChallengeStatusDto(
    val state: ChallengeState,
    /** state == APPROVED 时才有；一次性，绑定 paymentId/deviceId，60s 内有效 */
    val approvalToken: String?,
    val failHint: String?,
)

data class SubmitOtpReq(val code: String)
