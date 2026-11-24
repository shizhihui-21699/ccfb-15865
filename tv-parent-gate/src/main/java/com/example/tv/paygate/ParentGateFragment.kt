package com.example.tv.paygate

import android.graphics.Bitmap
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.util.Locale

/**
 * 家长确认页（纯 D-pad 遥控器交互）。
 *
 * 交互原则：
 * 1. 上下键选因素，OK 进入，返回键退出；默认焦点永远在最推荐的"手机确认"上。
 * 2. 屏幕上不出现任何可旁观的秘密：OTP 只显示圆点、语音问题由 TTS 朗读而非大字展示。
 * 3. 所有文案 ≥24sp，按钮高度 ≥56dp，焦点态描边加粗——适配老人和 3 米观看距离。
 * 4. OTP 直接响应遥控器数字键（0-9），不需要虚拟键盘；退格键删除。
 */
class ParentGateFragment : Fragment() {

    interface Listener {
        /** 确认通过：宿主凭 token 下单，并按 trustSession 落地信任窗口（可能为 null） */
        fun onApproved(approvalToken: String, trustSession: TrustSession?)
        fun onCancelled()
        fun onBlocked(reason: String)
    }

    var listener: Listener? = null
    lateinit var manager: ParentVerificationManager
    lateinit var payment: PaymentContext
    lateinit var decision: GateDecision
    lateinit var paymentId: String

