package com.example.aitranslate

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*

/** 贴着悬浮球的迷你小窗：一排图标（框选/全屏/输入/拍照/对话）+ 目标语种，输入框和结果按需展开，尽量少挡屏幕 */
class FloatPanel(
    private val ctx: Context,
    private val type: Int,
    private val onCapture: (full: Boolean) -> Unit,
    private val onDismiss: () -> Unit,
    /** 悬浮球位置：是否在右边、y、球大小 —— 小窗贴着球弹出 */
    private val anchor: () -> Triple<Boolean, Int, Int>,
    /** 连续翻译（Gal 对话框） */
    private val onLive: () -> Unit = {},
    /** 框选后只提取原文 */
    private val onOcr: () -> Unit = {},
) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val ui = Ui(ctx)
    private val prefs = Prefs(ctx)
    private fun dp(v: Float) = ui.dp(v)
    private var root: FrameLayout? = null
    private var wlp: WindowManager.LayoutParams? = null
    private var dragged = false
    private lateinit var srcBtn: TextView
    private lateinit var tgtBtn: TextView
    private lateinit var mainBox: LinearLayout
    private lateinit var langBox: LinearLayout
    private lateinit var input: EditText
    private lateinit var clearBtn: ImageView
    private lateinit var resultBox: LinearLayout
    private lateinit var thumb: ImageView
    private lateinit var resultText: TextView
    private lateinit var resultLang: TextView
    private lateinit var progress: ProgressBar
    private var gen = 0
    private var lastBitmap: Bitmap? = null
    private lateinit var origBtn: TextView
    private var showingOrig = false
    private var cacheTrans: String? = null
    private var cacheOrig: String? = null
    val showing get() = root != null
    /** 挂起为悬浮球时保留的窗口（内容、译文都还在），再点悬浮球原样恢复 */
    private var parked: FrameLayout? = null
    private var parkedLp: WindowManager.LayoutParams? = null
    private fun alive() = root != null || parked != null
    /** 有悬浮球才能挂起 */
    var canMinimize = true

    private fun icon(res: Int, tint: Int, sizeDp: Float = 20f) = ImageView(ctx).apply {
        setImageResource(res); imageTintList = ColorStateList.valueOf(tint)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun circleBtn(res: Int, bg: Int, tint: Int, size: Float = 34f, iconSize: Float = 18f, click: () -> Unit) =
        FrameLayout(ctx).apply {
            background = ui.ripple(bg, dp(size / 2).toFloat())
            addView(icon(res, tint, iconSize), FrameLayout.LayoutParams(dp(iconSize), dp(iconSize), Gravity.CENTER))
            layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
            setOnClickListener { click() }
        }

    fun toggle() = if (showing) hide() else show()

    fun show() {
        if (root != null) return
        val pk = parked; val pl = parkedLp
        if (pk != null && pl != null) {
            parked = null; parkedLp = null
            runCatching { wm.addView(pk, pl) }.onSuccess { root = pk; wlp = pl; return }
        }
        val r = object : FrameLayout(ctx) {
            override fun dispatchKeyEvent(e: KeyEvent): Boolean {
                if (e.keyCode == KeyEvent.KEYCODE_BACK && e.action == KeyEvent.ACTION_UP) {
                    if (langBox.visibility == View.VISIBLE) closeLang() else hide(); return true
                }
                return super.dispatchKeyEvent(e)
            }
        }
        r.addView(build(), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        r.setOnTouchListener { _, e ->
            // 已经有译文/输入/语音时，点屏幕别处不关闭；空小窗才点外面收起
            // 点了别的应用：交出输入焦点，别的应用的输入框才能弹出输入法
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) setFocusable(false)
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE && resultBox.visibility != View.VISIBLE &&
                inRow.visibility != View.VISIBLE && voiceRow.visibility != View.VISIBLE && langBox.visibility != View.VISIBLE) hide()
            false
        }
        val m = ShotFlow.real(ctx)
        val (right, by, bs) = anchor()
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or (if (right) Gravity.END else Gravity.START)
            x = bs + dp(2f)
            // 横屏时屏幕矮，上限可能比下限还小（以前这里会直接崩溃）
            y = (by - dp(6f)).coerceIn(0, maxOf(0, m.heightPixels - dp(300f)))
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            if (dragged) { gravity = Gravity.TOP or Gravity.START; x = savedX; y = savedY }
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            windowAnimations = android.R.style.Animation_Dialog
        }
        wm.addView(r, lp); root = r; wlp = lp
        refreshLangs()
    }

    /** 挂起：窗口收起成悬浮球，内容保留 */
    fun minimize() {
        val v = root ?: return
        (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
        stopRec(cancel = true)
        runCatching { wm.removeView(v) }
        parked = v; parkedLp = wlp; root = null; wlp = null
        onDismiss()
    }

    // —— 悬浮球环形菜单直接调用 ——
    fun openInput() { show(); if (inRow.visibility != View.VISIBLE) toggleInput() }
    fun openVoice() { show(); if (voiceRow.visibility != View.VISIBLE) toggleVoice() }
    fun openTargetLang() { show(); openLang(false) }
    val hasParked get() = parked != null

    fun hide() {
        gen++; parked = null; parkedLp = null; liftFrom = null
        root?.let { v ->
            (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
            runCatching { wm.removeView(v) }
        }
        stopRec(cancel = true)
        root = null; wlp = null; onDismiss()
    }

    /** 隐藏一下用于截屏，之后 showImage 会再打开 */
    fun hideForCapture() { if (alive()) hide() }

    private lateinit var inRow: LinearLayout
    private lateinit var gripMenu: LinearLayout
    private lateinit var gripLine: View
    private val autoHideGrip = Runnable { hideGripMenu() }
    private fun toggleGripMenu() {
        if (gripMenu.visibility == View.VISIBLE) { hideGripMenu(); return }
        gripLine.visibility = View.INVISIBLE
        gripMenu.visibility = View.VISIBLE; gripMenu.alpha = 0f; gripMenu.scaleX = 0.6f; gripMenu.scaleY = 0.6f
        gripMenu.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(android.view.animation.OvershootInterpolator(1.4f)).start()
        main.removeCallbacks(autoHideGrip); main.postDelayed(autoHideGrip, 4000)
    }
    private fun hideGripMenu() {
        main.removeCallbacks(autoHideGrip)
        if (gripMenu.visibility != View.VISIBLE) return
        gripMenu.animate().alpha(0f).scaleX(0.7f).scaleY(0.7f).setDuration(140).withEndAction {
            gripMenu.visibility = View.GONE; gripLine.visibility = View.VISIBLE
        }.start()
    }
    private fun openApp() {
        if (canMinimize) minimize() else hide()
        runCatching { ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun build(): View {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(if (ui.dark) Color.argb(242, 32, 32, 38) else Color.argb(246, 255, 255, 255))
                cornerRadius = dp(22f).toFloat()
                setStroke(1, if (ui.dark) Color.argb(36, 255, 255, 255) else Color.argb(20, 0, 0, 0))
            }
            elevation = dp(8f).toFloat()
            setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
        }
        val wrap = FrameLayout(ctx).apply { setPadding(dp(6f), dp(4f), dp(6f), dp(10f)); clipToPadding = false }
        wrap.addView(card, FrameLayout.LayoutParams(dp(278f), FrameLayout.LayoutParams.WRAP_CONTENT))

        // —— 顶栏（参考系统小窗）：左边 [□ 打开 App][— 最小化]，中间小白条按住拖动，右边 [✕ 关闭] ——
        // 按钮常驻、高度固定，点哪里都不会让下面的译文跳动
        val top = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        fun tb(res: Int, desc: String, act: () -> Unit) = FrameLayout(ctx).apply {
            background = ui.ripple(if (ui.dark) Color.argb(40, 255, 255, 255) else Color.argb(18, 0, 0, 0), dp(13f).toFloat())
            addView(icon(res, ui.onSurfaceVar, 13f), FrameLayout.LayoutParams(dp(13f), dp(13f), Gravity.CENTER))
            contentDescription = desc
            setOnClickListener { act() }
        }
        top.addView(tb(R.drawable.ic_t_window, "打开 App") { openApp() }, LinearLayout.LayoutParams(dp(26f), dp(26f)).apply { marginStart = dp(2f) })
        if (canMinimize) top.addView(tb(R.drawable.ic_t_min, "最小化为悬浮球") { minimize() }, LinearLayout.LayoutParams(dp(26f), dp(26f)).apply { marginStart = dp(6f) })
        val bar = FrameLayout(ctx)
        gripLine = View(ctx).apply { background = ui.round(Color.argb(120, Color.red(ui.onSurfaceVar), Color.green(ui.onSurfaceVar), Color.blue(ui.onSurfaceVar)), dp(2f).toFloat()) }
        bar.addView(gripLine, FrameLayout.LayoutParams(dp(36f), dp(4f), Gravity.CENTER))
        bar.setOnTouchListener(dragger { })
        top.addView(bar, LinearLayout.LayoutParams(0, dp(26f), 1f))
        if (canMinimize) top.addView(View(ctx), LinearLayout.LayoutParams(dp(32f), 1))   // 让小白条保持居中
        top.addView(tb(R.drawable.ic_t_close, "关闭") { hide() }, LinearLayout.LayoutParams(dp(26f), dp(26f)).apply { marginEnd = dp(2f) })
        card.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(26f)))
        gripMenu = LinearLayout(ctx)
        tgtBtn = TextView(ctx)
        srcBtn = TextView(ctx)

        mainBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        // —— 输入（点“输入”才展开） ——
        inRow = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE
            background = ui.round(ui.surfaceVar, dp(16f).toFloat())
            setPadding(dp(12f), 0, dp(3f), 0)
        }
        input = EditText(ctx).apply {
            hint = "输入单词或句子"; textSize = 14f; background = null
            setTextColor(ui.onSurface); setHintTextColor(ui.onSurfaceVar)
            maxLines = 3; minHeight = dp(38f); setPadding(0, dp(6f), 0, dp(6f))
            imeOptions = EditorInfo.IME_ACTION_GO
            setRawInputType(android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE)
            setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_GO) { translateInput(); true } else false }
            // 窗口平时不可聚焦：点输入框时再拿焦点、弹键盘
            setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_DOWN && (wlp?.flags ?: 0) and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0) focusInput(); false }
        }
        clearBtn = ImageView(ctx)
        inRow.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // 悬浮窗里系统长按菜单常常弹不出来，单独给一个“粘贴”按钮
        inRow.addView(TextView(ctx).apply {
            text = "粘贴"; textSize = 12f; setTextColor(ui.primary); gravity = Gravity.CENTER
            background = ui.ripple(Color.TRANSPARENT, dp(14f).toFloat())
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
            setOnClickListener { pasteClip() }
        })
        inRow.addView(circleBtn(R.drawable.ic_t_send, ui.primary, ui.onPrimary, 32f, 16f) { translateInput() })
        mainBox.addView(inRow, ui.lp(6f))

        // —— 语音（点“语音”才展开，按住说话，就在小窗里出结果） ——
        voiceRow = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }
        micBtn = TextView(ctx).apply {
            text = "按住说话"; textSize = 14f; gravity = Gravity.CENTER; setTextColor(ui.onPrimary)
            typeface = Typeface.DEFAULT_BOLD
            background = ui.round(ui.primary, dp(19f).toFloat())
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> startRec()
                    MotionEvent.ACTION_UP -> stopRec(false)
                    MotionEvent.ACTION_CANCEL -> stopRec(true)
                }
                true
            }
        }
        voiceRow.addView(micBtn, LinearLayout.LayoutParams(0, dp(38f), 1f))
        voiceStatus = ui.text("", 12f, ui.onSurfaceVar).apply { setPadding(dp(8f), 0, dp(4f), 0); maxWidth = dp(110f) }
        voiceRow.addView(voiceStatus)
        mainBox.addView(voiceRow, ui.lp(6f))

        // —— 结果（有内容才出现） ——
        resultBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(dp(8f), 0, dp(2f), 0) }
        val head = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        resultLang = ui.text("", 11f, ui.primary, bold = true).apply {
            setOnClickListener { if (langBox.visibility == View.VISIBLE) closeLang() else openLang(false) }
        }
        head.addView(resultLang, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        progress = ProgressBar(ctx, null, android.R.attr.progressBarStyleSmall).apply {
            indeterminateTintList = ColorStateList.valueOf(ui.primary)
        }
        head.addView(progress, LinearLayout.LayoutParams(dp(14f), dp(14f)))
        playBtn = circleBtn(R.drawable.ic_t_play, Color.TRANSPARENT, ui.primary, 28f, 16f) { play() }.apply { visibility = View.GONE }
        head.addView(playBtn)
        // 图片结果：一键切换「原文 / 译文」（原文 = 只提取图里的文字，不翻译）
        origBtn = TextView(ctx).apply {
            text = "原文"; textSize = 11.5f; setTextColor(ui.onTonal); gravity = Gravity.CENTER
            background = ui.ripple(ui.tonal, dp(12f).toFloat()); setPadding(dp(9f), 0, dp(9f), 0)
            visibility = View.GONE
            setOnClickListener { toggleOrig() }
        }
        head.addView(origBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(24f)).apply { leftMargin = dp(4f) })
        head.addView(circleBtn(R.drawable.ic_t_copy, Color.TRANSPARENT, ui.onSurfaceVar, 28f, 15f) { copy() })
        resultBox.addView(head)
        thumb = ImageView(ctx)
        resultText = ui.text("", 14f).apply { setTextIsSelectable(true); setLineSpacing(0f, 1.25f) }
        val sv = object : ScrollView(ctx) {
            override fun onMeasure(w: Int, h: Int) {
                val m = dragM ?: ShotFlow.real(ctx)
                super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxOf(dp(90f), (m.heightPixels * (if (m.widthPixels > m.heightPixels) 0.4f else 0.3f)).toInt()), View.MeasureSpec.AT_MOST))
            }
        }
        sv.addView(resultText)
        resultBox.addView(sv)
        mainBox.addView(resultBox, ui.lp(6f))
        card.addView(mainBox)

        langBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        card.addView(langBox, ui.lp(6f))
        return wrap
    }

    private fun toggleInput() {
        closeLang(); voiceRow.visibility = View.GONE
        if (inRow.visibility == View.VISIBLE) {
            inRow.visibility = View.GONE
            (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
            setFocusable(false); restoreLift()
        } else {
            inRow.visibility = View.VISIBLE; focusInput()
        }
    }

    private fun refreshLangs() {
        tgtBtn.text = "→${prefs.targetLang}▾"
    }

    private fun swap() {
        val s = prefs.sourceLang; val t = prefs.targetLang
        if (s == Lang.AUTO) { prefs.sourceLang = t; prefs.targetLang = if (t == "中文") "英语" else "中文" }
        else { prefs.sourceLang = t; prefs.targetLang = s }
        refreshLangs(); if (input.text.isNotBlank()) translateInput() else if (lastBitmap != null) showImage(lastBitmap!!)
    }

    private fun openLang(isSrc: Boolean) {
        langBox.removeAllViews()
        val base = (if (isSrc) listOf(Lang.AUTO) else emptyList()) + Lang.all
        val cur = if (isSrc) prefs.sourceLang else prefs.targetLang
        val search = EditText(ctx).apply {
            hint = if (isSrc) "搜索原文语言" else "翻译成…（搜索）"; textSize = 14f; isSingleLine = true
            setTextColor(ui.onSurface); setHintTextColor(ui.onSurfaceVar)
            background = ui.round(ui.surfaceVar, dp(14f).toFloat()); setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
        }
        langBox.addView(search)
        search.setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_DOWN) { setFocusable(true) }; false }
        val adapter = object : ArrayAdapter<String>(ctx, android.R.layout.simple_list_item_1, base.toMutableList()) {
            override fun getView(pos: Int, cv: View?, parent: ViewGroup): View =
                (super.getView(pos, cv, parent) as TextView).apply {
                    val it = getItem(pos); textSize = 14f
                    setTextColor(if (it == cur) ui.primary else ui.onSurface)
                    typeface = if (it == cur) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    minHeight = dp(38f)
                }
        }
        val list = ListView(ctx).apply { this.adapter = adapter; divider = null; selector = ui.ripple(Color.TRANSPARENT, 0f) }
        langBox.addView(list, ui.lp(4f, dp(220f)))
        base.indexOf(cur).takeIf { it > 3 }?.let { list.setSelection(it - 2) }
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(e: Editable?) { val q = e.toString().trim(); adapter.clear(); adapter.addAll(base.filter { q.isEmpty() || it.contains(q) }) }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        list.setOnItemClickListener { _, _, pos, _ ->
            val v = adapter.getItem(pos)!!
            if (isSrc) prefs.sourceLang = v else prefs.targetLang = v
            closeLang(); refreshLangs()
            if (input.text.isNotBlank()) translateInput() else if (lastBitmap != null) showImage(lastBitmap!!)
        }
        mainBox.visibility = View.GONE; langBox.visibility = View.VISIBLE
    }

    private fun closeLang() { langBox.visibility = View.GONE; mainBox.visibility = View.VISIBLE; if (inRow.visibility != View.VISIBLE) setFocusable(false) }

    private fun startResult(label: String): Int {
        resultBox.visibility = View.VISIBLE; progress.visibility = View.VISIBLE; playBtn.visibility = View.GONE
        origBtn.visibility = View.GONE
        resultLang.text = "$label ▾"; resultText.setTextColor(ui.onSurface); main.removeCallbacks(flushText); pendingText = null; resultText.text = ""
        return ++gen
    }

    // 流式译文：合并成每 120ms 刷新一次；拖动小窗时先不刷新（每次刷新都会让窗口重新排版，和拖动一起就卡）
    private var pendingText: String? = null
    private var lastFlush = 0L
    private var dragging = false
    private val flushText = Runnable { flushNow() }
    private fun flushNow() {
        val t = pendingText ?: return
        if (dragging || !alive()) return
        pendingText = null; lastFlush = android.os.SystemClock.uptimeMillis(); resultText.text = t
    }
    private fun pushText(t: String) {
        pendingText = t
        if (dragging) return
        main.removeCallbacks(flushText)
        val wait = 120 - (android.os.SystemClock.uptimeMillis() - lastFlush)
        if (wait <= 0) flushNow() else main.postDelayed(flushText, wait)
    }

    private fun streamer(g: Int): (String) -> Unit {
        val sb = StringBuilder()
        return { p -> synchronized(sb) { sb.append(p) }; main.post { if (g == gen && alive()) pushText(synchronized(sb) { sb.toString() }) } }
    }

    private fun finish(g: Int, r: Result<String>) = main.post {
        if (g != gen || !alive()) return@post
        progress.visibility = View.GONE
        main.removeCallbacks(flushText); pendingText = null
        r.onSuccess { resultText.text = it }.onFailure { resultText.setTextColor(ui.error); resultText.text = "翻译失败：${it.message}" }
    }

    private fun translateInput() {
        val t = input.text.toString().trim(); if (t.isEmpty()) return
        lastBitmap = null
        val g = startResult(AiClient.targetFor(prefs, t))
        val ins = prefs.instruction.takeIf { prefs.alwaysInstruct && it.isNotBlank() }
        Thread {
            finish(g, runCatching {
                if (ins != null) AiClient.translateInstruct(prefs, t, ins, streamer(g))
                else AiClient.translateText(prefs, t, streamer(g))
            })
        }.start()
    }

    /** 截图（框选或全屏）结果在小窗里显示 */
    fun showImage(bmp: Bitmap) {
        show(); closeLang()
        if (bmp !== lastBitmap) { cacheTrans = null; cacheOrig = null }
        lastBitmap = bmp; showingOrig = false
        input.setText(""); inRow.visibility = View.GONE
        val g = startResult("图片 → ${prefs.targetLang}")
        origBtn.visibility = View.VISIBLE; origBtn.text = "原文"
        val ins = if (prefs.alwaysInstruct) prefs.instruction else ""
        Thread {
            val r = runCatching { AiClient.translateImage(prefs, ImageUtil.toJpeg(ImageUtil.fit(bmp)), ins, streamer(g)) }
            r.onSuccess { main.post { if (g == gen) cacheTrans = it } }
            finish(g, r)
        }.start()
    }

    /** 只提取原文（不翻译），结果可直接选中/复制 */
    fun showOriginal(bmp: Bitmap) {
        show(); closeLang()
        if (bmp !== lastBitmap) { cacheTrans = null; cacheOrig = null }
        lastBitmap = bmp; showingOrig = true
        input.setText(""); inRow.visibility = View.GONE
        val c = cacheOrig
        val g = startResult("图片原文")
        origBtn.visibility = View.VISIBLE; origBtn.text = "译文"
        if (c != null) { progress.visibility = View.GONE; resultText.text = c; return }
        Thread {
            val r = runCatching { AiClient.extractText(ImageUtil.toJpeg(ImageUtil.fit(bmp)), streamer(g)).trim() }
            r.onSuccess { main.post { if (g == gen) cacheOrig = it } }
            finish(g, r)
        }.start()
    }

    private fun toggleOrig() {
        val b = lastBitmap ?: return
        if (showingOrig) {
            val c = cacheTrans
            if (c != null) {
                showingOrig = false; startResult("图片 → ${prefs.targetLang}")
                origBtn.visibility = View.VISIBLE; origBtn.text = "原文"
                progress.visibility = View.GONE; resultText.text = c
            } else showImage(b)
        } else showOriginal(b)
    }

    private fun copy() {
        val t = resultText.text?.toString().orEmpty(); if (t.isBlank()) return
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("译文", t))
        Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
    }

    private fun launch(c: Class<*>) { hide(); ctx.startActivity(Intent(ctx, c).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    // ================= 拖动 =================
    private var savedX = 0
    private var savedY = 0

    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private fun dragger(onTap: () -> Unit) = View.OnTouchListener { _, e ->
        val lp = wlp ?: return@OnTouchListener false
        val v = root ?: return@OnTouchListener false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; moved = false
                // 统一换成左上角坐标，拖起来方向才不会反（窗口是全屏坐标，不能再减状态栏，否则一按就往上跳）
                toTopLeft()
                dx = e.rawX - lp.x; dy = e.rawY - lp.y
                dragging = true; dragM = ShotFlow.real(ctx)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved && Math.hypot((e.rawX - downX).toDouble(), (e.rawY - downY).toDouble()) < dp(8f)) return@OnTouchListener true
                moved = true
                val m = dragM ?: ShotFlow.real(ctx)
                val ox = lp.x; val oy = lp.y
                lp.x = (e.rawX - dx).toInt().coerceIn(-dp(20f), maxOf(-dp(20f), m.widthPixels - v.width + dp(20f)))
                lp.y = (e.rawY - dy).toInt().coerceIn(0, maxOf(0, m.heightPixels - dp(60f)))
                if (lp.x != ox || lp.y != oy) runCatching { wm.updateViewLayout(v, lp) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false; dragM = null
                if (!moved) { if (e.actionMasked == MotionEvent.ACTION_UP) onTap() }
                else { dragged = true; savedX = lp.x; savedY = lp.y; liftFrom = null }
                if (pendingText != null) flushNow()
            }
        }
        true
    }
    /** 把窗口换成左上角定位，且保证窗口在屏幕上的位置一点不动 */
    private fun toTopLeft() {
        val lp = wlp ?: return; val v = root ?: return
        val loc = IntArray(2); v.getLocationOnScreen(loc)
        if (lp.gravity == (Gravity.TOP or Gravity.START) && lp.x == loc[0] && lp.y == loc[1]) return
        lp.gravity = Gravity.TOP or Gravity.START; lp.x = loc[0]; lp.y = loc[1]
        runCatching { wm.updateViewLayout(v, lp) }
        // 个别系统坐标原点不在屏幕顶上：排版后量一下，偏了就补回去
        v.post {
            if (root !== v) return@post
            val n = IntArray(2); v.getLocationOnScreen(n)
            val fx = loc[0] - n[0]; val fy = loc[1] - n[1]
            if (fx != 0 || fy != 0) { lp.x += fx; lp.y += fy; dx -= fx; dy -= fy; runCatching { wm.updateViewLayout(v, lp) } }
        }
    }

    // ================= 键盘 =================
    // 平时小窗不抢输入焦点（否则别的应用点输入框弹不出输入法）；只有用自己的输入框时才拿焦点
    private fun setFocusable(on: Boolean) {
        val v = root ?: return; val lp = wlp ?: return
        val f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        val want = if (on) lp.flags and f.inv() else lp.flags or f
        if (want == lp.flags) return
        if (!on) (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
        lp.flags = want; runCatching { wm.updateViewLayout(v, lp) }
    }

    private fun focusInput() {
        setFocusable(true); input.requestFocus()
        // 不等键盘：先估计着挪上去（键盘弹出动画期间就已经在正确位置），之后量到真实位置再微调
        fitAbove(guessImeTop())
        input.postDelayed({ (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, 0) }, 60)
        watchIme()
    }

    // 像普通 App 一样：键盘弹出时小窗贴到键盘正上方，键盘收起回到原位。键盘位置从无障碍窗口列表里读，准确
    private var liftFrom: Int? = null
    private var imeWatching = false
    private fun screenH(): Int = runCatching { ShotFlow.real(ctx).heightPixels }.getOrDefault(ctx.resources.displayMetrics.heightPixels)
    /** 键盘状态：top = 键盘顶部屏幕 y（不知道就是 null），shown = 是否确定键盘在显示 */
    private class Ime(val top: Int?, val shown: Boolean)
    private fun readIme(): Ime {
        runCatching {
            val a = ShotA11y.instance
            if (a != null) {
                val r = android.graphics.Rect()
                val ws = if (Build.VERSION.SDK_INT >= 30) {
                    val all = a.windowsOnAllDisplays; (0 until all.size()).flatMap { all.valueAt(it) }
                } else a.windows
                ws.firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                    ?.let { it.getBoundsInScreen(r); if (r.height() > dp(80f) && r.top > screenH() * 0.25f) return Ime(r.top, true) }
            }
        }
        if (Build.VERSION.SDK_INT >= 30) runCatching {
            val v = root ?: return@runCatching
            val ins = v.rootWindowInsets ?: return@runCatching
            // 悬浮窗的 ime insets = 键盘和本窗口重叠的高度（不是整块键盘高度）
            val b = ins.getInsets(android.view.WindowInsets.Type.ime()).bottom
            val vis = ins.isVisible(android.view.WindowInsets.Type.ime())
            if (b > 0) { val loc = IntArray(2); v.getLocationOnScreen(loc); return Ime(loc[1] + v.height - b, true) }
            if (vis) return Ime(null, true)
            return Ime(null, false)
        }
        return Ime(null, false)
    }
    private val imm get() = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    /** 拿不到键盘位置时的估计：上次量到的高度，默认屏幕 45% */
    private fun guessImeTop(): Int {
        val sp = ctx.getSharedPreferences("panel", Context.MODE_PRIVATE)
        val hh = sp.getInt("imeH", 0).takeIf { it > dp(120f) } ?: (screenH() * 0.45f).toInt()
        return screenH() - hh
    }

    private fun watchIme() {
        if (imeWatching) return
        imeWatching = true
        val tick = object : Runnable {
            var hidden = 0; var everSeen = false
            override fun run() {
                val v = root
                if (v == null || inRow.visibility != View.VISIBLE) { imeWatching = false; restoreLift(); return }
                if (dragging) { main.postDelayed(this, 150); return }
                val ime = readIme()
                if (ime.top != null) {
                    hidden = 0; everSeen = true
                    if (ime.top < screenH() - dp(120f)) {
                        fitAbove(ime.top)
                        ctx.getSharedPreferences("panel", Context.MODE_PRIVATE).edit().putInt("imeH", screenH() - ime.top).apply()
                    }
                } else if (ime.shown) {
                    hidden = 0; everSeen = true          // 键盘在，但被挡开了，保持现在的位置
                } else if (everSeen && liftFrom != null && ++hidden >= 3) {
                    restoreLift(); everSeen = false     // 键盘确实收起了才回原位
                }
                main.postDelayed(this, 150)
            }
        }
        main.post(tick)
    }

    private fun fitAbove(imeTop: Int) {
        val v = root ?: return; val lp = wlp ?: return
        val h = v.height; if (h == 0) return
        val limit = imeTop - dp(6f)
        if (liftFrom == null) {
            val loc = IntArray(2); v.getLocationOnScreen(loc)
            if (loc[1] + h <= limit) return          // 本来就在键盘上面，不用动
            toTopLeft(); liftFrom = lp.y
        }
        val y = minOf(liftFrom ?: lp.y, maxOf(statusBar(), limit - h))
        if (y != lp.y) { lp.y = y; runCatching { wm.updateViewLayout(v, lp) } }
    }
    private fun restoreLift() {
        val from = liftFrom ?: return; liftFrom = null
        val v = root ?: return; val lp = wlp ?: return
        lp.y = from; runCatching { wm.updateViewLayout(v, lp) }
    }

    private var dx = 0f
    private var dy = 0f
    private var dragM: android.util.DisplayMetrics? = null
    private fun statusBar(): Int {
        val id = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) ctx.resources.getDimensionPixelSize(id) else 0
    }

    // ================= 语音 =================
    private lateinit var voiceRow: LinearLayout
    private lateinit var micBtn: TextView
    private lateinit var voiceStatus: TextView
    private lateinit var playBtn: View
    @Volatile private var recording = false
    private var recStart = 0L
    private var recThread: Thread? = null
    private val pcm = java.io.ByteArrayOutputStream()
    private var lastWav: java.io.File? = null
    private var player: android.media.MediaPlayer? = null

    private fun toggleVoice() {
        closeLang(); inRow.visibility = View.GONE; setFocusable(false); restoreLift()
        (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
        voiceRow.visibility = if (voiceRow.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        if (voiceRow.visibility == View.VISIBLE) {
            val (s, t) = voiceLangs()
            voiceStatus.setTextColor(ui.onSurfaceVar)
            voiceStatus.text = if (t == null) "语音暂不支持译成${prefs.targetLang}" else "$s → ${prefs.targetLang}"
        }
    }

    /** 语音接口只支持部分语种；原文是“自动检测”时，按目标语言猜一个说话语言 */
    private fun voiceLangs(): Pair<String, String?> {
        val tgt = Lang.code[prefs.targetLang]
        val srcName = prefs.sourceLang.takeIf { it != Lang.AUTO && Lang.code.containsKey(it) }
            ?: if (prefs.targetLang == "中文") "英语" else "中文"
        return srcName to tgt
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun startRec() {
        if (recording) return
        if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            voiceStatus.setTextColor(ui.error); voiceStatus.text = "需要麦克风权限"
            Toast.makeText(ctx, "请先在 Index 里打开一次「语音翻译」授予麦克风权限", Toast.LENGTH_LONG).show(); return
        }
        val (srcName, tgt) = voiceLangs()
        if (tgt == null) { voiceStatus.setTextColor(ui.error); voiceStatus.text = "语音暂不支持译成${prefs.targetLang}"; return }
        if (Lang.code[srcName] == tgt) { voiceStatus.setTextColor(ui.error); voiceStatus.text = "说话语言和目标相同"; return }
        player?.release(); player = null
        val rate = 16000
        val minBuf = android.media.AudioRecord.getMinBufferSize(rate, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching {
            android.media.AudioRecord(android.media.MediaRecorder.AudioSource.MIC, rate, android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate))
        }.getOrNull()
        if (rec == null || rec.state != android.media.AudioRecord.STATE_INITIALIZED) {
            rec?.release(); voiceStatus.setTextColor(ui.error); voiceStatus.text = "麦克风打开失败"; return
        }
        synchronized(pcm) { pcm.reset() }; recording = true; recStart = System.currentTimeMillis()
        rec.startRecording()
        recThread = Thread {
            val buf = ByteArray(4096)
            while (recording) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) synchronized(pcm) { pcm.write(buf, 0, n) }
                if (System.currentTimeMillis() - recStart >= 10_000) { main.post { stopRec(false) }; break }
            }
            runCatching { rec.stop() }; rec.release()
        }.apply { start() }
        micBtn.text = "松开翻译"; micBtn.animate().scaleX(1.05f).scaleY(1.05f).setDuration(120).start()
        tickRec()
    }

    private fun tickRec() {
        if (!recording) return
        voiceStatus.setTextColor(ui.primary)
        voiceStatus.text = "录音 ${"%.1f".format((System.currentTimeMillis() - recStart) / 1000f)}s"
        main.postDelayed({ tickRec() }, 100)
    }

    private fun stopRec(cancel: Boolean) {
        if (!recording) return
        recording = false
        recThread?.join(500)
        if (!::micBtn.isInitialized) return
        micBtn.text = "按住说话"; micBtn.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        if (cancel) { voiceStatus.text = ""; return }
        if (System.currentTimeMillis() - recStart < 600) { voiceStatus.setTextColor(ui.error); voiceStatus.text = "说话太短"; return }
        val data = synchronized(pcm) { pcm.toByteArray() }
        val b = java.nio.ByteBuffer.allocate(44 + data.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + data.size).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(data.size).put(data)
        val (srcName, tgt) = voiceLangs()
        voiceStatus.setTextColor(ui.onSurfaceVar); voiceStatus.text = "识别中…"
        val g = startResult("语音 $srcName → ${prefs.targetLang}")
        resultText.text = "翻译中…（约 10 秒）"
        val fields = mapOf("lang" to tgt!!, "src" to Lang.code[srcName]!!, "model" to "9b")
        Thread {
            val r = runCatching {
                EchoClient.run("s2st", b.array(), "voice.wav", "audio/wav", fields) { el ->
                    main.post { if (g == gen && alive()) voiceStatus.text = "翻译中 ${el}s" }
                }
            }
            main.post {
                if (g != gen || !alive()) return@post
                progress.visibility = View.GONE; voiceStatus.text = ""
                r.onSuccess { d ->
                    val heard = d.optString("zh"); val out = d.optString("text")
                    resultText.text = if (heard.isNotBlank()) "$out\n\n— $heard" else out
                    val b64 = d.optString("audio_b64")
                    if (b64.isNotEmpty()) {
                        val f = java.io.File(ctx.cacheDir, "panel_out.wav")
                        f.writeBytes(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
                        lastWav = f; playBtn.visibility = View.VISIBLE
                    }
                }.onFailure { resultText.setTextColor(ui.error); resultText.text = "翻译失败：${it.message}" }
            }
        }.start()
    }

    private fun play() {
        val f = lastWav ?: return
        player?.release()
        player = runCatching { android.media.MediaPlayer().apply { setDataSource(f.absolutePath); prepare(); start() } }.getOrNull()
    }

    // 读剪贴板要求窗口有焦点：先拿焦点再读
    private fun pasteClip() { setFocusable(true); input.requestFocus(); main.postDelayed({ doPaste() }, 150) }
    private fun doPaste() {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val t = runCatching { cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString() }.getOrNull()
        if (t.isNullOrBlank()) { Toast.makeText(ctx, "剪贴板是空的（或系统未允许读取）", Toast.LENGTH_SHORT).show(); return }
        val s = maxOf(0, input.selectionStart); val e = maxOf(0, input.selectionEnd)
        input.text.replace(minOf(s, e), maxOf(s, e), t)
        translateInput()
    }
}
