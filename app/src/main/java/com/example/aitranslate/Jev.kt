package com.example.aitranslate

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * TypeSafe Jev（结构化判断模型），走 OpenCode Zen 免费接口，不要 Key。
 * 两个用途（参考 simul-subtitle）：① 停顿时判断这句话说完没有（智能断句）② 检查译文有没有明显译错。
 * 任何失败（超时、限流、断网）都返回 null，调用方按没开 Jev 的方式继续。
 */
object Jev {
    private const val URL_ZEN = "https://opencode.ai/zen/v1/systemone"
    private const val MODEL = "jev-1.13-free"
    @Volatile private var pausedUntil = 0L

    private val CRIT_UNFINISHED = JSONObject()
        .put("true", "unfinished or misheard fragment: wait for the next words")
        .put("false", "finished: current is a complete utterance or a standalone filler/reaction")

    private fun unfinishedQ(src: String): JSONObject = JSONObject().put("type", "noul").put("criteria", CRIT_UNFINISHED).put("instructions",
        if (src == "日语") "`current` is raw automatic speech-recognition (ASR) output for a Japanese livestream, ending where the streamer just paused. The ASR often mishears words: a short fragment may be a misrecognized piece of a longer word or phrase, even if it looks like a valid word. Decide whether the speaker's sentence is still unfinished at the end of `current`, so we should wait and join it with what the speaker says next. Unfinished: `current` ends with a dangling particle or connective (は, が, を, に, で, と, て, けど, から, たら, とか, って), a modifier waiting for its noun, a word cut in half, or is a short fragment that makes no sense in context and is probably misheard. Finished: `current` ends with a sentence-final form (です, ます, よ, ね, な, じゃない, ありがとう, ごめんね, こんばんは) or is a standalone filler/reaction (はい, えっ, あの, えっと)."
        else "`current` is raw automatic speech-recognition (ASR) output for a ${enName(src)} livestream, ending where the streamer just paused. The ASR often mishears words: a short fragment may be a misrecognized piece of a longer word or phrase, even if it looks like a valid word. Decide whether the speaker's sentence is still unfinished at the end of `current`, so we should wait and join it with what the speaker says next. Unfinished: `current` ends with a dangling preposition, conjunction, article or particle, a modifier waiting for its noun, a word cut in half, or is a short fragment that makes no sense in context and is probably misheard. Finished: `current` ends with a complete sentence or is a standalone filler/reaction.")

    private fun mistranslationQ(src: String, tgt: String): JSONObject {
        val tuned = src == "日语" && tgt == "中文"
        val s = enName(src); val t = enName(tgt)
        return JSONObject().put("type", "choice").put("instructions",
            if (tuned) "`ja` is Japanese VTuber livestream speech (raw speech recognition, may contain misheard words); `zh` is a Chinese machine translation that may be wrong. What is the main problem with `zh`?"
            else "`ja` is $s livestream speech (raw speech recognition, may contain misheard words); `zh` is a $t machine translation of it that may be wrong. What is the main problem with `zh`?")
            .put("criteria", JSONObject()
                .put("negation", "zh adds a negation or refusal that ja does not contain")
                .put("reversal", "zh reverses the meaning or the positive/negative polarity of ja")
                .put("literal_metaphor", if (tuned) "zh translates an idiom or livestream expression literally (e.g. comments 'flowing' rendered as water flowing)" else "zh translates an idiom or livestream expression literally")
                .put("platform_term", "zh mistranslates a livestream/platform term such as a platform name, superchat, member, gift")
                .put("untranslated", "zh leaves $s words untranslated")
                .put("misheard", "ja itself contains misheard nonsense words and zh translates them literally")
                .put("hallucination", "zh adds content that ja does not say")
                .put("ok", "zh is an acceptable translation"))
    }

    /** 能自动修正的错误类别 → 重译时附带的说明 */
    fun fixNote(choice: String, src: String, tgt: String): String? = if (src == "日语" && tgt == "中文") mapOf(
        "negation" to "原文没有否定或拒绝的意思，译文不要凭空加入“不”“不用”“没有”等否定；“〜なんて”表示惊讶或感叹，不是否定。",
        "reversal" to "注意保持原文的肯定/否定和转折关系，不要把意思翻反（例如“寂しくない”是“不寂寞”）。",
        "literal_metaphor" to "原文里的比喻或直播用语不要按字面直译（例如“流れる”指弹幕/评论滚动得快，不是水流），按语境意译。",
    )[choice] else mapOf(
        "negation" to "原文没有否定或拒绝的意思，译文不要凭空加入否定。",
        "reversal" to "注意保持原文的肯定/否定和转折关系，不要把意思翻反。",
        "literal_metaphor" to "原文里的比喻或直播用语不要按字面直译，按语境意译。",
    )[choice]

    private fun enName(z: String) = when (z) { "日语" -> "Japanese"; "英语" -> "English"; "中文" -> "Chinese"; "韩语" -> "Korean"; "自动" -> "Japanese or English"; else -> z }

    /** 这句话还没说完的概率（0~1），失败 null */
    fun unfinished(text: String, src: String, timeoutMs: Int = 1000): Double? =
        ask(JSONObject().put("current", text), unfinishedQ(src), timeoutMs)?.optDouble("noul", Double.NaN)?.takeIf { !it.isNaN() }

    /** (类别, 置信度)，失败 null */
    fun mistranslation(orig: String, trans: String, src: String, tgt: String, timeoutMs: Int = 2500): Pair<String, Double>? {
        val a = ask(JSONObject().put("ja", orig).put("zh", trans), mistranslationQ(src, tgt), timeoutMs) ?: return null
        val c = a.optString("choice").takeIf { it.isNotEmpty() } ?: return null
        val conf = a.optDouble("confidence", Double.NaN).takeIf { !it.isNaN() }
            ?: a.optJSONObject("probabilities")?.optDouble(c, Double.NaN)?.takeIf { !it.isNaN() } ?: return null
        return c to conf
    }

    private fun ask(state: JSONObject, q: JSONObject, timeoutMs: Int): JSONObject? {
        if (System.currentTimeMillis() < pausedUntil) return null
        val body = JSONObject().put("model", MODEL).put("state", state).put("questions", JSONObject().put("x", q))
        return runCatching {
            val c = URL(URL_ZEN).openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"; c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs; c.doOutput = true
                c.instanceFollowRedirects = false
                c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("User-Agent", "Index/40")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                if (c.responseCode == 429) {
                    val ra = c.getHeaderField("Retry-After")?.trim()?.toLongOrNull() ?: 60
                    pausedUntil = System.currentTimeMillis() + ra.coerceIn(5, 3600) * 1000; return null
                }
                if (c.responseCode != 200) return null
                JSONObject(c.inputStream.bufferedReader().readText()).optJSONObject("answers")?.optJSONObject("x")
            } finally { c.disconnect() }
        }.getOrNull()
    }
}
