package com.pagefold.reader.voice

import android.content.Context
import com.pagefold.reader.book.Cue
import com.pagefold.reader.book.Reading
import com.pagefold.reader.field.EngineCfg
import com.pagefold.reader.field.Engines
import com.pagefold.reader.field.Settings
import com.pagefold.reader.field.VoiceSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** 一段合成结果：磁盘上的文件 + 容器格式。 */
data class Clip(val file: File, val ext: String)

/**
 * 念过的一段，留个记号。
 *
 * 只有音频文件是没法给人看的 —— 一堆 md5 文件名，谁也认不出哪句是哪句。
 * 所以合成完顺手记一行：这段念的是什么、用的哪副嗓子、什么时候。
 * 家底那一页就是照这张表列出来的。
 */
data class LedgerEntry(
    val key: String,
    val text: String,
    val voice: String,
    val at: Long,
    val ext: String,
    /** 这一条是「点一下听个响」造出来的，不是书里的正文。 */
    val trial: Boolean = false,
)

/**
 * 合成调度。按**音色自己**属于哪副嗓子分流，几副嗓子可以同时开着。
 */
class Synth(private val ctx: Context, private val sys: SysVoice) {

    private var mimo = MiMo()
    private var cfg: Settings? = null

    /**
     * 每副音色自己那份调法。由 [com.pagefold.reader.App] 接进来。
     * 没接的时候一律按全局走，不会崩。
     */
    @Volatile var tunes: com.pagefold.reader.field.VoiceTune? = null

    /**
     * 念过的段落放 files/clips。
     *
     * 不放 cacheDir：那一整棵目录随时会被手机管家或系统清掉，而这批音频
     * 对用户是「我念过的书」，不是可以重下的临时文件。放 files 下，
     * 只有他在库房里勾掉、或者卸载应用，才会没。
     */
    val cacheDir: File get() = File(ctx.filesDir, "clips").apply { mkdirs() }

    /*
     * 账本文件必须声明在 init 上面。
     *
     * Kotlin 按**声明顺序**初始化属性和 init 块。下面那个 init 会调 cleanOnce()，
     * 而 cleanOnce 里要删这个文件；声明写在 init 下面，那一刻它还是 null，
     * 删的时候就是空指针 —— 被 runCatching 吞掉，于是「清一次」的记号永远写不下去，
     * 每次启动都当成没清过，把 clips 里所有音频删一遍。
     */
    private val ledgerFile = File(ctx.filesDir, "clips.json")
    private val ledgerBak = File(ctx.filesDir, "clips.json.bak")
    private val gson = com.google.gson.Gson()
    private var ledgerMem: MutableList<LedgerEntry>? = null
    /**
     * 账本读不出来的那一次。
     *
     * 读不出来和「本来就没有」必须分开：前者一旦被当成空账本写回去，
     * 所有条目的原文就永久没了 —— 音频还在，字没了。
     */
    private var ledgerBroken = false

    /**
     * 合成规则的版本。改了「怎么合成」就抬一格：缓存 key 全部换掉，旧音频一并清。
     *
     * 3：语速、音调、音量改由每副音色自己那份说了算；小米那一路开始发
     *    一句自然语言请求。同一段字，按新规则合成出来的跟旧的不是一条。
     */
    private val CLIP_REV = 3

    /**
     * 把 rev 抬格前的音频清一次。
     *
     * 这些文件结构上是完整的 wav，乱只乱在内容（开头多念了一句），
     * 靠 [sound] 查不出来，留着就会一直被播到。清完记一笔，不再重复清。
     */
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

    /** 老版本留在 cache 下的那一堆，开机搬到新家，不让他平白丢一次。 */
    private fun migrateOldClips() = runCatching {
        val old = File(ctx.cacheDir, "clips")
        if (!old.isDirectory) return@runCatching
        val now = cacheDir
        old.listFiles()?.forEach { f ->
            if (!f.isFile) return@forEach
            // 半截的临时文件没有留的价值 —— 它本来就没写完。
            if (f.name.contains(".part")) { f.delete(); return@forEach }
            val dst = File(now, f.name)
            if (dst.exists()) { f.delete(); return@forEach }
            if (!f.renameTo(dst)) {
                runCatching {
                    f.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
                    f.delete()
                }
            }
        }
        old.delete()
    }

