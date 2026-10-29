package com.example.aitranslate

import org.json.JSONArray
import org.json.JSONObject
import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 字幕内容场景：背景说明 + 术语表（术语只在译成中文时带上） */
object CaptionScene {
    const val VTUBER = "B站 VTuber 直播"
    const val VTUBER_BG = "这是日本虚拟主播（VTuber）在B站的直播，主播在和观众闲聊、念弹幕和SC（醒目留言）。原文是语音识别结果，可能有听错的词；口语里常有语气词和重复。"
    val VTUBER_TERMS = listOf("ビリビリ" to "B站", "スパチャ" to "SC", "スーパーチャット" to "SC", "館長" to "舰长", "艦長" to "舰长",
        "コメント" to "弹幕", "配信" to "直播", "ギフト" to "礼物")

    /** 一个内容类型：名字 + 背景 + 按译文语言分开的术语表 */
    class Profile(var name: String, var bg: String, val terms: LinkedHashMap<String, MutableList<Pair<String, String>>> = LinkedHashMap()) {
        fun count() = terms.values.sumOf { it.size }
        fun toJson(): JSONObject = JSONObject().put("name", name).put("bg", bg).put("terms", JSONObject().apply {
            terms.forEach { (l, ts) -> if (ts.isNotEmpty()) put(l, JSONArray().apply { ts.forEach { put(JSONArray().put(it.first).put(it.second)) } }) }
        })
        fun copy() = Profile(name, bg, LinkedHashMap(terms.mapValues { it.value.toMutableList() }))
    }

    private fun defaults(p: Prefs): MutableList<Profile> {
        val list = mutableListOf(Profile("通用", ""), Profile(VTUBER, VTUBER_BG).apply { terms["中文"] = VTUBER_TERMS.toMutableList() })
        // 旧版「自定义」迁移过来
        if (p.capBg.isNotBlank() || p.capTerms.isNotBlank())
            list += Profile("自定义", p.capBg).apply { parseTerms(p.capTerms).takeIf { it.isNotEmpty() }?.let { terms["中文"] = it.toMutableList() } }
        return list
    }

    fun load(p: Prefs): MutableList<Profile> {
        val raw = p.capProfiles
        if (raw.isBlank()) return defaults(p)
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val t = LinkedHashMap<String, MutableList<Pair<String, String>>>()
                o.optJSONObject("terms")?.let { tj -> tj.keys().forEach { l ->
                    val a = tj.getJSONArray(l)
                    t[l] = (0 until a.length()).map { a.getJSONArray(it).let { x -> x.getString(0) to x.getString(1) } }.toMutableList()
                } }
                Profile(o.getString("name"), o.optString("bg"), t)
            }.toMutableList().ifEmpty { defaults(p) }
        }.getOrElse { defaults(p) }
    }

    fun save(p: Prefs, list: List<Profile>) { p.capProfiles = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString() }

    fun current(p: Prefs): Profile {
        val l = load(p)
        val name = when (p.capScene) { "VTuber 直播" -> VTUBER; else -> p.capScene }
        return l.firstOrNull { it.name == name } ?: l.first()
    }

    fun parseTerms(s: String): List<Pair<String, String>> = s.lines().mapNotNull { l ->
        val p = l.split("→", "->", "=", limit = 2)
        if (p.size == 2 && p[0].isNotBlank() && p[1].isNotBlank()) p[0].trim().take(40) to p[1].trim().take(40) else null
    }.take(200)

    /** 用 Index 的模型按名字和背景想出常见术语（只返回新增的） */
    fun generate(name: String, bg: String, tgt: String, max: Int, have: List<Pair<String, String>>): List<Pair<String, String>> {
        val sys = "你在为视频/直播实时字幕准备翻译术语表。根据内容类型和背景，列出这类内容里经常出现、容易翻错的专有词、梗、称呼和固定说法，" +
            "左边是视频里说的原文（按背景推断原语言，例如日本主播就写日语），右边是地道的${tgt}译法。每行一条，格式：原文→译文。只输出术语行，不要编号和解释。"
        val user = "内容类型：$name\n背景：${bg.ifBlank { "无" }}\n" +
            (if (have.isNotEmpty()) "已有（不要重复）：" + have.joinToString("、") { it.first } + "\n" else "") + "最多 $max 条。"
        val out = AiClient.stream("Index-Translate-35B-A3B", user, 0.3, 1200, system = sys) {}
        val old = have.map { it.first }.toSet()
        return parseTerms(out.replace(Regex("^\\s*[0-9]+[.、)]\\s*", RegexOption.MULTILINE), ""))
            .filter { it.first !in old && it.first != it.second }.distinctBy { it.first }.take(max)
    }

    /** 每句都一样的 system 提示词（放最前，服务端前缀缓存能复用） */
    fun system(p: Prefs, src: String, tgt: String): String = buildString {
        val s = if (src == "自动") "外语" else src
        append("你是直播字幕翻译。把【当前句】的${s}翻译成自然、口语化的${tgt}，准确保留原意、肯定否定和人物关系；")
        append("保留人名，不凭空补充内容。字幕要简短，可以省略无意义的口头重复。")
        append("【前文】只用于理解当前句的指代和省略，不要翻译、不要输出。【注意】是这一句的额外要求。")
        append("只输出当前句的译文，不要解释、注释或其他文字。")
        val prof = current(p)
        val bg = prof.bg.trim().take(1000).ifBlank { null }
        val terms = prof.terms[tgt].orEmpty().take(200)
        bg?.let { append("\n\n背景：$it") }
        if (terms.isNotEmpty()) append("\n\n术语（原文出现时必须这样译）：" + terms.joinToString("、") { "${it.first}→${it.second}" })
        if (src == "日语" && tgt == "中文") append("\n\n如果【当前句】只有几个字（例如「あ」「日」「えっ」这类残缺片段），" +
            "不要根据【前文】把它补全成完整句子，也不要复述【前文】的内容；但这几个字本身仍要翻译成自然的中文（例如「犬」→「狗」、" +
            "「行く」→「去」），不要直接照抄原文。")
    }

    fun user(text: String, previous: List<String>, note: String? = null): String = buildString {
        if (previous.isNotEmpty()) append("【前文】\n" + previous.joinToString("\n") + "\n\n")
        append("【当前句】\n$text")
        note?.let { append("\n\n【注意】$it") }
    }
}

/** 把每句定稿的原文和译文写成纯文本，一场字幕一个文件 */
class Transcript(private val ctx: Context, private val src: String, private val tgt: String) {
    private var file: File? = null
    private val tf = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized fun add(atMs: Long, orig: String, trans: String) {
        if (orig.isBlank() && trans.isBlank()) return
        val f = file ?: run {
            val d = dir(ctx); d.mkdirs()
            val now = Date()
            File(d, "字幕_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now) + ".txt").also {
                it.writeText("Index 实时字幕 · ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(now)} · $src → $tgt\n\n")
                file = it
            }
        }
        runCatching { f.appendText("[${tf.format(Date(atMs))}] ${orig.trim()}\n${trans.trim()}\n\n") }
    }

    companion object {
        fun dir(ctx: Context) = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "transcripts")
        fun list(ctx: Context): List<File> = dir(ctx).listFiles { f -> f.name.endsWith(".txt") }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
}
