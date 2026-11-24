package com.example.tv.paygate.tv

import android.os.Bundle
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.example.tv.paygate.GateAction
import com.example.tv.paygate.GateDecision
import com.example.tv.paygate.ParentGateFragment
import com.example.tv.paygate.ParentVerificationManager
import com.example.tv.paygate.PaymentContext
import com.example.tv.paygate.TrustSession
import com.example.tv.paygate.TrustStore

/**
 * 接入方页面的骨架：演示"下单 → 风险判定 → 弹确认 → 带 token 下单"的完整链路。
 * 真实工程中 [payGate]、[payment] 由你们的 DI 容器和订单模块提供。
 */
class CheckoutActivity : FragmentActivity(), ParentGateFragment.Listener {

    // 骨架示例：接入工程中由 DI 容器/订单模块赋值，本文件不提供它们的构造
    private lateinit var payGate: ParentVerificationManager
    private lateinit var trustStore: TrustStore
    private lateinit var payment: PaymentContext
    private lateinit var paymentId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 构造示例：
        // payGate = ParentVerificationManager(
        //     api = retrofit.create(PayGateApi::class.java),
        //     riskEngine = RiskEngine(),
        //     now = System::currentTimeMillis,
        //     deviceId = Installation.id(applicationContext),
        // )
        // trustStore = TrustStore(applicationContext)
        beginCheckout()
    }

    private fun beginCheckout() {
        val now = System.currentTimeMillis()
        val ctx = payment.copy(
            nowMillis = now,
            trustSession = trustStore.load(payment.profileId, now),
        )
        val decision = payGate.decide(ctx)
        when (decision.action) {
            // 信任窗口内小额：无感放行，直接下单（服务端仍按账号额度做二次校验）
            GateAction.ALLOW -> placeOrder(approvalToken = null)

            GateAction.BLOCK -> onBlocked(decision.reason)

            GateAction.VERIFY_STANDARD, GateAction.VERIFY_HIGH_RISK -> showGate(decision)
        }
    }

    private fun showGate(decision: GateDecision) {
        val fragment = ParentGateFragment().apply {
            this.manager = payGate
            this.payment = this@CheckoutActivity.payment
            this.decision = decision
            this.paymentId = this@CheckoutActivity.paymentId
            this.listener = this@CheckoutActivity
        }
        supportFragmentManager.beginTransaction()
            .add(android.R.id.content, fragment)
            .commit()
    }

    // ---------- ParentGateFragment.Listener ----------

    override fun onApproved(approvalToken: String, trustSession: TrustSession?) {
        // 信任窗口只在确认通过时写入；游戏充值场景 trustSession 不会被使用（RiskEngine 已排除）
        if (trustSession != null) {
            trustStore.save(payment.profileId, trustSession)
        }
        placeOrder(approvalToken)
    }

    override fun onCancelled() {
        Toast.makeText(this, "已取消购买", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onBlocked(reason: String) {
        Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
        finish()
    }

    /** 真实下单：服务端校验 approvalToken 一次性、绑定订单与设备后才扣款。 */
    private fun placeOrder(approvalToken: String?) {
        // orderApi.placeOrder(paymentId, approvalToken)
        finish()
    }
}
