package com.example.aitranslate

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Groq 的 Whisper 语音识别（免费 Key，日语等多语言，一段通常 < 1 秒） */
object GroqAsr {
    private const val API = "https://api.groq.com/openai/v1/audio/transcriptions"

    /** Whisper 在静音/音乐上常见的“幻觉”句子 */
    private val junk = listOf("ご視聴ありがとうございました", "ご覧いただきありがとうございます", "チャンネル登録", "Thank you for watching",
        "Thanks for watching", "字幕由", "请不吝点赞", "訂閱", "订阅", "Amara.org", "おやすみなさい")

    /** 每个 Key 下次可用的时间（被限流后冷却） + 上次使用时间（免费档约 20 次/分钟） */
    private val coolUntil = HashMap<String, Long>()
    private val lastUse = HashMap<String, Long>()
    private const val GAP = 3100L

    /** 挑一个能用的 Key；全都忙就最多等 4 秒 */
    private fun pick(keys: List<String>): String {
        val deadline = System.currentTimeMillis() + 4000
        while (true) {
            synchronized(this) {
                val now = System.currentTimeMillis()
                val k = keys.filter { (coolUntil[it] ?: 0) <= now && now - (lastUse[it] ?: 0) >= GAP }
                    .minByOrNull { lastUse[it] ?: 0 }
                if (k != null) { lastUse[k] = now; return k }
            }
            if (System.currentTimeMillis() > deadline) throw RuntimeException(if (keys.size == 1) "Groq 额度用完，多填几个 Key" else "${keys.size} 个 Key 额度都用完了")
            Thread.sleep(150)
        }
    }

    /** 多 Key 轮流；某个 Key 限流或失效就换下一个 */
    fun transcribe(keys: List<String>, model: String, wav: ByteArray, lang: String?, prompt: String = ""): String {
        var err: Exception? = null
        repeat(keys.size.coerceAtMost(4)) {
            val k = pick(keys)
            try { return once(k, model, wav, lang, prompt) }
            catch (e: Limited) { synchronized(this) { coolUntil[k] = System.currentTimeMillis() + e.waitMs }; err = e }
        }
        throw err ?: RuntimeException("Groq 失败")
    }

    private class Limited(msg: String, val waitMs: Long) : RuntimeException(msg)

    private fun once(key: String, model: String, wav: ByteArray, lang: String?, prompt: String): String {
        val b = "----IdxGroq${System.currentTimeMillis()}"
        val out = ByteArrayOutputStream()
        fun f(k: String, v: String) = out.write("--$b\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n".toByteArray())
        f("model", model); f("response_format", "json"); f("temperature", "0")
        if (lang != null) f("language", lang)
        if (prompt.isNotBlank()) f("prompt", prompt)
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
            if (code == 401) throw Limited("有个 Groq Key 无效（${KeyCheck.mask(key)}）", 3_600_000L)
            if (code == 403) throw RuntimeException("Groq 拒绝访问（403）：所在网络/地区可能被 Groq 屏蔽，需要换网络或开代理")
            if (code == 429) {
                val ra = c.getHeaderField("retry-after")?.toDoubleOrNull()
                throw Limited("Groq 额度用完", ((ra ?: 20.0) * 1000).toLong().coerceIn(2000L, 600_000L))
            }
            if (code !in 200..299) throw RuntimeException("Groq HTTP $code ${KeyCheck.errMsg(txt)}".trim())
            val t = JSONObject(txt).optString("text").trim()
            // 纯音乐/掌声时 Whisper 会输出“音楽”“♪”“(拍手)”之类，直接丢掉
            val core = t.replace(Regex("[\\s　。、，,.!！?？♪♫～~・…()（）\\[\\]【】「」『』]"), "")
                .replace(Regex("音楽|音乐|拍手|笑い|笑|BGM|Music|music|MUSIC|Applause|ん+|あ+|え+|うん"), "")
            return if (t.length < 2 || core.isEmpty() || junk.any { t.contains(it) }) "" else t
        } finally { c.disconnect() }
    }
}
