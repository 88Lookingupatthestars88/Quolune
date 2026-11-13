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

/** 念过的一段，留个记号。只有音频文件是没法给人看的 —— 一堆 md5 文件名，谁也认不出哪句是哪句。 */
data class LedgerEntry(
    val key: String,
    val text: String,
    val voice: String,
    val at: Long,
    val ext: String,
    /** 这一条是「点一下听个响」造出来的，不是书里的正文。 */
    val trial: Boolean = false,
)

/** 合成调度。按音色自己属于哪副嗓子分流，几副嗓子可以同时开着。 */
class Synth(private val ctx: Context, private val sys: SysVoice) {

    private var mimo = MiMo()
    private var cfg: Settings? = null

    /** 每副音色自己那份调法。由 [com.pagefold.reader.App] 接进来。 */
    @Volatile var tunes: com.pagefold.reader.field.VoiceTune? = null

    /** 念过的段落放 files/clips。 */
    val cacheDir: File get() = File(ctx.filesDir, "clips").apply { mkdirs() }

    /* 账本文件必须声明在 init 上面。 */
    private val ledgerFile = File(ctx.filesDir, "clips.json")
    private val ledgerBak = File(ctx.filesDir, "clips.json.bak")
    private val gson = com.google.gson.Gson()
    private var ledgerMem: MutableList<LedgerEntry>? = null
    /** 账本读不出来的那一次。读不出来和「本来就没有」必须分开：前者一旦被当成空账本写回去，所有条目的原文就永久没了 —— 音频还在，字没了。 */
    private var ledgerBroken = false

    /** 合成规则的版本。改了「怎么合成」就抬一格：缓存 key 全部换掉，旧音频一并清。 */
    private val CLIP_REV = 3

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
        val old = cfg
        if (old == null || old.mimoKey != key || old.mimoBase != base) {
            mimo = MiMo(base, key)
        }
        mimo.appCtx = ctx
        mimo.extra = emptyList()
        cfg = s
    }

    /** 这一段交给哪副嗓源。顺序：先拿音色的 engine 当嗓源 id 找（一家一套音色名。 */
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

    /** 不同音色、不同段落判定必须分开，否则换了嗓音/换了调子还在放旧音频。 */
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

    /** 把一段字节落成缓存文件。六个服务分支都走这里，别处不要再自己拼临时文件名。 */
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
        /* 一次性的调法。试听传这个：听到的是手上还没存的数，不是上一次存下的。 */
        one: com.pagefold.reader.field.Vt? = null,
    ): Clip {
        sync(s)
        val k = key(s, v, text, r, rate, one?.let { "u=${it.speed},${it.pitch},${it.volume},${it.mood},${it.temp}" }.orEmpty())
        /* force = 试听时用：绕开缓存，逼它真去问一次。 */
        if (!force) cached(k)?.let { return it }

        /* 倍速要烘进音频里，不能只靠播放器。 */
        /* 这一副音色自己调过快慢、音调、音量没有。 */
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
                /* 系统 TTS 自己往文件里写，不经过我们那层「先写临时名再改名」，所以它写的时候，另一路读到这里会看到一个只写了一半的 wav。 */
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
                    /* 这一家没有数值的 speed / pitch 字段。 */
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


    /** 这一句的调门词。三层往下掉：这一副自己调的 → 这一段判定出来的 → 全局那一档。 */
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
     * 把账本里不能用的行剔掉。两种都能来：数组里直接是个 null，或者某个字段是 null。
     * 一条坏行留在账本里，就是每次进定音页都崩一次：读出来、碰第一个字段、空指针。
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
        /*
         * 正文那份读不出来，就试备份。
         * 备份再不行，把这一份留个底再往下走 ——宁可这一轮账是空的，不能把唯一那份原文盖掉。
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

    /** 试听那一句。音色页点「听一下」、复刻页点「听听像不像」，念的都是这一句 ——它跟着账本一起存，所以能认出来：这些不是书里的字，是点出来的响。 */
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
        /* 账本不截断。只留最后 400 行的话，盘上有一千五百段，页面上只列得出四百段，剩下那一千多段看不见也就删不掉。 */
        ledgerSave(list)
    }

    /** 念过的段落，新的在前。只扫一遍目录：盘上有账上没有的补进来，账上有盘上没有的销掉。 */
    fun ledger(): List<LedgerEntry> {
        val list = ledgerLoad()
        val known = list.mapTo(HashSet()) { it.key }
        var dirty = false
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
                // 临时的半截文件不是一段念过的书，别让它进账本。
                if (name.contains(".part")) return@forEach
                val key = name.substring(0, dot)
                onDisk[key.lowercase() + "." + ext] = f
                if (f.length() <= 512) return@forEach
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
            val f = onDisk[e.key.lowercase() + "." + e.ext.lowercase()] ?: return@filter false
            f.length() > 512
        }
        /* 账本本身读坏了的时候，只能用扫目录扫出来的这份临时给界面看，不能写回盘上 —— 那等于拿一千条「没留下字」把原文全替了。 */
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