    /** 清掉一小时前还没写完的临时文件。进程被杀、断电会留下这些半截货。 */
    private fun sweepParts() = runCatching {
        val dir = cacheDir
        val cut = System.currentTimeMillis() - 3600_000L
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.contains(".part") && f.lastModified() < cut) f.delete()
        }
    }

    init {
        migrateOldClips()
        cleanOnce()
        sweepParts()
    }

    fun sync(s: Settings) {
        // 播放时每一句都会走到这儿。设置没换过就直接回头。
        if (cfg === s) return
        val m = Engines.first(s.engines, VoiceSource.MIMO)
        val base = m?.base?.ifBlank { null } ?: s.mimoBase
        val key = if (m?.key?.isNotBlank() == true) m.key else s.mimoKey
        if (cfg == null || cfg!!.mimoKey != key || cfg!!.mimoBase != base) {
            mimo = MiMo(base, key)
        }
        mimo.appCtx = ctx
        mimo.extra = emptyList()
        cfg = s
    }

    /**
     * 这一段交给哪副嗓源。
     *
     * 顺序：先拿音色的 engine 当嗓源 id 找（一家一套音色名，只有 id 分得清）；
     * 再按来路找；都不行就退回第一副开着的。
     *
     * 这里不许抛异常。抛一次就把整场播放带走。
     */
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
     * 段落判定直接进指纹 —— 这是「选了风格却读得一样」的另一半原因。
     *
     * 这一副自己的调法也进指纹：调完语速再合成，必须是一条新音频，
     * 否则听到的还是上次那条，看着像「调了没生效」。
     */
    private fun fingerprint(s: Settings, v: Voice, r: Reading?, rate: Int): String = buildString {
        append(v.engine).append('/').append(v.kind).append('/').append(v.param)
        // 模型进指纹，否则换了模型照样命中旧缓存，听着一模一样。
        append('/').append(route(s, v)?.modelOr().orEmpty())
        append('/').append(s.speed).append('/').append(s.pitch)
        val vt = tunes?.of(v.id) ?: com.pagefold.reader.field.Vt()
        if (tunes?.has(v.id) == true) {
            append("/v=").append(vt.speed).append(',').append(vt.pitch)
            append(',').append(vt.volume).append(',').append(vt.mood).append(',').append(vt.lang)
            append(',').append(vt.bright).append(',').append(vt.soft).append(',').append(vt.clear)
            append(',').append(vt.fx).append(',').append(vt.dialect).append(',').append(vt.silence)
            append(',').append(vt.temp).append(',').append(vt.boost).append(',').append(vt.norm)
        }
        // 这一本的念速也要进指纹：倍速是烘进音频里的，不进指纹就会放到旧速度那条。
        append('/').append(rate.coerceIn(50, 300))
        if (r != null) {
            append('/').append(r.kind.name).append(',').append(r.rate)
            append(',').append(r.pitch).append(',').append(r.volume)
            // 调门也要进指纹，否则「换了风格还是老声音」
            append('/').append(r.style)
        }
    }

    fun key(
        s: Settings,
        v: Voice,
        text: String,
        r: Reading?,
        rate: Int = 100,
        extra: String = "",
    ): String {
        /* rev 进 key：合成规则一变，旧音频就不再被复用，升级完不会还听到旧毛病。 */
        val raw = fingerprint(s, v, r, rate) + "|" + text + "|r=$CLIP_REV|" + extra
        val md = MessageDigest.getInstance("MD5").digest(raw.toByteArray())
        return md.joinToString("") { "%02x".format(it) }
    }

    fun cached(key: String): Clip? {
        for (ext in listOf("wav", "mp3")) {
            val f = File(cacheDir, "$key.$ext")
            if (!f.exists()) continue
            if (sound(f)) return Clip(f, ext)
            // 坏文件当场烧掉，让调用方重新合成，不留着反复播。
            runCatching { f.delete() }
        }
        return null
    }

    /**
     * 判断这个文件是不是一段完整、能播的音频。
     *
     * 拿 wav 头里 `data` chunk 声明的长度跟实际长度对一下。对不上就是没写完，
     * 或者被另一条流插过。只拦明显不对的：各家写 RIFF 头的手艺参差，
     * 卡太紧会把好文件当坏的烧掉，那每次播放都要重合成。
     *
     * 调用它的是 [cached] 和 [land]。
     */
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
            if (size < 0) return false
            if (id == "data") { at = i + 8; len = size; break }
            i += 8 + size + (size and 1)
        }
        if (at < 0 || len <= 0) return false
        // 声明比实际长 = 写断了。实际比声明长出太多 = 后面被塞了别的东西。
        val claim = at.toLong() + len
        if (claim > n) return false
        if (n - claim > 32L * 1024) return false
        return true
    }

    /**
     * 把一段字节落成缓存文件。六个服务分支都走这里，别处不要再自己拼临时文件名。
     *
     * 两件事：
     * 临时名带纳秒，两路同时合成同一段也各写各的，最后 rename 上来是原子的；
     * 落盘后过一遍 [sound]，坏的删掉直接抛，交给上层重试。
     */
    private fun land(k: String, ext: String, bytes: ByteArray): File {
        if (bytes.size < 512) throw java.io.IOException("只回了 ${bytes.size} 字节，不像一段声音")
        val dst = File(cacheDir, "$k.$ext")
        val tmp = File(cacheDir, "$k." + System.nanoTime().toString(36) + ".part")
        try {
            tmp.writeBytes(bytes)
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
     * @param r      段落判定结果（旁白/对白/耳语…）
     * @param before 上文原句，给 MiMo 做语气参考
     * @param after  下文原句
     */
    suspend fun make(
        s: Settings,
        v: Voice,
        text: String,
        r: Reading?,
        before: String? = null,
        after: String? = null,
        rate: Int = 100,
        force: Boolean = false,
        /*
         * 一次性的调法。试听传这个：听到的是手上还没存的数，不是上一次存下的。
         * 传了它就不走缓存 —— 并且由它参与缓存键，所以不会把存下的那条覆盖掉。
         */
        one: com.pagefold.reader.field.Vt? = null,
    ): Clip {
        sync(s)
        val k = key(s, v, text, r, rate, one?.let { "u=${it.speed},${it.pitch},${it.volume},${it.mood},${it.temp}" }.orEmpty())
        /*
         * force = 试听时用：绕开缓存，逼它真去问一次。
         * 不绕的话听到的是旧文件，刚改的模型、刚换的钥匙全没验到。
         */
        if (!force) cached(k)?.let { return it }

        /*
         * 倍速要烘进音频里，不能只靠播放器。
         *
         * 只设 MediaPlayer.playbackParams 的话，机器不支持时间伸缩（有些 ROM 会静默失败），
         * 表现就是「拖到 2x，听着还是原速」。这里把这一本的念速乘进合成的语速，
         * 出来的音频本身就是那个速度，播放器那层只当微调。
         */
        /*
         * 这一副音色自己调过快慢、音调、音量没有。
         *
         * 调过就以它为准，四格一起看 —— 不去逐格跟全局比。逐格比的话，
         * 用户把这一副的语速定在 100 想让它「就按原速」，而全局是 130 的时候，
         * 这一格会被当成「没设」，嗓门又被拽回 130。整条替掉才说得清。
         */
        val mine = one ?: tunes?.let { t -> if (t.has(v.id)) t.of(v.id) else null }
        val own = mine ?: com.pagefold.reader.field.Vt(
            speed = s.speed,
            pitch = s.pitch,
            volume = 100,
            mood = "",
        )
        val sp = ((own.speed.toLong() * rate.coerceIn(50, 300)) / 100L).toInt().coerceIn(50, 250)
        val pitchPct = own.pitch.coerceIn(50, 200)
        val volPct = own.volume.coerceIn(50, 200)

        val ext: String
        val file: File
        // 按**协议**分流，不按 engine 字符串。engine 现在可能是某一副嗓源的 id。
        val proto = protocolOf(s, v)
        /*
         * 模型不对，请求根本不发。
         *
         * 这一条不是防呆，是防**对面的沉默**：小米那套口收到不认识的 model 不报错，
         * 直接按默认 TTS 念出来、回 200 加一段音频。客户端拿到音频就当成功，
         * 于是「写字模型也能出声」「换哪个模型听着都一样」同时成立，而错误一个都不出现。
         * 它不说，我们说。
         */
        val eng = route(s, v)
        if (proto != "system") {
            // 拦截要用**真正会发的那个名字**，不是嗓源格子上那个：
            // 设计/克隆音色各走各的模型，三者可能都不同。
            val used = if (proto == "mimo") MiMo.modelFor(v, eng) else eng?.modelOr().orEmpty()
            val role = if (proto == "mimo" && (v.kind == "design" || v.kind == "clone")) v.kind else ""
            /*
             * 拦下来的理由原样带出去，别在这一层再压成一句话。
             *
             * 三种拦法说的不是一件事：「不是语音模型」「是造嗓音的模型，念不了书」
             * 「不是音色克隆模型」。压成一句「不是语音模型」，用户拿到的方向就是错的。
             */
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
                note(k, text, v.name, ext)
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
                note(k, text, v.name, ext)
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
                note(k, text, v.name, ext)
            }
            "system" -> {
                /*
                 * 系统 TTS 自己往文件里写，不经过我们那层「先写临时名再改名」，
                 * 所以它写的时候，另一路读到这里会看到一个只写了一半的 wav。
                 * 这里给它一个临时名，写完再改名 —— 跟别的家一个待遇。
                 */
                val tmp = File(cacheDir, "$k." + System.nanoTime().toString(36) + ".part.wav")
                sys.file(text, v.param.ifBlank { s.sysVoice }, sp, pitchPct, tmp)
                val dst = File(cacheDir, "$k.wav")
                if (!tmp.renameTo(dst)) { tmp.copyTo(dst, true); tmp.delete() }
                file = dst
                ext = "wav"
                note(k, text, v.name, ext)
            }
            "speech" -> {
                // 标准口：POST /v1/audio/speech，直接回音频。
                // 标准口。没有这一支的话，只有小米那套 chat/completions 可用，
                // 填了 OpenAI 的地址和 tts-1 就是一个字也念不出来。
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
                note(k, text, v.name, ext)
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
                    /*
                     * 这一家没有数值的 speed / pitch 字段。这两格和调门一起，
                     * 由 [MiMo.askPhrase] 拼成一句自然语言请求发出去；
                     * 三样都没动就一个字都不发。
                     */
                    speed = sp,
                    pitch = pitchPct,
                    mood = moodOf(s, v, r),
                )
                file = land(k, "wav", bytes)
                ext = "wav"
                note(k, text, v.name, ext)
            }
        }
        return Clip(file, ext)
    }


    /**
     * 这一句的调门词。
     *
     * 三层往下掉：这一副自己调的 → 这一段判定出来的 → 全局那一档。
     * 回空串表示「这一格不给」，让对面按文本自己判。
     *
     * 只回一个词。MiniMax 的 emotion、火山的语音指令都要的是短句，一句话塞进去不是它认的形状。
     */
    private fun moodOf(s: Settings, v: Voice, r: Reading?): String {
        val own = tunes?.let { t -> if (t.has(v.id)) t.of(v.id).mood else null }
        if (!own.isNullOrBlank()) return own
        if (r == null) {
            val t = s.style.trim()
            return if (t == "平静" || t == "无") "" else t.take(4)
        }
        return Cue.say(r, r.style.ifBlank { s.style })
    }

    /** 百分数的音调 → 各家那个整数刻度。MiniMax 和火山都是 −12…12，0 是原调。 */
    private fun grade(pct: Int, span: Int): Int =
        Math.round((pct.coerceIn(50, 200) - 100) / 100f * span).coerceIn(-span, span)

    // ── 账本 ────────────────────────────────────────────────

    /**
     * 把账本里不能用的行剔掉。
     *
     * 两种都能来：数组里直接是个 `null`，或者某个字段是 `null`。
     * 后者更常见 —— Gson 不走构造函数，是拿反射往字段里塞值，
     * 存档里少哪一个键，那一个字段就是 `null`，哪怕它在 data class 里声明成非空。
     * 字段声明保证不了运行时，所以这里逐格兜一遍。
     *
     * 一条坏行留在账本里，就是**每次进定音页都崩一次**：读出来、碰第一个字段、空指针。
     * 而且崩在读取的路上，下一次读到的还是那一行 —— 自己好不了。
     */
    private fun ledgerClean(raw: List<LedgerEntry?>?): MutableList<LedgerEntry> {
        val out = ArrayList<LedgerEntry>((raw?.size ?: 0).coerceAtLeast(8))
        raw?.forEach { e ->
            if (e == null) return@forEach
            // 没有 key 的条目指不到任何文件，留着只会崩。key 里带分隔符的也不能要。
            val key = e.key.orEmpty()
            if (key.isBlank() || key.contains('/') || key.contains('\\')) return@forEach
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

    /**
     * 把 JSON 原文读成条目。
     *
     * 不走 Gson 的反射绑定，改成先解析成 JsonArray 再一格一格取。
     * 反射绑定时字段缺一个就是 null，null 混进 list 就在下一次 it.key 处炸；
     * 而整份文件只要有一处类型不对，Gson 直接把整份抛掉 —— 一千多条全没了。
     * 逐格读，坏一条只丢一条。
     */
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
        /*
         * 正文那份读不出来，就试备份。备份再不行，把这一份**留个底**再往下走 ——
         * 宁可这一轮账是空的，不能把唯一那份原文盖掉。
         */
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
     * 试听那一句。
     *
     * 音色页点「听一下」、复刻页点「听听像不像」，念的都是这一句 ——
     * 它跟着账本一起存，所以能认出来：这些不是书里的字，是点出来的响。
     */
    private val trialLine = "这一段，念给你听。"

    private fun note(key: String, text: String, voice: String, ext: String) {
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
                trial = text.trim() == trialLine,
            )
        )
        /*
         * 账本不截断。只留最后 400 行的话，盘上有一千五百段，页面上只列得出四百段，
         * 剩下那一千多段看不见也就删不掉。一行几十字节，留着不占地方。
         */
        ledgerSave(list)
    }

    /**
     * 念过的段落，新的在前。
     *
     * 两头都要对齐：账上有、盘上没了（缓存被系统清过）的要销掉；
     * 盘上有、账上没有的（老版本截断账本留下的）也要补进来 ——
     * 不然那些文件永远列不出来，也就永远删不掉。
     */
    fun ledger(): List<LedgerEntry> {
        val list = ledgerLoad()
        val known = list.mapTo(HashSet()) { it.key }
        var dirty = false
        runCatching {
            cacheDir.listFiles()?.forEach { f ->
                if (!f.isFile) return@forEach
                val name = f.name
                val dot = name.lastIndexOf('.')
                if (dot <= 0) return@forEach
                val ext = name.substring(dot + 1).lowercase()
                if (ext != "wav" && ext != "mp3") return@forEach
                // 临时的半截文件不是一段念过的书，别让它进账本。
                if (name.contains(".part")) return@forEach
                if (f.length() <= 512) return@forEach
                val key = name.substring(0, dot)
                if (!known.add(key)) return@forEach
                list.add(
                    LedgerEntry(
                        key = key,
                        text = "（早先念的一段，没留下字）",
                        voice = "",
                        at = f.lastModified(),
                        ext = ext,
                    )
                )
                dirty = true
            }
        }
        val alive = list.filter { e ->
            val f = File(cacheDir, e.key + "." + e.ext)
            f.exists() && f.length() > 512
        }
        /*
         * 账本本身读坏了的时候，只能用扫目录扫出来的这份**临时**给界面看，
         * 不能写回盘上 —— 那等于拿一千条「没留下字」把原文全替了。
         */
        if (!ledgerBroken && (dirty || alive.size != list.size)) ledgerSave(alive)
        return alive.sortedByDescending { it.at }
    }

    fun sizeOf(key: String, ext: String): Long =
        File(cacheDir, key + "." + ext).let { if (it.exists()) it.length() else 0L }

    /** 删掉一段念过的。文件先走，账随后销。 */
    fun drop(key: String): Boolean {
        var gone = false
        listOf("wav", "mp3").forEach { ext ->
            val f = File(cacheDir, key + "." + ext)
            if (f.exists() && f.delete()) gone = true
        }
        ledgerSave(ledgerLoad().filterNot { it.key == key })
        return gone
    }

    fun usage(): Pair<Long, Int> {
        val files = cacheDir.listFiles() ?: return 0L to 0
        var total = 0L
        var n = 0
        files.forEach { if (it.isFile) { total += it.length(); n++ } }
        return total to n
    }

    fun clearAll(): Int {
        ledgerSave(emptyList())
        val files = cacheDir.listFiles() ?: return 0
        var n = 0
        files.forEach { if (it.isFile && it.delete()) n++ }
        return n
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
