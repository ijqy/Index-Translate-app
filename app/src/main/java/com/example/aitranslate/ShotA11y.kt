package com.example.aitranslate

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

/** 无障碍截屏（Android 11+）：像系统截屏一样直接截，不需要共享屏幕 */
class ShotA11y : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var selector: SelectView? = null
    private var bubble: BubbleController? = null

    override fun onServiceConnected() { instance = this; applyBubble() }
    override fun onUnbind(intent: Intent?): Boolean { live?.stop(); soloPanel?.hide(); soloPanel = null; bubble?.destroy(); bubble = null; instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { bubble?.destroy(); bubble = null; instance = null; super.onDestroy() }

    /** 按设置显示/隐藏悬浮球（无障碍窗口不需要悬浮窗权限，也不常驻通知） */
    fun applyBubble() {
        if (Prefs(this).bubbleOn) {
            if (bubble == null) bubble = BubbleController(this, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                onCapture = { full -> captureSoon(220, full) },
                onClose = { Prefs(this).bubbleOn = false; applyBubble(); toast("已关闭悬浮球，可在首页重新打开") },
                onLive = { startLive() }, onOcr = { ocrNext = true; captureSoon(220) }).also { it.show() }
        } else { bubble?.destroy(); bubble = null }
    }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onConfigurationChanged(c: android.content.res.Configuration) {
        super.onConfigurationChanged(c)
        main.postDelayed({ bubble?.onScreenChanged() }, 300)
    }

    /** 没开悬浮球时，结果也用同一个小窗显示（不启动 Activity，HyperOS 会拦后台启动） */
    private var soloPanel: FloatPanel? = null
    private fun resultPanel(): FloatPanel = bubble?.panel ?: soloPanel ?: FloatPanel(this,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        onCapture = { full -> captureSoon(220, full) }, onDismiss = {},
        anchor = { Triple(true, resources.displayMetrics.heightPixels / 4, 0) },
        onLive = { startLive() }, onOcr = { ocrNext = true; captureSoon(220) }).also { it.canMinimize = false; soloPanel = it }

    /** 下拉磁贴直接调用：先收起通知栏，再截屏框选，全程不经过 Activity */
    fun captureFromTile() {
        if (selector != null) remove(getSystemService(WINDOW_SERVICE) as WindowManager)
        bubble?.setVisible(false); soloPanel?.hideForCapture()
        if (Build.VERSION.SDK_INT >= 31) performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        collapseStatusBar(this)
        waitShadeGone(System.currentTimeMillis(), 0)
    }

    /** 等控制中心/通知栏真正收起来再截，最多等 2.5 秒；中途再补收一次 */
    private fun waitShadeGone(t0: Long, tries: Int) {
        val elapsed = System.currentTimeMillis() - t0
        val open = shadeOpen()
        if ((!open && elapsed >= 250) || elapsed > 2500) {
            if (open) toast("控制中心没收起来，下拉后再点一次，或用悬浮球框选")
            main.postDelayed({ capture(false) }, 180); return
        }
        if (open && tries == 8) {
            if (Build.VERSION.SDK_INT >= 31) performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            collapseStatusBar(this)
        }
        main.postDelayed({ waitShadeGone(t0, tries + 1) }, 100)
    }

    /** 屏幕上是否还有大块的系统界面（下拉通知栏 / HyperOS 控制中心） */
    private fun shadeOpen(): Boolean = runCatching {
        val h = resources.displayMetrics.heightPixels
        val r = Rect()
        windows.any { w ->
            w.getBoundsInScreen(r)
            w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM && r.height() > h / 3 &&
                (w.root?.packageName?.toString()?.let { it == "com.android.systemui" || it.contains("controlcenter") } ?: false)
        }
    }.getOrDefault(false)

    fun captureSoon(delay: Long = 450, full: Boolean = false) {
        bubble?.setVisible(false); soloPanel?.hideForCapture()
        main.postDelayed({ capture(full) }, delay)
    }

    private fun capture(full: Boolean = false) {
        if (Build.VERSION.SDK_INT < 30 || selector != null) return
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(r: ScreenshotResult) {
                val hb = r.hardwareBuffer
                val bmp = Bitmap.wrapHardwareBuffer(hb, r.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)?.let { ShotFlow.upright(this@ShotA11y, it) }
                hb.close()
                bubble?.setVisible(true)
                when {
                    bmp == null -> toast("截屏失败")
                    full -> deliver(bmp, Rect(0, 0, bmp.width, bmp.height))
                    else -> showSelector(bmp)
                }
            }
            override fun onFailure(code: Int) {
                // 频率限制等，稍后重试一次
                if (code == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) main.postDelayed({ capture(full) }, 400)
                else { bubble?.setVisible(true); toast("截屏失败（$code）") }
            }
        })
    }

    /** 只截一张图，回调在主线程（截屏频率受限时自动稍后重试） */
    fun shoot(cb: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) { cb(null); return }
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(r: ScreenshotResult) {
                val hb = r.hardwareBuffer
                val bmp = runCatching { Bitmap.wrapHardwareBuffer(hb, r.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)?.let { ShotFlow.upright(this@ShotA11y, it) } }.getOrNull()
                hb.close(); cb(bmp)
            }
            override fun onFailure(code: Int) {
                if (code == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) main.postDelayed({ shoot(cb) }, 400) else cb(null)
            }
        })
    }

    // ================= 连续翻译（Gal 对话框） =================
    private var live: LiveTranslator? = null
    /** 下一次框选只提取原文 */
    private var ocrNext = false

    fun startLive() {
        if (Build.VERSION.SDK_INT < 30) { toast("连续翻译需要 Android 11 及以上"); return }
        bubble?.setVisible(false); soloPanel?.hideForCapture(); live?.hold(); live?.setVisible(false)
        main.postDelayed({
            shoot { bmp ->
                if (bmp == null) { toast("截屏失败"); if (live != null) { live?.setVisible(true); live?.resume() } else restoreAfterLive(); return@shoot }
                showSelector(bmp, "框住游戏的对话框 · 之后会自动连续翻译", onCancel = {
                    if (live != null) { live?.setVisible(true); live?.resume() } else restoreAfterLive()
                }) { r ->
                    val cur = live
                    if (cur != null) cur.setArea(r)
                    else live = LiveTranslator(this, r, ::shoot, reselect = { startLive() },
                        onStop = { live = null; restoreAfterLive() }).also { it.start() }
                }
            }
        }, if (live != null) 120 else 260)
    }

    private fun restoreAfterLive() { bubble?.setVisible(true) }

    private fun showSelector(shot: Bitmap, hint: String? = null, onCancel: () -> Unit = {}, onPicked: ((Rect) -> Unit)? = null) {
        main.postDelayed({ KeepAlive.stop(this) }, 1500)
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val v = SelectView(this, shot,
            onPick = { r -> remove(wm); if (onPicked != null) onPicked(r) else deliver(shot, r) },
            onCancel = { remove(wm); ocrNext = false; onCancel() }, hint = hint ?: if (ocrNext) "拖动框选要提取原文的区域 · 轻点取消" else null,
            okLabel = if (onPicked != null) "开始" else if (ocrNext) "提取" else "翻译")
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_FULLSCREEN,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        wm.addView(v, lp); selector = v
    }

    /** 有悬浮球就在小窗里出结果（不启动 Activity，避免后台启动被系统拦截）；否则弹翻译卡片 */
    private fun deliver(shot: Bitmap, r: Rect) {
        val ocr = ocrNext; ocrNext = false
        val crop = ShotFlow.crop(shot, r) ?: return
        if (ocr) resultPanel().showOriginal(crop) else resultPanel().showImage(crop)
    }

    private fun remove(wm: WindowManager) { selector?.let { runCatching { wm.removeView(it) } }; selector = null }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        @Volatile var instance: ShotA11y? = null

        /** 老办法收起状态栏（需要 EXPAND_STATUS_BAR，HyperOS 上通常有效） */
        @Suppress("WrongConstant")
        fun collapseStatusBar(ctx: Context) = runCatching {
            val sb = ctx.getSystemService("statusbar") ?: return@runCatching
            sb.javaClass.getMethod("collapsePanels").invoke(sb)
        }
    }
}

