package com.example.aitranslate

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.View
import android.widget.*

/** 长文档翻译：Index-NativeLong，支持 中↔英、中↔日 */
class DocActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var prefs: Prefs
    private lateinit var info: TextView
    private lateinit var output: TextView
    private lateinit var progress: ProgressBar
    private lateinit var goBtn: TextView
    private var text = ""
    private var name = "document.txt"
    private var dir: String? = null
    private var result: String? = null
    private val sb = StringBuilder()
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var dirty = false
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Ui.themeFor(this))
        super.onCreate(savedInstanceState)
        ui = Ui(this); ui.setupScreen(this); prefs = Prefs(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20f), ui.dp(28f), ui.dp(20f), ui.dp(28f))
        }
        root.addView(ui.text("长文档翻译", 28f, bold = true))
        root.addView(ui.text("支持 中↔英、中↔日，单次最长约 20 万字，全文保持人名和术语一致。", 13f, ui.onSurfaceVar), ui.lp(4f))

        val c = ui.card()
        info = ui.text("还没选择文件", 14f, ui.onSurfaceVar)
        c.addView(info)
        val btns = LinearLayout(this)
        btns.addView(ui.pill("选择 txt", false) {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("text/*"), PICK)
        })
        btns.addView(ui.pill("粘贴剪贴板", false) {
            val clip = getSystemService(ClipboardManager::class.java).primaryClip
            val t = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
            if (t.isNullOrBlank()) toast("剪贴板是空的") else setText(t, "剪贴板文本")
        })
        c.addView(btns, ui.lp(14f))
        goBtn = ui.pill("开始翻译", true, weight = false) { start() }
        c.addView(goBtn, ui.lp(10f, ui.dp(48f)))
        root.addView(c, ui.lp(20f))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true; visibility = View.GONE
            indeterminateTintList = ColorStateList.valueOf(ui.primary)
        }
        root.addView(progress, ui.lp(16f, ui.dp(4f)))

        val out = ui.card()
        output = ui.text("译文会显示在这里", 16f, ui.onSurfaceVar).apply { setTextIsSelectable(true) }
        out.addView(output)
        val ob = LinearLayout(this)
        ob.addView(ui.pill("复制", false) {
            result?.let {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("doc", it))
                toast("已复制")
            }
        })
        ob.addView(ui.pill("保存为 txt", false) {
            if (result != null) startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain")
                .putExtra(Intent.EXTRA_TITLE, name.substringBeforeLast('.') + ".译文.txt"), SAVE)
        })
        out.addView(ob, ui.lp(16f))
        root.addView(out, ui.lp(12f))

        setContentView(ScrollView(this).apply { setBackgroundColor(ui.bg); addView(root) })

        intent.data?.let { load(it) }
        @Suppress("DEPRECATION")
        (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { load(it) }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun load(uri: Uri) {
        runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { cur ->
                val i = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && cur.moveToFirst()) name = cur.getString(i)
            }
            val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            var t = String(bytes, Charsets.UTF_8)
            if (t.count { it == '\uFFFD' } > 20) t = String(bytes, charset("GB18030"))
            setText(t, name)
        }.onFailure { toast("读取失败：${it.message}") }
    }

    private fun setText(t: String, label: String) {
        text = t; name = if (label.endsWith(".txt")) label else "$label.txt"
        dir = AiClient.docDirection(prefs, t)
        info.setTextColor(if (dir == null) ui.error else ui.onSurface)
        info.text = "$label · ${t.length} 字\n" + (dir?.split("-")?.joinToString(" → ") { Lang.nameOf(it) }
            ?: "只支持中文、英文、日文原文")
    }

    private val flusher = object : Runnable {
        override fun run() {
            if (dirty) { dirty = false; synchronized(sb) { output.text = sb.toString() } }
            if (running) handler.postDelayed(this, 250)
        }
    }

    private fun start() {
        val d = dir ?: return toast("请先选择中文 / 英文 / 日文文本")
        if (running) return
        running = true; result = null
        synchronized(sb) { sb.setLength(0) }
        output.setTextColor(ui.onSurface); output.text = "正在翻译…"
        progress.visibility = View.VISIBLE; goBtn.alpha = 0.5f
        handler.post(flusher)
        Thread {
            val r = runCatching {
                AiClient.translateDoc(text, d) { piece -> synchronized(sb) { sb.append(piece) }; dirty = true }
            }
            runOnUiThread {
                running = false; progress.visibility = View.GONE; goBtn.alpha = 1f
                r.fold({ result = it; output.text = it; toast("翻译完成") },
                    { output.setTextColor(ui.error); output.text = "翻译失败：${it.message}" })
            }
        }.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            PICK -> load(uri)
            SAVE -> runCatching {
                contentResolver.openOutputStream(uri)!!.use { it.write(result!!.toByteArray()) }
                toast("已保存")
            }.onFailure { toast("保存失败：${it.message}") }
        }
    }

    companion object { private const val PICK = 1; private const val SAVE = 2 }
}
