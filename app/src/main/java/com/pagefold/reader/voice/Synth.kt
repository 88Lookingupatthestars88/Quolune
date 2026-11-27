package com.pagefold.reader.voice

import android.content.Context
import com.pagefold.reader.book.Cue
import com.pagefold.reader.book.Reading
import com.pagefold.reader.field.EngineCfg
import com.pagefold.reader.field.Engines
import com.pagefold.reader.field.Settings
import com.pagefold.reader.field.VoiceSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** 一段合成结果：磁盘上的文件 + 容器格式。 */
data class Clip(val file: File, val ext: String)

/** 念过的一段，留个记号。只有音频文件是没法给人看的 —— 一堆 md5 文件名，谁也认不出哪句是哪句。 */
data class LedgerEntry(
    val key: String,
    val text: String,
    val voice: String,
    val at: Long,
    val ext: String,
    /** 这一条由试听生成，不属于书中正文。 */
    val trial: Boolean = false,
)

/** 合成调度。按音色自己属于哪副嗓子分流，几副嗓子可以同时开着。 */
class Synth(private val ctx: Context, private val sys: SysVoice) {

    private var mimo = MiMo()
    private var cfg: Settings? = null

    /** 每副音色自己那份调法。由 [com.pagefold.reader.App] 接进来。 */
    @Volatile var tunes: com.pagefold.reader.field.VoiceTune? = null

    /** 念过的段落放 files/clips。正式区：账本指着它，界面上看得见、删得掉。 */
    val cacheDir: File get() = File(ctx.filesDir, "clips").apply { mkdirs() }

    /**
     * 合成出来还没念的放应用缓存：不列账本，用户清缓存时能一并清掉。
     * 念完那一刻才收进 [cacheDir]。
     */
    val pendingDir: File get() = File(ctx.cacheDir, "clips").apply { mkdirs() }

    /* 账本文件必须声明在 init 上面。 */
    private val ledgerFile = File(ctx.filesDir, "clips.json")
    private val ledgerBak = File(ctx.filesDir, "clips.json.bak")
    private val gson = com.google.gson.Gson()
    private var ledgerMem: MutableList<LedgerEntry>? = null
    /** 账本读不出来的那一次。读不出来和「本来就没有」必须分开：前者一旦被当成空账本写回去，所有条目的原文就永久没了 —— 音频还在，字没了。 */
    private var ledgerBroken = false

    /** 账本只有一份。两三路合成同时记账、界面同时在读，读改写都要过这把锁。 */
    private val bookLock = Any()

