package com.example.aitranslate

import android.animation.ValueAnimator
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/** 屏幕边缘的“磁铁”悬浮球：点一下 → 截屏 → 框选区域 → 图片翻译 */
class FloatService : Service() {
    private lateinit var wm: WindowManager
    private var bubble: BubbleController? = null
    private lateinit var blp: WindowManager.LayoutParams
    private var selector: SelectView? = null
    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var capW = 0; private var capH = 0
    private val bgThread = HandlerThread("cap").apply { start() }
    private val bg = Handler(bgThread.looper)
    private val main = Handler(Looper.getMainLooper())
    private var busy = false

    override fun onBind(i: Intent?): IBinder? = null

    private fun overlayType() = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == ACTION_CAPTURE && projection != null) {
            main.postDelayed({ capture() }, 500); return START_NOT_STICKY
        }
        if (intent?.action == ACTION_BUBBLE && projection != null) {
            showBubble(); return START_NOT_STICKY
        }
        startForegroundCompat()
        if (projection == null) {
            val code = intent?.getIntExtra("code", 0) ?: 0
            @Suppress("DEPRECATION") val data: Intent? = intent?.getParcelableExtra("data")
            if (data == null) { stopSelf(); return START_NOT_STICKY }
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = try { mpm.getMediaProjection(code, data) } catch (e: Exception) { null }
            if (projection == null) { toast("无法获取截屏权限"); stopSelf(); return START_NOT_STICKY }
            projection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { main.post { stopSelf() } }
            }, main)
            setupDisplay()
        }
        if (intent?.getBooleanExtra(CaptureActivity.EXTRA_BUBBLE, false) == true) showBubble()
        running = true
        refreshTile()
        if (intent?.getBooleanExtra("captureNow", false) == true) main.postDelayed({ capture() }, 600)
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(NotificationChannel("float", "屏幕磁铁", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1, Intent(this, FloatService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "float") else @Suppress("DEPRECATION") Notification.Builder(this)
        val n = b.setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("Index 屏幕磁铁运行中")
            .setContentText("下拉磁贴或悬浮球框选翻译 · 点这里关闭")
            .setContentIntent(stop).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(7, n)
    }

    private fun realMetrics(): DisplayMetrics = DisplayMetrics().also {
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(it)
    }

    private fun refreshTile() {
        if (Build.VERSION.SDK_INT >= 24) runCatching {
            android.service.quicksettings.TileService.requestListeningState(this,
                android.content.ComponentName(this, QuickTile::class.java))
        }
    }

    private fun setupDisplay() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val m = realMetrics(); capW = m.widthPixels; capH = m.heightPixels
        reader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
        vd = projection!!.createVirtualDisplay("index-cap", capW, capH, m.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, bg)
    }

    /** 旋转后尺寸变化，重建 ImageReader */
    private fun ensureSize(): Boolean {
        val m = realMetrics()
        if (m.widthPixels == capW && m.heightPixels == capH) return false
        capW = m.widthPixels; capH = m.heightPixels
        val old = reader
        reader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
        vd?.resize(capW, capH, m.densityDpi); vd?.surface = reader!!.surface
        old?.close()
        return true
    }

    // ---------------- 悬浮球（Android 10 及以下的兜底；11+ 由无障碍服务托管） ----------------
    private var fullMode = false
    private fun showBubble() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (bubble == null) bubble = BubbleController(this, overlayType(),
            onCapture = { full -> fullMode = full; capture() },
            onClose = { toast("已关闭悬浮球"); stopSelf() }).also { it.show() }
    }

    // ---------------- 截屏 ----------------
    private fun capture() {
        if (busy || selector != null) return
        if (Build.VERSION.SDK_INT >= 30 && ShotA11y.instance != null) {
            bubble?.setVisible(false)
            ShotA11y.instance!!.captureSoon(150, fullMode)
            main.postDelayed({ bubble?.setVisible(true) }, 700)
            return
        }
        busy = true
        bubble?.setVisible(false)
        val resized = ensureSize()
        main.postDelayed({ grab(if (resized) 8 else 6) }, if (resized) 350 else 180)
    }

    private fun grab(tries: Int) {
        bg.post {
            val bmp = runCatching { readBitmap() }.getOrNull()
            main.post {
                when {
                    bmp != null && fullMode -> { busy = false; bubble?.setVisible(true); cropAndTranslate(bmp, Rect(0, 0, bmp.width, bmp.height)) }
                    bmp != null -> { busy = false; showSelector(bmp) }
                    tries > 0 -> main.postDelayed({ grab(tries - 1) }, 80)
                    else -> { busy = false; bubble?.setVisible(true); toast("截屏失败，请再点一次") }
                }
            }
        }
    }

    private fun readBitmap(): Bitmap? {
        val img = reader?.acquireLatestImage() ?: return null
        img.use {
            val p = it.planes[0]
            val rowPx = p.rowStride / p.pixelStride
            val full = Bitmap.createBitmap(rowPx, it.height, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(p.buffer)
            return if (rowPx == it.width) full else Bitmap.createBitmap(full, 0, 0, it.width, it.height)
        }
    }

    // ---------------- 框选 ----------------
    private fun showSelector(shot: Bitmap) {
        val v = SelectView(this, shot,
            onPick = { r -> finishSelect(); cropAndTranslate(shot, r) },
            onCancel = { finishSelect() })
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_FULLSCREEN,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        wm.addView(v, lp); selector = v
    }

    private fun finishSelect() {
        selector?.let { runCatching { wm.removeView(it) } }
        selector = null
        bubble?.setVisible(true)
    }

    private fun cropAndTranslate(shot: Bitmap, r: Rect) {
        val b = bubble
        if (b == null) ShotFlow.openTranslate(this, shot, r) else ShotFlow.crop(shot, r)?.let { b.panel.showImage(it) }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        running = false
        finishSelect()
        bubble?.destroy(); bubble = null
        runCatching { vd?.release() }; runCatching { reader?.close() }
        runCatching { projection?.stop() }; projection = null
        bgThread.quitSafely()
        refreshTile()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "stop"
        const val ACTION_CAPTURE = "capture"
        const val ACTION_BUBBLE = "bubble"
        @Volatile var running = false
    }
}

