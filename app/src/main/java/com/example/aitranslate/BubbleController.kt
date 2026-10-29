package com.example.aitranslate

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/** 小巧悬浮球：闲置半藏在屏幕边；点一下 = 打开翻译小窗；长按 = 直接框选；长按后拖到底部✕可关闭 */
class BubbleController(
    private val ctx: Context,
    private val type: Int,
    private val onCapture: (full: Boolean) -> Unit,
    private val onClose: () -> Unit,
    private val onLive: () -> Unit = {},
    private val onOcr: () -> Unit = {},
) {
    val panel = FloatPanel(ctx, type, onCapture = { full -> onCapture(full) }, onDismiss = { scheduleDock() },
        anchor = { Triple(onRight, if (::lp.isInitialized) lp.y else 300, size) },
        onLive = { onLive() }, onOcr = { onOcr() })
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = (v * d).toInt()
    private val size = dp(40f)            // 窗口（含阴影）
    private var ball: BallView? = null
    private lateinit var lp: WindowManager.LayoutParams
    private var menu: View? = null
    private var onRight = true
    private var docked = false
    private val sp = ctx.getSharedPreferences("bubble", Context.MODE_PRIVATE)

    private fun metrics() = DisplayMetrics().also { @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(it) }
    private val dockRunnable = Runnable { dock() }

    fun show() {
        if (ball != null) return
        val m = metrics()
        onRight = sp.getBoolean("right", true)
        val v = BallView(ctx)
        lp = WindowManager.LayoutParams(size, size, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (onRight) m.widthPixels - size else 0
            y = sp.getInt("y", m.heightPixels / 3)
        }
        attachTouch(v)
        wm.addView(v, lp); ball = v
        scheduleDock()
    }

    /** 转屏/全屏切换后：重新贴边、夹回屏幕内 */
    fun onScreenChanged() {
        val v = ball ?: return
        val m = metrics()
        lp.y = lp.y.coerceIn(0, maxOf(0, m.heightPixels - size))
        lp.x = edgeX(docked); update()
        if (panel.showing) panel.hideForCapture()
        menu?.let { runCatching { wm.removeView(it) } }; menu = null
        v.post { scheduleDock() }
    }

    fun destroy() {
        panel.hide(); menu?.let { runCatching { wm.removeView(it) } }; menu = null; main.removeCallbacks(dockRunnable)
        ball?.let { runCatching { wm.removeView(it) } }; ball = null
    }

    fun setVisible(vis: Boolean) { if (!vis && menu != null) { menu?.let { runCatching { wm.removeView(it) } }; menu = null }; ball?.visibility = if (vis) View.VISIBLE else View.INVISIBLE; if (!vis) panel.hideForCapture() }

    private fun update() { ball?.let { runCatching { wm.updateViewLayout(it, lp) } } }

    private fun edgeX(dockedOut: Boolean): Int {
        val w = metrics().widthPixels
        val out = if (dockedOut) size / 2 else 0
        return if (onRight) w - size + out else -out
    }

    private fun animX(to: Int, dur: Long = 240) = ValueAnimator.ofInt(lp.x, to).apply {
        duration = dur; interpolator = DecelerateInterpolator(1.8f)
        addUpdateListener { lp.x = it.animatedValue as Int; update() }
        start()
    }

    private fun scheduleDock() { main.removeCallbacks(dockRunnable); main.postDelayed(dockRunnable, 2500) }
    private fun dock() {
        if (menu != null || panel.showing) return
        docked = true; animX(edgeX(true), 300)
        ball?.animate()?.alpha(0.55f)?.setDuration(300)?.start()
    }
    private fun wake() {
        main.removeCallbacks(dockRunnable)
        ball?.animate()?.alpha(1f)?.setDuration(120)?.start()
        if (docked) { docked = false; animX(edgeX(false), 160) }
    }

    private fun attachTouch(v: BallView) {
        var downX = 0f; var downY = 0f; var sx = 0; var sy = 0
        var dragging = false; var longFired = false
        val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        val long = Runnable {
            longFired = true
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            panel.hideForCapture(); onCapture(false)
        }
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    wake()
                    downX = e.rawX; downY = e.rawY; sx = lp.x; sy = lp.y
                    dragging = false; longFired = false
                    v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(80).start()
                    main.postDelayed(long, 450)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && !longFired && (abs(dx) > slop || abs(dy) > slop)) { dragging = true; main.removeCallbacks(long) }
                    if (dragging) {
                        lp.x = sx + dx.toInt(); lp.y = sy + dy.toInt(); update()
                        showTrash(); trashHot(isOverTrash(e.rawX, e.rawY))
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(long)
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    when {
                        longFired -> {}
                        dragging -> { val kill = isOverTrash(e.rawX, e.rawY); hideTrash(); if (kill) onClose() else snap() }
                        e.actionMasked == MotionEvent.ACTION_UP -> onTap()
                    }
                }
            }
            true
        }
    }

    private fun snap() {
        val m = metrics()
        onRight = lp.x + size / 2 > m.widthPixels / 2
        lp.y = lp.y.coerceIn(dp(40f), m.heightPixels - size - dp(40f))
        sp.edit().putBoolean("right", onRight).putInt("y", lp.y).apply()
        animX(edgeX(false)); scheduleDock()
    }

    // ---------------- 拖到底部关闭 ----------------
    private var trash: TextView? = null
    private fun showTrash() {
        if (trash != null) return
        val t = TextView(ctx).apply {
            text = "✕"; textSize = 18f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(150, 30, 30, 34)) }
        }
        val tl = WindowManager.LayoutParams(dp(52f), dp(52f), type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(72f)
        }
        runCatching { wm.addView(t, tl); trash = t }
    }
    private fun hideTrash() { trash?.let { runCatching { wm.removeView(it) } }; trash = null }
    private fun isOverTrash(x: Float, y: Float): Boolean {
        val m = metrics()
        val cx = m.widthPixels / 2f; val cy = m.heightPixels - dp(72f) - dp(26f)
        return abs(x - cx) < dp(56f) && abs(y - cy) < dp(56f)
    }
    private fun trashHot(hot: Boolean) {
        trash?.let { it.scaleX = if (hot) 1.25f else 1f; it.scaleY = it.scaleX
            (it.background as GradientDrawable).setColor(if (hot) Color.parseColor("#E5484D") else Color.argb(150, 30, 30, 34)) }
    }

    private fun dismissMenu() { menu?.let { runCatching { wm.removeView(it) } }; menu = null }

    // ---------------- 点一下：一圈小球绕着悬浮球转出来 ----------------
    private fun onTap() {
        when {
            menu != null -> closeMenu()
            panel.showing -> panel.hide()
            panel.hasParked -> panel.show()          // 挂起的小窗（有译文）原样恢复
            else -> openMenu()
        }
    }

    private class Item(val res: Int, val color: String, val desc: String, val name: String, val label: String? = null,
                       val long: (() -> Unit)? = null, val act: () -> Unit)

    private fun items() = listOf(
        Item(R.drawable.ic_t_crop, "#FB7299", "框选翻译（长按：只提取原文）", "框选", long = { onOcr() }) { onCapture(false) },
        Item(R.drawable.ic_t_full, "#FF9548", "全屏翻译", "全屏") { onCapture(true) },
        Item(R.drawable.ic_t_edit, "#3A9BFF", "输入翻译", "输入") { panel.openInput() },
        Item(R.drawable.ic_t_chat, "#21B8A6", "语音翻译（长按：实时字幕）", "语音",
            long = { ctx.startActivity(Intent(ctx, CaptionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { panel.openVoice() },
        Item(R.drawable.ic_t_cam, "#8B7BFF", "拍照翻译", "拍照") { ctx.startActivity(Intent(ctx, PhotoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
        Item(R.drawable.ic_t_live, "#C25BFF", "连续翻译（Gal 对话框）", "连续") { onLive() },
        Item(0, "#FB7299", "翻译成哪种语言", "目标语", label = Prefs(ctx).targetLang.take(1)) { panel.openTargetLang() },
    ).let { all -> val want = Prefs(ctx).menuItems; all.filter { it.name in want }.ifEmpty { all.take(1) } }

    private fun openMenu() {
        main.removeCallbacks(dockRunnable)
        ball?.animate()?.alpha(1f)?.setDuration(120)?.start()
        if (docked) { docked = false; lp.x = edgeX(false); update() }
        val list = items()
        val m = metrics()
        val itemS = dp(50f)
        // 贴边半圆：不管选了几个按钮，都铺满朝屏幕里的这半圈（两端贴着屏幕边），
        // 半径按数量自适应——按钮少就靠近悬浮球，多就往外扩，相邻按钮始终留出一点空隙
        val nItems = list.size
        val span = when { nItems <= 1 -> 0f; nItems == 2 -> 110f; else -> 168f }
        val step = if (nItems > 1) span / (nItems - 1) else 0f
        val need = if (nItems > 1) (itemS + dp(10f)) / (2 * Math.sin(Math.toRadians(step / 2.0))).toFloat() else 0f
        val R0 = maxOf(dp(80f).toFloat(), need)
        var cx = edgeX(false) + size / 2f; var cy = lp.y + size / 2f
        // 悬浮球窗口和菜单窗口的坐标原点可能差一个状态栏：用悬浮球在屏幕上的真实位置
        runCatching { val bv = ball; if (bv != null && bv.height > 0) { val l = IntArray(2); bv.getLocationOnScreen(l); cy = l[1] + bv.height / 2f } }
        var offX = 0f; var offY = 0f
        val baseIn = if (onRight) 180f else 0f
        fun angOf(i: Int, r: Float) = baseIn - span / 2 + i * step + r
        fun fits(r: Float) = list.indices.all { i ->
            val y = cy + R0 * Math.sin(Math.toRadians(angOf(i, r).toDouble()))
            y > itemS / 2 + dp(8f) && y < m.heightPixels - itemS / 2 - dp(8f)
        }
        var rot = 0f
        val dir = if ((cy < m.heightPixels / 2) == onRight) -1f else 1f
        // 靠近屏幕顶/底时不转弧（转了两端会跑出屏幕边），而是把整个半圆往里挪
        if (!fits(0f)) {
            val lim = R0 * Math.sin(Math.toRadians(span / 2.0)).toFloat() + itemS / 2 + dp(8f)
            cy = cy.coerceIn(lim, maxOf(lim, m.heightPixels - lim))
        }
        if (dir == 0f) rot = 0f

        val box = FrameLayout(ctx).apply { clipChildren = false }
        val balls = ArrayList<View>()
        list.forEach { it ->
            val col = Color.parseColor(it.color)
            val v = FrameLayout(ctx).apply {
                // 和系统悬浮球一样：半透明深灰圆 + 白色线条图标，不加文字
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(190, 52, 52, 56)) }
                if (it.res != 0) addView(android.widget.ImageView(ctx).apply {
                    setImageResource(it.res); imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                }, FrameLayout.LayoutParams(dp(22f), dp(22f), Gravity.CENTER))
                else addView(TextView(ctx).apply {
                    text = it.label; textSize = 17f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                contentDescription = it.desc
                setOnClickListener { _ -> closeMenu(); it.act() }
                setOnLongClickListener { _ ->
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    if (it.long != null) { closeMenu(); it.long.invoke() } else android.widget.Toast.makeText(ctx, it.desc, android.widget.Toast.LENGTH_SHORT).show()
                    true
                }
                scaleX = 0f; scaleY = 0f; alpha = 0f
            }
            box.addView(v, FrameLayout.LayoutParams(itemS, itemS))
            balls += v
        }
        // 点空白处（包括悬浮球本身）收起
        box.setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_DOWN) closeMenu(); true }
        val mlp = WindowManager.LayoutParams(m.widthPixels, m.heightPixels, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START; x = 0; y = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        runCatching { wm.addView(box, mlp); menu = box }.onFailure { panel.toggle(); return }
        val n = list.size
        // t: 0→1 整体进度；每个球错开一点，从球心旋转着甩出来
        val place = { t: Float ->
            ball?.openProgress(t.coerceIn(0f, 1f))
            balls.forEachIndexed { i, v ->
                val raw = ((t - i * 0.05f) / (1f - (n - 1) * 0.05f))
                val f = android.view.animation.OvershootInterpolator(1.3f).getInterpolation(raw.coerceIn(0f, 1f))
                val spin = (1f - f) * (if (onRight) -70f else 70f)
                val ang = Math.toRadians((angOf(i, rot) + spin).toDouble())
                val rr = R0 * f
                val edge = itemS / 2f + dp(4f)
                val bx = (cx + rr * Math.cos(ang)).toFloat().coerceIn(edge, maxOf(edge, m.widthPixels - edge))
                val by = (cy + rr * Math.sin(ang)).toFloat()
                v.translationX = bx - offX - itemS / 2; v.translationY = by - offY - itemS / 2
                val sc = raw.coerceIn(0f, 1f); v.scaleX = 0.4f + 0.6f * f.coerceAtMost(1.1f); v.scaleY = v.scaleX; v.alpha = sc
            }
        }
        place(0f)
        menuPlace = place
        box.post {
            val ml = IntArray(2); box.getLocationOnScreen(ml); offX = ml[0].toFloat(); offY = ml[1].toFloat()
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 460; interpolator = DecelerateInterpolator(1.4f)
                addUpdateListener { a -> place(a.animatedValue as Float) }
                start()
            }
        }
    }

    private var menuPlace: ((Float) -> Unit)? = null
    private fun closeMenu() {
        val box = menu ?: return
        val place = menuPlace
        menu = null; menuPlace = null
        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 200
            addUpdateListener { a -> place?.invoke(a.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(an: android.animation.Animator) { runCatching { wm.removeView(box) }; scheduleDock() }
            })
            start()
        }
    }

    private fun blend(c: Int): Int = Color.rgb(minOf(255, Color.red(c) + 40), minOf(255, Color.green(c) + 30), minOf(255, Color.blue(c) + 20))

}

/** 渐变小圆球 + 白色“译” */
class BallView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * d; color = Color.argb(140, 255, 255, 255) }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; textSize = 15f * d
    }
    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }
    private var open = 0f
    private val xp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 2f * d; strokeCap = Paint.Cap.ROUND }
    private val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 52, 52, 56) }
    /** 菜单展开进度：0 = 正常“译”，1 = 深灰圆 + ✕（跟系统悬浮球一样） */
    fun openProgress(p: Float) { if (p != open) { open = p; invalidate() } }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = minOf(width, height) / 2f - 4f * d
        fill.shader = LinearGradient(cx - r, cy - r, cx + r, cy + r,
            Color.parseColor("#FB7299"), Color.parseColor("#FFA26B"), Shader.TileMode.CLAMP)
        fill.setShadowLayer(3f * d, 0f, 1f * d, Color.argb(90, 0, 0, 0))
        c.drawCircle(cx, cy, r, fill)
        c.drawCircle(cx, cy, r - 0.6f * d, ring)
        if (open > 0f) { dark.alpha = (230 * open).toInt(); c.drawCircle(cx, cy, r, dark) }
        if (open < 1f) {
            txt.alpha = (255 * (1f - open)).toInt()
            val fm = txt.fontMetrics
            c.drawText("译", cx, cy - (fm.ascent + fm.descent) / 2, txt)
        }
        if (open > 0f) {
            xp.alpha = (255 * open).toInt()
            c.save(); c.rotate(90f * (1f - open), cx, cy)
            val k = 5.5f * d
            c.drawLine(cx - k, cy - k, cx + k, cy + k, xp); c.drawLine(cx + k, cy - k, cx - k, cy + k, xp)
            c.restore()
        }
    }
}
