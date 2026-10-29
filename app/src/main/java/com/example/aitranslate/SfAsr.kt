package com.example.aitranslate

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** 硅基流动 SenseVoice 语音识别（免费、国内直连、中日韩英粤，非自回归所以很快） */
object SfAsr {
    private const val API = "https://api.siliconflow.cn/v1/audio/transcriptions"
    const val MODEL = "FunAudioLLM/SenseVoiceSmall"
    private val coolUntil = HashMap<String, Long>()
    private var rr = 0

    private fun pick(keys: List<String>): String = synchronized(this) {
        val now = System.currentTimeMillis()
        val ok = keys.filter { (coolUntil[it] ?: 0) <= now }
        if (ok.isEmpty()) throw RuntimeException("硅基流动 Key 都在限流，稍等")
        ok[(rr++) % ok.size]
    }

    fun transcribe(keys: List<String>, wav: ByteArray): String {
        var err: Exception? = null
        repeat(keys.size.coerceIn(1, 4)) {
            val k = pick(keys)
            try { return clean(once(k, wav)) }
            catch (e: GroqLike) { synchronized(this) { coolUntil[k] = System.currentTimeMillis() + e.waitMs }; err = e }
        }
        throw err ?: RuntimeException("硅基流动识别失败")
    }

    private class GroqLike(msg: String, val waitMs: Long) : RuntimeException(msg)

    private fun once(key: String, wav: ByteArray): String {
        val b = "----IdxSf${System.currentTimeMillis()}"
        val out = ByteArrayOutputStream()
        out.write("--$b\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n$MODEL\r\n".toByteArray())
        out.write("--$b\r\nContent-Disposition: form-data; name=\"file\"; filename=\"seg.wav\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
        out.write(wav); out.write("\r\n--$b--\r\n".toByteArray())
        val c = URL(API).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 6000; c.readTimeout = 12000
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Authorization", "Bearer ${key.trim()}")
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$b")
            c.outputStream.use { it.write(out.toByteArray()) }
            val code = c.responseCode
            val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code == 401 || code == 403) throw GroqLike("有个硅基流动 Key 无效（${KeyCheck.mask(key)}）", 3_600_000L)
            if (code == 429) throw GroqLike("硅基流动限流", 15_000L)
            if (code !in 200..299) throw RuntimeException("硅基流动 HTTP $code ${KeyCheck.errMsg(txt)}".trim())
            return JSONObject(txt).optString("text").trim()
        } finally { c.disconnect() }
    }

    /** 去掉 SenseVoice 的 <|ja|><|NEUTRAL|><|Speech|> 标签和 🎼😊 之类事件表情，纯音乐/笑声丢掉 */
    private fun clean(raw: String): String {
        val t = raw.replace(Regex("<\\|[^|]*\\|>"), "")
            .replace(Regex("[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}]"), "").trim()
        val core = t.replace(Regex("[\\s　。、，,.!！?？♪♫～~・…()（）\\[\\]【】「」『』]"), "")
            .replace(Regex("ん+|あ+|え+|うん|嗯+|啊+"), "")
        return if (t.length < 2 || core.isEmpty()) "" else t
    }
}
