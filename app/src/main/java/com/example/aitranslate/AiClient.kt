package com.example.aitranslate

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** B 站 Index-Translate 免费公网 API（OpenAI 兼容，无需 Key），全部流式输出 */
object AiClient {
    private const val API = "https://index-translate.bilibili.com/v1/chat/completions"
    const val VISION_MODEL = "Index-Translate-35B-A3B"

    /** 用户指定的原文语言；自动检测返回 null */
    fun sourceOf(p: Prefs) = p.sourceLang.takeIf { it != Lang.AUTO }

    fun targetFor(p: Prefs, text: String): String {
        val src = sourceOf(p) ?: Lang.detect(text)
        return if (src == p.targetLang) p.fallbackLang else p.targetLang
    }

    private fun header(p: Prefs, text: String) = sourceOf(p)?.let { "请将以下${it}文本翻译为${targetFor(p, text)}" }
        ?: "请将以下文本翻译为${targetFor(p, text)}"

    // ---------- 标准文本 ----------
    fun translateText(p: Prefs, text: String, onDelta: (String) -> Unit): String =
        stream(p.model, "${header(p, text)}，直接输出翻译结果，不要进行任何解释。\n\n${text.trim()}",
            0.0, 4096, onDelta = onDelta)

    // ---------- 按要求翻译（指令遵循，与官网 instTrans 规范一致；2B 不遵循指令，自动升 9B） ----------
    fun instructModel(p: Prefs) = if (p.model.contains("2B")) "Index-Translate-9B" else p.model