    private lateinit var root: LinearLayout
    private lateinit var title: TextView
    private lateinit var statusLine: TextView
    private var tts: TextToSpeech? = null
    private var currentChallenge: ChallengeDto? = null
    private var otpBuffer = StringBuilder()
    private var otpTargetChallengeId: String? = null

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: android.view.ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(64, 48, 64, 48)
        }
        title = tv("家长确认", 34f, bold = true)
        statusLine = tv(decision.reason, 24f)
        root.addView(title)
        root.addView(statusLine)
        renderFactorList()
        // 按键监听挂在根视图上：OTP 录入阶段拦截数字键，其余阶段放行给焦点系统
        root.isFocusable = true
        root.setOnKeyListener { _, keyCode, event -> handleKey(keyCode, event) }
        return root
    }

    private fun handleKey(keyCode: Int, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val challengeId = otpTargetChallengeId ?: return false
        val digit = keyCode - KeyEvent.KEYCODE_0
        if (digit in 0..9 && otpBuffer.length < 6) {
            otpBuffer.append(digit)
            refreshDots()
            if (otpBuffer.length == 6) submitOtp(challengeId)
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DEL && otpBuffer.isNotEmpty()) {
            otpBuffer.deleteCharAt(otpBuffer.length - 1)
            refreshDots()
            return true
        }
        return false
    }

    // ---------- 因素选择列表 ----------

    private fun renderFactorList() {
        root.removeViews(2, root.childCount - 2)
        otpBuffer.clear()
        otpTargetChallengeId = null
        decision.acceptedFactors.forEach { factor ->
            val btn = Button(requireContext()).apply {
                text = factorLabel(factor)
                textSize = 26f
                minHeight = dp(64)
                setOnClickListener { enterFactor(factor) }
            }
            root.addView(btn, matchWidth())
        }
        root.addView(Button(requireContext()).apply {
            text = "取消购买"
            textSize = 22f
            setOnClickListener { listener?.onCancelled() }
        }, matchWidth())
    }

    private fun factorLabel(factor: VerifyFactor): String = when (factor) {
        VerifyFactor.PHONE_PUSH -> "① 手机确认（推荐：电视显示请求，家长在手机按指纹）"
        VerifyFactor.QR_SCAN -> "② 扫码确认（家长用微信扫一扫，无需装 App）"
        VerifyFactor.SMS_OTP -> "③ 短信验证码（手机收码，遥控器数字键输入）"
        VerifyFactor.VOICE_CHALLENGE -> "④ 语音问答（手机不在身边时，按住确认键说话）"
        VerifyFactor.PIN_AS_SECOND_FACTOR -> "⑤ 密码二次确认（需与上面任一方式组合）"
    }

    private fun enterFactor(factor: VerifyFactor) {
        launchUi {
            val challenge = try {
                manager.startChallenge(payment, decision, factor, paymentId)
            } catch (e: Exception) {
                statusLine.text = "发起确认失败，请重试：${e.message}"
                return@launchUi
            }
            currentChallenge = challenge
            when (factor) {
                VerifyFactor.PHONE_PUSH -> renderPush(challenge)
                VerifyFactor.QR_SCAN -> renderQr(challenge)
                VerifyFactor.SMS_OTP -> renderOtp(challenge)
                VerifyFactor.VOICE_CHALLENGE -> renderVoice(challenge)
                VerifyFactor.PIN_AS_SECOND_FACTOR ->
                    statusLine.setText("密码不能单独用于确认，请选择①-④")
            }
        }
    }

    // ---------- 手机推送 / 扫码：轮询等待 ----------

    private fun renderPush(c: ChallengeDto) {
        replaceBody(
            tv("请用尾号 ${c.guardianPhoneMasked.orEmpty()} 的手机确认本次付费", 26f),
            tv("手机将收到通知，按指纹或刷脸即可，无需在电视上输入", 22f),
        )
        pollStatus(c.challengeId, c.ttlSeconds)
    }

    private fun renderQr(c: ChallengeDto) {
        val qr: Bitmap? = c.qrPayload?.let { payload ->
            runCatching {
                val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 480, 480)
                Bitmap.createBitmap(480, 480, Bitmap.Config.RGB_565).apply {
                    for (x in 0 until 480) for (y in 0 until 480) {
                        setPixel(x, y, if (matrix.get(x, y)) 0xFF111111.toInt() else 0xFFFFFFFF.toInt())
                    }
                }
            }.getOrNull()
        }
        replaceBody(
            ImageView(requireContext()).apply {
                setImageBitmap(qr)
                minimumWidth = dp(320)
                minimumHeight = dp(320)
                contentDescription = "家长确认二维码"
            },
            tv("请家长用手机微信扫码，在小程序内确认", 24f),
        )
        pollStatus(c.challengeId, c.ttlSeconds)
    }

    private fun pollStatus(challengeId: String, ttlSeconds: Int) {
        val countdown = tv("", 22f)
        root.addView(countdown)
        launchUi {
            when (val r = manager.awaitExternalApproval(
                challengeId, ttlSeconds, payment.profileId,
            ) { remain ->
                countdown.text = "剩余 $remain 秒，超时可返回重新发起"
            }) {
                is VerifyResult.Approved -> listener?.onApproved(r.approvalToken, r.trustSession)
                is VerifyResult.Rejected -> { statusLine.text = r.message; renderFactorList() }
                is VerifyResult.Locked -> renderLocked(r.lockSeconds)
                else -> renderFactorList()
            }
        }
    }

    // ---------- 短信 OTP：遥控器数字键直输，圆点回显 ----------

    private fun renderOtp(c: ChallengeDto) {
        val dots = tv("○ ○ ○ ○ ○ ○", 34f)
        replaceBody(
            tv("验证码已发送至 ${c.guardianPhoneMasked.orEmpty()}，2 分钟内有效", 24f),
            dots,
            tv("直接按遥控器数字键输入，按返回键退格", 20f),
        )
        otpTargetChallengeId = c.challengeId
        // 圆点回显保存在 tag 中，handleKey 里统一刷新
        root.tag = dots
        root.requestFocus()
    }

    private fun refreshDots() {
        val dots = root.tag as? TextView ?: return
        dots.text = (0 until 6).joinToString(" ") { if (it < otpBuffer.length) "●" else "○" }
    }

    private fun submitOtp(challengeId: String) {
        val code = otpBuffer.toString()
        launchUi {
            when (val r = manager.submitOtp(challengeId, payment.profileId, code)) {
                is VerifyResult.Approved -> listener?.onApproved(r.approvalToken, r.trustSession)
                is VerifyResult.Locked -> renderLocked(r.lockSeconds)
                is VerifyResult.Rejected -> { statusLine.text = r.message; renderFactorList() }
                is VerifyResult.Error -> { statusLine.text = r.message; renderFactorList() }
                else -> Unit
            }
        }
    }

    // ---------- 语音问答：TTS 读题，确认键录音，答案不落屏 ----------

    private fun renderVoice(c: ChallengeDto) {
        val prompt = c.voicePrompt.orEmpty()
        val speakBtn = Button(requireContext()).apply {
            text = "按确认键收听问题；按住说话回答"
            textSize = 24f
            setOnClickListener { speak(prompt) }
            setOnLongClickListener { startVoiceCapture(c.challengeId); true }
        }
        replaceBody(
            tv("语音确认（问题不会显示在屏幕上，防止旁观）", 22f),
            speakBtn,
        )
        speak(prompt) // 进入即自动朗读一次
    }

    private var ttsReady = false

    private fun speak(text: String) {
        val existing = tts
        if (existing != null && ttsReady) {
            existing.speak(text, TextToSpeech.QUEUE_FLUSH, null, "paygate_prompt")
            return
        }
        // init 是异步的，回调成功前不能 speak；用标志位保证只初始化一次
        tts = TextToSpeech(requireContext()) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.CHINESE
                ttsReady = true
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "paygate_prompt")
            }
        }
    }

    /** 实际项目中用 AudioRecord 录 16k 单声道 PCM/OPUS，上传由服务端做声纹+语义双校验。 */
    private fun startVoiceCapture(challengeId: String) {
        statusLine.text = "正在录音，松开发送…"
        launchUi {
            val part = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                VoiceRecorder.captureAndBuildPart(challengeId)
            }
            when (val r = manager.submitVoice(challengeId, payment.profileId, part)) {
                is VerifyResult.Approved -> listener?.onApproved(r.approvalToken, r.trustSession)
                is VerifyResult.Locked -> renderLocked(r.lockSeconds)
                else -> { statusLine.text = "没听清或不匹配，请换一种确认方式"; renderFactorList() }
            }
        }
    }

    // ---------- 锁定态 ----------

    private fun renderLocked(lockSeconds: Long) {
        replaceBody(
            tv("尝试次数过多，为保护账户已暂时锁定", 28f, bold = true),
            tv(
                if (lockSeconds > 0) "请 $lockSeconds 秒后再试，或改用家长手机确认"
                else "请稍后再试，或改用家长手机确认",
                24f,
            ),
            Button(requireContext()).apply {
                text = "返回"
                setOnClickListener { renderFactorList() }
            },
        )
    }

    // ---------- 小工具 ----------

    private fun replaceBody(vararg views: View) {
        root.removeViews(2, root.childCount - 2)
        views.forEach { root.addView(it, if (it is TextView || it is Button) matchWidth() else wrapCenter()) }
    }

    private fun showError(msg: String) { statusLine.text = msg }

    private fun tv(s: String, size: Float, bold: Boolean = false) =
        TextView(requireContext()).apply {
            text = s; textSize = size
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun matchWidth() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(12) }
    private fun wrapCenter() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(12) }

    override fun onDestroyView() {
        super.onDestroyView()
        val pending = currentChallenge
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
        if (pending != null) {
            launchUi { manager.cancel(pending.challengeId) }
        }
    }
}