object ShotFlow {
    /** 真实整屏尺寸（含状态栏/导航栏/刘海，横屏也对） */
    fun real(ctx: Context): android.util.DisplayMetrics = android.util.DisplayMetrics().also {
        @Suppress("DEPRECATION") (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(it)
    }

    /** 个别机型横屏截图仍是竖着的：按屏幕方向转正 */
    fun upright(ctx: Context, b: Bitmap): Bitmap {
        val m = real(ctx)
        if ((b.width > b.height) == (m.widthPixels > m.heightPixels) || b.width == b.height) return b
        @Suppress("DEPRECATION")
        val rot = (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
        val deg = when (rot) { android.view.Surface.ROTATION_90 -> 270f; android.view.Surface.ROTATION_270 -> 90f; else -> 90f }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, android.graphics.Matrix().apply { postRotate(deg) }, true)
    }

    fun crop(shot: Bitmap, r: Rect): Bitmap? {
        val rr = Rect(r); rr.intersect(0, 0, shot.width, shot.height)
        if (rr.width() < 8 || rr.height() < 8) return null
        return if (rr.width() == shot.width && rr.height() == shot.height) shot
            else Bitmap.createBitmap(shot, rr.left, rr.top, rr.width(), rr.height())
    }

    fun openTranslate(ctx: Context, shot: Bitmap, r: Rect) {
        val crop = crop(shot, r) ?: return
        ctx.cacheDir.listFiles { x -> x.name.startsWith("shot_") }?.forEach { it.delete() }
        val f = File(ctx.cacheDir, "shot_${System.currentTimeMillis()}.jpg")
        FileOutputStream(f).use { crop.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        ctx.startActivity(Intent(ctx, TranslateActivity::class.java)
            .putExtra(TranslateActivity.EXTRA_SHOT, f.absolutePath)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    }
}