    fun buildInstructionPrompt(p: Prefs, text: String, instruct: String): String {
        val t = text.trim(); val ins = instruct.trim()
        if (ins.contains("【源文】") || ins.contains("【待翻译文本】")) return "$ins\n\n$t"
        val tgt = targetFor(p, t)
        val cons = mutableListOf<String>()
        for (raw in ins.lines().map { it.trim() }.filter { it.isNotEmpty() }) {
            if (raw.startsWith("【")) { cons += raw; continue }
            var clean = raw.replace(Regex("^(?:注意[：:]\\s*)?(?:\\d+[.、\\-]\\s*)?"), "").trim().ifEmpty { raw }
            val delim = listOf("->", "→", ":", "：").firstOrNull { clean.contains(it) }
            if (delim != null && !clean.contains("翻译为") && !clean.contains("注意") && !clean.contains("请将")) {
                val parts = clean.split(delim)
                if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                    cons += "【术语干预】必须按照术语表翻译：${parts[0].trim()}→${parts[1].trim()}"; continue
                }
            }
            clean = clean.replace("目标语言", tgt)
            cons += "【指令要求】$clean"
        }
        val req = if (cons.isEmpty()) "1. 【指令要求】${ins.replace("目标语言", tgt)}"
            else cons.mapIndexed { i, c -> "${i + 1}. $c" }.joinToString("\n")
        val footer = if (ins.lowercase().contains("json")) "请以合法的 JSON 格式输出翻译结果。只输出 JSON，不要有任何额外说明。"
            else "只输出译文，不要有任何额外说明。"
        return "${header(p, t)}。\n\n【源文】\n$t\n\n【约束要求】\n$req\n\n$footer"
    }

    fun translateInstruct(p: Prefs, text: String, instruct: String, onDelta: (String) -> Unit): String =
        stream(instructModel(p), buildInstructionPrompt(p, text, instruct), 0.0, 4096, onDelta = onDelta)

    // ---------- 图片（只有 35B 支持视觉） ----------
    /** 只提取图片里的原文（OCR），不翻译 */
    fun extractText(jpeg: ByteArray, onDelta: (String) -> Unit): String {
        val b64 = android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)
        val prompt = "请原样提取图片中的所有文字，保持原来的语言和换行顺序，不要翻译，不要解释，不要添加任何多余内容。若图片中没有文字，只输出 …"
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(JSONObject().put("type", "image_url")
                .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64")))
        return stream(VISION_MODEL, content, 0.0, 4096, onDelta = onDelta)
    }

    fun translateImage(p: Prefs, jpeg: ByteArray, instruct: String, onDelta: (String) -> Unit): String {
        val b64 = android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)
        val src = sourceOf(p)
        var prompt = (if (src != null) "请将图片中的${src}文本翻译为${p.targetLang}" else "请将图片中的文本翻译为${p.targetLang}") +
            "，直接输出翻译结果，不要进行任何解释。"
        if (instruct.isNotBlank())
            prompt += "\n\n【要求与约束】\n${instruct.trim().replace("目标语言", p.targetLang)}\n\n只输出翻译结果，不要进行任何解释。"
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(JSONObject().put("type", "image_url")
                .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64")))
        return stream(VISION_MODEL, content, 0.0, 4096, onDelta = onDelta)
    }

    // ---------- 音节控制（Index-Homura，配音/字幕用） ----------
    fun homuraModel(p: Prefs) = if (p.model.contains("2B")) "Index-Homura-2B" else "Index-Homura-9B"
    fun defaultSyllables(text: String): Int {
        val t = text.trim()
        val n = if (Lang.detect(t) == "中文" || Lang.detect(t) == "日语") t.count { it.isLetter() }
            else (t.split(Regex("\\s+")).size * 1.4).toInt()
        return n.coerceIn(2, 120)
    }
    fun translateSyllable(p: Prefs, text: String, syllables: Int, onDelta: (String) -> Unit): String {
        val tgt = targetFor(p, text)
        return stream(homuraModel(p),
            "请将以下文本翻译为$tgt，译文严格控制在 $syllables 个音节。直接输出翻译结果，不要进行任何解释。\n\n${text.trim()}",
            0.3, maxOf(512, 3 * text.length), onDelta = onDelta)
    }

    // ---------- 长文档（Index-NativeLong，只支持 中↔英 / 中↔日） ----------
    private val SRC_WORD = mapOf("zh" to "中文", "en" to "英文", "ja" to "日文")
    private val TGT_WORD = mapOf("zh" to "中文", "en" to "英语", "ja" to "日语")

    /** 返回 "zh-en" 这类方向；不支持返回 null */
    fun docDirection(p: Prefs, text: String): String? {
        val src = Lang.code[sourceOf(p) ?: Lang.detect(text.take(5000)) ?: return null] ?: return null
        val tgt = Lang.code[targetFor(p, text.take(5000))] ?: return null
        val dir = "$src-$tgt"
        if (dir in listOf("zh-en", "en-zh", "zh-ja", "ja-zh")) return dir
        // 目标不支持时退回到能用的方向
        return when (src) { "zh" -> "zh-en"; "en", "ja" -> "$src-zh"; else -> null }
    }
    fun translateDoc(text: String, dir: String, onDelta: (String) -> Unit): String {
        val (s, t) = dir.split("-")
        val prompt = "请将以下${SRC_WORD[s]}小说翻译成${TGT_WORD[t]}，并且严格遵循所有约束要求。\n\n" +
            "这是一段长篇连续文本，请完整处理全部内容，并在全文范围内保持译名和表达一致。\n\n" +
            "【约束要求】\n\n" +
            "1. 完整翻译所有内容，不要遗漏、概括、合并或添加内容。\n" +
            "2. 保持人名、地名、称谓和术语在全文中一致。\n" +
            "3. 保持原文的章节、段落和内容顺序。\n\n" +
            "只输出译文，不要有任何额外说明。\n\n" +
            "【源文】\n\n${text.trim()}\n"
        val model = if (text.length > 150_000) "Index-Nailong-2B" else "Index-Nailong-9B"
        return stream(model, prompt, 0.0, null,
            extra = JSONObject().put("stop_token_ids", JSONArray().put(248044).put(248046)),
            onDelta = onDelta)
    }

    // ---------- 通用流式调用 ----------
    fun stream(model: String, content: Any, temperature: Double, maxTokens: Int?,
               extra: JSONObject? = null, system: String? = null, onDelta: (String) -> Unit): String {
        val body = JSONObject()
            .put("model", model)
            .put("temperature", temperature)
            .put("stream", true)
            .put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
            .put("messages", JSONArray().apply {
                if (system != null) put(JSONObject().put("role", "system").put("content", system))
                put(JSONObject().put("role", "user").put("content", content))
            })
        if (maxTokens != null) body.put("max_tokens", maxTokens)
        extra?.keys()?.forEach { body.put(it, extra.get(it)) }

        val conn = URL(API).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 120000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "text/event-stream")
            conn.setRequestProperty("User-Agent", "Index-Translate-Client/1.0")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            if (conn.responseCode !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw RuntimeException("HTTP ${conn.responseCode}: ${err.take(300)}")
            }
            val sb = StringBuilder()
            conn.inputStream.bufferedReader().useLines { lines ->
                for (raw in lines) {
                    val line = raw.trim()
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val obj = try { JSONObject(data) } catch (e: Exception) { continue }
                    val choices = obj.optJSONArray("choices") ?: continue
                    if (choices.length() == 0) continue
                    val piece = choices.getJSONObject(0).optJSONObject("delta")
                        ?.optString("content", "") ?: ""
                    if (piece.isNotEmpty()) { sb.append(piece); onDelta(piece) }
                }
            }
            var out = sb.toString()
            if (out.contains("</think>")) out = out.substringAfter("</think>")
            return out.removePrefix("<think>").trim()
        } finally {
            conn.disconnect()
        }
    }
}
