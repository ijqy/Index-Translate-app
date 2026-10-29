package com.example.aitranslate

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.*
import kotlin.math.abs

/**
 * 连续翻译（Gal / 视觉小说对话框）：框一次区域，之后每秒看一眼，
 * 文字变了且停稳（打字机效果结束）才翻译，译文像字幕一样贴在区域上方/下方。
 */
class LiveTranslator(
    private val svc: ShotA11y,
    private var area: Rect,
    private val shoot: ((Bitmap?) -> Unit) -> Unit,
    private val reselect: () -> Unit,
    private val onStop: () -> Unit,
) {
    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val ui = Ui(svc)
    private val prefs = Prefs(svc)
    private fun dp(v: Float) = ui.dp(v)

    private var frame: View? = null
    private var bar: LinearLayout? = null
    private var barLp: WindowManager.LayoutParams? = null
    private lateinit var text: TextView
    private lateinit var dot: View
    private lateinit var status: TextView
    private lateinit var pauseIcon: ImageView
    private lateinit var scroll: ScrollView

    private var running = false
    private var paused = false
    private var busy = false
    private var lastFrame: IntArray? = null      // 上一帧
    private var lastDone: IntArray? = null       // 上次翻译时的画面
    private var stableCount = 0
    private var busySig: IntArray? = null       // 正在翻译的那一帧
    private var screenW = 0; private var screenH = 0
    private var gen = 0
    private var userMoved = false
    /** true = 只显示原文（提取文字，不翻译） */
    private var origMode = false
    private lateinit var modeBtn: TextView
    /** 调整框模式：直接拖框移动、拖四角/四边改大小，不用重新截屏 */
    private var adjusting = false
    private lateinit var cropIcon: ImageView
    private lateinit var redrawBtn: TextView

    fun start() {
        running = true
        ShotFlow.real(svc).let { screenW = it.widthPixels; screenH = it.heightPixels }
        addFrame(); addBar()
        setStatus("识别中", Color.parseColor("#FFB020"))
        main.postDelayed(tick, 150)
    }

    fun stop() {
        running = false; gen++; adjusting = false
        main.removeCallbacksAndMessages(null)
        frame?.let { runCatching { wm.removeView(it) } }; frame = null
        bar?.let { runCatching { wm.removeView(it) } }; bar = null
        onStop()
    }

    /** 换区域（重新框选后调用） */
    fun setArea(r: Rect) {
        ShotFlow.real(svc).let { screenW = it.widthPixels; screenH = it.heightPixels }
        area = r; lastFrame = null; lastDone = null; stableCount = 0; held = false
        frame?.let { runCatching { wm.removeView(it) } }; frame = null
        addFrame()
        if (!userMoved) bar?.let { b -> barLp?.let { placeBar(it); runCatching { wm.updateViewLayout(b, it) } } }
        setVisible(true)
        main.removeCallbacks(tick); main.postDelayed(tick, 150)
    }

    /** 重新框选时先停掉循环截屏，不然和框选的那次截屏抢频率限制，要等很久 */
    private var held = false
    fun hold() { held = true; gen++; busy = false; main.removeCallbacks(tick) }
    fun resume() { held = false; main.removeCallbacks(tick); main.postDelayed(tick, 150) }

    fun setVisible(v: Boolean) {
        val vis = if (v) View.VISIBLE else View.INVISIBLE
        frame?.visibility = vis; bar?.visibility = vis
    }

    // ================= 循环 =================
    private val tick = object : Runnable {
        override fun run() {
            if (!running || held) return
            if (paused || adjusting) { main.postDelayed(this, 300); return }
            shoot { bmp ->
                if (!running || held) return@shoot
                if (bmp != null) {
                    // 转屏了：原来框的位置已经不对，自动让用户重新框
                    if (bmp.width != screenW || bmp.height != screenH) {
                        val m = ShotFlow.real(svc)
                        if (m.widthPixels != screenW || m.heightPixels != screenH) {
                            screenW = m.widthPixels; screenH = m.heightPixels
                            Toast.makeText(svc, "屏幕方向变了，请重新框选对话框", Toast.LENGTH_SHORT).show()
                            main.postDelayed({ if (running) reselect() }, 300); return@shoot
                        }
                    }
                    handle(bmp)
                }
                main.postDelayed(this, INTERVAL)
            }
        }
    }

    private fun handle(full: Bitmap) {
        var crop = ShotFlow.crop(full, area) ?: return
        // 字幕条若压在区域上，把那块涂掉，免得自己的译文变化触发翻译
        bar?.let { b ->
            val loc = IntArray(2); b.getLocationOnScreen(loc)
            val br = Rect(loc[0], loc[1], loc[0] + b.width, loc[1] + b.height)
            if (b.visibility == View.VISIBLE && br.intersect(area)) {
                crop = crop.copy(Bitmap.Config.ARGB_8888, true)
                Canvas(crop).drawRect(Rect(br.left - area.left, br.top - area.top, br.right - area.left, br.bottom - area.top),
                    Paint().apply { color = Color.BLACK })
            }
        }
        val sig = signature(crop)
        val prev = lastFrame
        lastFrame = sig
        if (prev == null || changed(prev, sig)) { stableCount = 0; if (lastDone != null && prev != null) setStatus("等待文字出完", Color.parseColor("#FFB020")); return }
        stableCount++
        // 相邻两帧（约 0.4 秒）不变 = 打字机效果结束
        val done = lastDone
        if (done != null && !changed(done, sig)) return
        if (busy) {
            // 正在翻上一句、但画面已经换成新的一句：直接丢掉旧的，马上翻新的
            val bs = busySig
            if (bs == null || !changed(bs, sig)) return
        }
        translate(crop, sig)
    }

    private fun translate(crop: Bitmap, sig: IntArray) {
        busy = true; busySig = sig
        val g = ++gen
        setStatus(if (origMode) "提取中" else "翻译中", ui.primary)
        val om = origMode
        val ins = buildString {
            append("这是游戏（视觉小说/Galgame）对话框的截图。只翻译对话框里的台词；若有说话人名字，按「名字：台词」输出；忽略按钮、菜单等界面文字。若没有可翻译的文字，只输出 …")
            if (prefs.alwaysInstruct && prefs.instruction.isNotBlank()) append("\n").append(prefs.instruction)
        }
        val sb = StringBuilder()
        Thread {
            val r = runCatching {
                // 对话框图不需要太大，小图上传/识别更快
                val jpg = ImageUtil.toJpeg(ImageUtil.fit(crop, 1024))
                val cb: (String) -> Unit = { d -> main.post { if (g == gen && running) { sb.append(d); showText(sb.toString(), false) } } }
                if (om) AiClient.extractText(jpg, cb) else AiClient.translateImage(prefs, jpg, ins, cb)
            }
            main.post {
                if (g != gen || !running) return@post
                busy = false; busySig = null
                r.onSuccess {
                    lastDone = sig
                    if (it.isNotBlank()) showText(it.trim(), false)
                    setStatus(if (paused) "已暂停" else "连续翻译中", if (paused) ui.onSurfaceVar else Color.parseColor("#2BC48A"))
                }.onFailure {
                    // 失败了不记 lastDone，下次还会重试
                    setStatus("翻译失败，稍后重试", ui.error)
                }
            }
        }.start()
    }

    /** 缩成 96 宽灰度图做比较 */
    private fun signature(b: Bitmap): IntArray {
        val w = 96; val h = (96f * b.height / b.width).toInt().coerceIn(8, 96)
        val s = Bitmap.createScaledBitmap(b, w, h, true)
        val px = IntArray(w * h); s.getPixels(px, 0, w, 0, 0, w, h)
        if (s !== b) s.recycle()
        for (i in px.indices) { val c = px[i]; px[i] = (Color.red(c) * 30 + Color.green(c) * 59 + Color.blue(c) * 11) / 100 }
        return px
    }

    private fun changed(a: IntArray, b: IntArray): Boolean {
        if (a.size != b.size) return true
        var n = 0
        for (i in a.indices) if (abs(a[i] - b[i]) > 36) n++
        return n > maxOf(3, a.size / 300)
    }

    private companion object { const val INTERVAL = 380L }

    // ================= 界面 =================
    private fun overlayLp(w: Int, h: Int, touch: Boolean) = WindowManager.LayoutParams(w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (touch) WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    /** 区域外圈画一条细虚线（画在区域外面，不会被截进去） */
    private fun addFrame() {
        val pad = dp(3f)
        val v = object : View(svc) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#B3FB7299"); style = Paint.Style.STROKE; strokeWidth = dp(1.5f).toFloat()
                pathEffect = DashPathEffect(floatArrayOf(dp(6f).toFloat(), dp(4f).toFloat()), 0f)
            }
            override fun onDraw(c: Canvas) { val s = p.strokeWidth / 2; c.drawRoundRect(s, s, width - s, height - s, dp(4f).toFloat(), dp(4f).toFloat(), p) }
        }
        val lp = overlayLp(area.width() + pad * 2, area.height() + pad * 2, false).apply { x = area.left - pad; y = area.top - pad }
        runCatching { wm.addView(v, lp); frame = v }
    }

    private fun placeBar(lp: WindowManager.LayoutParams) {
        val m = ShotFlow.real(svc)
        val w = maxOf(area.width(), dp(260f)).coerceAtMost(m.widthPixels - dp(12f))
        lp.width = w
        lp.x = (area.centerX() - w / 2).coerceIn(dp(6f), m.widthPixels - w - dp(6f))
        val need = dp(110f)
        lp.y = if (m.heightPixels - area.bottom - dp(8f) >= need) area.bottom + dp(8f)
            else (area.top - dp(8f) - need).coerceAtLeast(dp(24f))
    }

    private fun addBar() {
        val card = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.argb(225, 18, 18, 22)); cornerRadius = dp(14f).toFloat()
            }
            setPadding(dp(12f), dp(4f), dp(4f), dp(8f))
        }
        val head = LinearLayout(svc).apply { gravity = Gravity.CENTER_VERTICAL }
        dot = View(svc).apply { background = ui.round(Color.parseColor("#FFB020"), dp(4f).toFloat()) }
        head.addView(dot, LinearLayout.LayoutParams(dp(7f), dp(7f)).apply { rightMargin = dp(6f) })
        status = TextView(svc).apply { textSize = 11f; setTextColor(Color.argb(170, 255, 255, 255)) }
        head.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fun btn(res: Int, desc: String, act: () -> Unit) = ImageView(svc).apply {
            setImageResource(res); imageTintList = ColorStateList.valueOf(Color.argb(200, 255, 255, 255))
            setPadding(dp(7f), dp(7f), dp(7f), dp(7f)); contentDescription = desc
            background = ui.ripple(Color.TRANSPARENT, dp(15f).toFloat())
            setOnClickListener { act() }
            setOnLongClickListener { Toast.makeText(svc, desc, Toast.LENGTH_SHORT).show(); true }
        }.also { head.addView(it, LinearLayout.LayoutParams(dp(30f), dp(30f))) }
        modeBtn = TextView(svc).apply {
            text = "原文"; textSize = 11f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = ui.ripple(Color.argb(60, 255, 255, 255), dp(11f).toFloat()); setPadding(dp(8f), 0, dp(8f), 0)
            setOnClickListener {
                origMode = !origMode; text = if (origMode) "译文" else "原文"
                gen++; busy = false; lastDone = null; lastFrame = null
                setStatus(if (origMode) "只提取原文" else "连续翻译中", Color.parseColor("#2BC48A"))
            }
        }
        head.addView(modeBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(22f)).apply { rightMargin = dp(2f) })
        redrawBtn = TextView(svc).apply {
            text = "重画"; textSize = 11f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; visibility = View.GONE
            background = ui.ripple(Color.argb(60, 255, 255, 255), dp(11f).toFloat()); setPadding(dp(8f), 0, dp(8f), 0)
            setOnClickListener { endAdjust(false); reselect() }
        }
        head.addView(redrawBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(22f)).apply { rightMargin = dp(2f) })
        cropIcon = btn(R.drawable.ic_t_crop, "调整框（长按重新框选）") { if (adjusting) endAdjust(true) else beginAdjust() }
        cropIcon.setOnLongClickListener { endAdjust(false); reselect(); true }
        btn(R.drawable.ic_t_copy, "复制译文") {
            val t = text.text?.toString().orEmpty()
            if (t.isNotBlank()) {
                (svc.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("译文", t))
                Toast.makeText(svc, "已复制", Toast.LENGTH_SHORT).show()
            }
        }
        pauseIcon = btn(R.drawable.ic_t_pause, "暂停/继续") { togglePause() }
        btn(R.drawable.ic_t_close, "结束连续翻译") { stop() }
        head.setOnTouchListener(dragger())
        card.addView(head)
        text = TextView(svc).apply {
            textSize = 16f; setTextColor(Color.WHITE); setLineSpacing(0f, 1.25f)
            setShadowLayer(dp(2f).toFloat(), 0f, 0f, Color.BLACK)
            text = "框好了，正在看对话框…"
            setPadding(0, 0, dp(8f), 0)
            setTextIsSelectable(false)
        }
        scroll = object : ScrollView(svc) {
            override fun onMeasure(w: Int, h: Int) =
                super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(dp(150f), View.MeasureSpec.AT_MOST))
        }.apply { addView(text); isVerticalScrollBarEnabled = false }
        card.addView(scroll)
        val lp = overlayLp(dp(260f), WindowManager.LayoutParams.WRAP_CONTENT, true)
        placeBar(lp)
        runCatching { wm.addView(card, lp); bar = card; barLp = lp }
    }

    private fun beginAdjust() {
        adjusting = true; gen++; busy = false
        frame?.let { runCatching { wm.removeView(it) } }; frame = null
        addAdjustFrame()
        cropIcon.setImageResource(R.drawable.ic_t_check); redrawBtn.visibility = View.VISIBLE
        status.text = "拖框移动 · 拖四角/四边改大小 · 点 ✓ 完成"
        dot.background = ui.round(Color.parseColor("#FB7299"), dp(4f).toFloat())
    }

    /** apply=true：按新框继续连续翻译 */
    private fun endAdjust(apply: Boolean) {
        if (!adjusting) return
        adjusting = false
        cropIcon.setImageResource(R.drawable.ic_t_crop); redrawBtn.visibility = View.GONE
        frame?.let { runCatching { wm.removeView(it) } }; frame = null
        if (apply) setArea(Rect(area)) else addFrame()
        setStatus(if (paused) "已暂停" else "连续翻译中", if (paused) ui.onSurfaceVar else Color.parseColor("#2BC48A"))
    }

    private fun addAdjustFrame() {
        val H = dp(16f)            // 框外留出把手的位置
        val grab = dp(26f)         // 边/角的触摸范围
        val minS = dp(40f)
        val pink = Color.parseColor("#FB7299")
        lateinit var lp: WindowManager.LayoutParams
        val v = object : View(svc) {
            val fill = Paint().apply { color = Color.argb(46, 251, 114, 153) }
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pink; style = Paint.Style.STROKE; strokeWidth = dp(2f).toFloat() }
            val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            val knobLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pink; style = Paint.Style.STROKE; strokeWidth = dp(2f).toFloat() }
            override fun onDraw(c: Canvas) {
                val l = H.toFloat(); val t = H.toFloat(); val r = (width - H).toFloat(); val b = (height - H).toFloat()
                c.drawRect(l, t, r, b, fill); c.drawRect(l, t, r, b, line)
                val k = dp(7f).toFloat()
                for (x in floatArrayOf(l, r)) for (y in floatArrayOf(t, b)) { c.drawCircle(x, y, k, knob); c.drawCircle(x, y, k, knobLine) }
                val mx = (l + r) / 2; val my = (t + b) / 2; val e = dp(5f).toFloat()
                for ((x, y) in listOf(mx to t, mx to b, l to my, r to my)) { c.drawCircle(x, y, e, knob); c.drawCircle(x, y, e, knobLine) }
            }
            var mL = false; var mT = false; var mR = false; var mB = false
            var sx = 0f; var sy = 0f; var start = Rect()
            override fun onTouchEvent(e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        val x = e.x - H; val y = e.y - H; val w = area.width(); val h = area.height()
                        val gx = minOf(grab, w / 3); val gy = minOf(grab, h / 3)
                        mL = x < gx; mR = x > w - gx; mT = y < gy; mB = y > h - gy
                        if (!mL && !mR && !mT && !mB) { mL = true; mR = true; mT = true; mB = true }  // 中间 = 整体移动
                        sx = e.rawX; sy = e.rawY; start = Rect(area)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val ddx = (e.rawX - sx).toInt(); val ddy = (e.rawY - sy).toInt()
                        val r = Rect(start)
                        if (mL && mR && mT && mB) {
                            r.offset(ddx.coerceIn(-start.left, screenW - start.right), ddy.coerceIn(-start.top, screenH - start.bottom))
                        } else {
                            if (mL) r.left = (start.left + ddx).coerceIn(0, r.right - minS)
                            if (mR) r.right = (start.right + ddx).coerceIn(r.left + minS, screenW)
                            if (mT) r.top = (start.top + ddy).coerceIn(0, r.bottom - minS)
                            if (mB) r.bottom = (start.bottom + ddy).coerceIn(r.top + minS, screenH)
                        }
                        if (r != area) {
                            area = r
                            lp.x = r.left - H; lp.y = r.top - H; lp.width = r.width() + H * 2; lp.height = r.height() + H * 2
                            runCatching { wm.updateViewLayout(this, lp) }
                            if (!userMoved) bar?.let { b -> barLp?.let { placeBar(it); runCatching { wm.updateViewLayout(b, it) } } }
                        }
                    }
                }
                return true
            }
        }
        lp = overlayLp(area.width() + H * 2, area.height() + H * 2, true).apply { x = area.left - H; y = area.top - H }
        runCatching { wm.addView(v, lp); frame = v }
        // 字幕条保持在调整框上面，按钮点得到
        bar?.let { b -> barLp?.let { runCatching { wm.removeView(b); wm.addView(b, it) } } }
    }

    private fun togglePause() {
        paused = !paused
        pauseIcon.setImageResource(if (paused) R.drawable.ic_t_play else R.drawable.ic_t_pause)
        if (paused) setStatus("已暂停", ui.onSurfaceVar)
        else { lastFrame = null; setStatus("连续翻译中", Color.parseColor("#2BC48A")) }
    }

    private fun showText(s: String, err: Boolean) {
        text.setTextColor(if (err) ui.error else Color.WHITE); text.text = s
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun setStatus(s: String, c: Int) {
        if (!::status.isInitialized) return
        status.text = if (origMode) "原文 · $s" else "连续翻译 · $s · →${prefs.targetLang}"
        dot.background = ui.round(c, dp(4f).toFloat())
    }

    private var dx = 0f; private var dy = 0f
    private fun dragger() = View.OnTouchListener { _, e ->
        val lp = barLp ?: return@OnTouchListener false
        val v = bar ?: return@OnTouchListener false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dx = e.rawX - lp.x; dy = e.rawY - lp.y }
            MotionEvent.ACTION_MOVE -> {
                val m = ShotFlow.real(svc)
                lp.x = (e.rawX - dx).toInt().coerceIn(0, maxOf(0, m.widthPixels - v.width))
                lp.y = (e.rawY - dy).toInt().coerceIn(0, maxOf(0, m.heightPixels - dp(60f)))
                runCatching { wm.updateViewLayout(v, lp) }; userMoved = true
            }
        }
        true
    }
}
