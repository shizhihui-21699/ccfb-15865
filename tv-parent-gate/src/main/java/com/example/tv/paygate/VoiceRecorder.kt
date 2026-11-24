package com.example.tv.paygate

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream

/**
 * 遥控器确认键按住录音、松开发送的最小实现骨架。
 * 16kHz / 16bit / 单声道 PCM——服务端声纹比对的通用输入格式。
 *
 * 注意：声纹只用于"是不是已登记家长"的辅助判断，且必须配合语义校验
 * （回答的内容是否正确），单独声纹可被高质量录音重放攻击。
 */
object VoiceRecorder {

    private const val SAMPLE_RATE = 16_000
    private const val MAX_DURATION_MILLIS = 8_000

    /** 录音是阻塞操作，调用方需在 IO 调度器中调用。 */
    fun captureAndBuildPart(challengeId: String): MultipartBody.Part {
        val pcm = capture()
        // 生产环境建议封装为 OUS/OPUS 再传；示例直接 PCM 裸流，服务端按 Content-Type 解析
        val body = pcm.toRequestBody("audio/L16;rate=16000;channels=1".toMediaType())
        return MultipartBody.Part.createFormData("audio", "$challengeId.pcm", body)
    }

    private fun capture(): ByteArray {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, // 电视端首选，部分芯片会做回声消除
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf,
        )
        val out = ByteArrayOutputStream()
        val buf = ByteArray(minBuf)
        recorder.startRecording()
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < MAX_DURATION_MILLIS) {
            val read = recorder.read(buf, 0, buf.size)
            if (read > 0) out.write(buf, 0, read)
        }
        recorder.stop()
        recorder.release()
        return out.toByteArray()
    }
}
