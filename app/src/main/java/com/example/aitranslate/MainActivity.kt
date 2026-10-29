package com.example.aitranslate

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.graphics.Color
import android.widget.*

class MainActivity : Activity() {
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
            setPadding(ui.dp(20f), ui.dp(28f), ui.dp(20f), ui.dp(28f))
            setBackgroundColor(ui.bg)
        }
        root.addView(ui.text("Index", 30f, bold = true))
        root.addView(ui.text("由 B 站 Index-Translate 免费接口驱动 · 无需 Key", 13f, ui.onSurfaceVar), ui.lp(4f))

        // 功能入口：一张卡片里一行一项，左边彩色图标
        fun group(title: String?): LinearLayout {
            if (title != null) root.addView(ui.text(title, 13f, ui.onSurfaceVar, bold = true).apply { setPadding(ui.dp(8f), 0, 0, 0) }, ui.lp(22f))
            val g = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = ui.round(ui.surface, ui.dp(20f).toFloat()); setPadding(0, ui.dp(4f), 0, ui.dp(4f)) }
            root.addView(g, ui.lp(8f)); return g
        }
        fun row(g: LinearLayout, res: Int, color: String, title: String, desc: String, onClick: () -> Unit) {
            val r = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; isClickable = true
                background = ui.ripple(Color.TRANSPARENT, ui.dp(16f).toFloat())
                setPadding(ui.dp(16f), ui.dp(12f), ui.dp(12f), ui.dp(12f))
                setOnClickListener { onClick() }
            }
            val c = Color.parseColor(color)
            r.addView(FrameLayout(this).apply {
                background = ui.round(c, ui.dp(10f).toFloat())
                addView(ImageView(this@MainActivity).apply { setImageResource(res); imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE) },
                    FrameLayout.LayoutParams(ui.dp(18f), ui.dp(18f), Gravity.CENTER))
            }, LinearLayout.LayoutParams(ui.dp(34f), ui.dp(34f)))
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(14f), 0, ui.dp(8f), 0) }
            col.addView(ui.text(title, 16f))
            col.addView(ui.text(desc, 12f, ui.onSurfaceVar).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            r.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            r.addView(ui.text("›", 22f, ui.onSurfaceVar))
            g.addView(r)
        }
        val quick = group("快捷入口")
        row(quick, R.drawable.ic_tile, "#FB7299", "悬浮球", "点一下弹出功能圈，长按直接框选") { toggleMagnet() }
        row(quick, R.drawable.ic_t_crop, "#FF9548", "控制中心磁贴", "下拉一点就框选翻译") { addTile() }
        val tools = group("翻译工具")
        row(tools, R.drawable.ic_t_cam, "#8B7BFF", "图片翻译", "选一张图，识别文字并翻译") {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), PICK_IMAGE)
        }
        row(tools, R.drawable.ic_t_chat, "#21B8A6", "语音翻译", "按住说话，译成语音") { startActivity(Intent(this, VoiceActivity::class.java)) }
        row(tools, R.drawable.ic_t_live, "#C25BFF", "实时字幕", "直播 / 视频声音自动出译文字幕") { startActivity(Intent(this, CaptionActivity::class.java)) }
        row(tools, R.drawable.ic_t_edit, "#3A9BFF", "长文档翻译", "整本小说 / txt，译名前后一致") { startActivity(Intent(this, DocActivity::class.java)) }
        val more = group(null)
        row(more, R.drawable.ic_t_full, "#8E8E93", "设置", "模型、语种、悬浮球菜单、字幕识别") { startActivity(Intent(this, SettingsActivity::class.java)) }

        root.addView(ui.text("小提示：在任意 App 长按选中文字 → 菜单里点「AI译」", 12f, ui.onSurfaceVar).apply { gravity = Gravity.CENTER }, ui.lp(20f))

        setContentView(ScrollView(this).apply { setBackgroundColor(ui.bg); addView(root) })
    }

    @Deprecated("Deprecated in Java")
    private fun addTile() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val sbm = getSystemService(android.app.StatusBarManager::class.java)
            sbm.requestAddTileService(android.content.ComponentName(this, QuickTile::class.java),
                "Index 框选翻译", android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_tile),
                mainExecutor) { r ->
                val msg = when (r) {
                    android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "已添加，下拉快捷开关即可使用"
                    android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "磁贴已经在快捷开关里了"
                    else -> "下拉快捷开关 → 编辑（铅笔）→ 把「Index 框选翻译」拖进去"
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        } else Toast.makeText(this, "下拉快捷开关 → 编辑（铅笔）→ 把「Index 框选翻译」拖进去", Toast.LENGTH_LONG).show()
    }

    private fun toggleMagnet() {
        val p = Prefs(this)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val a = ShotA11y.instance
            if (a == null) {
                p.bubbleOn = true
                Toast.makeText(this, "在无障碍里打开「Index 框选翻译」，悬浮球就会出现", Toast.LENGTH_LONG).show()
                startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return
            }
            p.bubbleOn = !p.bubbleOn; a.applyBubble()
            Toast.makeText(this, if (p.bubbleOn) "悬浮球已开启：点一下打开小窗，长按直接框选" else "已关闭悬浮球", Toast.LENGTH_SHORT).show()
            return
        }
        if (FloatService.running) {
            startService(Intent(this, FloatService::class.java).setAction(FloatService.ACTION_STOP))
            Toast.makeText(this, "已关闭悬浮球", Toast.LENGTH_SHORT).show(); return
        }
        startActivity(Intent(this, CaptureActivity::class.java).putExtra(CaptureActivity.EXTRA_BUBBLE, true))
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (requestCode == PICK_IMAGE && resultCode == RESULT_OK) {
            startActivity(Intent(this, TranslateActivity::class.java)
                .setAction(Intent.ACTION_SEND).setType("image/*")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
    }

    companion object { private const val PICK_IMAGE = 1; private const val CAPTURE = 2 }
}
