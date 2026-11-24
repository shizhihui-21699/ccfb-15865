package com.example.tv.paygate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RiskEngineTest {

    private val engine = RiskEngine()
    private val t0 = 1_000_000_000_000L

    private fun payment(
        amountFen: Long = 29_00L,
        age: AgeBand = AgeBand.ADULT,
        kind: PaymentKind = PaymentKind.VOD,
        counters: SpendingCounters = SpendingCounters(0, 0, 0, 0),
        trust: TrustSession? = null,
    ) = PaymentContext("sku_1", amountFen, kind, "p1", age, t0, counters, trust)

    @Test
    fun `儿童档案直接拦截，不进入任何验证因素`() {
        val d = engine.evaluate(payment(age = AgeBand.CHILD))
        assertEquals(GateAction.BLOCK, d.action)
        assertTrue(d.acceptedFactors.isEmpty())
    }

    @Test
    fun `青少年档案同样拦截`() {
        assertEquals(GateAction.BLOCK, engine.evaluate(payment(age = AgeBand.TEEN)).action)
    }

    @Test
    fun `成人小额走标准确认，四因素可用但不含 PIN 单独放行`() {
        val d = engine.evaluate(payment())
        assertEquals(GateAction.VERIFY_STANDARD, d.action)
        assertEquals(PayGateLimits.STANDARD_FACTORS, d.acceptedFactors)
        assertTrue(VerifyFactor.PIN_AS_SECOND_FACTOR !in d.acceptedFactors)
    }

    @Test
    fun `大额只接受手机端生物识别因素，短信和语音被降级排除`() {
        val d = engine.evaluate(payment(amountFen = 300_00L))
        assertEquals(GateAction.VERIFY_HIGH_RISK, d.action)
        assertTrue(VerifyFactor.SMS_OTP !in d.acceptedFactors)
        assertTrue(VerifyFactor.VOICE_CHALLENGE !in d.acceptedFactors)
        assertTrue(VerifyFactor.PHONE_PUSH in d.acceptedFactors)
    }

    @Test
    fun `高频消费（当月第10笔起）同样升高强度`() {
        val d = engine.evaluate(
            payment(counters = SpendingCounters(0, 0, 100_00L, 10)),
        )
        assertEquals(GateAction.VERIFY_HIGH_RISK, d.action)
    }

    @Test
    fun `信任窗口内小额免确认，但游戏充值不享受`() {
        val trust = TrustSession(t0 - 60_000L, PayGateLimits.TRUST_MAX_AMOUNT_FEN, "p1")
        assertEquals(GateAction.ALLOW, engine.evaluate(payment(trust = trust)).action)
        val game = payment(kind = PaymentKind.GAME_RECHARGE, trust = trust)
        assertEquals(GateAction.VERIFY_STANDARD, engine.evaluate(game).action)
    }

    @Test
    fun `过期的信任窗口不允许放行`() {
        val trust = TrustSession(t0 - PayGateLimits.TRUST_WINDOW_MILLIS - 1, 100_00L, "p1")
        assertEquals(GateAction.VERIFY_STANDARD, engine.evaluate(payment(trust = trust)).action)
    }

    @Test
    fun `超出单笔窗口额度直接拦截`() {
        val d = engine.evaluate(
            payment(counters = SpendingCounters(last1hFen = 490_00L, last24hFen = 0, monthlyFen = 0, monthlyCount = 0)),
        )
        assertEquals(GateAction.BLOCK, d.action)
    }

    @Test
    fun `信任窗口内但金额超上限仍需验证`() {
        val trust = TrustSession(t0, maxAmountFen = 50_00L, scopeProfileId = "p1")
        assertEquals(
            GateAction.VERIFY_STANDARD,
            engine.evaluate(payment(amountFen = 99_00L, trust = trust)).action,
        )
    }
}

class ThrottleTest {

    private val t0 = 1_000_000L

    @Test
    fun `失败退避阶梯递增，成功清零`() {
        val t = AttemptThrottle()
        assertEquals(15L, t.onFailure(t0).remainingLockSeconds(t0))
        t.onFailure(t0); t.onFailure(t0)
        assertEquals(60L, t.onFailure(t0).remainingLockSeconds(t0))
        t.onSuccess()
        assertEquals(0, t.current(t0).consecutiveFailures)
    }

    @Test
    fun `十次失败锁一天`() {
        val t = AttemptThrottle()
        repeat(9) { t.onFailure(t0) }
        val s = t.onFailure(t0)
        assertEquals(24 * 3600L, s.remainingLockSeconds(t0))
    }
}
