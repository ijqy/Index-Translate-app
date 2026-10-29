package com.example.aitranslate

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** B 站 Index-Echo 语音接口：提交任务拿 job_id，再轮询结果 */
object EchoClient {
    private const val API = "https://index-translate.bilibili.com/?p=/api/echo"

    /** kind = "s2tt"（语音→文字）或 "s2st"（语音→语音） */
    fun run(kind: String, audio: ByteArray, filename: String, mime: String,
            fields: Map<String, String>, pollMs: Long = 2000, onTick: (Int) -> Unit): JSONObject {
        val boundary = "----IdxEcho${System.currentTimeMillis()}"
        val out = ByteArrayOutputStream()
        fun w(s: String) = out.write(s.toByteArray())
        fields.forEach { (k, v) ->
            w("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n")
        }
        w("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$filename\"\r\n")
        w("Content-Type: $mime\r\n\r\n"); out.write(audio); w("\r\n--$boundary--\r\n")

        val submit = request("$API/$kind", out.toByteArray(), "multipart/form-data; boundary=$boundary")
        if (!submit.optBoolean("ok")) throw RuntimeException(submit.optString("error", "提交失败"))
        val job = submit.getString("job_id")

        val t0 = System.currentTimeMillis()
        var notFound = 0
        while (true) {
            Thread.sleep(pollMs)
            val el = ((System.currentTimeMillis() - t0) / 1000).toInt()
            if (el > 180) throw RuntimeException("等待超时，请重试")
            onTick(el)
            val r = try { request("$API/result?job_id=$job", null, null) } catch (e: Exception) { continue }
            if (!r.optBoolean("ok")) {
                if (r.optInt("_code") == 404 && notFound++ < 3) continue
                throw RuntimeException(r.optString("error", "任务失败"))
            }
            notFound = 0
            if (r.optString("status") != "done") continue
            val body = r.optJSONObject("echo") ?: JSONObject()
            if (!body.optBoolean("ok")) {
                val e = body.optString("error", "翻译失败")
                throw RuntimeException(if (e.contains("source lang == target lang"))
                    "说话的语言和目标语言相同，请换一个目标语言" else e)
            }
            return body
        }
    }

    private fun request(url: String, body: ByteArray?, type: String?): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15000; c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "Index-Translate-Client/1.0")
            if (body != null) {
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type", type)
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val txt = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            return try { JSONObject(txt) } catch (e: Exception) {
                JSONObject().put("ok", false).put("error", "HTTP $code")
            }.put("_code", code)
        } finally { c.disconnect() }
    }
}
