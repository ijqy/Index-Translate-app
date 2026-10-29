package com.example.aitranslate

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 识别 Key 的显示和测试 */
object KeyCheck {
    fun mask(k: String): String = if (k.length <= 10) "••••" else k.take(4) + "…" + k.takeLast(4)

    fun errMsg(body: String): String = runCatching {
        val o = JSONObject(body)
        (o.optJSONObject("error")?.optString("message") ?: o.optString("message")).take(80)
    }.getOrNull().orEmpty().ifBlank { body.take(60).replace("\n", " ") }

    /** 返回 (能用?, 说明) */
    fun test(kind: String, key: String): Pair<Boolean, String> = runCatching {
        val url = if (kind == "groq") "https://api.groq.com/openai/v1/models" else "https://api.siliconflow.cn/v1/user/info"
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000; c.readTimeout = 8000
            c.setRequestProperty("Authorization", "Bearer ${key.trim()}")
            val code = c.responseCode
            val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            when (code) {
                in 200..299 -> true to "可用"
                401 -> false to "无效"
                403 -> false to if (kind == "groq") "被拒（网络/地区）" else "无权限"
                429 -> true to "限流中"
                else -> false to "HTTP $code ${errMsg(txt)}".trim()
            }
        } finally { c.disconnect() }
    }.getOrElse { false to "连不上" }
}
