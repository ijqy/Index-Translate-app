package com.example.aitranslate

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.*

class SettingsActivity : Activity() {
    private val models = listOf("35B", "9B", "2B")
    private val modelIds = mapOf("35B" to "Index-Translate-35B-A3B", "9B" to "Index-Translate-9B", "2B" to "Index-Translate-2B")

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Ui.themeFor(this))
        super.onCreate(savedInstanceState)
        Ui.applyRecents(this)
        val ui = Ui(this); ui.setupScreen(this)
        val p = Prefs(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20f), ui.dp(20f), ui.dp(20f), ui.dp(28f))
            setBackgroundColor(ui.bg)
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(ui.text("‹", 30f).apply { setPadding(ui.dp(4f), 0, ui.dp(14f), ui.dp(4f)); setOnClickListener { finish() } })
        top.addView(ui.text("设置", 24f, bold = true))
        root.addView(top)
        var s = ui.card()
        fun section(t: String) {
            root.addView(ui.text(t, 13f, ui.onSurfaceVar, bold = true).apply { setPadding(ui.dp(8f), 0, 0, 0) }, ui.lp(22f))
            s = ui.card(); root.addView(s, ui.lp(8f))
        }
        section("翻译")
        s.addView(ui.text("文本模型（35B 最准，2B 最快；图片固定用 35B）", 13f, ui.onSurfaceVar))
        s.addView(ui.chips(models, (modelIds.entries.firstOrNull { it.value == p.model }?.key ?: "35B")) {
            p.model = modelIds[it]!!
        }, ui.lp(8f))
        fun langRow(label: String, get: () -> String, withAuto: Boolean, set: (String) -> Unit) {
            val row = LinearLayout(this@SettingsActivity).apply {
                gravity = Gravity.CENTER_VERTICAL; isClickable = true
                background = ui.ripple(ui.surfaceVar, ui.dp(14f).toFloat())
                setPadding(ui.dp(14f), ui.dp(12f), ui.dp(14f), ui.dp(12f))
            }
            row.addView(ui.text(label, 14f), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            val v = ui.text(get() + "  ›", 14f, ui.primary, bold = true)
            row.addView(v)
            row.setOnClickListener { ui.pickLang(label, withAuto, get()) { set(it); v.text = "$it  ›" } }
            s.addView(row, ui.lp(10f))
        }
        langRow("原文语言", { p.sourceLang }, true) { p.sourceLang = it }
        langRow("翻译成", { p.targetLang }, false) { p.targetLang = it }
        langRow("自动检测到原文就是目标语言时，改译成", { p.fallbackLang }, false) { p.fallbackLang = it }

        s.addView(ui.text("按你的要求翻译", 13f, ui.onSurfaceVar), ui.lp(16f))
        val insView = ui.text(p.instruction.ifBlank { "未设置（点击填写语气、格式、术语等要求）" }, 14f).apply {
            background = ui.ripple(ui.surfaceVar, ui.dp(14f).toFloat())
            setPadding(ui.dp(14f), ui.dp(12f), ui.dp(14f), ui.dp(12f)); isClickable = true
        }
        insView.setOnClickListener { ui.editInstruction(p) { insView.text = it.ifBlank { "未设置（点击填写语气、格式、术语等要求）" } } }
        s.addView(insView, ui.lp(8f))
        val sw = Switch(this@SettingsActivity).apply {
            text = "每次翻译都默认套用（含图片）"; isChecked = p.alwaysInstruct
            setTextColor(ui.onSurface); textSize = 14f
            setOnCheckedChangeListener { _, c -> p.alwaysInstruct = c }
            val st = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = android.content.res.ColorStateList(st, intArrayOf(ui.primary, ui.onSurfaceVar))
            trackTintList = android.content.res.ColorStateList(st, intArrayOf(ui.tonal, ui.surfaceVar))
        }
        s.addView(sw, ui.lp(10f))
        section("悬浮球菜单")
        s.addView(ui.text("点悬浮球时弹出哪些按钮", 13f, ui.onSurfaceVar), ui.lp(0f))
        run {
            val flow = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.VERTICAL }
            var row: LinearLayout? = null
            val sel = p.menuItems.toMutableSet()
            MENU_ALL.forEachIndexed { i, name ->
                if (i % 4 == 0) { row = LinearLayout(this@SettingsActivity); flow.addView(row, ui.lp(if (i == 0) 0f else 8f)) }
                val v = TextView(this@SettingsActivity).apply {
                    text = name; textSize = 14f; gravity = Gravity.CENTER; isClickable = true
                    setPadding(ui.dp(14f), 0, ui.dp(14f), 0)
                }
                fun paint() {
                    val on = name in sel
                    v.setTextColor(if (on) ui.onPrimary else ui.onSurfaceVar)
                    v.background = ui.ripple(if (on) ui.primary else ui.surfaceVar, ui.dp(18f).toFloat())
                }
                paint()
                v.setOnClickListener {
                    if (name in sel) { if (sel.size > 1) sel.remove(name) else { Toast.makeText(this, "至少留一个", Toast.LENGTH_SHORT).show(); return@setOnClickListener } }
                    else sel.add(name)
                    p.menuItems = MENU_ALL.filter { it in sel }; paint()
                }
                row!!.addView(v, LinearLayout.LayoutParams(0, ui.dp(36f), 1f).apply { marginEnd = ui.dp(6f) })
            }
            repeat((4 - MENU_ALL.size % 4) % 4) { row!!.addView(android.view.View(this@SettingsActivity), LinearLayout.LayoutParams(0, 1, 1f).apply { marginEnd = ui.dp(6f) }) }
            s.addView(flow, ui.lp(8f))
        }
        section("实时字幕识别")
        s.addView(ui.text("识别模型", 13f, ui.onSurfaceVar))
        val asr = linkedMapOf("Whisper V3 准" to "whisper-large-v3", "Whisper Turbo" to "whisper-large-v3-turbo", "SenseVoice 快" to "sensevoice", "Index Echo（中英）" to "echo")
        s.addView(ui.chips(asr.keys.toList(), asr.entries.firstOrNull { it.value == p.asrModel }?.key ?: "Whisper V3 准") {
            p.asrModel = asr[it]!!
        }, ui.lp(8f))
        s.addView(keyList(ui, "groq", "Groq Key", "Whisper 用，console.groq.com 免费申请。多个 Key 轮流用，躲开每分钟限额", { p.groqKey }, { p.groqKey = it }), ui.lp(16f))
        s.addView(keyList(ui, "sf", "硅基流动 Key", "SenseVoice 用，cloud.siliconflow.cn 免费申请，国内直连", { p.sfKey }, { p.sfKey = it }), ui.lp(16f))

        fun switch(label: String, on: Boolean, set: (Boolean) -> Unit) = Switch(this@SettingsActivity).apply {
            text = label; isChecked = on; setTextColor(ui.onSurface); textSize = 14f
            setOnCheckedChangeListener { _, c -> set(c) }
            val st = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = android.content.res.ColorStateList(st, intArrayOf(ui.primary, ui.onSurfaceVar))
            trackTintList = android.content.res.ColorStateList(st, intArrayOf(ui.tonal, ui.surfaceVar))
        }
        fun hint(t: String) = ui.text(t, 12f, ui.onSurfaceVar)
        fun rowBtn(label: String, value: String, onClick: (TextView) -> Unit): LinearLayout {
            val row = LinearLayout(this@SettingsActivity).apply {
                gravity = Gravity.CENTER_VERTICAL; isClickable = true
                background = ui.ripple(ui.surfaceVar, ui.dp(14f).toFloat())
                setPadding(ui.dp(14f), ui.dp(12f), ui.dp(14f), ui.dp(12f))
            }
            row.addView(ui.text(label, 14f), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            val v = ui.text("$value  ›", 14f, ui.primary, bold = true); row.addView(v)
            row.setOnClickListener { onClick(v) }
            return row
        }

        section("实时字幕翻译")
        s.addView(ui.text("出字幕方式", 13f, ui.onSurfaceVar))
        s.addView(ui.chips(listOf("说完再出", "边听边出"), if (p.capDrafts) "边听边出" else "说完再出") { p.capDrafts = it == "边听边出" }, ui.lp(8f))
        s.addView(hint("边听边出：说话中途先出灰色草稿，说完变白。要用硅基流动或 3 个以上 Groq Key，识别请求会多好几倍"), ui.lp(6f))
        s.addView(ui.text("结合上文来翻译", 13f, ui.onSurfaceVar), ui.lp(16f))
        val ctxMap = linkedMapOf("关" to 0, "1 句" to 1, "2 句" to 2, "3 句" to 3, "5 句" to 5)
        s.addView(ui.chips(ctxMap.keys.toList(), ctxMap.entries.firstOrNull { it.value == p.capContext }?.key ?: "2 句") { p.capContext = ctxMap[it]!! }, ui.lp(8f))
        s.addView(hint("参考最近几句已定稿的原文，理解指代和省略；越多越慢"), ui.lp(6f))
        s.addView(ui.text("内容类型", 13f, ui.onSurfaceVar), ui.lp(16f))
        val sceneHint = hint("")
        val sceneName = ui.text("", 15f, bold = true)
        fun paintScene() {
            val c = CaptionScene.current(p); sceneName.text = c.name
            val n = c.terms[p.targetLang]?.size ?: 0
            sceneHint.text = when {
                c.bg.isBlank() && c.count() == 0 -> "不带额外背景和术语"
                else -> "背景说明${if (c.bg.isBlank()) "（空）" else ""}和 $n 条术语（译成${p.targetLang}时）会在翻译时一起交给模型" +
                    (if (n == 0 && c.count() > 0) "；其他语言的术语现在用不上" else "")
            }
        }
        val pick = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = ui.ripple(ui.surfaceVar, ui.dp(14f).toFloat())
            setPadding(ui.dp(16f), 0, ui.dp(14f), 0)
            addView(sceneName, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(ui.text("▾", 14f, ui.onSurfaceVar))
        }
        pick.setOnClickListener { v ->
            ui.popup(v, CaptionScene.load(p).map { it.name }, CaptionScene.current(p).name) { p.capScene = it; paintScene() }
        }
        s.addView(pick, ui.lp(8f, ui.dp(50f)))
        val ops = LinearLayout(this).apply { gravity = Gravity.END }
        fun op(t: String, act: () -> Unit) = ops.addView(TextView(this).apply {
            text = t; textSize = 14f; gravity = Gravity.CENTER; setTextColor(ui.onTonal)
            background = ui.ripple(ui.tonal, ui.dp(18f).toFloat()); setPadding(ui.dp(20f), 0, ui.dp(20f), 0)
            setOnClickListener { act() }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, ui.dp(38f)).apply { marginStart = ui.dp(8f) })
        op("编辑") { editProfile(ui, p, CaptionScene.current(p).name) { paintScene() } }
        op("新建") { editProfile(ui, p, null) { paintScene() } }
        s.addView(ops, ui.lp(8f))
        s.addView(sceneHint, ui.lp(6f))
        paintScene()

        s.addView(ui.text("Jev 智能辅助（OpenCode Zen 免费接口，不用 Key）", 13f, ui.onSurfaceVar), ui.lp(18f))
        s.addView(switch("智能断句", p.jevSeg) { p.jevSeg = it }, ui.lp(6f))
        s.addView(hint("停顿时先问 Jev 这句话说完没有，没说完就等下文接上再翻（最长 10 秒）"), ui.lp(2f))
        s.addView(switch("检查译文", p.jevCheck) { p.jevCheck = it }, ui.lp(10f))
        s.addView(hint("每句翻完检查一次，发现加了否定、意思翻反、比喻直译就重译一次"), ui.lp(2f))
        s.addView(hint("只发送识别出的文字，不发音频；接口不稳定时自动按没开处理"), ui.lp(6f))

        s.addView(ui.text("字幕文本", 13f, ui.onSurfaceVar), ui.lp(18f))
        s.addView(switch("保存字幕文本", p.capSave) { p.capSave = it }, ui.lp(6f))
        s.addView(hint("每句定稿的原文和译文写进文本文件，一场字幕一个文件"), ui.lp(2f))
        s.addView(rowBtn("已保存的字幕", "${Transcript.list(this).size} 个") { v -> showTranscripts(ui) { v.text = "${Transcript.list(this).size} 个  ›" } }, ui.lp(10f))

        section("后台")
        s.addView(switch("留在最近任务里", p.keepRecents) { p.keepRecents = it; Ui.applyRecents(this) })
        s.addView(hint("关掉时，退出 App 后最近任务里看不到 Index（悬浮球、磁贴、字幕照常能用）；打开就和普通 App 一样留在后台列表里，方便切回来"), ui.lp(2f))

        setContentView(ScrollView(this).apply { setBackgroundColor(ui.bg); addView(root) })
    }

    private fun showTranscripts(ui: Ui, onChange: () -> Unit) {
        val files = Transcript.list(this)
        if (files.isEmpty()) { Toast.makeText(this, "还没有保存的字幕", Toast.LENGTH_SHORT).show(); return }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var dlg: android.app.Dialog? = null
        files.take(50).forEach { f ->
            val name = f.name.removePrefix("字幕_").removeSuffix(".txt").let {
                runCatching { "${it.substring(0, 4)}-${it.substring(4, 6)}-${it.substring(6, 8)} ${it.substring(9, 11)}:${it.substring(11, 13)}" }.getOrDefault(it)
            }
            val n = runCatching { f.readLines().count { l -> l.startsWith("[") } }.getOrDefault(0)
            col.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; isClickable = true
                background = ui.ripple(ui.surfaceVar, ui.dp(12f).toFloat())
                setPadding(ui.dp(14f), ui.dp(10f), ui.dp(14f), ui.dp(10f))
                addView(ui.text(name, 14f), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(ui.text("$n 句", 13f, ui.onSurfaceVar))
                setOnClickListener { dlg?.dismiss(); openTranscript(ui, f, onChange) }
            }, ui.lp(6f))
        }
        dlg = ui.dialog("已保存的字幕", ScrollView(this).apply { addView(col); layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, minOf(ui.dp(420f), ui.dp(60f) * files.size)) },
            listOf("关闭" to null)).also { it.show() }
    }

    private fun openTranscript(ui: Ui, f: java.io.File, onChange: () -> Unit) {
        val txt = runCatching { f.readText() }.getOrDefault("")
        val tv = ui.text(txt, 14f).apply { setTextIsSelectable(true); setLineSpacing(0f, 1.15f) }
        val sv = ScrollView(this).apply { addView(tv); layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(420f)) }
        ui.dialog(f.name.removeSuffix(".txt"), sv, listOf(
            "删除" to { f.delete(); onChange(); Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show() },
            "复制" to {
                (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("字幕", txt))
                Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
            },
            "分享" to {
                startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, txt); putExtra(android.content.Intent.EXTRA_SUBJECT, f.name)
                }, "分享字幕"))
            },
        )).show()
    }

    /** 多 Key 列表：每个一行（打码 + 测试结果 + 删除），底下「添加」「全部测试」 */
    private fun keyList(ui: Ui, kind: String, title: String, sub: String, get: () -> String, set: (String) -> Unit): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = ui.text("", 13f, ui.onSurfaceVar)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val results = HashMap<String, Pair<Boolean?, String>>()
        fun keys() = get().split(Regex("[\\s,，;；]+")).map { it.trim() }.filter { it.length > 10 }.distinct()
        lateinit var redraw: () -> Unit
        fun runTest(k: String) {
            results[k] = null to "测试中…"; redraw()
            Thread { val r = KeyCheck.test(kind, k); runOnUiThread { results[k] = r.first to r.second; redraw() } }.start()
        }
        redraw = {
            val ks = keys()
            head.text = "$title · ${if (ks.isEmpty()) "未填" else "${ks.size} 个"}"
            list.removeAllViews()
            ks.forEachIndexed { i, k ->
                val row = LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    background = ui.round(ui.surfaceVar, ui.dp(12f).toFloat())
                    setPadding(ui.dp(12f), 0, ui.dp(4f), 0)
                }
                row.addView(ui.text("${i + 1}", 12f, ui.onSurfaceVar, bold = true), LinearLayout.LayoutParams(ui.dp(20f), WRAP_CONTENT))
                row.addView(ui.text(KeyCheck.mask(k), 14f).apply { typeface = android.graphics.Typeface.MONOSPACE }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                val r = results[k]
                val st = ui.text(r?.second ?: "测试", 12f, when (r?.first) { true -> ui.primary; false -> ui.error; else -> ui.onSurfaceVar }, bold = r?.first != null).apply {
                    gravity = Gravity.CENTER; setPadding(ui.dp(10f), 0, ui.dp(10f), 0)
                    background = ui.ripple(android.graphics.Color.TRANSPARENT, ui.dp(14f).toFloat())
                    setOnClickListener { runTest(k) }
                }
                row.addView(st, LinearLayout.LayoutParams(WRAP_CONTENT, ui.dp(32f)))
                row.addView(ui.text("✕", 15f, ui.onSurfaceVar).apply {
                    gravity = Gravity.CENTER
                    background = ui.ripple(android.graphics.Color.TRANSPARENT, ui.dp(16f).toFloat())
                    setOnClickListener { set(keys().filter { it != k }.joinToString("\n")); results.remove(k); redraw() }
                }, LinearLayout.LayoutParams(ui.dp(36f), ui.dp(36f)))
                list.addView(row, ui.lp(if (i == 0) 8f else 6f, ui.dp(44f)))
            }
        }
        box.addView(head)
        box.addView(ui.text(sub, 12f, ui.onSurfaceVar), ui.lp(2f))
        box.addView(list)
        val btns = LinearLayout(this)
        fun pillBtn(t: String, filled: Boolean, act: () -> Unit) = btns.addView(TextView(this).apply {
            text = t; textSize = 13f; gravity = Gravity.CENTER
            setTextColor(if (filled) ui.onPrimary else ui.onTonal)
            background = ui.ripple(if (filled) ui.primary else ui.tonal, ui.dp(18f).toFloat())
            setPadding(ui.dp(16f), 0, ui.dp(16f), 0); setOnClickListener { act() }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, ui.dp(36f)).apply { marginEnd = ui.dp(8f) })
        pillBtn("＋ 添加", true) {
            val input = EditText(this).apply {
                hint = "粘贴 Key，可以一次贴多个（换行、空格或逗号隔开）"; textSize = 14f; minLines = 3; maxLines = 8; gravity = Gravity.TOP
                setTextColor(ui.onSurface); setHintTextColor(ui.onSurfaceVar)
                background = ui.round(ui.surfaceVar, ui.dp(14f).toFloat())
                setPadding(ui.dp(14f), ui.dp(12f), ui.dp(14f), ui.dp(12f))
            }
            val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            wrap.addView(input)
            wrap.addView(ui.text("从剪贴板粘贴", 13f, ui.primary, bold = true).apply {
                setPadding(0, ui.dp(10f), 0, 0)
                setOnClickListener {
                    val clip = (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip
                    val t = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this@SettingsActivity)?.toString().orEmpty()
                    if (t.isNotBlank()) input.append((if (input.text.isNotEmpty()) "\n" else "") + t.trim())
                }
            })
            ui.dialog("添加 $title", wrap, listOf("取消" to null, "添加" to {
                val old = keys()
                val add = input.text.toString().split(Regex("[\\s,，;；]+")).map { it.trim() }.filter { it.length > 10 && it !in old }.distinct()
                if (add.isEmpty()) Toast.makeText(this, "没有新的 Key", Toast.LENGTH_SHORT).show()
                else { set((old + add).joinToString("\n")); redraw(); add.forEach { runTest(it) }
                    Toast.makeText(this, "已添加 ${add.size} 个", Toast.LENGTH_SHORT).show() }
            })).show()
        }
        pillBtn("全部测试", false) { keys().forEach { runTest(it) } }
        box.addView(btns, ui.lp(10f))
        redraw()
        return box
    }

    /** 编辑/新建内容类型：名字、背景、按译文语言分开的术语（左原文 → 右译法），可让模型生成 */
    private fun editProfile(ui: Ui, p: Prefs, name: String?, onDone: () -> Unit) {
        val all = CaptionScene.load(p)
        val orig = name?.let { n -> all.firstOrNull { it.name == n } }
        val prof = orig?.copy() ?: CaptionScene.Profile("", "")
        fun edit(t: String, h: String, multi: Boolean) = EditText(this).apply {
            setText(t); hint = h; textSize = 14f; setTextColor(ui.onSurface); setHintTextColor(ui.onSurfaceVar)
            if (multi) { minLines = 2; maxLines = 6; gravity = Gravity.TOP } else isSingleLine = true
            background = ui.round(ui.surfaceVar, ui.dp(12f).toFloat())
            setPadding(ui.dp(12f), ui.dp(9f), ui.dp(12f), ui.dp(9f))
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(ui.text("名字", 13f, ui.onSurfaceVar))
        val nameIn = edit(prof.name, "例如：FPS 比赛解说", false); box.addView(nameIn, ui.lp(6f))
        box.addView(ui.text("背景说明", 13f, ui.onSurfaceVar), ui.lp(14f))
        val bgIn = edit(prof.bg, "这是什么内容、谁在说话、什么场合", true); box.addView(bgIn, ui.lp(6f))
        box.addView(ui.text("术语", 13f, ui.onSurfaceVar), ui.lp(14f))
        var lang = p.targetLang
        val langRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        langRow.addView(ui.text("译文是", 14f), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = ui.dp(10f) })
        val langBtn = ui.text("", 14f, ui.onTonal, bold = true).apply {
            gravity = Gravity.CENTER_VERTICAL; background = ui.ripple(ui.tonal, ui.dp(16f).toFloat()); setPadding(ui.dp(14f), 0, ui.dp(14f), 0)
        }
        langRow.addView(langBtn, LinearLayout.LayoutParams(WRAP_CONTENT, ui.dp(34f)))
        box.addView(langRow, ui.lp(6f))
        val langHint = ui.text("", 12f, ui.onSurfaceVar); box.addView(langHint, ui.lp(4f))
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(rows, ui.lp(4f))
        val fields = mutableListOf<Pair<EditText, EditText>>()
        fun collect() {
            prof.terms[lang] = fields.mapNotNull { (a, b) ->
                val x = a.text.toString().trim(); val y = b.text.toString().trim()
                if (x.isNotEmpty() && y.isNotEmpty()) x.take(40) to y.take(40) else null
            }.distinctBy { it.first }.toMutableList()
        }
        fun addRow(a: String, b: String) {
            val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val ea = edit(a, "视频里说的", false); val eb = edit(b, "想要的译法", false)
            r.addView(ea, LinearLayout.LayoutParams(0, ui.dp(40f), 1f))
            r.addView(ui.text("→", 14f, ui.onSurfaceVar).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(ui.dp(26f), WRAP_CONTENT))
            r.addView(eb, LinearLayout.LayoutParams(0, ui.dp(40f), 1f))
            val pair = ea to eb
            r.addView(ui.text("✕", 14f, ui.onSurfaceVar).apply {
                gravity = Gravity.CENTER; background = ui.ripple(android.graphics.Color.TRANSPARENT, ui.dp(16f).toFloat())
                setOnClickListener { fields.remove(pair); rows.removeView(r) }
            }, LinearLayout.LayoutParams(ui.dp(34f), ui.dp(34f)))
            fields += pair
            rows.addView(r, ui.lp(6f))
        }
        fun showLang() {
            langBtn.text = "$lang ▾"
            langHint.text = (if (lang == p.targetLang) "现在字幕翻成$lang，这些术语会用上。" else "现在字幕翻成${p.targetLang}，这组术语暂时用不上。") +
                "左边写视频里说的词，右边写想要的译法。"
            rows.removeAllViews(); fields.clear()
            prof.terms[lang].orEmpty().forEach { addRow(it.first, it.second) }
        }
        langBtn.setOnClickListener { collect(); ui.pickLang("术语的译文语言", false, lang) { lang = it; showLang() } }
        showLang()
        box.addView(ui.text("＋ 加一条", 14f, ui.primary, bold = true).apply {
            gravity = Gravity.CENTER; setOnClickListener { addRow("", "") }
        }, ui.lp(8f, ui.dp(40f)))
        val genRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val maxIn = edit("20", "", false).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER; gravity = Gravity.CENTER }
        val genBtn = ui.text("生成术语", 14f, ui.onPrimary, bold = true).apply {
            gravity = Gravity.CENTER; background = ui.ripple(ui.primary, ui.dp(18f).toFloat()); setPadding(ui.dp(16f), 0, ui.dp(16f), 0)
        }
        genRow.addView(genBtn, LinearLayout.LayoutParams(WRAP_CONTENT, ui.dp(36f)))
        genRow.addView(ui.text("最多", 14f).apply { setPadding(ui.dp(14f), 0, ui.dp(6f), 0) })
        genRow.addView(maxIn, LinearLayout.LayoutParams(ui.dp(56f), ui.dp(36f)))
        genRow.addView(ui.text("条", 14f).apply { setPadding(ui.dp(6f), 0, 0, 0) })
        box.addView(genRow, ui.lp(6f))
        box.addView(ui.text("「生成术语」按名字和背景想出这类内容的常见词，只往下加，不改也不删已有的。", 12f, ui.onSurfaceVar), ui.lp(6f))
        genBtn.setOnClickListener {
            val nm = nameIn.text.toString().trim(); val bg = bgIn.text.toString().trim()
            if (nm.isEmpty() && bg.isEmpty()) { Toast.makeText(this, "先写名字或背景说明", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            collect()
            val have = prof.terms[lang].orEmpty().toList(); val l = lang
            val max = (maxIn.text.toString().toIntOrNull() ?: 20).coerceIn(1, 60)
            genBtn.isEnabled = false; genBtn.text = "生成中…"
            Thread {
                val r = runCatching { CaptionScene.generate(nm, bg, l, max, have) }
                runOnUiThread {
                    genBtn.isEnabled = true; genBtn.text = "生成术语"
                    r.onSuccess { add -> if (l == lang) add.forEach { addRow(it.first, it.second) }
                        Toast.makeText(this, if (add.isEmpty()) "没想出新的术语" else "加了 ${add.size} 条，看一下再保存", Toast.LENGTH_SHORT).show() }
                     .onFailure { Toast.makeText(this, "生成失败：${it.message?.take(40)}", Toast.LENGTH_SHORT).show() }
                }
            }.start()
        }
        val maxH = (resources.displayMetrics.heightPixels * 0.62f).toInt()
        val scroll = object : ScrollView(this) {
            override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, android.view.View.MeasureSpec.makeMeasureSpec(maxH, android.view.View.MeasureSpec.AT_MOST))
        }.apply { addView(box, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }
        val btns = mutableListOf<Pair<String, (() -> Unit)?>>()
        if (orig != null && all.size > 1 && orig.name != "通用") btns += "删除" to {
            ui.dialog("删除「${orig.name}」？", ui.text("背景和术语都会删掉", 14f, ui.onSurfaceVar), listOf("取消" to null, "删除" to {
                all.removeAll { it.name == orig.name }; CaptionScene.save(p, all)
                if (p.capScene == orig.name) p.capScene = all.first().name
                onDone()
            })).show()
        }
        btns += "取消" to null
        btns += "保存" to save@{
            collect()
            val nm = nameIn.text.toString().trim().take(30)
            if (nm.isEmpty()) { Toast.makeText(this, "名字不能空", Toast.LENGTH_SHORT).show(); return@save }
            if (all.any { it.name == nm && it !== orig }) { Toast.makeText(this, "已有同名的类型", Toast.LENGTH_SHORT).show(); return@save }
            prof.name = nm; prof.bg = bgIn.text.toString().trim()
            prof.terms.entries.removeAll { it.value.isEmpty() }
            val i = if (orig != null) all.indexOf(orig) else -1
            if (i >= 0) all[i] = prof else all += prof
            CaptionScene.save(p, all); p.capScene = nm
            Toast.makeText(this, "已保存并切到「$nm」", Toast.LENGTH_SHORT).show()
            onDone()
        }
        ui.dialog(if (orig != null) "编辑「${orig.name}」" else "新建内容类型", scroll, btns).show()
    }
}