    /** 记账不占播的那条线程。念完一句就往盘上写一遍账，写在主线程上就是每句卡一下。 */
    private val house = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO
    )

    /**
     * 合成规则的版本。
     *
     * 抬一格会清空 clips 目录与账本 —— 只有「同一个 key 会产出不一样的声音」才抬。
     * 改参数（语速、音调、模型）不用抬：那些都进 key。
     */
    private val CLIP_REV = 3

    /** 电平归一化的版本。只有把声音改成另一个电平才抬。 */
    private val LEVEL = 1

    /** 本机引擎念一句最多等这么久。它不联网，正常一句一两秒。 */
    private val SYS_WAIT = 15_000L

    /** 旧账里由扫目录补出来的那一类条目写的字。念的时候没人记下原文，认不出是哪一段。 */
    private val LEGACY_BLANK = "（早先念的一段，没留下字）"

    /** 把 rev 抬格前的音频清一次。 */
    private fun cleanOnce() = runCatching {
        val mark = File(ctx.filesDir, "clips.rev")
        val was = mark.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 0
        if (was >= CLIP_REV) return@runCatching
        cacheDir.listFiles()?.forEach { if (it.isFile) it.delete() }
        ledgerMem = mutableListOf()
        runCatching { ledgerFile.delete() }
        runCatching { ledgerBak.delete() }
        mark.writeText(CLIP_REV.toString())
    }

    /**
     * 清掉半截的本机引擎音频。引擎边合成边写，写的时候 wav 头里没有长度，
     * 正常音频都被 [Wav.fix] 补过，所以「头里没长度、文件又小」就是没写完的。只清一次。
     */
    private fun sweepHalf() = runCatching {
        val mark = File(ctx.filesDir, "clips.half")
        if (mark.exists()) return@runCatching
        listOf(cacheDir, pendingDir).forEach { dir ->
            dir.listFiles()?.forEach { f ->
                if (!f.isFile || !f.name.endsWith(".wav", true)) return@forEach
                if (f.length() >= 40_000L) return@forEach
                if (half(f)) f.delete()
            }
        }
        mark.writeText("1")
    }

    /**
     * 念过、但没记下原文的那一批，挪进应用缓存。账本里不列这些，
     * 留在 files/clips 里界面上看不见就永远删不掉。只做一次。
     */
    private fun parkLegacy(list: List<LedgerEntry>) = runCatching {
        val mark = File(ctx.filesDir, "clips.parked")
        if (mark.exists()) return@runCatching
        val dir = File(ctx.cacheDir, "legacy-clips")
        list.forEach { e ->
            val orphan = e.text.isNullOrBlank() || e.text == LEGACY_BLANK
            if (!orphan) return@forEach
            val key = e.key.orEmpty()
            if (key.isBlank() || key.contains('/') || key.contains('\\')) return@forEach
            listOf("wav", "mp3").forEach { ext ->
                val f = File(cacheDir, "$key.$ext")
                if (!f.isFile) return@forEach
                runCatching {
                    dir.mkdirs()
                    val dst = File(dir, f.name)
                    if (!f.renameTo(dst)) {
                        f.copyTo(dst, true)
                        f.delete()
                    }
                }
            }
        }
        mark.writeText("1")
    }

    /**
     * 盘上有、账上没有的音频，挪进应用缓存。账本读坏的那一次不搬。只做一次。
     */
    private fun parkOrphans(list: List<LedgerEntry>) = runCatching {
        if (ledgerBroken) return@runCatching
        val mark = File(ctx.filesDir, "clips.orphan")
        if (mark.exists()) return@runCatching
        val known = list.mapTo(HashSet()) {
            it.key.orEmpty().lowercase() + "." + it.ext.orEmpty().lowercase()
        }
        val dir = File(ctx.cacheDir, "legacy-clips")
        cacheDir.listFiles()?.forEach { f ->
            if (!f.isFile) return@forEach
            if (f.name.contains(".part")) return@forEach
            if (known.contains(f.name.lowercase())) return@forEach
            runCatching {
                dir.mkdirs()
                val dst = File(dir, f.name)
                if (!f.renameTo(dst)) {
                    f.copyTo(dst, true)
                    f.delete()
                }
            }
        }
        mark.writeText("1")
    }

    /** 这个 wav 头里 data 的长度没写：0、负数，或者比文件本身还长。 */
    private fun half(f: File): Boolean {
        val head = runCatching {
            f.inputStream().use { s -> ByteArray(minOf(64L, f.length()).toInt()).also { s.read(it) } }
        }.getOrNull() ?: return false
        if (head.size < 12) return true
        if (head[0] != 'R'.code.toByte() || head[1] != 'I'.code.toByte()) return false
        var i = 12
        var guard = 0
        while (i + 8 <= head.size && guard++ < 8) {
            val id = String(head, i, 4, Charsets.US_ASCII)
            val size = (head[i + 4].toInt() and 0xFF) or ((head[i + 5].toInt() and 0xFF) shl 8) or
                ((head[i + 6].toInt() and 0xFF) shl 16) or ((head[i + 7].toInt() and 0xFF) shl 24)
            if (id == "data") return size <= 0 || i + 8L + size > f.length()
            if (size < 0 || i + 8 + size > head.size) return false
            i += 8 + size + (size and 1)
        }
        return false
    }

    /** 清掉一小时前还没写完的临时文件。进程被杀、断电会留下这些半截货。 */
    private fun sweepParts() = runCatching {
        val cut = System.currentTimeMillis() - 3600_000L
        listOf(cacheDir, pendingDir).forEach { dir ->
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.name.contains(".part") && f.lastModified() < cut) f.delete()
            }
        }
    }

    init {
        cleanOnce()
        sweepParts()
        sweepHalf()
    }

    fun sync(s: Settings) {
        // 播放时每一句都会走到这儿。设置没换过就直接回头。
        if (cfg === s) return
        val m = Engines.first(s.engines, VoiceSource.MIMO)
        val base = m?.base?.ifBlank { null } ?: s.mimoBase
        val key = if (m?.key?.isNotBlank() == true) m.key else s.mimoKey
        val old = cfg
        if (old == null || old.mimoKey != key || old.mimoBase != base) {
            mimo = MiMo(base, key)
        }
        mimo.appCtx = ctx
        mimo.extra = emptyList()
        cfg = s
    }

    /** 这一段走哪副嗓源：先按音色的 engine 找嗓源 id，再按嗓源类型找，最后取第一路开着的。 */
    private fun route(s: Settings, v: Voice): EngineCfg? {
        s.engines.firstOrNull { it.id == v.engine }?.let { return it }
        val src = runCatching { VoiceSource.valueOf(v.engine.uppercase()) }.getOrNull()
        if (src != null) Engines.first(s.engines, src)?.let { return it }
        return Engines.open(s.engines).firstOrNull()
    }

    /** 这一句该走哪套协议。 */
    private fun protocolOf(s: Settings, v: Voice): String =
        route(s, v)?.protocol ?: when (v.engine.lowercase()) {
            "system" -> "system"
            else -> "mimo"
        }

    /**
     * 不同音色、不同段落判定必须分开，否则换了嗓音/换了调子还在放旧音频。
     *
     * 语速和音调不进指纹 —— 它们由播放器设，同一段音频能按任何速度放。
     * [bakeSpeed] / [bakePitch] 是烘进声音里的那一份（对外当朗读引擎时才有），它得进。
     */
    private fun fingerprint(
        s: Settings,
        v: Voice,
        r: Reading?,
        bakeSpeed: Int,
        bakePitch: Int,
    ): String = buildString {
        append(v.engine).append('/').append(v.kind).append('/').append(v.param)
        // 模型进指纹，换模型要另起一条缓存。
        append('/').append(route(s, v)?.modelOr().orEmpty())
        append("/b=").append(bakeSpeed).append(',').append(bakePitch)
        val vt = tunes?.of(v.id) ?: com.pagefold.reader.field.Vt()
        if (tunes?.has(v.id) == true) {
            append("/v=").append(vt.volume).append(',').append(vt.mood).append(',').append(vt.lang)
            append(',').append(vt.bright).append(',').append(vt.soft).append(',').append(vt.clear)
            append(',').append(vt.fx).append(',').append(vt.dialect).append(',').append(vt.silence)
            append(',').append(vt.temp).append(',').append(vt.boost).append(',').append(vt.norm)
        }
        if (r != null) {
            /* 语速不进指纹：它在播放器上设，同一段音频能按任何速度放。 */
            append('/').append(r.pitch).append(',').append(r.volume)
            append('/').append(r.style)
        }
    }

    fun key(
        s: Settings,
        v: Voice,
        text: String,
        r: Reading?,
        bakeSpeed: Int = 100,
        bakePitch: Int = 100,
        extra: String = "",
    ): String {
        /* rev 与 LEVEL 都进 key，抬了就换用新音频。抬 CLIP_REV 会连账本一起清掉，
         * 那是另一件事。 */
        val raw = fingerprint(s, v, r, bakeSpeed, bakePitch) + "|" + text + "|r=$CLIP_REV|l=$LEVEL|" + extra
        val md = MessageDigest.getInstance("MD5").digest(raw.toByteArray())
        return md.joinToString("") { "%02x".format(it) }
    }

    fun cached(key: String): Clip? {
        for (ext in listOf("wav", "mp3")) {
            /* 先看还没念的那一份：命中就省一次合成。 */
            for (dir in listOf(pendingDir, cacheDir)) {
                val f = File(dir, "$key.$ext")
                if (!f.exists()) continue
                if (sound(f)) return Clip(f, ext)
                /* 坏文件当场烧掉，让调用方重新合成，不留着反复播。 */
                runCatching { f.delete() }
            }
        }
        return null
    }

    /** 判断这个文件是不是一段完整、能播的音频。 */
    private fun sound(f: File): Boolean {
        if (!f.isFile) return false
        val n = f.length()
        if (n < 512) return false
        val head = runCatching { f.inputStream().use { s -> ByteArray(12).also { s.read(it) } } }
            .getOrNull() ?: return false
        if (head.size < 12) return false

        val riff = head[0] == 'R'.code.toByte() && head[1] == 'I'.code.toByte() &&
            head[2] == 'F'.code.toByte() && head[3] == 'F'.code.toByte()

        // mp3 / 其它容器：只认「不像一段人声」的明显坏法（错误页、空壳）。
        if (!riff) {
            if (head[0] == '<'.code.toByte()) return false          // 回了一段 HTML
            if (head[0] == '{'.code.toByte()) return false          // 回了一段 JSON
            return true
        }
        if (head[8] != 'W'.code.toByte() || head[9] != 'A'.code.toByte()) return false

        val b = runCatching { f.readBytes() }.getOrNull() ?: return false
        var i = 12
        var at = -1
        var len = -1
        var guard = 0
        while (i + 8 <= b.size && guard++ < 64) {
            val id = String(b, i, 4, Charsets.US_ASCII)
            val size = (b[i + 4].toInt() and 0xFF) or ((b[i + 5].toInt() and 0xFF) shl 8) or
                ((b[i + 6].toInt() and 0xFF) shl 16) or ((b[i + 7].toInt() and 0xFF) shl 24)
            /* data 的长短写在头里，边合成边回的服务写的时候还不知道有多长，填的是占位。 */
            if (id == "data") { at = i + 8; len = size; break }
            if (size < 0) return false
            i += 8 + size + (size and 1)
        }
        if (at < 0) return false
        // 没长度可用时（占位 0、负数、比文件还长），按文件余下来的算。
        val claim = at.toLong() + maxOf(0, len)
        val real = if (len <= 0 || claim > n) n - at else len.toLong()
        if (real <= 256L) return false
        // 声称的长度与实际对得上时，多出来的尾巴不能太长 —— 那是后面被塞了别的东西。
        if (len > 0 && claim <= n && n - claim > 32L * 1024) return false
        return true
    }

    /**
     * 把一段字节落成缓存文件。六个服务分支都走这里，别处不要再自己拼临时文件名。
     *
     * @param gain wav 的电平，百分数。各家回来的响度差得远，这里拉齐到同一个基准再按它增减。
     */
    private fun land(k: String, ext: String, bytes: ByteArray, gain: Int = 100): File {
        /* wav 的头补齐：边合成边回的那几家，data 长度是占位，播放器按它解析会判坏。 */
        val body = if (ext == "wav") Wav.level(Wav.fix(bytes), gain) else bytes
        if (body.size < 512) throw java.io.IOException("只回了 ${body.size} 字节，不像一段声音")
        val dst = File(pendingDir, "$k.$ext")
        val tmp = File(pendingDir, "$k." + System.nanoTime().toString(36) + ".part")
        try {
            tmp.writeBytes(body)
            if (!tmp.renameTo(dst)) {
                tmp.copyTo(dst, true)
                tmp.delete()
            }
        } catch (e: Throwable) {
            runCatching { tmp.delete() }
            throw e
        }
        if (!sound(dst)) {
            runCatching { dst.delete() }
            throw java.io.IOException("这一段没合成完整，重来")
        }
        return dst
    }

    /**
* 合成一段。先查缓存。
*
* @param r     这段怎么念（旁白/对白/耳语…）
* @param one   一次性的调法。试听传这个：听到的是手上还没存的数，不是上一次存下的。
* @param bakeSpeed 把快慢烘进声音里，给「Quolune 当手机朗读引擎」那条路用。
*/
    suspend fun make(
        s: Settings,
        v: Voice,
        text: String,
        r: Reading?,
        force: Boolean = false,
        /* 一次性的调法。试听传这个：听到的是手上还没存的数，不是上一次存下的。 */
        one: com.pagefold.reader.field.Vt? = null,
        /* 把快慢烘进声音里，给「Quolune 当手机朗读引擎」那条路用。播放时由播放器设，不烘。 */
        bakeSpeed: Int = 100,
        bakePitch: Int = 100,
    ): Clip {
        sync(s)
        val k = key(s, v, text, r, bakeSpeed, bakePitch,
            one?.let { "u=${it.volume},${it.mood},${it.temp}" }.orEmpty())
        /* force = 试听时用：绕开缓存，逼它真去问一次。 */
        if (!force) cached(k)?.let { return it }

        /* 语速不进合成：播放器放的时候设，换速度不必重念。音高改不了，只能进合成。 */
        val mine = one ?: tunes?.let { t -> if (t.has(v.id)) t.of(v.id) else null }
        val own = mine ?: com.pagefold.reader.field.Vt(volume = 100, mood = "")
        val sp = bakeSpeed.coerceIn(50, 300)
        /* 这一块的语气音高叠在嗓源自己那一格上。播放器改不了音高，只能烘进声音里。 */
        val pitchPct = (bakePitch + (r?.pitch ?: 0)).coerceIn(50, 200)
        val volPct = own.volume.coerceIn(50, 200)

        val ext: String
        val file: File
        // 按**协议**分流，不按 engine 字符串。engine 现在可能是某一副嗓源的 id。
        val proto = protocolOf(s, v)
        /* 模型不对，请求根本不发。这一条不是防呆，是防对面的沉默：小米那套口收到不认识的 model 不报错。 */
        val eng = route(s, v)
        if (proto != "system") {
            // 拦截要用**真正会发的那个名字**，不是嗓源格子上那个：
            // 设计/克隆音色各走各的模型，三者可能都不同。
            val used = if (proto == "mimo") MiMo.modelFor(v, eng) else eng?.modelOr().orEmpty()
            val role = if (proto == "mimo" && (v.kind == "design" || v.kind == "clone")) v.kind else ""
            /* 拦下来的理由原样带出去，别在这一层再压成一句话。 */
            Why.check(used, proto, role)?.let { throw IllegalStateException("不能合成：$it") }
        }
        when (proto) {
            "volc" -> {
                // 火山豆包：自己的口，自己的 resource id，自己的 speaker。
                // 音调在这一家的 post_process 里，不在 audio_params 里。
                val e = route(s, v) ?: throw java.io.IOException("这副嗓源不在了，去定音里重新挑一副")
                val bytes = Volc.mp3(
                    base = e.base ?: "",
                    key = e.key ?: "",
                    model = e.modelOr(),
                    text = text,
                    speaker = v.param.ifBlank { v.name },
                    speed = (sp / 100f).coerceIn(0.5f, 2f),
                    loudness = (volPct / 100f).coerceIn(0.5f, 2f),
                    pitch = grade(pitchPct, 12),
                    mood = moodOf(s, v, r),
                    dialect = own.dialect,
                    silenceMs = own.silence,
                )
                file = land(k, "mp3", bytes)
                ext = "mp3"
            }
            "minimax" -> {
                // MiniMax：自己的口，音频回来是十六进制。
                // voice_setting 里四格都是它真认的：speed / vol / pitch / emotion。
                val e = route(s, v) ?: throw java.io.IOException("这副嗓源不在了，去定音里重新挑一副")
                val bytes = MiniMax.mp3(
                    base = e.base ?: "",
                    key = e.key ?: "",
                    model = e.modelOr(),
                    text = text,
                    voiceId = v.param.ifBlank { v.name },
                    speed = (sp / 100f).coerceIn(0.5f, 2f),
                    style = moodOf(s, v, r),
                    pitch = grade(pitchPct, 12),
                    vol = (volPct / 100f).coerceIn(0.1f, 10f),
                    // 三根效果器：界面上是 0–200、100 不动；这家要 −100…100、0 不动。
                    bright = own.bright.coerceIn(0, 200) - 100,
                    soft = own.soft.coerceIn(0, 200) - 100,
                    clear = own.clear.coerceIn(0, 200) - 100,
                    fx = own.fx,
                    boost = own.boost,
                    norm = own.norm,
                )
                file = land(k, "mp3", bytes)
                ext = "mp3"
            }
            "bailian" -> {
                // 阿里百炼：JSON 里给一个音频地址，要再下一次。
                // 这家的 input 只有文字、音色、语种三样，快慢和音调只能交给播放器。
                val e = route(s, v) ?: throw java.io.IOException("这副嗓源不在了，去定音里重新挑一副")
                val bytes = Bailian.mp3(
                    base = e.base ?: "",
                    key = e.key ?: "",
                    model = e.modelOr(),
                    text = text,
                    voice = v.param.ifBlank { v.name },
                    speed = (sp / 100f).coerceIn(0.5f, 2f),
                    lang = own.lang,
                )
                file = land(k, "mp3", bytes)
                ext = "mp3"
            }
            "system" -> {
                /* 引擎往临时名里写，写完才改名。param 空会落回系统默认引擎，[SysVoice.file] 拦自环。 */
                val tmp = File(pendingDir, "$k." + System.nanoTime().toString(36) + ".part.wav")
                val target = v.param.ifBlank { s.sysVoice }
                sys.file(text, target, sp, pitchPct, tmp, waitMs = SYS_WAIT)
                val bytes = runCatching { tmp.readBytes() }.getOrDefault(ByteArray(0))
                runCatching { tmp.delete() }
                file = land(k, "wav", bytes, volPct)
                ext = "wav"
            }
            "deepseek" -> {
                // DeepSeek：自己的口，回来是 24 kHz 单声道 wav。没有语速这一格，快慢交给播放器。
                val e = route(s, v) ?: throw java.io.IOException("这副嗓源不在了，去定音里重新挑一副")
                val bytes = Deepseek.wav(
                    base = e.base ?: "",
                    key = e.key ?: "",
                    text = text,
                    voice = v.param.ifBlank { v.name },
                )
                file = land(k, "wav", bytes, volPct)
                ext = "wav"
            }
            "speech" -> {
                // 标准口：POST /v1/audio/speech，直接回音频。没有这一支，填 OpenAI 的地址也念不出声。
                val e = route(s, v) ?: throw java.io.IOException("这副嗓源不在了，去定音里重新挑一副")
                val model = e.modelOr()
                val silicon = com.pagefold.reader.field.Caps.isSilicon(model)
                val bytes = Speak.mp3(
                    base = e.base ?: "",
                    key = e.key ?: "",
                    model = model,
                    text = text,
                    voice = v.param.ifBlank { v.name },
                    speed = (sp / 100f).coerceIn(0.25f, 4f),
                    extra = emptyList(),
                    // 增益是硅基独有的一格，-10…10 dB。界面上是百分数，这里折算。
                    gain = if (silicon) (volPct - 100) / 5f else null,
                    // instructions 只有 gpt-4o 那几个认，别家发过去是废字段。
                    instructions = if (model.lowercase().contains("gpt-4o")) moodOf(s, v, r) else null,
                )
                file = land(k, "mp3", bytes)
                ext = "mp3"
            }
            else -> {
                // MiMo：音频在 chat/completions 的 message.audio 里
                val bytes = mimo.wav(
                    text = text,
                    voice = v,
                    instruction = null,
                    // 采样温度 0.00–1.00。超过 1 对面直接 400。
                    temperature = (own.temp.coerceIn(0, 100) / 100f),
                    cfg = eng,
                    /* 这一家没有数值的 speed / pitch 字段。 */
                    speed = sp,
                    pitch = pitchPct,
                    mood = moodOf(s, v, r),
                )
                file = land(k, "wav", bytes, volPct)
                ext = "wav"
            }
        }
        return Clip(file, ext)
    }


    /** 这一句的调门词。三层往下掉：这一副自己调的 → 这一段判定出来的 → 全局那一档。 */
    private fun moodOf(s: Settings, v: Voice, r: Reading?): String {
        val own = tunes?.let { t -> if (t.has(v.id)) t.of(v.id).mood else null }
        if (!own.isNullOrBlank()) return own
        if (r == null) {
            val t = s.style.trim()
            return if (t == "平静" || t == "无") "" else t.take(4)
        }
        return Cue.say(r.style.ifBlank { s.style })
    }

    /** 百分数的音调 → 各家那个整数刻度。MiniMax 和火山都是 −12…12，0 是原调。 */
    private fun grade(pct: Int, span: Int): Int =
        Math.round((pct.coerceIn(50, 200) - 100) / 100f * span).coerceIn(-span, span)

    // ── 账本 ────────────────────────────────────────────────

    /** 把账本里不能用的行剔掉。 */
    private fun ledgerClean(raw: List<LedgerEntry?>?): MutableList<LedgerEntry> {
        val out = ArrayList<LedgerEntry>((raw?.size ?: 0).coerceAtLeast(8))
        raw?.forEach { e ->
            if (e == null) return@forEach
            // 没有 key 的条目指不到任何文件，留着只会崩。key 里带分隔符的也不能要。
            val key = e.key.orEmpty()
            if (key.isBlank() || key.contains('/') || key.contains('\\')) return@forEach
            /* 没有字的条目不上账：认不出是哪一段，也没法让他知道自己删的是什么。 */
            if (e.text.isNullOrBlank()) return@forEach
            /* 早年扫目录补进来的条目一并清掉，不看 voice。 */
            if (e.text == LEGACY_BLANK) return@forEach
            val ext = e.ext.orEmpty().lowercase().ifBlank { "wav" }
            if (ext != "wav" && ext != "mp3") return@forEach
            out.add(
                e.copy(
                    key = key,
                    text = e.text.orEmpty(),
                    voice = e.voice.orEmpty(),
                    ext = ext,
                    at = if (e.at > 0L) e.at else 0L,
                )
            )
        }
        return out
    }

    /** 把 JSON 原文读成条目。 */
    private fun ledgerParse(raw: String): List<LedgerEntry>? {
        val arr = runCatching {
            com.google.gson.JsonParser.parseString(raw).asJsonArray
        }.getOrNull() ?: return null
        val out = ArrayList<LedgerEntry>(arr.size())
        arr.forEach { el ->
            val o = runCatching { el.asJsonObject }.getOrNull() ?: return@forEach
            fun str(k: String): String {
                val v = o.get(k) ?: return ""
                if (v.isJsonNull) return ""
                return runCatching { v.asString }.getOrDefault("")
            }
            fun num(k: String): Long {
                val v = o.get(k) ?: return 0L
                if (v.isJsonNull) return 0L
                return runCatching { v.asLong }.getOrDefault(0L)
            }
            val key = str("key")
            if (key.isBlank()) return@forEach
            out.add(
                LedgerEntry(
                    key = key,
                    text = str("text"),
                    voice = str("voice"),
                    at = num("at"),
                    ext = str("ext").ifBlank { "wav" },
                    trial = runCatching {
                        val v = o.get("trial")
                        v != null && !v.isJsonNull && v.asBoolean
                    }.getOrDefault(false),
                )
            )
        }
        return out
    }

    private fun ledgerLoad(): MutableList<LedgerEntry> {
        ledgerMem?.let { return it }
        if (!ledgerFile.exists()) {
            val empty = mutableListOf<LedgerEntry>()
            ledgerMem = empty
            return empty
        }
        val raw = runCatching { ledgerFile.readText() }.getOrNull()
        var parsed = raw?.let { ledgerParse(it) }
        /* 正文那份读不出来，就试备份。 */
        if (parsed == null) {
            parsed = runCatching { ledgerBak.readText() }.getOrNull()?.let { ledgerParse(it) }
        }
        if (parsed == null) {
            val save = File(ctx.filesDir, "clips.broken." + System.currentTimeMillis() + ".json")
            runCatching { ledgerFile.copyTo(save, overwrite = true) }
            ledgerBroken = true
            val empty = mutableListOf<LedgerEntry>()
            ledgerMem = empty
            return empty
        }
        /* 先挪文件，再剔条目 —— 剔完就不知道是哪几个文件了。 */
        parkLegacy(parsed)
        parkOrphans(parsed)
        val list = ledgerClean(parsed)
        ledgerMem = list
        // 剔掉过东西就立刻写回：坏行留在盘上，每开一次应用都要再洗一遍。
        if (parsed.size != list.size) ledgerWrite(list)
        return list
    }

    /** 先写临时文件再改名。写到一半断电，也不会留下半截 JSON。 */
    private fun ledgerWrite(list: List<LedgerEntry>) {
        val json = gson.toJson(list)
        runCatching {
            val tmp = File(ctx.filesDir, "clips.json.tmp")
            tmp.writeText(json)
            if (ledgerFile.exists()) runCatching { ledgerFile.copyTo(ledgerBak, overwrite = true) }
            if (!tmp.renameTo(ledgerFile)) {
                ledgerFile.writeText(json)
                tmp.delete()
            }
        }
    }

    private fun ledgerSave(list: List<LedgerEntry>) {
        // 写进去的也过一遍：坏行不该有机会落到盘上。
        val safe = ledgerClean(list)
        ledgerMem = safe
        ledgerWrite(safe)
    }

    /**
     * 一段念完了，记账。
     *
     * 合成出来还没念的不进「念过的段落」—— 预取的几段、缓存备下的整本，
     * 都不该出现在他念过的那本账里。
     *
     * @param trial 这一条是试听留下的，不算书里的正文
     */
    fun notePlayed(clip: Clip, text: String, voice: String, trial: Boolean = false) {
        val ext = clip.ext
        house.launch {
            /* 搬与记账在同一把锁里：中间被人当成「盘上有、账上没有」搬去缓存就错了。 */
            synchronized(bookLock) {
                val kept = keep(clip) ?: return@launch
                note(kept.file.nameWithoutExtension.lowercase(), text, voice, ext, trial)
            }
        }
    }

    /**
     * 把念完的音频从缓存区收进正式区。
     *
     * 文件已经不在（系统清了缓存）就返回 null，那一段不记账 —— 账上有字、盘上没声的条目
     * 点开只会是空的。
     */
    private fun keep(clip: Clip): Clip? {
        val f = clip.file
        if (!f.isFile) return null
        val here = runCatching { f.parentFile?.canonicalPath == cacheDir.canonicalPath }
            .getOrDefault(false)
        if (here) return clip
        val dst = File(cacheDir, f.name)
        if (!dst.isFile && !f.renameTo(dst)) {
            runCatching {
                f.copyTo(dst, true)
                f.delete()
            }
        }
        if (!dst.isFile) return null
        runCatching { if (f.exists()) f.delete() }
        return Clip(dst, clip.ext)
    }

    /** 记一段念过的。 */
    private fun note(key: String, text: String, voice: String, ext: String, trial: Boolean) {
        val list = ledgerLoad()
        list.removeAll { it.key == key }
        list.add(
            0,
            LedgerEntry(
                key = key,
                text = text.take(80),
                voice = voice,
                at = System.currentTimeMillis(),
                ext = ext,
                trial = trial,
            )
        )
        /* 账本不截断。只留最后 400 行的话，盘上有一千五百段，页面上只列得出四百段，剩下那一千多段看不见也就删不掉。 */
        ledgerSave(list)
    }

    /**
     * 念过的段落，新的在前。
     *
     * 只列账上有字的那几条。盘上有、账上没有的是合成出来还没念的（预取的、备章的），
     * 不在这本账里；账上有、盘上没有的销掉。
     */
    fun ledger(): List<LedgerEntry> = synchronized(bookLock) {
        val list = ledgerLoad()
        /* key.ext → 文件。一千五百段时逐条 exists() 是三千次 stat，一次扫目录就够。 */
        val onDisk = HashMap<String, File>(2048)
        runCatching {
            cacheDir.listFiles()?.forEach { f ->
                if (!f.isFile) return@forEach
                val name = f.name
                val dot = name.lastIndexOf('.')
                if (dot <= 0) return@forEach
                val ext = name.substring(dot + 1).lowercase()
                if (ext != "wav" && ext != "mp3") return@forEach
                // 临时的半截文件不是一段念过的书。
                if (name.contains(".part")) return@forEach
                onDisk[name.substring(0, dot).lowercase() + "." + ext] = f
            }
        }
        val alive = list.filter { e ->
            val f = onDisk[e.key.lowercase() + "." + e.ext.lowercase()] ?: return@filter false
            f.length() > 512
        }
        /* 账本读坏过就不写回：那时手里这份是空的，写回去会盖掉原文。 */
        if (!ledgerBroken && alive.size != list.size) ledgerSave(alive)
        alive.sortedByDescending { it.at }
    }

    fun sizeOf(key: String, ext: String): Long =
        File(cacheDir, key + "." + ext).let { if (it.exists()) it.length() else 0L }

    /** 删掉一段念过的。文件先走，账随后销。 */
    fun drop(key: String): Boolean = synchronized(bookLock) {
        var gone = false
        listOf("wav", "mp3").forEach { ext ->
            val f = File(cacheDir, key + "." + ext)
            if (f.exists() && f.delete()) gone = true
        }
        ledgerSave(ledgerLoad().filterNot { it.key == key })
        gone
    }

    /**
* 一次删一批：文件逐个删，账本只落一次。
*
* @param onTick 每删一批报一次「删到第几条」。给界面画进度用；回调在调用线程上跑。
* @return 真删掉文件的条数
*/
    fun dropAll(keys: Collection<String>, onTick: ((Int) -> Unit)? = null): Int =
        synchronized(bookLock) {
        if (keys.isEmpty()) return 0
        var n = 0
        var gone = 0
        keys.forEach { key ->
            n++
            listOf("wav", "mp3").forEach { ext ->
                val f = File(cacheDir, key + "." + ext)
                if (f.exists() && f.delete()) gone++
            }
            if (onTick != null && n % 64 == 0) onTick(n)
        }
        ledgerSave(ledgerLoad().filterNot { it.key in keys })
        onTick?.invoke(n)
        gone
        }

    fun usage(): Pair<Long, Int> {
        val files = cacheDir.listFiles() ?: return 0L to 0
        var total = 0L
        var n = 0
        files.forEach { if (it.isFile) { total += it.length(); n++ } }
        return total to n
    }

    fun clearAll(): Int = synchronized(bookLock) {
        ledgerSave(emptyList())
        val files = cacheDir.listFiles() ?: return 0
        var n = 0
        files.forEach { if (it.isFile && it.delete()) n++ }
        n
    }

    fun prune(maxBytes: Long) {
        val files = cacheDir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        files.sortedBy { it.lastModified() }.forEach { f ->
            if (total <= maxBytes) return@forEach
            total -= f.length()
            f.delete()
        }
    }
}
