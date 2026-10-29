package com.example.aitranslate

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/** 统一配色与组件：跟随深色模式，Android 12+ 跟随系统取色，否则 B 站粉 */
class Ui(private val ctx: Context) {
    val dark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    fun dp(v: Float) = (v * ctx.resources.displayMetrics.density).toInt()
    private fun sys(id: Int, fb: String) =
        if (Build.VERSION.SDK_INT >= 31) ctx.getColor(id) else Color.parseColor(fb)

    val bg = if (dark) Color.parseColor("#141317") else Color.parseColor("#F7F5FA")
    val surface = if (dark) Color.parseColor("#1F1F24") else Color.WHITE
    val surfaceVar = if (dark) Color.parseColor("#2C2C33") else Color.parseColor("#F1EEF5")
    val onSurface = if (dark) Color.parseColor("#ECEAF0") else Color.parseColor("#1C1B20")
    val onSurfaceVar = if (dark) Color.parseColor("#A9A6B1") else Color.parseColor("#6B6875")
    // 全 App 统一用图标的 Index 粉（不再跟系统取色，免得有的地方蓝有的地方粉）
    val primary = if (dark) Color.parseColor("#FF8DB0") else Color.parseColor("#F25D8E")
    val onPrimary = if (dark) Color.parseColor("#3A0A1C") else Color.WHITE
    val tonal = if (dark) Color.parseColor("#4A2533") else Color.parseColor("#FFE3EC")
    val onTonal = if (dark) Color.parseColor("#FFD9E4") else Color.parseColor("#5C1A33")
    val error = Color.parseColor("#E5484D")

