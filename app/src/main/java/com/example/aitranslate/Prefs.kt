package com.example.aitranslate

import android.content.Context

val MENU_ALL = listOf("框选", "全屏", "输入", "语音", "拍照", "连续", "目标语")

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("settings2", Context.MODE_PRIVATE)
    private fun s(k: String, d: String) = sp.getString(k, d)!!
    private fun put(k: String, v: String) = sp.edit().putString(k, v).apply()

    /** Index-Translate-2B / Index-Translate-9B / Index-Translate-35B-A3B */
    var model: String get() = s("model", "Index-Translate-35B-A3B"); set(v) = put("model", v)
    /** 原文语言，"自动检测" 或具体语种 */
    var sourceLang: String get() = s("sourceLang", Lang.AUTO); set(v) = put("sourceLang", v)
    var targetLang: String get() = s("targetLang", "中文"); set(v) = put("targetLang", v)
    /** 自动检测时，原文已是目标语言则改译成 */
    var fallbackLang: String get() = s("fallbackLang", "英语"); set(v) = put("fallbackLang", v)
    /** 翻译要求（指令遵循），留空即普通翻译 */
    var instruction: String get() = s("instruction", ""); set(v) = put("instruction", v)
    /** Groq API Key（实时字幕识别日语等语言用，可留空） */
    var groqKey: String get() = s("groqKey", ""); set(v) = put("groqKey", v.trim())
    /** 可以填多个 Key（换行 / 逗号 / 空格隔开），轮流用，躲开免费额度限制 */
    val groqKeys: List<String> get() = groqKey.split(Regex("[\\s,，;；]+")).map { it.trim() }.filter { it.length > 10 }.distinct()
    /** 硅基流动 API Key（SenseVoice 免费识别，国内直连），可多个 */
    var sfKey: String get() = s("sfKey", ""); set(v) = put("sfKey", v.trim())
    val sfKeys: List<String> get() = sfKey.split(Regex("[\\s,，;；]+")).map { it.trim() }.filter { it.length > 10 }.distinct()
    /** 识别模型：whisper-large-v3（准）/ whisper-large-v3-turbo（快）/ sensevoice（硅基流动） */
    var asrModel: String get() = s("asrModel", "whisper-large-v3"); set(v) = put("asrModel", v)
    // ---------- 实时字幕（参考 simul-subtitle） ----------
    /** 边听边出：说话中途也出灰色草稿（多耗识别请求）；关 = 说完再出 */
    var capDrafts: Boolean get() = sp.getBoolean("capDrafts", false); set(v) = sp.edit().putBoolean("capDrafts", v).apply()
    /** 结合上文：参考最近几句定稿原文（0 = 关） */
    var capContext: Int get() = sp.getInt("capContext", 2); set(v) = sp.edit().putInt("capContext", v).apply()
    /** 内容场景：通用 / VTuber 直播 / 自定义 */
    var capScene: String get() = s("capScene", "通用"); set(v) = put("capScene", v)
    var capBg: String get() = s("capBg", ""); set(v) = put("capBg", v)
    /** 自定义术语，一行一条「原文→译文」 */
    var capProfiles: String get() = s("capProfiles", ""); set(v) = put("capProfiles", v)
    var keepRecents: Boolean get() = sp.getBoolean("keepRecents", false); set(v) = sp.edit().putBoolean("keepRecents", v).apply()
    var capTerms: String get() = s("capTerms", ""); set(v) = put("capTerms", v)
    /** Jev 智能断句 / 检查译文（OpenCode Zen 免费接口） */
    var jevSeg: Boolean get() = sp.getBoolean("jevSeg", false); set(v) = sp.edit().putBoolean("jevSeg", v).apply()
    var jevCheck: Boolean get() = sp.getBoolean("jevCheck", false); set(v) = sp.edit().putBoolean("jevCheck", v).apply()
    /** 保存字幕文本 */
    var capSave: Boolean get() = sp.getBoolean("capSave", false); set(v) = sp.edit().putBoolean("capSave", v).apply()
    /** 悬浮球环形菜单里显示哪些按钮（名字用逗号隔开） */
    var menuItems: List<String>
        get() = s("menuItems", MENU_ALL.joinToString(",")).split(",").filter { it in MENU_ALL }
        set(v) = put("menuItems", v.joinToString(","))
    var bubbleOn: Boolean
        get() = sp.getBoolean("bubbleOn", false)
        set(v) = sp.edit().putBoolean("bubbleOn", v).apply()
    /** 是否默认对每次翻译都套用翻译要求 */
    var alwaysInstruct: Boolean
        get() = sp.getBoolean("alwaysInstruct", false)
        set(v) = sp.edit().putBoolean("alwaysInstruct", v).apply()
}