/** 全屏冻结截图，拖动画框 → 松手后进入调整：拖边角改大小、拖框内移动，点「翻译」确认 */
class SelectView(ctx: Context, private val shot: Bitmap,
                 private val onPick: (Rect) -> Unit, private val onCancel: () -> Unit,
                 private val hint: String? = null, private val okLabel: String = "翻译") : View(ctx) {
    private val d = resources.displayMetrics.density
    private val dim = Paint().apply { color = Color.argb(140, 0, 0, 0) }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FB7299"); style = Paint.Style.STROKE; strokeWidth = 2.5f * d }
    private val corner = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4f * d; strokeCap = Paint.Cap.ROUND }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; setShadowLayer(3f * d, 0f, 0f, Color.argb(120, 0, 0, 0)) }
    private val hintP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 15f * d; textAlign = Paint.Align.CENTER; setShadowLayer(4f * d, 0f, 0f, Color.BLACK) }
    private val barBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(225, 40, 40, 46) }
    private val okBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F25D8E") }
    private val btnP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 14.5f * d; textAlign = Paint.Align.CENTER; typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL) }
    private val loc = IntArray(2)
    private var sx = 0f; private var sy = 0f; private var ex = 0f; private var ey = 0f
    private var drawing = false; private var moved = false
    /** 调整状态：已经有框 */
    private var adjusting = false
    private val box = RectF()
    private var grab = 0          // 位标记：1 左 2 上 4 右 8 下；16 = 整体移动
    private var lx = 0f; private var ly = 0f
    private val btnCancel = RectF(); private val btnRedo = RectF(); private val btnOk = RectF(); private val bar = RectF()

    private fun sel() = RectF(minOf(sx, ex), minOf(sy, ey), maxOf(sx, ex), maxOf(sy, ey))

    private fun layoutBar() {
        val bw = 236 * d; val bh = 46 * d; val gap = 12 * d
        val w = width.toFloat(); val h = height.toFloat()
        var top = box.bottom + gap
        if (top + bh > h - 24 * d) top = box.top - gap - bh
        if (top < 40 * d) top = box.bottom - gap - bh
        val left = (box.centerX() - bw / 2).coerceIn(8 * d, maxOf(8 * d, w - bw - 8 * d))
        bar.set(left, top, left + bw, top + bh)
        val p = 4 * d; val cw = (bw - p * 2) / 3
        btnCancel.set(bar.left + p, bar.top + p, bar.left + p + cw, bar.bottom - p)
        btnRedo.set(btnCancel.right, bar.top + p, btnCancel.right + cw, bar.bottom - p)
        btnOk.set(btnRedo.right, bar.top + p, bar.right - p, bar.bottom - p)
    }

    override fun onDraw(c: Canvas) {
        getLocationOnScreen(loc)
        c.drawBitmap(shot, -loc[0].toFloat(), -loc[1].toFloat(), null)
        val w = width.toFloat(); val h = height.toFloat()
        if (!adjusting && !drawing && !moved) {
            c.drawRect(0f, 0f, w, h, dim)
            c.drawText(hint ?: "拖动框选要翻译的区域 · 轻点取消", w / 2, h * 0.14f, hintP)
            return
        }
        val r = if (adjusting) box else sel()
        c.drawRect(0f, 0f, w, r.top, dim); c.drawRect(0f, r.bottom, w, h, dim)
        c.drawRect(0f, r.top, r.left, r.bottom, dim); c.drawRect(r.right, r.top, w, r.bottom, dim)
        c.drawRect(r, border)
        val k = minOf(16f * d, r.width() / 3, r.height() / 3)
        c.drawLine(r.left, r.top, r.left + k, r.top, corner); c.drawLine(r.left, r.top, r.left, r.top + k, corner)
        c.drawLine(r.right, r.top, r.right - k, r.top, corner); c.drawLine(r.right, r.top, r.right, r.top + k, corner)
        c.drawLine(r.left, r.bottom, r.left + k, r.bottom, corner); c.drawLine(r.left, r.bottom, r.left, r.bottom - k, corner)
        c.drawLine(r.right, r.bottom, r.right - k, r.bottom, corner); c.drawLine(r.right, r.bottom, r.right, r.bottom - k, corner)
        if (!adjusting) return
        // 四条边中点的小圆点：提示可以拖
        val kr = 4.5f * d
        c.drawCircle(r.centerX(), r.top, kr, knob); c.drawCircle(r.centerX(), r.bottom, kr, knob)
        c.drawCircle(r.left, r.centerY(), kr, knob); c.drawCircle(r.right, r.centerY(), kr, knob)
        if (grab != 0) return
        c.drawText("拖边角调整范围 · 拖框内移动", w / 2, maxOf(48 * d, minOf(r.top - 18 * d, h * 0.14f)), hintP)
        layoutBar()
        c.drawRoundRect(bar, bar.height() / 2, bar.height() / 2, barBg)
        c.drawRoundRect(btnOk, btnOk.height() / 2, btnOk.height() / 2, okBg)
        val fm = btnP.fontMetrics; val dy = -(fm.ascent + fm.descent) / 2
        c.drawText("取消", btnCancel.centerX(), btnCancel.centerY() + dy, btnP)
        c.drawText("重画", btnRedo.centerX(), btnRedo.centerY() + dy, btnP)
        c.drawText(okLabel, btnOk.centerX(), btnOk.centerY() + dy, btnP)
    }

    private fun pick() {
        getLocationOnScreen(loc)
        onPick(Rect((box.left + loc[0]).toInt(), (box.top + loc[1]).toInt(), (box.right + loc[0]).toInt(), (box.bottom + loc[1]).toInt()))
    }

    private fun hitGrab(x: Float, y: Float): Int {
        val t = 26 * d
        val inX = x > box.left - t && x < box.right + t; val inY = y > box.top - t && y < box.bottom + t
        if (!inX || !inY) return 0
        var g = 0
        if (abs(x - box.left) < t) g = g or 1 else if (abs(x - box.right) < t) g = g or 4
        if (abs(y - box.top) < t) g = g or 2 else if (abs(y - box.bottom) < t) g = g or 8
        if (g == 0 && box.contains(x, y)) g = 16
        return g
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (adjusting) return adjustTouch(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { sx = e.x; sy = e.y; ex = e.x; ey = e.y; drawing = true; moved = false }
            MotionEvent.ACTION_MOVE -> {
                ex = e.x.coerceIn(0f, width.toFloat()); ey = e.y.coerceIn(0f, height.toFloat())
                if (abs(ex - sx) > 10 * d || abs(ey - sy) > 10 * d) moved = true
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                drawing = false
                val r = sel()
                if (!moved || r.width() < 24 * d || r.height() < 12 * d) { moved = false; onCancel(); return true }
                box.set(r); adjusting = true; grab = 0
                performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> onCancel()
        }
        return true
    }

    private fun adjustTouch(e: MotionEvent): Boolean {
        val x = e.x; val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (bar.contains(x, y)) { grab = -1; return true }
                grab = hitGrab(x, y); lx = x; ly = y
                if (grab == 0) {
                    // 点在框外：从这里重新画
                    adjusting = false; sx = x; sy = y; ex = x; ey = y; drawing = true; moved = false
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (grab <= 0) return true
                val dx = x - lx; val dy = y - ly; lx = x; ly = y
                val w = width.toFloat(); val h = height.toFloat(); val minW = 24 * d; val minH = 16 * d
                if (grab == 16) {
                    val mx = dx.coerceIn(-box.left, w - box.right); val my = dy.coerceIn(-box.top, h - box.bottom)
                    box.offset(mx, my)
                } else {
                    if (grab and 1 != 0) box.left = (box.left + dx).coerceIn(0f, box.right - minW)
                    if (grab and 4 != 0) box.right = (box.right + dx).coerceIn(box.left + minW, w)
                    if (grab and 2 != 0) box.top = (box.top + dy).coerceIn(0f, box.bottom - minH)
                    if (grab and 8 != 0) box.bottom = (box.bottom + dy).coerceIn(box.top + minH, h)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (grab == -1) {
                    when {
                        btnOk.contains(x, y) -> pick()
                        btnCancel.contains(x, y) -> onCancel()
                        btnRedo.contains(x, y) -> { adjusting = false; moved = false; drawing = false; invalidate() }
                    }
                }
                grab = 0; invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { grab = 0; invalidate() }
        }
        return true
    }
}