    fun round(color: Int, r: Float, topOnly: Boolean = false) = GradientDrawable().apply {
        setColor(color)
        if (topOnly) cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) else cornerRadius = r
    }
    fun ripple(color: Int, r: Float) =
        RippleDrawable(ColorStateList.valueOf(Color.argb(40, 0, 0, 0)), round(color, r), null)

    fun text(s: String, size: Float = 15f, color: Int = onSurface, bold: Boolean = false) =
        TextView(ctx).apply {
            text = s; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setLineSpacing(0f, 1.2f)
        }

    fun pill(label: String, filled: Boolean, weight: Boolean = true, onClick: () -> Unit) =
        TextView(ctx).apply {
            text = label; textSize = 15f; gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (filled) onPrimary else onTonal)
            setPadding(dp(20f), 0, dp(20f), 0)
            background = ripple(if (filled) primary else tonal, dp(24f).toFloat())
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = if (weight) LinearLayout.LayoutParams(0, dp(48f), 1f).apply {
                marginStart = dp(4f); marginEnd = dp(4f)
            } else LinearLayout.LayoutParams(WRAP_CONTENT, dp(48f))
        }

    /** 单选胶囊组 */
    fun chips(items: List<String>, selected: String, onPick: (String) -> Unit): View {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val views = mutableListOf<TextView>()
        fun paint(sel: String) = views.forEach { v ->
            val on = v.tag == sel
            v.setTextColor(if (on) onPrimary else onSurfaceVar)
            v.background = ripple(if (on) primary else surfaceVar, dp(18f).toFloat())
        }
        items.forEach { item ->
            val v = TextView(ctx).apply {
                text = item; tag = item; textSize = 13f; gravity = Gravity.CENTER
                setPadding(dp(14f), 0, dp(14f), 0)
                isClickable = true
                setOnClickListener { paint(item); onPick(item) }
            }
            views += v
            row.addView(v, LinearLayout.LayoutParams(WRAP_CONTENT, dp(36f)).apply { marginEnd = dp(8f) })
        }
        paint(selected)
        return HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false; addView(row)
        }
    }

    fun card() = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = round(surface, dp(24f).toFloat())
        setPadding(dp(20f), dp(18f), dp(20f), dp(18f))
    }

    fun lp(top: Float = 0f, h: Int = WRAP_CONTENT) =
        LinearLayout.LayoutParams(MATCH_PARENT, h).apply { topMargin = dp(top) }

    /** 普通页面：主题、状态栏随深浅色 */
    fun setupScreen(a: Activity) {
        a.window.statusBarColor = bg
        a.window.navigationBarColor = bg
        if (!dark) @Suppress("DEPRECATION")
            a.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
    }

    private fun dialogTheme() = if (dark) android.R.style.Theme_Material_Dialog_Alert
        else android.R.style.Theme_Material_Light_Dialog_Alert

    /** 统一样式的弹窗：圆角卡片 + 标题 + 内容 + 右下角文字按钮（不用系统 AlertDialog 的灰底青色） */
    fun dialog(title: String, content: View, buttons: List<Pair<String, (() -> Unit)?>>): android.app.Dialog {
        val d = android.app.Dialog(ctx)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = round(surface, dp(26f).toFloat())
            setPadding(dp(22f), dp(20f), dp(22f), dp(12f))
        }
        box.addView(text(title, 18f, bold = true))
        box.addView(content, lp(14f))
        if (buttons.isNotEmpty()) {
            val row = LinearLayout(ctx).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
            buttons.forEachIndexed { k, (label, act) ->
                val last = k == buttons.lastIndex
                row.addView(TextView(ctx).apply {
                    text = label; textSize = 15f; gravity = Gravity.CENTER
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(if (last) onPrimary else onTonal)
                    background = ripple(if (last) primary else tonal, dp(20f).toFloat())
                    setPadding(dp(18f), 0, dp(18f), 0)
                    setOnClickListener { d.dismiss(); act?.invoke() }
                }, LinearLayout.LayoutParams(WRAP_CONTENT, dp(40f)).apply { marginStart = dp(8f) })
            }
            box.addView(row, lp(14f).apply { bottomMargin = dp(6f) })
        }
        d.setContentView(box)
        d.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setLayout((ctx.resources.displayMetrics.widthPixels * 0.88f).toInt(), WRAP_CONTENT)
            setDimAmount(0.4f)
        }
        return d
    }

    /** 统一样式的下拉菜单（代替系统白色 PopupMenu） */
    fun popup(anchor: View, items: List<String>, current: String, onPick: (String) -> Unit) {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = round(if (dark) Color.parseColor("#2C2C33") else Color.WHITE, dp(18f).toFloat())
            setPadding(dp(6f), dp(6f), dp(6f), dp(6f)); elevation = dp(10f).toFloat()
        }
        val pw = android.widget.PopupWindow(col, dp(150f), WRAP_CONTENT, true).apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            elevation = dp(10f).toFloat(); isOutsideTouchable = true
        }
        items.forEach { it ->
            val on = it == current
            col.addView(LinearLayout(ctx).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = ripple(if (on) tonal else Color.TRANSPARENT, dp(12f).toFloat())
                setPadding(dp(14f), 0, dp(12f), 0)
                addView(text(it, 15f, if (on) onTonal else onSurface), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                if (on) addView(text("✓", 15f, primary, bold = true))
                setOnClickListener { _ -> pw.dismiss(); onPick(it) }
            }, LinearLayout.LayoutParams(MATCH_PARENT, dp(42f)))
        }
        pw.showAsDropDown(anchor, -dp(60f), dp(6f))
    }

    private fun field(hintText: String) = android.widget.EditText(ctx).apply {
        hint = hintText; textSize = 14f
        setTextColor(onSurface); setHintTextColor(onSurfaceVar)
        background = round(surfaceVar, dp(14f).toFloat())
        setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
    }

    /** 可搜索的语种选择 */
    fun pickLang(title: String, withAuto: Boolean, current: String, onPick: (String) -> Unit) {
        val base = (if (withAuto) listOf(Lang.AUTO) else emptyList()) + Lang.all
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val search = field("搜索语种").apply { isSingleLine = true }
        box.addView(search, lp(0f, dp(42f)))
        val shown = base.toMutableList()
        var dlg: android.app.Dialog? = null
        val adapter = object : android.widget.BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(i: Int) = shown[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, cv: View?, parent: android.view.ViewGroup?): View {
                val name = shown[i]; val on = name == current
                return LinearLayout(ctx).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    background = ripple(if (on) tonal else Color.TRANSPARENT, dp(12f).toFloat())
                    setPadding(dp(14f), 0, dp(14f), 0)
                    minimumHeight = dp(46f)
                    addView(text(name, 15f, if (on) onTonal else onSurface), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    if (on) addView(text("✓", 16f, primary, bold = true))
                    layoutParams = android.widget.AbsListView.LayoutParams(MATCH_PARENT, dp(46f))
                }
            }
        }
        val list = android.widget.ListView(ctx).apply {
            this.adapter = adapter; divider = null; selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
        }
        box.addView(list, lp(10f, dp(360f)))
        base.indexOf(current).takeIf { it > 3 }?.let { list.setSelection(it - 2) }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) {
                val q = e.toString().trim()
                shown.clear(); shown.addAll(base.filter { q.isEmpty() || it.contains(q) }); adapter.notifyDataSetChanged()
            }
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
        })
        list.setOnItemClickListener { _, _, pos, _ -> dlg?.dismiss(); onPick(shown[pos]) }
        dlg = dialog(title, box, listOf("取消" to null)).also { it.show() }
    }

    /** 编辑“按要求翻译”的指令 */
    fun editInstruction(p: Prefs, onDone: (String) -> Unit) {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val et = field("例如：用自然口语表达 / 保留 Markdown 格式 / 术语干预（每行一条，如 セイア: 圣亚）").apply {
            setText(p.instruction); minLines = 3; maxLines = 8; gravity = Gravity.TOP
        }
        box.addView(text("快捷要求（点一下添加一行）", 12f, onSurfaceVar))
        box.addView(chips(PRESETS.keys.toList(), "") { k ->
            val cur = et.text.toString().trimEnd()
            et.setText(if (cur.isEmpty()) PRESETS[k] else "$cur\n${PRESETS[k]}"); et.setSelection(et.text.length)
        }, lp(8f))
        box.addView(et, lp(12f))
        box.addView(text("“目标语言”会自动替换为当前目标语种。2B 模型不遵循指令，会自动改用 9B。", 12f, onSurfaceVar), lp(8f))
        dialog("按你的要求翻译", box, listOf(
            "清空" to { p.instruction = ""; onDone("") },
            "取消" to null,
            "确定" to { p.instruction = et.text.toString().trim(); onDone(p.instruction) })).apply {
            window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            show()
        }
    }

    companion object {
        val PRESETS = linkedMapOf(
            "自然口语" to "用自然口语表达，语气轻松。",
            "正式书面" to "使用正式、专业的书面语。",
            "敬体日语" to "使用敬体(です・ます调)，语气客气得体。",
            "保留 Markdown" to "严格保留所有 Markdown 格式（标题、加粗、列表）。",
            "JSON 输出" to "以合法的 JSON 格式输出，包含 source、translation 和 confidence 三个字段，不要包含其他说明。",
            "数字转单词" to "将译文中所有的阿拉伯数字全部拼写为目标语言的单词。",
            "保留网络梗" to "保留原文网络流行语和梗的喜感，译成目标语言里地道的对应说法。",
            "术语示例" to "大模型: Frontier Model",
        )

        /** 「留在最近任务里」开关：只改当前 App 主任务，不碰框选/翻译这些单独的小窗口 */
        @Suppress("DEPRECATION")
        fun applyRecents(a: Activity) = runCatching {
            val am = a.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.appTasks.filter { it.taskInfo.id == a.taskId }.forEach { it.setExcludeFromRecents(!Prefs(a).keepRecents) }
        }

        fun themeFor(ctx: Context) =
            if ((ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES) R.style.IndexDark
            else R.style.IndexLight
    }
}
