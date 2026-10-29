package com.example.aitranslate

import android.annotation.SuppressLint
import android.app.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.sqrt

/**
 * 直播/视频实时字幕：录下手机正在播放的声音（AudioPlaybackCapture），
 * 按停顿切成 1.5–6 秒的小段，逐段送 Index-Echo 语音翻译，字幕条按顺序显示。
 */
class CaptionService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var rec: AudioRecord? = null
    @Volatile private var recording = false
    @Volatile private var paused = false
    private var showOrig = false
    private var gen = 0

    private var bar: View? = null
    private var barLp: WindowManager.LayoutParams? = null
    private var wm: WindowManager? = null
    private lateinit var text: TextView
    private lateinit var status: TextView
    private lateinit var dot: View
    private lateinit var srcBtn: TextView
    private lateinit var pauseIcon: ImageView
    private lateinit var ui: Ui
    private lateinit var prefs: Prefs
    private fun dp(v: Float) = ui.dp(v)

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (projection != null) return START_NOT_STICKY
        startFg()
        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION") val data: Intent? = intent?.getParcelableExtra("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = runCatching { mpm.getMediaProjection(code, data) }.getOrNull()
        if (projection == null) { toast("没拿到授权"); stopSelf(); return START_NOT_STICKY }
        projection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopSelf() } }
        }, main)
        ui = Ui(ShotA11y.instance ?: this); prefs = Prefs(this)
        // 云端识别有频率限制：草稿只在 SenseVoice 或 ≥3 个 Groq Key 时开
        draftsOn = prefs.capDrafts && !echoMode() && (useSf() || prefs.groqKeys.size >= 3)
        draftEvery = if (useSf()) 900 else 1200
        if (!addBar()) { toast("需要打开无障碍「Index 框选翻译」或悬浮窗权限才能显示字幕"); stopSelf(); return START_NOT_STICKY }
        if (!startRec()) { stopSelf(); return START_NOT_STICKY }
        running = true
        return START_NOT_STICKY
    }

    private fun startFg() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("caption", "实时字幕", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 2, Intent(this, CaptionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, "caption").setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Index 实时字幕运行中").setContentText("点这里关闭")
            .setContentIntent(stop).setOngoing(true).build()
        startForeground(8, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    // ================= 录音 + 分句（参考 simul-subtitle Segmenter） =================
    @SuppressLint("MissingPermission")
    private fun startRec(): Boolean {
        val cfg = AudioPlaybackCaptureConfiguration.Builder(projection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
        val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
        val r = runCatching {
            AudioRecord.Builder().setAudioFormat(fmt).setAudioPlaybackCaptureConfig(cfg)
                .setBufferSizeInBytes(maxOf(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), RATE * 2)).build()
        }.getOrNull()
        if (r == null || r.state != AudioRecord.STATE_INITIALIZED) { r?.release(); toast("收不到系统声音（可能需要重新授予录音权限）"); return false }
        if (prefs.capSave) transcript = Transcript(this, srcName(), prefs.targetLang)
        rec = r; recording = true; r.startRecording()
        setStatus("听着呢", GREEN)
        Thread { loop(r) }.apply { name = "caption-rec"; start() }
        return true
    }

    private fun concat(list: List<ByteArray>, n: Int = list.size): ByteArray {
        val out = ByteArrayOutputStream(); for (k in 0 until n) out.write(list[k]); return out.toByteArray()
    }

    /**
     * 50ms 一帧，按音量判断说没说话（自适应底噪）：
     * - 说话中每 [draftEvery] 出一次草稿（当前句累积音频，灰色）
     * - 静音 0.4s 定稿；已说满 3s 的长句静音 0.2s 就定稿；单句最长 7s，在句中最长停顿处切
     * - 不足 0.4s 的短语音先等最多 1.5s，接着开口就并成一句
     */
    private fun loop(r: AudioRecord) {
        val frame = ByteArray(RATE / 20 * 2)          // 50ms
        val frames = ArrayList<ByteArray>()
        val loudFlags = ArrayList<Boolean>()
        val levels = ArrayList<Double>()
        val pre = ArrayDeque<ByteArray>()            // 开口前 200ms，免得吞掉第一个音
        var floor = 150.0
        var speechMs = 0; var silence = 0; var quietLong = 0; var lastDraftMs = 0
        var shortPcm: ByteArray? = null; var shortQuiet = 0
        fun reset() { frames.clear(); loudFlags.clear(); levels.clear(); speechMs = 0; silence = 0; lastDraftMs = 0 }
        fun final(n: Int) {
            val head = concat(frames, n)
            val pcm = shortPcm?.let { it + head } ?: head
            shortPcm = null
            repeat(n) { frames.removeAt(0); loudFlags.removeAt(0); levels.removeAt(0) }
            speechMs = loudFlags.count { it } * 50; silence = 0; lastDraftMs = frames.size * 50
            submitFinal(pcm)
        }
        while (recording) {
            var got = 0
            while (got < frame.size && recording) { val n = r.read(frame, got, frame.size - got); if (n <= 0) break; got += n }
            if (got < frame.size) continue
            if (paused || overloaded) { reset(); shortPcm = null; continue }
            val lv = rms(frame)
            floor = if (lv < floor) floor * 0.85 + lv * 0.15 else floor * 0.997 + lv * 0.003
            val loud = lv > maxOf(200.0, floor * 2.2)
            if (loud) { silence = 0; quietLong = 0 } else { silence += 50; quietLong += 50 }
            val f = frame.copyOf()
            if (frames.isEmpty() && !loud) {
                pre.addLast(f); if (pre.size > 4) pre.removeFirst()
                if (shortPcm != null) { shortQuiet += 50; if (shortQuiet >= 1500) { val sp = shortPcm!!; shortPcm = null; submitFinal(sp) } }
                if (quietLong == 3000) main.post { if (!paused) setStatus("没听到声音", Color.parseColor("#FFB020")) }
                continue
            }
            if (frames.isEmpty()) { while (pre.isNotEmpty()) { frames.add(pre.removeFirst()); loudFlags.add(false); levels.add(0.0) }; shortQuiet = 0 }
            frames.add(f); loudFlags.add(loud); levels.add(lv)
            if (loud) speechMs += 50
            val ms = frames.size * 50
            // 草稿
            if (draftsOn && loud && speechMs >= 600 && ms - lastDraftMs >= draftEvery) {
                lastDraftMs = ms
                val pcm = concat(frames); submitDraft(shortPcm?.let { it + pcm } ?: pcm)
            }
            val endSil = if (speechMs >= 3000) 200 else 400
            when {
                silence >= endSil -> {
                    if (speechMs < 400 && shortPcm == null) {   // 太短：先存着，等等看还有没有下文
                        shortPcm = concat(frames); shortQuiet = 0; reset()
                    } else final(frames.size)
                }
                ms >= MAX_SEG -> {
                    // 超长：在后 5 秒里找最长的一段停顿，从中间切；没有停顿就切在最安静的那帧
                    val from = maxOf(20, frames.size - 100)
                    var bestLen = 0; var bestMid = -1; var run = 0
                    for (k in from until frames.size) {
                        if (!loudFlags[k]) { run++; if (run > bestLen) { bestLen = run; bestMid = k - run / 2 } } else run = 0
                    }
                    val cut = if (bestLen >= 2) bestMid + 1 else {
                        var c = frames.size; var min = Double.MAX_VALUE
                        for (k in frames.size - 30 until frames.size) if (levels[k] < min) { min = levels[k]; c = k + 1 }
                        c
                    }
                    final(cut.coerceIn(1, frames.size))
                }
            }
        }
        runCatching { r.stop() }; r.release()
    }

    private fun rms(b: ByteArray): Double {
        val sb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var s = 0.0; val n = sb.remaining()
        for (i in 0 until n) { val v = sb.get(i).toDouble(); s += v * v }
        return sqrt(s / n)
    }

    private fun srcName(): String = (getSharedPreferences("caption", MODE_PRIVATE).getString("src", null)
        ?: if (prefs.targetLang == "日语") "中文" else "日语"
        ).let { if (it == "自动检测") "自动" else it }

    // ================= 流水线（参考 simul-subtitle Pipeline） =================
    /** 一句字幕。draft = 草稿（灰色，会被定稿替换） */
    class Line(var o: String = "", var t: String = "", var draft: Boolean = false, val at: Long = System.currentTimeMillis())

    private val lines = ArrayList<Line>()          // 已定稿，按顺序
    private var draftLine: Line? = null            // 正在说的这句
    @Volatile private var utter = 0                // 每次定稿 +1，旧草稿作废
    private val finalExec = java.util.concurrent.ScheduledThreadPoolExecutor(1)   // 定稿识别（按顺序）+ Jev 断句
    private val draftExec = Executors.newSingleThreadExecutor()
    private val mtExec = Executors.newFixedThreadPool(3)
    private val latestDraft = java.util.concurrent.atomic.AtomicReference<ByteArray?>(null)
    private val queuedMs = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile private var overloaded = false
    private val prevFinals = ArrayDeque<String>()  // 最近定稿原文（结合上文）
    private val mtCache = object : LinkedHashMap<String, String>(32, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, String>?) = size > 40
    }
    // Jev 断句：没说完的半句先攒着，等下一句接上
    private var carryText = ""; private var carryMs = 0L; private var carryAt = 0L
    private var carryFlush: java.util.concurrent.ScheduledFuture<*>? = null
    private var transcript: Transcript? = null
    private var draftsOn = false
    private var draftEvery = 1000

    private fun echoMode() = prefs.asrModel == "echo" || (!useSf() && prefs.groqKeys.isEmpty())
    private fun pcmMs(pcm: ByteArray) = pcm.size / 32L

    /** 识别一段音频，返回原文；Echo 模式返回 (原文, 译文) */
    private fun asr(pcm: ByteArray): String {
        val lang = when (srcName()) { "日语" -> "ja"; "英语" -> "en"; "中文" -> "zh"; "韩语" -> "ko"; else -> null }
        val ctx = synchronized(prevFinals) { prevFinals.joinToString(" ").takeLast(180) }
        val w = wav(normalize(pad(pcm)))
        return if (useSf()) SfAsr.transcribe(prefs.sfKeys, w)
        else GroqAsr.transcribe(prefs.groqKeys, if (prefs.asrModel == "sensevoice") "whisper-large-v3" else prefs.asrModel, w, lang, ctx)
    }

    /** 首尾补 0.3s 静音：语音从第一个采样点就开始时模型容易吞句首 */
    private fun pad(pcm: ByteArray): ByteArray { val z = ByteArray(RATE * 2 * 3 / 10); return z + pcm + z }

    private fun submitDraft(pcm: ByteArray) {
        if (echoMode()) return
        val u = utter
        if (latestDraft.getAndSet(pcm) != null) return   // 已有一份排着：替换成最新的就行（旧草稿折叠）
        draftExec.execute {
            val p = latestDraft.getAndSet(null) ?: return@execute
            if (u != utter) return@execute
            val h = runCatching { asr(p) }.getOrNull()?.trim().orEmpty()
            if (h.isBlank() || u != utter) return@execute
            val text = joinCarry(h)
            main.post {
                if (u != utter || !running) return@post
                val d = draftLine ?: Line(draft = true).also { draftLine = it }
                if (d.o != text) { d.o = text; d.t = "" ; render() }
            }
            // 草稿也翻一下（同文复用缓存）
            val tr = runCatching { translate(text, contextSnapshot(), null) { } }.getOrNull()
            main.post { if (u == utter && running) draftLine?.let { if (it.o == text && tr != null) { it.t = tr; render() } } }
        }
    }

    private fun joinCarry(h: String) = if (carryText.isEmpty()) h else if (srcName() == "英语") "$carryText $h" else carryText + h

    private fun submitFinal(pcm: ByteArray) {
        if (pcmMs(pcm) < 250) return
        utter++; latestDraft.set(null)
        val g = gen
        queuedMs.addAndGet(pcmMs(pcm))
        checkOverload()
        if (echoMode()) { submitEcho(pcm, g); return }
        finalExec.execute {
            try {
                if (g != gen) return@execute
                main.post { setStatus("识别中", ui.primary) }
                val heard = runCatching { asr(pcm) }
                heard.exceptionOrNull()?.let { e -> main.post { fail("识别失败", e) } }
                val h = heard.getOrNull()?.trim().orEmpty()
                carryFlush?.cancel(false); carryFlush = null
                if (h.isBlank()) {
                    if (carryText.isNotEmpty()) finishSentence(g) else main.post { if (draftLine != null) { draftLine = null; render() } }
                    return@execute
                }
                val text = joinCarry(h)
                val totalMs = carryMs + pcmMs(pcm)
                // Jev 智能断句：没说完就先不定稿，等下一句接上（最长 10 秒，没下文 2.5 秒后自动定稿）
                if (prefs.jevSeg && totalMs < 10_000) {
                    val pUnf = Jev.unfinished(text, srcName())
                    if (pUnf != null && pUnf >= 0.5) {
                        if (carryText.isEmpty()) carryAt = System.currentTimeMillis()
                        carryText = text; carryMs = totalMs
                        main.post { if (g == gen && running) { val d = draftLine ?: Line(draft = true).also { draftLine = it }; d.o = text; d.t = ""; render() } }
                        carryFlush = finalExec.schedule({ if (carryText.isNotEmpty()) finishSentence(g) }, 2500, java.util.concurrent.TimeUnit.MILLISECONDS)
                        return@execute
                    }
                }
                if (carryText.isEmpty()) carryAt = System.currentTimeMillis()
                carryText = text; finishSentence(g)
            } finally {
                queuedMs.addAndGet(-pcmMs(pcm)); checkOverload()
            }
        }
    }

    /** 当前攒下的整句定稿 → 上屏 → 翻译 → （Jev 检查）→ 写入字幕文本。在 finalExec 线程调用 */
    private fun finishSentence(g: Int) {
        val text = carryText; val at = carryAt
        carryText = ""; carryMs = 0; carryFlush?.cancel(false); carryFlush = null
        if (text.isBlank()) return
        utter++
        val prev = contextSnapshot()
        synchronized(prevFinals) { prevFinals.addLast(text); while (prevFinals.size > 5) prevFinals.removeFirst() }
        val line = Line(o = text, at = at)
        main.post {
            if (g != gen || !running) return@post
            // 草稿上已经有这句的译文就先顶着用
            draftLine?.let { if (it.o == text) line.t = it.t }
            draftLine = null
            lines.add(line); while (lines.size > 3) lines.removeAt(0)
            render()
        }
        mtExec.execute {
            if (g != gen) return@execute
            val src = srcName(); val tgt = prefs.targetLang
            var last = 0L
            val res = runCatching {
                if (Lang.detect(text) == tgt) text
                else translate(text, prev, null) { partial ->
                    val now = System.currentTimeMillis()
                    if (now - last > 120) { last = now; main.post { if (g == gen) { line.t = partial; render() } } }
                }
            }
            val t0 = res.getOrNull()?.trim()
            var t = t0
            main.post {
                if (g != gen || !running) return@post
                if (t0 != null) line.t = t0
                res.exceptionOrNull()?.let { fail("翻译失败", it) } ?: idleStatus()
                render()
            }
            // Jev 检查译文：明显译错（否定加反、意思翻反、比喻直译）就带说明重译一次
            if (t0 != null && prefs.jevCheck && t0 != text) {
                val c = Jev.mistranslation(text, t0, src, tgt)
                val note = c?.takeIf { it.second >= 0.6 }?.let { Jev.fixNote(it.first, src, tgt) }
                if (note != null) {
                    runCatching { translate(text, prev, note) { } }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }?.let { t2 ->
                        t = t2; main.post { if (g == gen) { line.t = t2; render() } }
                    }
                }
            }
            transcript?.add(at, text, t ?: "")
        }
    }

    private fun contextSnapshot(): List<String> {
        val n = prefs.capContext
        if (n <= 0) return emptyList()
        return synchronized(prevFinals) { prevFinals.toList().takeLast(n) }
    }

    /** 流式翻译，同一提示词复用上次译文。onPartial 收到累积文本 */
    private fun translate(text: String, prev: List<String>, note: String?, onPartial: (String) -> Unit): String {
        val src = srcName(); val tgt = prefs.targetLang
        val sys = CaptionScene.system(prefs, src, tgt)
        val user = CaptionScene.user(text, prev, note)
        val key = "$sys\u0000$user"
        synchronized(mtCache) { mtCache[key] }?.let { return it }
        val sb = StringBuilder()
        val out = AiClient.stream(fastModel(), user, 0.0, if (tgt == "中文") 160 else 256, system = sys) { d -> sb.append(d); onPartial(sb.toString()) }
        synchronized(mtCache) { mtCache[key] = out }
        return out
    }

    private fun checkOverload() {
        val q = queuedMs.get()
        if (!overloaded && q > 30_000) { overloaded = true; main.post { setStatus("跟不上了，先停一下收音", ui.error) } }
        else if (overloaded && q < 6_000) { overloaded = false; main.post { idleStatus() } }
    }

    @Volatile private var lastErr: String? = null
    private fun fail(what: String, e: Throwable) {
        val m = e.message ?: e.javaClass.simpleName
        lastErr = "$what：$m"
        main.post { setStatus("$what：$m（点这里看详情）", ui.error); showTools(true) }
    }

    private fun idleStatus() = setStatus(if (paused) "已暂停" else "听着呢", if (paused) ui.onSurfaceVar else GREEN)

    /** 没有识别 Key：只能用 B 站 Echo（只听得懂中文 / 英文），直接出译文 */
    private fun submitEcho(pcm: ByteArray, g: Int) {
        val name = srcName(); val echo = prefs.asrModel == "echo"
        val tgt = Lang.code[prefs.targetLang]
        val src = when (name) { "中文" -> "zh"; "英语" -> "en"; "自动" -> "auto"; else -> null }
        val fail: String? = when {
            src == null -> if (echo) "Index Echo 只听中文/英语，${name}请换 Whisper/SenseVoice" else "${name}要先在 Index 设置里填硅基流动或 Groq Key"
            tgt == null -> "语音暂不支持译成${prefs.targetLang}"
            tgt == src -> "说话语言和目标相同，点左边语言切换"
            else -> null
        }
        if (fail != null) { queuedMs.addAndGet(-pcmMs(pcm)); main.post { setStatus(fail, ui.error) }; return }
        main.post { setStatus("翻译中", ui.primary) }
        val model = if (prefs.model.contains("2B")) "2b" else "9b"
        val line = Line(o = "", t = "…")
        main.post { if (g == gen) { lines.add(line); while (lines.size > 3) lines.removeAt(0) } }
        mtExec.execute {
            val res = runCatching {
                EchoClient.run("s2tt", wav(pcm), "seg.wav", "audio/wav", mapOf("lang" to tgt!!, "src" to src!!, "model" to model), pollMs = 500) {}
            }
            queuedMs.addAndGet(-pcmMs(pcm)); checkOverload()
            main.post {
                if (g != gen || !running) return@post
                val d = res.getOrNull()
                val o = d?.optString("zh")?.trim().orEmpty(); val t = d?.optString("text")?.trim().orEmpty()
                if (t.isBlank() || t == "…") lines.remove(line) else { line.o = o; line.t = t; transcript?.add(line.at, o, t) }
                res.exceptionOrNull()?.let { fail("一段失败", it) } ?: idleStatus()
                render()
            }
        }
    }

    /** 选了 SenseVoice 且填了硅基流动 Key；或者没填 Groq 只填了硅基流动 */
    private fun useSf(): Boolean = prefs.sfKeys.isNotEmpty() && (prefs.asrModel == "sensevoice" || prefs.groqKeys.isEmpty())

    /** 音量拉到统一响度，小声的直播也能识别清楚 */
    private fun normalize(pcm: ByteArray): ByteArray {
        val sb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var peak = 1
        for (i in 0 until sb.remaining()) peak = maxOf(peak, kotlin.math.abs(sb.get(i).toInt()))
        val gain = (22000.0 / peak).coerceIn(1.0, 8.0)
        if (gain < 1.2) return pcm
        val out = ByteBuffer.allocate(pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sb.remaining()) out.putShort((sb.get(i) * gain).toInt().coerceIn(-32767, 32767).toShort())
        return out.array()
    }

    /** 字幕要快：35B 换成 9B */
    private fun fastModel() = if (prefs.model.contains("2B")) "Index-Translate-2B" else "Index-Translate-9B"

    private fun render() {
        if (!::text.isInitialized) return
        val show = ArrayList<Line>(lines.takeLast(2))
        draftLine?.let { show.add(it); while (show.size > 2) show.removeAt(0) }
        if (show.isEmpty()) return
        val sb = android.text.SpannableStringBuilder()
        show.forEachIndexed { i, l ->
            if (i > 0) sb.append('\n')
            val start = sb.length
            sb.append(l.t.ifBlank { if (l.o.isBlank()) "…" else "${l.o} …" })
            val dim = l.draft || (i == 0 && show.size > 1)
            if (dim) sb.setSpan(android.text.style.ForegroundColorSpan(Color.argb(if (l.draft) 130 else 150, 255, 255, 255)), start, sb.length, 0)
            if (showOrig && l.o.isNotBlank() && l.t.isNotBlank()) {
                sb.append('\n'); val s2 = sb.length; sb.append(l.o)
                sb.setSpan(android.text.style.ForegroundColorSpan(Color.argb(140, 255, 255, 255)), s2, sb.length, 0)
                sb.setSpan(android.text.style.RelativeSizeSpan(0.78f), s2, sb.length, 0)
            }
        }
        text.text = sb
    }

    private fun wav(data: ByteArray): ByteArray {
        val b = ByteBuffer.allocate(44 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + data.size).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(data.size).put(data)
        return b.array()
    }

    // ================= 字幕条 =================
    private fun addBar(): Boolean {
        val a11y = ShotA11y.instance
        val host: Context; val type: Int
        if (a11y != null) { host = a11y; type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY }
        else if (Settings.canDrawOverlays(this)) { host = this; type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY }
        else return false
        val w = host.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val card = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(14f).toFloat() }.also { cardBg = it }
            setPadding(dp(12f), dp(2f), dp(4f), dp(8f))
        }
        barCard = card
        val head = LinearLayout(host).apply { gravity = Gravity.CENTER_VERTICAL }
        dot = View(host).apply { background = ui.round(Color.parseColor("#FFB020"), dp(4f).toFloat()) }
        head.addView(dot, LinearLayout.LayoutParams(dp(7f), dp(7f)).apply { rightMargin = dp(6f) })
        srcBtn = TextView(host).apply {
            textSize = 11f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = ui.ripple(Color.argb(60, 255, 255, 255), dp(11f).toFloat()); setPadding(dp(8f), 0, dp(8f), 0)
            setOnClickListener {
                val list = listOf("日语", "英语", "中文", "韩语", "自动")
                val n = list[(list.indexOf(srcName()) + 1) % list.size]
                getSharedPreferences("caption", MODE_PRIVATE).edit().putString("src", n).apply()
                updateSrc(); setStatus("听着呢", Color.parseColor("#2BC48A"))
            }
        }
        head.addView(srcBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(22f)).apply { rightMargin = dp(6f) })
        status = TextView(host).apply {
            textSize = 11f; setTextColor(Color.argb(170, 255, 255, 255)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setOnClickListener { lastErr?.let { e -> Toast.makeText(host, e, Toast.LENGTH_LONG).show() } }
        }
        head.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val orig = TextView(host).apply {
            text = "原文"; textSize = 11f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = ui.ripple(Color.argb(60, 255, 255, 255), dp(11f).toFloat()); setPadding(dp(8f), 0, dp(8f), 0)
            setOnClickListener { showOrig = !showOrig; alpha = if (showOrig) 1f else 0.6f; render() }
            alpha = 0.6f
        }
        head.addView(orig, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(22f)).apply { rightMargin = dp(2f) })
        fun btn(res: Int, desc: String, act: () -> Unit) = ImageView(host).apply {
            setImageResource(res); imageTintList = ColorStateList.valueOf(Color.argb(200, 255, 255, 255))
            setPadding(dp(7f), dp(7f), dp(7f), dp(7f)); contentDescription = desc
            background = ui.ripple(Color.TRANSPARENT, dp(15f).toFloat())
            setOnClickListener { act() }
            setOnLongClickListener { Toast.makeText(host, desc, Toast.LENGTH_SHORT).show(); true }
        }.also { head.addView(it, LinearLayout.LayoutParams(dp(30f), dp(30f))) }
        btn(R.drawable.ic_t_copy, "复制字幕") {
            val t = lines.joinToString("\n") { if (it.o.isNotBlank()) "${it.t}\n${it.o}" else it.t }
            if (t.isNotBlank()) { (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("字幕", t)); toast("已复制") }
        }
        pauseIcon = btn(R.drawable.ic_t_pause, "暂停/继续") {
            paused = !paused
            pauseIcon.setImageResource(if (paused) R.drawable.ic_t_play else R.drawable.ic_t_pause)
            setStatus(if (paused) "已暂停" else "听着呢", if (paused) ui.onSurfaceVar else Color.parseColor("#2BC48A"))
        }
        btn(R.drawable.ic_t_close, "关闭实时字幕") { stopSelf() }
        card.addView(head)
        headRow = head
        card.addView(buildTools(host), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4f) })
        card.setOnTouchListener(dragger())
        text = TextView(host).apply {
            textSize = cp.getInt("font", 16).toFloat(); setTextColor(Color.WHITE); setLineSpacing(0f, 1.2f)
            setShadowLayer(dp(2f).toFloat(), 0f, 0f, Color.BLACK)
            text = "播放视频/直播，有人说话就会出字幕。轻点字幕调字号、背景和锁定"
            setPadding(0, dp(6f), dp(8f), 0)
        }
        card.addView(text)
        applyBg(); showTools(true)
        val m = ShotFlow.real(host)
        val bw = minOf(m.widthPixels - dp(16f), dp(520f))
        val lp = WindowManager.LayoutParams(bw, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            x = (m.widthPixels - bw) / 2
            y = maxOf(0, m.heightPixels - dp(150f) - (m.heightPixels / 10))
            savedPos(m, bw)?.let { (sx, sy) -> x = sx; y = sy }
        }
        barHost = host; lastW = m.widthPixels
        val ok = runCatching { w.addView(card, lp); bar = card; barLp = lp; wm = w; updateSrc() }.isSuccess
        if (ok) { ui0.removeCallbacks(rot); ui0.postDelayed(rot, 600) }
        return ok
    }

    private var barHost: Context? = null
    private var lastW = 0
    private val ui0 = android.os.Handler(android.os.Looper.getMainLooper())
    /** 横竖屏切换：重新算字幕条宽度和位置（服务收不到可靠的旋转回调，就每 0.6 秒看一眼屏宽） */
    private val rot = object : Runnable {
        override fun run() {
            val h = barHost ?: return; val v = bar ?: return; val lp = barLp ?: return
            val m = ShotFlow.real(h)
            if (m.widthPixels != lastW) {
                lastW = m.widthPixels
                val bw = minOf(m.widthPixels - dp(16f), dp(520f))
                lp.width = bw
                lp.x = (m.widthPixels - bw) / 2
                lp.y = maxOf(0, m.heightPixels - dp(150f) - (m.heightPixels / 10))
                savedPos(m, bw)?.let { (sx, sy) -> lp.x = sx; lp.y = sy }
                runCatching { wm?.updateViewLayout(v, lp) }
            }
            ui0.postDelayed(this, 600)
        }
    }

    override fun onConfigurationChanged(c: android.content.res.Configuration) {
        super.onConfigurationChanged(c)
        ui0.removeCallbacks(rot); ui0.postDelayed(rot, 250)
    }

    private fun updateSrc() { if (::srcBtn.isInitialized) srcBtn.text = "${srcName()}→${prefs.targetLang}" }

    private val cp by lazy { getSharedPreferences("caption", MODE_PRIVATE) }
    private var cardBg: GradientDrawable? = null
    private var barCard: View? = null
    private var headRow: View? = null
    private var toolsRow: View? = null
    private var toolsOn = false
    private val hideTools = Runnable { showTools(false) }

    /** 字幕背景透明度（0 = 全透明，只剩文字描边） */
    private fun applyBg() {
        val a = cp.getInt("bgAlpha", 85).coerceIn(0, 100)
        cardBg?.setColor(Color.argb(a * 255 / 100, 14, 14, 18))
    }

    /** 点一下字幕：显示/收起顶部按钮和设置行；6 秒没动就自动收起 */
    private fun showTools(on: Boolean) {
        toolsOn = on
        headRow?.visibility = if (on) View.VISIBLE else View.GONE
        toolsRow?.visibility = if (on) View.VISIBLE else View.GONE
        ui0.removeCallbacks(hideTools)
        if (on) ui0.postDelayed(hideTools, 6000)
    }
    private fun poke() { if (toolsOn) { ui0.removeCallbacks(hideTools); ui0.postDelayed(hideTools, 6000) } }

    private fun buildTools(host: Context): View {
        val row = LinearLayout(host).apply { gravity = Gravity.CENTER_VERTICAL }
        fun chip(t: String, act: (TextView) -> Unit) = TextView(host).apply {
            text = t; textSize = 12f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = ui.ripple(Color.argb(60, 255, 255, 255), dp(12f).toFloat()); setPadding(dp(9f), 0, dp(9f), 0)
            setOnClickListener { act(this); poke() }
        }.also { row.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(26f)).apply { rightMargin = dp(5f) }) }
        fun label(t: String) = TextView(host).apply { text = t; textSize = 11f; setTextColor(Color.argb(170, 255, 255, 255)); setPadding(0, 0, dp(4f), 0) }
            .also { row.addView(it) }
        label("字号")
        val fontV = TextView(host)
        fun setFont(d: Int) {
            val f = (cp.getInt("font", 16) + d).coerceIn(12, 32); cp.edit().putInt("font", f).apply()
            text.textSize = f.toFloat(); fontV.text = "$f"
        }
        chip("A−") { setFont(-2) }
        fontV.apply { textSize = 12f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; text = "${cp.getInt("font", 16)}" }
        row.addView(fontV, LinearLayout.LayoutParams(dp(24f), ViewGroup.LayoutParams.WRAP_CONTENT))
        chip("A＋") { setFont(2) }
        row.addView(View(host), LinearLayout.LayoutParams(dp(6f), 1))
        label("背景")
        val alphaV = TextView(host)
        fun setAlpha(d: Int) {
            val a = (cp.getInt("bgAlpha", 85) + d).coerceIn(0, 100); cp.edit().putInt("bgAlpha", a).apply()
            applyBg(); alphaV.text = "$a%"
        }
        chip("−") { setAlpha(-15) }
        alphaV.apply { textSize = 12f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; text = "${cp.getInt("bgAlpha", 85)}%" }
        row.addView(alphaV, LinearLayout.LayoutParams(dp(38f), ViewGroup.LayoutParams.WRAP_CONTENT))
        chip("＋") { setAlpha(15) }
        row.addView(View(host), LinearLayout.LayoutParams(0, 1, 1f))
        fun lockText() = if (cp.getBoolean("locked", false)) "🔒 已锁定" else "🔓 可拖动"
        chip(lockText()) { v ->
            cp.edit().putBoolean("locked", !cp.getBoolean("locked", false)).apply(); v.text = lockText()
        }
        toolsRow = row
        return android.widget.HorizontalScrollView(host).apply { isHorizontalScrollBarEnabled = false; addView(row) }.also { toolsRow = it }
    }

    /** 记住的位置（按屏幕比例存，横竖屏各自适配） */
    private fun savedPos(m: android.util.DisplayMetrics, bw: Int): Pair<Int, Int>? {
        val fx = cp.getFloat("posX", -1f); val fy = cp.getFloat("posY", -1f)
        if (fx < 0 || fy < 0) return null
        return (fx * m.widthPixels).toInt().coerceIn(0, maxOf(0, m.widthPixels - bw)) to (fy * m.heightPixels).toInt().coerceIn(0, maxOf(0, m.heightPixels - dp(60f)))
    }

    /** 字幕条：轻点 = 显示/收起设置；没锁定时按住拖动，松手记住位置 */
    private fun dragger() = View.OnTouchListener { _, e ->
        val lp = barLp ?: return@OnTouchListener false; val v = bar ?: return@OnTouchListener false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dx = e.rawX - lp.x; dy = e.rawY - lp.y; downX = e.rawX; downY = e.rawY; moved = false; true }
            MotionEvent.ACTION_MOVE -> {
                if (!moved && Math.hypot((e.rawX - downX).toDouble(), (e.rawY - downY).toDouble()) > dp(8f)) moved = true
                if (moved && !cp.getBoolean("locked", false)) {
                    val m = ShotFlow.real(v.context)
                    lp.x = (e.rawX - dx).toInt().coerceIn(0, maxOf(0, m.widthPixels - v.width))
                    lp.y = (e.rawY - dy).toInt().coerceIn(0, maxOf(0, m.heightPixels - dp(60f)))
                    runCatching { wm?.updateViewLayout(v, lp) }
                    poke()
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                if (!moved) showTools(!toolsOn)
                else if (!cp.getBoolean("locked", false)) {
                    val m = ShotFlow.real(v.context)
                    cp.edit().putFloat("posX", lp.x.toFloat() / m.widthPixels).putFloat("posY", lp.y.toFloat() / m.heightPixels).apply()
                }
                true
            }
            else -> true
        }
    }
    private var downX = 0f; private var downY = 0f; private var moved = false
    private var dx = 0f; private var dy = 0f

    private fun setStatus(s: String, c: Int) {
        if (!::status.isInitialized) return
        status.text = "实时字幕 · $s"; dot.background = ui.round(c, dp(4f).toFloat())
    }

    private fun toast(s: String) = main.post { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    override fun onDestroy() {
        ui0.removeCallbacks(rot); ui0.removeCallbacks(hideTools)
        running = false; recording = false; gen++
        bar?.let { b -> runCatching { wm?.removeView(b) } }; bar = null
        runCatching { projection?.stop() }; projection = null
        finalExec.shutdownNow(); draftExec.shutdownNow(); mtExec.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "stop"
        private const val RATE = 16000
        private const val MAX_SEG = 7000
        private val GREEN = Color.parseColor("#2BC48A")
        @Volatile var running = false
    }
}
