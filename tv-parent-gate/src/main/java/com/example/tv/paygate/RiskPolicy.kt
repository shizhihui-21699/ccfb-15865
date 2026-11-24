package com.example.tv.paygate

/**
 * 一次待确认的付费操作上下文。所有金额单位：分。
 */
data class PaymentContext(
    val sku: String,
    val amountFen: Long,
    val kind: PaymentKind,
    val profileId: String,
    val profileAgeBand: AgeBand,
    val nowMillis: Long,
    val counters: SpendingCounters,
    val trustSession: TrustSession? = null,
)

enum class PaymentKind { MEMBERSHIP, VOD, GAME_RECHARGE, OTHER }

enum class AgeBand { ADULT, TEEN, CHILD, UNKNOWN }

/** 服务端按账号统计的滚动窗口累计消费，TV 端只读，不能本地篡改。 */
data class SpendingCounters(
    val last1hFen: Long,
    val last24hFen: Long,
    val monthlyFen: Long,
    val monthlyCount: Int,
)

enum class GateAction {
    /** 直接放行（仍在短时信任窗口内的小额操作） */
    ALLOW,

    /** 硬拦截：儿童档案不允许发起购买，或超出家长设定的额度 */
    BLOCK,

    /** 标准确认：任一可用因素通过即可 */
    VERIFY_STANDARD,

    /** 高强度确认：只接受手机端生物识别类因素，短信/语音单独不够 */
    VERIFY_HIGH_RISK,
}

data class GateDecision(
    val action: GateAction,
    val acceptedFactors: List<VerifyFactor>,
    val reason: String,
)

/**
 * 确认因素。设计要点：
 * - 没有任何"在电视屏幕上输入的静态密码"作为独立因素——四位 PIN 已被肩窥攻破。
 * - 秘密只出现在家长手机上（推送/扫码/生物识别），电视屏幕上不出现可被旁观的秘密。
 * - PIN 即使保留，也只能作为多因素中的第二因素（[VerifyFactor.PIN_AS_SECOND_FACTOR]）。
 */
enum class VerifyFactor {
    /** 家长手机 App/小程序收到推送，在手机上用指纹/面容确认（主推，老人也只需按一下） */
    PHONE_PUSH,

    /** 电视展示二维码，家长用手机扫码后在手机端确认（不要求同 Wi-Fi、不要求装 App，可走小程序） */
    QR_SCAN,

    /** 手机收短信 OTP，电视端用遥控器数字键输入（兜底，6 位随机、一次性、2 分钟过期） */
    SMS_OTP,

    /** 电视 TTS 朗读随机私密问题，遥控器按住说话回答（无手机场景兜底，答案不落屏） */
    VOICE_CHALLENGE,

    /** 迁移期兼容：PIN 只能与上面任一因素组合，绝不单独放行 */
    PIN_AS_SECOND_FACTOR,
}

/** 全部阈值集中在一处，灰度调参和按家庭下发配置都改这里/由服务端覆盖。 */
object PayGateLimits {
    /** 一次确认通过后的短时信任窗口：窗口内小额操作免再确认 */
    const val TRUST_WINDOW_MILLIS = 5 * 60_000L
    const val TRUST_MAX_AMOUNT_FEN = 100_00L // 100 元

    const val HIGH_RISK_AMOUNT_FEN = 300_00L // 单笔 ≥300 元升高强度
    const val HOUR_CAP_FEN = 500_00L
    const val DAY_CAP_FEN = 2_000_00L
    const val MONTH_CAP_FEN = 8_000_00L
    const val MONTH_COUNT_CAP = 30
    const val HIGH_FREQUENCY_COUNT = 10 // 当月第 10 笔起视为高频

    val STANDARD_FACTORS = listOf(
        VerifyFactor.PHONE_PUSH,
        VerifyFactor.QR_SCAN,
        VerifyFactor.SMS_OTP,
        VerifyFactor.VOICE_CHALLENGE,
    )

    /** 高强度场景只接受"手机本机生物识别"这一持有+固有因素。 */
    val HIGH_RISK_FACTORS = listOf(
        VerifyFactor.PHONE_PUSH,
        VerifyFactor.QR_SCAN,
    )
}

/**
 * 纯 Kotlin 风险判定，不依赖 Android Framework，便于单测。
 * 规则刻意保持简单可解释——被拦的家长需要一眼看懂为什么被拦。
 */
class RiskEngine {

    fun evaluate(ctx: PaymentContext): GateDecision {
        // 1. 儿童/青少年档案根本不允许发起付费，从入口掐断，而不是靠密码拦
        if (RiskEngine.KID_PROFILES_BLOCK &&
            (ctx.profileAgeBand == AgeBand.CHILD || ctx.profileAgeBand == AgeBand.TEEN)
        ) {
            return GateDecision(
                GateAction.BLOCK,
                emptyList(),
                "当前为儿童/青少年档案，请切换到家长档案后再购买",
            )
        }

        // 2. 额度硬顶：超出直接拦，引导去家长手机端调整，电视端无法自行提额
        with(ctx.counters) {
            if (last1hFen + ctx.amountFen > PayGateLimits.HOUR_CAP_FEN)
                return block("一小时内累计消费将超过家长设定的 500 元上限")
            if (last24hFen + ctx.amountFen > PayGateLimits.DAY_CAP_FEN)
                return block("今日累计消费将超过家长设定的 2000 元上限")
            if (monthlyFen + ctx.amountFen > PayGateLimits.MONTH_CAP_FEN)
                return block("本月累计消费将超过家长设定的 8000 元上限")
            if (monthlyCount >= PayGateLimits.MONTH_COUNT_CAP)
                return block("本月购买次数已达上限（30 次）")
        }

        // 3. 短时信任窗口：小额、非游戏充值时免再确认，解决"买一集剧也要验证"的摩擦
        val trust = ctx.trustSession
        if (trust != null && trust.validFor(ctx.nowMillis, ctx.amountFen) &&
            ctx.kind != PaymentKind.GAME_RECHARGE // 游戏充值是重灾区，不享受信任窗口
        ) {
            return GateDecision(GateAction.ALLOW, emptyList(), "处于本次确认的 5 分钟信任窗口内")
        }

        // 4. 高风险：大额或高频，强制手机端生物识别
        val highRisk = ctx.amountFen >= PayGateLimits.HIGH_RISK_AMOUNT_FEN ||
            ctx.counters.monthlyCount >= PayGateLimits.HIGH_FREQUENCY_COUNT
        return if (highRisk) {
            GateDecision(
                GateAction.VERIFY_HIGH_RISK,
                PayGateLimits.HIGH_RISK_FACTORS,
                "大额或高频消费，需要家长在手机上完成指纹/面容确认",
            )
        } else {
            GateDecision(
                GateAction.VERIFY_STANDARD,
                PayGateLimits.STANDARD_FACTORS,
                "请家长本人确认本次付费",
            )
        }
    }

    private fun block(reason: String) =
        GateDecision(GateAction.BLOCK, emptyList(), reason)

    companion object {
        const val KID_PROFILES_BLOCK = true
    }
}
