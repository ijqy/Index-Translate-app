package com.example.aitranslate

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import org.json.JSONObject
import java.io.File

/** 语音翻译：S2ST 按住说话→译成语音（克隆音色）；S2TT 上传音频→识别+译文+字幕 */
class VoiceActivity : Activity() {
    private lateinit var ui: Ui
    private val handler = Handler(Looper.getMainLooper())
    private var model = "2b"

    // S2ST
    private var stSrc = "中文"; private var stTgt = "英语"
    @Volatile private var recording = false
    private var recThread: Thread? = null
    private val pcm = java.io.ByteArrayOutputStream()
    private var recStart = 0L
    private lateinit var micBtn: TextView
    private lateinit var stStatus: TextView
    private lateinit var stResult: LinearLayout
    private lateinit var stRecognized: TextView
    private lateinit var stTranslated: TextView
    private var player: MediaPlayer? = null
    private var lastWav: File? = null

    // S2TT
    private var ttSrc = "中文"; private var ttTgt = "英语"
    private var ttUri: Uri? = null; private var ttName = "audio"
    private lateinit var ttPicked: TextView
    private lateinit var ttStatus: TextView
    private lateinit var ttResult: LinearLayout
    private lateinit var ttRecognized: TextView
    private lateinit var ttTranslated: TextView
    private lateinit var ttSegs: TextView
    private var srt: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Ui.themeFor(this))
        super.onCreate(savedInstanceState)
        ui = Ui(this); ui.setupScreen(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20f), ui.dp(28f), ui.dp(20f), ui.dp(28f))
        }
        root.addView(ui.text("语音翻译", 28f, bold = true))
        root.addView(ui.text("Index-Echo 端到端语音模型", 13f, ui.onSurfaceVar), ui.lp(4f))

        val stPane = buildS2st(); val ttPane = buildS2tt()
        root.addView(ui.chips(listOf("语音译语音", "语音译文字"), "语音译语音") {
            stPane.visibility = if (it == "语音译语音") View.VISIBLE else View.GONE
            ttPane.visibility = if (it == "语音译文字") View.VISIBLE else View.GONE
        }, ui.lp(18f))
        root.addView(ui.text("模型", 13f, ui.onSurfaceVar), ui.lp(16f))
        root.addView(ui.chips(listOf("2B 快", "9B 准"), "2B 快") { model = if (it.startsWith("9")) "9b" else "2b" }, ui.lp(8f))
        root.addView(stPane, ui.lp(16f)); root.addView(ttPane, ui.lp(16f))
        ttPane.visibility = View.GONE
        setContentView(ScrollView(this).apply { setBackgroundColor(ui.bg); addView(root) })

        // 从别的 App 分享音频进来 → 直接到“语音译文字”
        @Suppress("DEPRECATION")
        val shared = if (intent.action == Intent.ACTION_SEND) intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) else null
        if (shared != null) {
            stPane.visibility = View.GONE; ttPane.visibility = View.VISIBLE
            ((root.getChildAt(2) as HorizontalScrollView).getChildAt(0) as LinearLayout).getChildAt(1).performClick()
            pickAudio(shared)
        }
    }

    private fun label(s: String) = ui.text(s, 13f, ui.onSurfaceVar)

    // ================= S2ST =================
    @SuppressLint("ClickableViewAccessibility")
    private fun buildS2st(): LinearLayout {
        val c = ui.card()
        c.addView(label("我说的是"))
        c.addView(ui.chips(listOf("中文", "英语", "日语"), stSrc) { stSrc = it }, ui.lp(8f))
        c.addView(label("译成"), ui.lp(14f))
        c.addView(ui.chips(listOf("英语", "中文", "西班牙语", "日语", "韩语"), stTgt) { stTgt = it }, ui.lp(8f))

        micBtn = TextView(this).apply {
            text = "按住说话"; textSize = 16f; gravity = Gravity.CENTER
            setTextColor(ui.onPrimary)
            background = ui.round(ui.primary, ui.dp(60f).toFloat())
            elevation = ui.dp(4f).toFloat()
        }
        c.addView(micBtn, LinearLayout.LayoutParams(ui.dp(120f), ui.dp(120f)).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = ui.dp(24f)
        })
        micBtn.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> startRec()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopRec()
            }
            true
        }
        stStatus = ui.text("最长 10 秒，松开后自动翻译", 13f, ui.onSurfaceVar).apply { gravity = Gravity.CENTER }
        c.addView(stStatus, ui.lp(14f))

        stResult = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        stResult.addView(label("识别原文"), ui.lp(18f))
        stRecognized = ui.text("", 15f).apply { setTextIsSelectable(true) }
        stResult.addView(stRecognized, ui.lp(4f))
        stResult.addView(label("译文"), ui.lp(12f))
        stTranslated = ui.text("", 17f, bold = true).apply { setTextIsSelectable(true) }
        stResult.addView(stTranslated, ui.lp(4f))
        val row = LinearLayout(this)
        row.addView(ui.pill("▶ 播放译音", true) { play() })
        row.addView(ui.pill("复制译文", false) { copy(stTranslated.text.toString()) })
        stResult.addView(row, ui.lp(16f))
        c.addView(stResult)
        return c
    }

    @SuppressLint("MissingPermission")
    private fun startRec() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7); return
        }
        if (stSrc == stTgt) { stStatus.setTextColor(ui.error); stStatus.text = "说的语言和目标语言相同，请换一个"; return }
        if (recording) return
        player?.release(); player = null
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, RATE))
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); toast("麦克风打开失败"); return }
        pcm.reset(); recording = true; recStart = System.currentTimeMillis()
        rec.startRecording()
        recThread = Thread {
            val buf = ByteArray(4096)
            while (recording) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) synchronized(pcm) { pcm.write(buf, 0, n) }
                if (System.currentTimeMillis() - recStart >= 10_000) { runOnUiThread { stopRec() }; break }
            }
            runCatching { rec.stop() }; rec.release()
        }.apply { start() }
        micBtn.text = "松开翻译"; micBtn.animate().scaleX(1.12f).scaleY(1.12f).setDuration(150).start()
        stStatus.setTextColor(ui.primary)
        tickRec()
    }

    private fun tickRec() {
        if (!recording) return
        val s = (System.currentTimeMillis() - recStart) / 100 / 10f
        stStatus.text = "录音中 ${"%.1f".format(s)} s"
        handler.postDelayed({ tickRec() }, 100)
    }

    private fun stopRec() {
        if (!recording) return
        recording = false
        recThread?.join(500)
        micBtn.text = "按住说话"; micBtn.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
        if (System.currentTimeMillis() - recStart < 600) {
            stStatus.setTextColor(ui.error); stStatus.text = "说话时间太短"; return
        }
        val bytes = synchronized(pcm) { wav(pcm.toByteArray()) }
        stStatus.setTextColor(ui.onSurfaceVar); stStatus.text = "翻译中…（约 10~20 秒）"
        val fields = mapOf("lang" to Lang.code[stTgt]!!, "src" to Lang.code[stSrc]!!, "model" to model)
        Thread {
            val res = runCatching {
                EchoClient.run("s2st", bytes, "voice.wav", "audio/wav", fields) { el ->
                    runOnUiThread { stStatus.text = "翻译中… ${el}s" }
                }
            }
            runOnUiThread { res.fold({ showS2st(it) }, { stStatus.setTextColor(ui.error); stStatus.text = "翻译失败：${it.message}" }) }
        }.start()
    }

    /** 16kHz 单声道 16bit PCM → WAV */
    private fun wav(data: ByteArray): ByteArray {
        val b = java.nio.ByteBuffer.allocate(44 + data.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + data.size).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2)
            .putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(data.size).put(data)
        return b.array()
    }

    private fun showS2st(d: JSONObject) {
        stStatus.setTextColor(ui.onSurfaceVar)
        stStatus.text = "完成" + (d.optDouble("dur", 0.0).takeIf { it > 0 }?.let { " · 译音 ${it}s" } ?: "")
        stRecognized.text = d.optString("zh"); stTranslated.text = d.optString("text")
        stResult.visibility = View.VISIBLE
        val b64 = d.optString("audio_b64")
        if (b64.isNotEmpty()) {
            val f = File(cacheDir, "out.wav")
            f.writeBytes(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
            lastWav = f; play()
        }
    }

    private fun play() {
        val f = lastWav ?: return
        player?.release()
        player = MediaPlayer().apply { setDataSource(f.absolutePath); prepare(); start() }
    }

    // ================= S2TT =================
    private fun buildS2tt(): LinearLayout {
        val c = ui.card()
        c.addView(label("音频语言"))
        c.addView(ui.chips(listOf("中文", "英语"), ttSrc) { ttSrc = it }, ui.lp(8f))
        c.addView(label("译成"), ui.lp(14f))
        c.addView(ui.chips(listOf("英语", "中文", "日语", "西班牙语", "韩语", "阿拉伯语", "葡萄牙语"), ttTgt) { ttTgt = it }, ui.lp(8f))
        ttPicked = ui.text("还没选择音频（建议 60 秒以内）", 14f, ui.onSurfaceVar)
        c.addView(ttPicked, ui.lp(18f))
        val row = LinearLayout(this)
        row.addView(ui.pill("选择音频", false) {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("audio/*"), PICK_AUDIO)
        })
        row.addView(ui.pill("开始翻译", true) { runS2tt() })
        c.addView(row, ui.lp(12f))
        ttStatus = ui.text("", 13f, ui.onSurfaceVar)
        c.addView(ttStatus, ui.lp(10f))

        ttResult = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        ttResult.addView(label("识别原文"), ui.lp(12f))
        ttRecognized = ui.text("", 15f).apply { setTextIsSelectable(true) }
        ttResult.addView(ttRecognized, ui.lp(4f))
        ttResult.addView(label("译文"), ui.lp(12f))
        ttTranslated = ui.text("", 17f, bold = true).apply { setTextIsSelectable(true) }
        ttResult.addView(ttTranslated, ui.lp(4f))
        ttResult.addView(label("时间轴"), ui.lp(12f))
        ttSegs = ui.text("", 13f, ui.onSurfaceVar).apply {
            setTextIsSelectable(true)
            background = ui.round(ui.surfaceVar, ui.dp(14f).toFloat())
            setPadding(ui.dp(12f), ui.dp(10f), ui.dp(12f), ui.dp(10f))
        }
        ttResult.addView(ttSegs, ui.lp(6f))
        val r2 = LinearLayout(this)
        r2.addView(ui.pill("复制译文", false) { copy(ttTranslated.text.toString()) })
        r2.addView(ui.pill("保存 SRT", true) {
            if (srt != null) startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("application/x-subrip")
                .putExtra(Intent.EXTRA_TITLE, ttName.substringBeforeLast('.') + ".srt"), SAVE_SRT)
        })
        ttResult.addView(r2, ui.lp(16f))
        c.addView(ttResult)
        return c
    }

    private fun pickAudio(uri: Uri) {
        ttUri = uri; ttName = "audio"
        var size = 0L
        runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { cur ->
                if (cur.moveToFirst()) {
                    cur.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { ttName = cur.getString(it) }
                    cur.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = cur.getLong(it) }
                }
            }
        }
        ttPicked.setTextColor(ui.onSurface)
        ttPicked.text = ttName + if (size > 0) " · ${"%.1f".format(size / 1048576f)} MB" else ""
    }

    private fun runS2tt() {
        val uri = ttUri ?: return toast("先选择一个音频文件")
        ttStatus.setTextColor(ui.onSurfaceVar); ttStatus.text = "上传中…"
        val mime = contentResolver.getType(uri) ?: "audio/mpeg"
        val fields = mapOf("lang" to Lang.code[ttTgt]!!, "src" to Lang.code[ttSrc]!!, "model" to model, "with_ts" to "1")
        Thread {
            val res = runCatching {
                val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                EchoClient.run("s2tt", bytes, ttName, mime, fields) { el ->
                    runOnUiThread { ttStatus.text = "识别翻译中… ${el}s" }
                }
            }
            runOnUiThread {
                res.fold({ d ->
                    ttStatus.text = "完成" + (d.optDouble("dur", 0.0).takeIf { it > 0 }?.let { " · 音频 ${it}s" } ?: "")
                    ttRecognized.text = d.optString("zh"); ttTranslated.text = d.optString("text")
                    srt = d.optString("srt").ifBlank { null }
                    val segs = d.optJSONArray("segs")
                    val sb = StringBuilder()
                    if (segs != null) for (i in 0 until segs.length()) {
                        val s = segs.getJSONObject(i)
                        sb.append("${fmt(s.optDouble("st"))} – ${fmt(s.optDouble("et"))}\n")
                            .append(s.optString("zh")).append('\n').append(s.optString("tgt")).append("\n\n")
                    }
                    ttSegs.text = sb.toString().trim().ifEmpty { "（无时间轴）" }
                    ttResult.visibility = View.VISIBLE
                }, { ttStatus.setTextColor(ui.error); ttStatus.text = "翻译失败：${it.message}" })
            }
        }.start()
    }

    private fun fmt(sec: Double): String {
        val t = sec.toInt(); return "%02d:%02d.%d".format(t / 60, t % 60, ((sec - t) * 10).toInt())
    }

    private fun copy(s: String) {
        if (s.isBlank()) return
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("t", s)); toast("已复制")
    }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            PICK_AUDIO -> pickAudio(uri)
            SAVE_SRT -> runCatching {
                contentResolver.openOutputStream(uri)!!.use { it.write(srt!!.toByteArray()) }; toast("已保存")
            }.onFailure { toast("保存失败：${it.message}") }
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (res.firstOrNull() == PackageManager.PERMISSION_GRANTED) toast("已授权，按住按钮开始说话")
    }

    override fun onDestroy() {
        super.onDestroy(); recording = false; player?.release()
    }

    companion object { private const val PICK_AUDIO = 1; private const val SAVE_SRT = 2; private const val RATE = 16000 }
}
