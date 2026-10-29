package com.example.aitranslate

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.*

/** 底部弹出卡片：长按选中文字 / 分享文字 / 分享图片 */
class TranslateActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var prefs: Prefs
    private lateinit var card: LinearLayout
    private lateinit var scrim: View
    private lateinit var resultView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var actions: LinearLayout
    private lateinit var modeBtn: TextView
    private lateinit var srcBtn: TextView
    private lateinit var tgtBtn: TextView
    private lateinit var sylRow: LinearLayout
    private lateinit var sylLabel: TextView
    private var translated: String? = null
    private var closing = false
    private var gen = 0
    private var source = ""
    private var mode = "标准"
    private var syllables = 10
    private lateinit var insRow: LinearLayout
    private lateinit var insText: TextView
    private var imageUri: Uri? = null
    private var preview: ImageView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this); prefs = Prefs(this)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        val isSend = intent.action == Intent.ACTION_SEND
        @Suppress("DEPRECATION")
        val stream: Uri? = if (isSend) intent.getParcelableExtra(Intent.EXTRA_STREAM) else null
        val shot = intent.getStringExtra(EXTRA_SHOT)
        val imageUri = if (shot != null) Uri.fromFile(java.io.File(shot))
            else if (intent.type?.startsWith("image/") == true) stream else null
        this.imageUri = imageUri
        source = (if (isSend) intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
            else intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT))?.toString().orEmpty()

        // 分享的是 txt 文件 → 交给长文档页
        if (isSend && imageUri == null && stream != null && source.isBlank()) {
            startActivity(Intent(this, DocActivity::class.java).setData(stream)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            finish(); return
        }
        val readOnly = isSend || intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        syllables = AiClient.defaultSyllables(source)
        if (prefs.alwaysInstruct && prefs.instruction.isNotBlank()) mode = "按要求"
        if (source.length > 4000 && AiClient.docDirection(prefs, source) != null) mode = "长文档"

        val root = FrameLayout(this)
        scrim = View(this).apply {
            setBackgroundColor(Color.argb(110, 0, 0, 0)); alpha = 0f
            setOnClickListener { close() }
        }
        root.addView(scrim, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ui.round(ui.surface, ui.dp(26f).toFloat(), topOnly = true)
            elevation = ui.dp(12f).toFloat()
            setPadding(ui.dp(18f), ui.dp(8f), ui.dp(18f), ui.dp(18f))
            isClickable = true
        }
        root.addView(card, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        card.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(ui.dp(18f), ui.dp(8f), ui.dp(18f), ui.dp(18f) + insets.systemWindowInsetBottom)
            insets
        }

        // 小白条 + 标题行：都可以按住往下拖，松手超过一段就收起，否则弹回
        val dragArea = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        dragArea.addView(View(this).apply {
            background = ui.round(Color.argb(110, Color.red(ui.onSurfaceVar),
                Color.green(ui.onSurfaceVar), Color.blue(ui.onSurfaceVar)), ui.dp(2f).toFloat())
        }, LinearLayout.LayoutParams(ui.dp(36f), ui.dp(4f)).apply {
            gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = ui.dp(12f)
        })
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, ui.dp(6f).toFloat())
            }
            clipToOutline = true
        }, LinearLayout.LayoutParams(ui.dp(24f), ui.dp(24f)))
        header.addView(ui.text("翻译", 17f, bold = true).apply { setPadding(ui.dp(10f), 0, 0, 0) },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        modeBtn = ui.text("", 13f, ui.onSurfaceVar).apply {
            background = ui.ripple(ui.surfaceVar, ui.dp(14f).toFloat())
            setPadding(ui.dp(12f), ui.dp(6f), ui.dp(10f), ui.dp(6f))
        }
        if (imageUri == null) header.addView(modeBtn)
        header.addView(FrameLayout(this).apply {
            background = ui.ripple(Color.TRANSPARENT, ui.dp(18f).toFloat())
            addView(ImageView(this@TranslateActivity).apply { setImageResource(R.drawable.ic_t_close); imageTintList = ColorStateList.valueOf(ui.onSurface) },
                FrameLayout.LayoutParams(ui.dp(18f), ui.dp(18f), Gravity.CENTER))
            setOnClickListener { close() }
        }, LinearLayout.LayoutParams(ui.dp(36f), ui.dp(36f)).apply { marginStart = ui.dp(6f) })
        dragArea.addView(header)
        dragArea.setOnTouchListener(sheetDragger())
        card.addView(dragArea)

        // 语种胶囊：[原文 ▾] ⇄ [译文 ▾]
        val langPill = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = ui.round(ui.surfaceVar, ui.dp(20f).toFloat())
            setPadding(ui.dp(4f), 0, ui.dp(4f), 0)
        }
        fun langBtn() = ui.text("", 14f).apply {
            gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            background = ui.ripple(Color.TRANSPARENT, ui.dp(18f).toFloat())
            setPadding(ui.dp(8f), 0, ui.dp(8f), 0)
        }
        srcBtn = langBtn(); tgtBtn = langBtn()
        langPill.addView(srcBtn, LinearLayout.LayoutParams(0, ui.dp(40f), 1f))
        langPill.addView(FrameLayout(this).apply {
            background = ui.ripple(Color.TRANSPARENT, ui.dp(16f).toFloat())
            addView(ImageView(this@TranslateActivity).apply { setImageResource(R.drawable.ic_t_swap); imageTintList = ColorStateList.valueOf(ui.onSurfaceVar) },
                FrameLayout.LayoutParams(ui.dp(18f), ui.dp(18f), Gravity.CENTER))
            setOnClickListener { swapLangs() }
        }, LinearLayout.LayoutParams(ui.dp(40f), ui.dp(40f)))
        langPill.addView(tgtBtn, LinearLayout.LayoutParams(0, ui.dp(40f), 1f))
        srcBtn.setOnClickListener {
            if (mode == "长文档") return@setOnClickListener
            ui.pickLang("原文语言", true, prefs.sourceLang) { prefs.sourceLang = it; relaunch() }
        }
        tgtBtn.setOnClickListener {
            if (mode == "长文档") return@setOnClickListener
            ui.pickLang("翻译成", false, prefs.targetLang) { prefs.targetLang = it; relaunch() }
        }
        card.addView(langPill, LinearLayout.LayoutParams(ui.dp(260f), WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = ui.dp(12f)
        })

        // 模式的附加设置（按要求 / 控音节）
        if (imageUri == null) {
            modeBtn.setOnClickListener { v ->
                val modes = mutableListOf("标准", "按要求", "控音节")
                if (AiClient.docDirection(prefs, source) != null) modes.add(2, "长文档")
                ui.popup(v, modes, mode) { picked ->
                    mode = picked; updateMode()
                    if (mode == "按要求" && prefs.instruction.isBlank()) ui.editInstruction(prefs) { _ -> updateMode(); translate() }
                    else translate()
                }
            }
            insRow = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = ui.round(ui.tonal, ui.dp(14f).toFloat())
                setPadding(ui.dp(14f), ui.dp(10f), ui.dp(10f), ui.dp(10f))
            }
            insText = ui.text("", 13f, ui.onTonal).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
            insRow.addView(insText, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            insRow.addView(ui.text("编辑", 13f, ui.primary, bold = true).apply { setPadding(ui.dp(10f), 0, ui.dp(4f), 0) })
            insRow.isClickable = true
            insRow.setOnClickListener { ui.editInstruction(prefs) { _ -> updateMode(); translate() } }
            card.addView(insRow, ui.lp(10f))
            sylRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            sylRow.addView(ui.text("目标音节数", 13f, ui.onSurfaceVar), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            fun step(label: String, d: Int) = ui.pill(label, false, weight = false) {
                syllables = (syllables + d).coerceIn(1, 200); sylLabel.text = "$syllables"; translate()
            }.apply { layoutParams = LinearLayout.LayoutParams(ui.dp(44f), ui.dp(36f)); setPadding(0, 0, 0, 0) }
            sylRow.addView(step("−", -1))
            sylLabel = ui.text("$syllables", 16f, bold = true).apply { gravity = Gravity.CENTER }
            sylRow.addView(sylLabel, LinearLayout.LayoutParams(ui.dp(44f), WRAP_CONTENT))
            sylRow.addView(step("+", 1))
            card.addView(sylRow, ui.lp(10f))
        } else { sylRow = LinearLayout(this); insRow = LinearLayout(this); insText = TextView(this) }

        // 原文 / 译文 一张卡片
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ui.round(ui.surfaceVar, ui.dp(18f).toFloat())
            setPadding(ui.dp(16f), ui.dp(12f), ui.dp(12f), ui.dp(14f))
        }
        fun iconBtn(res: Int, desc: String, act: () -> Unit) = FrameLayout(this).apply {
            background = ui.ripple(Color.TRANSPARENT, ui.dp(15f).toFloat())
            addView(ImageView(this@TranslateActivity).apply { setImageResource(res); imageTintList = ColorStateList.valueOf(ui.onSurfaceVar) },
                FrameLayout.LayoutParams(ui.dp(17f), ui.dp(17f), Gravity.CENTER))
            contentDescription = desc
            setOnClickListener { act() }
        }
        fun labelRow(label: String): LinearLayout = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.text(label, 12.5f, ui.onSurfaceVar))
        }
        val srcRow = labelRow(if (imageUri != null) "图片" else "原文")
        if (imageUri == null) srcRow.addView(iconBtn(R.drawable.ic_t_speak, "朗读原文") { speak(source, srcLangName()) }, LinearLayout.LayoutParams(ui.dp(30f), ui.dp(30f)))
        box.addView(srcRow, LinearLayout.LayoutParams(MATCH_PARENT, ui.dp(30f)))

        val preview = ImageView(this).apply {
            this@TranslateActivity.preview = this
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = ui.round(ui.surface, ui.dp(12f).toFloat())
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) =
                    o.setRoundRect(0, 0, v.width, v.height, ui.dp(12f).toFloat())
            }
            clipToOutline = true
        }
        if (imageUri != null) box.addView(preview, ui.lp(4f, ui.dp(140f)))
        else box.addView(ui.text(source.trim(), 15f).apply {
            maxLines = 4; ellipsize = TextUtils.TruncateAt.END; setTextIsSelectable(true); selectable += this
            setPadding(0, ui.dp(2f), ui.dp(4f), 0)
        }, ui.lp(2f))
        box.addView(View(this).apply { setBackgroundColor(Color.argb(if (ui.dark) 40 else 24, Color.red(ui.onSurface), Color.green(ui.onSurface), Color.blue(ui.onSurface))) },
            ui.lp(12f, 1).apply { bottomMargin = ui.dp(6f) })

        val tRow = labelRow("译文")
        actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; alpha = 0.4f }
        actions.addView(iconBtn(R.drawable.ic_t_speak, "朗读译文") { translated?.let { speak(it, AiClient.targetFor(prefs, source)) } }, LinearLayout.LayoutParams(ui.dp(30f), ui.dp(30f)))
        actions.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        if (!readOnly) actions.addView(ui.text("替换", 13f, ui.primary, bold = true).apply {
            gravity = Gravity.CENTER; setPadding(ui.dp(10f), 0, ui.dp(10f), 0)
            background = ui.ripple(Color.TRANSPARENT, ui.dp(14f).toFloat())
            setOnClickListener { translated?.let { setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, it)); close() } }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, ui.dp(30f)))
        actions.addView(iconBtn(R.drawable.ic_t_copy, "复制") {
            translated?.let {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("translation", it))
                Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(ui.dp(30f), ui.dp(30f)))
        tRow.addView(actions, LinearLayout.LayoutParams(0, ui.dp(30f), 1f))
        box.addView(tRow, LinearLayout.LayoutParams(MATCH_PARENT, ui.dp(30f)))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(ui.primary)
        }
        box.addView(progress, ui.lp(4f, ui.dp(3f)))
        resultView = ui.text("", 17f).apply {
            setLineSpacing(0f, 1.25f); setTextIsSelectable(true); selectable += this
            highlightColor = Color.argb(80, Color.red(ui.primary), Color.green(ui.primary), Color.blue(ui.primary))
        }
        val maxH = (resources.displayMetrics.heightPixels * 0.4).toInt()
        val scroll = object : ScrollView(this) {
            override fun onMeasure(w: Int, h: Int) =
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
        }.apply { addView(resultView); isVerticalScrollBarEnabled = false }
        box.addView(scroll, ui.lp(4f))
        card.addView(box, ui.lp(14f))

        setContentView(root)
        root.post { animateIn() }

        if (imageUri != null) {
            updateMode()
            translateImage(imageUri, preview)
        } else {
            updateMode()
            if (source.isBlank()) showDone("没有选中文字", false) else translate()
        }
    }

    private fun updateMode() {
        sylRow.visibility = if (mode == "控音节") View.VISIBLE else View.GONE
        insRow.visibility = if (mode == "按要求") View.VISIBLE else View.GONE
        insText.text = prefs.instruction.ifBlank { "还没设置要求，点这里填写" }
        modeBtn.text = "$mode ▾"
        if (imageUri == null && mode == "长文档") {
            val d = AiClient.docDirection(prefs, source)!!.split("-")
            srcBtn.text = Lang.nameOf(d[0]); tgtBtn.text = Lang.nameOf(d[1]); return
        }
        srcBtn.text = (if (imageUri != null) (AiClient.sourceOf(prefs) ?: Lang.AUTO) else srcLangName()) + " ▾"
        tgtBtn.text = (if (imageUri != null) prefs.targetLang else AiClient.targetFor(prefs, source)) + " ▾"
    }
    private fun srcLangName() = AiClient.sourceOf(prefs) ?: Lang.detect(source) ?: Lang.AUTO
    private fun relaunch() {
        updateMode()
        val u = imageUri
        if (u != null) translateImage(u, preview!!) else translate()
    }
    private fun swapLangs() {
        if (mode == "长文档") return
        val from = if (imageUri != null) AiClient.sourceOf(prefs) else srcLangName().takeIf { it != Lang.AUTO }
        val to = if (imageUri != null) prefs.targetLang else AiClient.targetFor(prefs, source)
        if (from == null) { Toast.makeText(this, "原文语言是自动检测，先选一个具体语种", Toast.LENGTH_SHORT).show(); return }
        prefs.sourceLang = to; prefs.targetLang = from
        // 已经有译文：把译文当成新的原文来回译
        translated?.takeIf { imageUri == null }?.let { source = it }
        relaunch()
    }

    // ===== 朗读 =====
    private var tts: android.speech.tts.TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeak: Pair<String, String>? = null
    private fun localeOf(lang: String): java.util.Locale = when (lang) {
        "中文" -> java.util.Locale.SIMPLIFIED_CHINESE; "粤语" -> java.util.Locale("yue", "HK")
        "英语" -> java.util.Locale.US; "日语" -> java.util.Locale.JAPANESE; "韩语" -> java.util.Locale.KOREAN
        "法语" -> java.util.Locale.FRENCH; "德语" -> java.util.Locale.GERMAN; "意大利语" -> java.util.Locale.ITALIAN
        "西班牙语" -> java.util.Locale("es"); "俄语" -> java.util.Locale("ru"); "葡萄牙语" -> java.util.Locale("pt")
        "泰语" -> java.util.Locale("th"); "越南语" -> java.util.Locale("vi"); "阿拉伯语" -> java.util.Locale("ar")
        else -> java.util.Locale.getDefault()
    }
    private fun speak(text: String, lang: String) {
        if (text.isBlank()) return
        val t = tts
        if (t == null) {
            pendingSpeak = text to lang
            tts = android.speech.tts.TextToSpeech(this) { st ->
                ttsReady = st == android.speech.tts.TextToSpeech.SUCCESS
                if (!ttsReady) runOnUiThread { Toast.makeText(this, "系统没有可用的朗读引擎", Toast.LENGTH_SHORT).show() }
                pendingSpeak?.let { (a, b) -> pendingSpeak = null; if (ttsReady) speak(a, b) }
            }
            return
        }
        if (!ttsReady) { pendingSpeak = text to lang; return }
        t.language = localeOf(lang)
        t.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "index")
    }
    override fun onDestroy() { tts?.shutdown(); super.onDestroy() }

    // ===== 下拉收起 =====
    private fun sheetDragger(): View.OnTouchListener {
        var startY = 0f; var lastY = 0f; var lastT = 0L; var vel = 0f
        return View.OnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startY = e.rawY; lastY = e.rawY; lastT = e.eventTime; vel = 0f; card.animate().cancel() }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - startY
                    card.translationY = if (dy > 0) dy else dy / 4f        // 往上拉只给一点阻尼
                    scrim.alpha = (1f - card.translationY.coerceAtLeast(0f) / card.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                    val dt = (e.eventTime - lastT).coerceAtLeast(1); vel = (e.rawY - lastY) / dt; lastY = e.rawY; lastT = e.eventTime
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (card.translationY > card.height * 0.28f || vel > 1.2f) close()
                    else {
                        card.animate().translationY(0f).setDuration(260).setInterpolator(android.view.animation.OvershootInterpolator(0.8f)).start()
                        scrim.animate().alpha(1f).setDuration(200).start()
                    }
                }
            }
            true
        }
    }

    private fun startLoading(hint: String): Int {
        val g = ++gen
        translated = null
        progress.visibility = View.VISIBLE; progress.alpha = 1f
        resultView.setTextColor(ui.onSurfaceVar); resultView.text = hint
        actions.alpha = 0.4f
        return g
    }

    private fun streamer(g: Int): (String) -> Unit {
        val sb = StringBuilder()
        return { piece ->
            runOnUiThread {
                if (g == gen && !isFinishing) {
                    sb.append(piece)
                    resultView.setTextColor(ui.onSurface); resultView.text = sb
                }
            }
        }
    }

    private fun translate() {
        val g = startLoading("正在翻译…")
        val m = mode; val n = syllables; val ins = prefs.instruction
        Thread {
            val r = runCatching {
                when (m) {
                    "长文档" -> AiClient.translateDoc(source, AiClient.docDirection(prefs, source)!!, streamer(g))
                    "控音节" -> AiClient.translateSyllable(prefs, source, n, streamer(g))
                    "按要求" -> if (ins.isBlank()) AiClient.translateText(prefs, source, streamer(g))
                        else AiClient.translateInstruct(prefs, source, ins, streamer(g))
                    else -> AiClient.translateText(prefs, source, streamer(g))
                }
            }
            runOnUiThread {
                if (g == gen && !isFinishing) r.fold({ showDone(it, true) },
                    { showDone("翻译失败：${it.message}", false) })
            }
        }.start()
    }

    private fun translateImage(uri: Uri, preview: ImageView) {
        val g = startLoading("正在识别图片…")
        Thread {
            val r = runCatching {
                val bmp: Bitmap = ImageUtil.load(this, uri)
                runOnUiThread { preview.setImageBitmap(bmp) }
                AiClient.translateImage(prefs, ImageUtil.toJpeg(bmp),
                    if (prefs.alwaysInstruct) prefs.instruction else "", streamer(g))
            }
            runOnUiThread {
                if (g == gen && !isFinishing) r.fold({ showDone(it, true) },
                    { showDone("翻译失败：${it.message}", false) })
            }
        }.start()
    }

    private fun showDone(text: String, ok: Boolean) {
        if (ok) translated = text
        progress.animate().alpha(0f).setDuration(150).withEndAction { progress.visibility = View.GONE }
        resultView.text = text
        resultView.setTextColor(if (ok) ui.onSurface else ui.error)
        actions.animate().alpha(if (ok) 1f else 0.6f).setDuration(220).start()
    }

    private fun animateIn() {
        card.translationY = card.height.toFloat()
        card.animate().translationY(0f).setDuration(320).setInterpolator(DecelerateInterpolator(2.2f)).start()
        scrim.animate().alpha(1f).setDuration(260).start()
    }

    private fun close() {
        if (closing) return
        closing = true; gen++
        scrim.animate().alpha(0f).setDuration(200).start()
        card.animate().translationY(card.height.toFloat()).setDuration(240)
            .setInterpolator(DecelerateInterpolator(1.2f)).withEndAction {
                finish(); overridePendingTransition(0, 0)
            }.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = close()

    /** 选中文字后，点别的地方（不在这段文字上）就取消选中 */
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            selectable.forEach { tv ->
                if (tv.hasSelection()) {
                    val r = android.graphics.Rect(); tv.getGlobalVisibleRect(r)
                    if (!r.contains(e.rawX.toInt(), e.rawY.toInt())) {
                        (tv.text as? android.text.Spannable)?.let { android.text.Selection.removeSelection(it) }
                        tv.clearFocus()
                    }
                }
            }
        }
        return super.dispatchTouchEvent(e)
    }
    private val selectable = ArrayList<TextView>()

    companion object { const val EXTRA_SHOT = "shot_path" }
}
