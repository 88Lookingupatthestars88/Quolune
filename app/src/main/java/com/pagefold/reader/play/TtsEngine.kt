package com.pagefold.reader.play

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import com.pagefold.reader.App
import com.pagefold.reader.book.Cue
import com.pagefold.reader.voice.Catalog
import com.pagefold.reader.voice.Voice
import java.util.Locale

/** 让别的应用也能用 Quolune 的声音。 */
class TtsEngine : TextToSpeechService() {

    private companion object {
        const val RATE = 24000
        const val MAX_TOTAL = 4000
        const val MAX_ONE = 240

        /** 等第一声最多等这么久。调用方等不起，等到最后只会弹一个失败。 */
        const val FIRST_MS = 18_000L

        /** 出声之后，每往下一段重新计时。连着这么久没出下一段，就收尾。 */
        const val NEXT_MS = 30_000L
    }

    private val app: App get() = application as App

    @Volatile private var stopped = false

    override fun onGetLanguage(): Array<String> = arrayOf("zho", "CHN", "")

    /** lang/country 传进来的是 ISO-639-2 三字母码，不是 zh/CN。 */
    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        val l = lang?.lowercase(Locale.ROOT).orEmpty()
        val ok = l == "zho" || l == "chi" || l == "cmn" || l.startsWith("zh")
        return if (ok) TextToSpeech.LANG_COUNTRY_AVAILABLE else TextToSpeech.LANG_NOT_SUPPORTED
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int =
        onIsLanguageAvailable(lang, country, variant)

    override fun onStop() {
        stopped = true
    }

    override fun onGetVoices(): MutableList<android.speech.tts.Voice> {
        val out = ArrayList<android.speech.tts.Voice>()
        runCatching { pool() }.getOrDefault(Catalog.MIMO).forEach { v ->
            runCatching {
                out += android.speech.tts.Voice(
                    tag(v),
                    Locale.SIMPLIFIED_CHINESE,
                    android.speech.tts.Voice.QUALITY_HIGH,
                    android.speech.tts.Voice.LATENCY_NORMAL,
                    // false：让系统不要把这几副嗓子藏起来。
                    // 真联网是引擎自己的事，不该由 framework 拿这句话卡人。
                    false,
                    setOf("zh", v.engine),
                )
            }
        }
        return out
    }

    /** API 21 起，setLanguage 实际是「取这个 locale 的默认嗓音，再 setVoice」。 */
    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String {
        val l = lang?.lowercase(Locale.ROOT).orEmpty()
        val ok = l == "zho" || l == "chi" || l == "cmn" || l.startsWith("zh")
        val list = runCatching { pool() }.getOrDefault(Catalog.MIMO)
        if (!ok || list.isEmpty()) return list.firstOrNull()?.let { tag(it) } ?: ""
        // 用户自己挑的那副优先，别的应用念中文就是这个声
        val want = runCatching { app.field.now.defaultVoice }.getOrDefault("")
        return tag(list.firstOrNull { it.name == want } ?: list.first())
    }

    /** 系统问「这个名字你认不认」。 */
    override fun onIsValidVoiceName(voiceName: String?): Int {
        val want = voiceName?.removePrefix("tl:") ?: return TextToSpeech.ERROR
        val list = runCatching { pool() }.getOrDefault(Catalog.MIMO)
        return if (list.any { it.id == want }) TextToSpeech.SUCCESS else TextToSpeech.ERROR
    }

    /** 系统通知「接下来用这副」。 */
    override fun onLoadVoice(voiceName: String?): Int = onIsValidVoiceName(voiceName)

    private fun tag(v: Voice): String = "tl:" + v.id

    /** 调用方挑的是哪副嗓子。空 = 没指定，按默认那副念。 */
    private fun voiceNameOf(r: SynthesisRequest): String? =
        runCatching { r.voiceName }.getOrNull()?.takeIf { it.isNotBlank() }

    /** 其他应用调用时可用的话音：与书里能挑的一致，含本机引擎那几副，不含本应用自己。 */
    private fun pool(): List<Voice> {
        val s = app.field.now
        /* 自己念自己会死在这条路上：那一句永远等不到声音，只能等满超时。 */
        val me = com.pagefold.reader.field.Engines.sysId(packageName)
        val mine = Catalog.ours(s.engines, app.box.custom())
            .filterNot { it.engine == me || it.engine.startsWith("$me#") }
        return mine.ifEmpty { Catalog.MIMO }
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        stopped = false

        val full = request.text.orEmpty().trim()
        if (full.isEmpty()) {
            runCatching { callback.error() }
            return
        }

        // 先把「开始了」告诉系统。这一步必须在任何慢事之前。
        val began = runCatching {
            callback.start(RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
        }.getOrDefault(TextToSpeech.ERROR)
        if (began != TextToSpeech.SUCCESS) return

        // 先喂一口静音。start() 之后 framework 立刻拉起 AudioTrack，而头一包数据
        // 要一两秒才到，空档里有些系统会判定引擎死了把播放掐掉。垫 120ms。
        runCatching {
            val cap0 = callback.maxBufferSize.coerceIn(1024, 65536)
            val pad = ByteArray(minOf(cap0, RATE / 1000 * 120 * 2))
            callback.audioAvailable(pad, 0, pad.size)
        }

        val s = app.field.now
        val voices = runCatching { pool() }.getOrDefault(Catalog.MIMO)
        val want = voiceNameOf(request)?.removePrefix("tl:")
        val v = voices.firstOrNull { it.id == want }
            ?: Catalog.byName(voices, s.defaultVoice)
            ?: voices.firstOrNull { it.kind == "system" }
            ?: voices.firstOrNull()
        if (v == null) {
            runCatching { callback.error() }
            return
        }

        /* 音色页调过这一副就用它那套；没调过才听系统来的，系统没给就用全局。 */
        val own = if (app.vtune.has(v.id)) app.vtune.of(v.id) else null
        val speed = own?.speed ?: runCatching { request.speechRate }.getOrDefault(100).let {
            if (it in 20..400) it else s.speed
        }
        val pitch = own?.pitch ?: runCatching { request.pitch }.getOrDefault(100).let {
            if (it in 20..400) it else s.pitch
        }

        var until = System.currentTimeMillis() + FIRST_MS
        var fed = 0
        val cap = runCatching { callback.maxBufferSize }.getOrDefault(4096).coerceAtLeast(2048)

        try {
            for (piece in chunk(full)) {
                if (stopped) return
                /* 已经超时就收尾。没出过声的话，下面会照实报失败。 */
                if (System.currentTimeMillis() > until) break
                val reading = Cue.of(piece, null, null, s.style, !s.cue)
                val dark = if (v.reactsToStyle) reading else null
                val clip = runCatching {
                    kotlinx.coroutines.runBlocking {
                        app.synth.make(
                            s, v, piece, dark,
                            bakeSpeed = speed, bakePitch = pitch,
                        )
                    }
                }.getOrNull()
                if (clip == null) continue
                val raw = runCatching { Pcm.read(clip.file) }.getOrNull()
                if (raw == null || raw.pcm.isEmpty()) continue
                val pcm = to24k(raw)
                var off = 0
                while (off < pcm.size) {
                    if (stopped) return
                    val n = minOf(cap, pcm.size - off)
                    val r = runCatching { callback.audioAvailable(pcm, off, n) }
                        .getOrDefault(TextToSpeech.ERROR)
                    if (r != TextToSpeech.SUCCESS) return
                    off += n
                    fed += n
                }
                runCatching { app.synth.notePlayed(clip, piece, v.name) }
                /* 出声了。后面每段各算各的，一整段长文不该被总时限砍断。 */
                until = System.currentTimeMillis() + NEXT_MS
                // 句间留一点，别像机器播报
                if (reading.pauseMs > 0) {
                    val silence = ByteArray(RATE / 1000 * reading.pauseMs * 2)
                    var o2 = 0
                    while (o2 < silence.size) {
                        if (stopped) return
                        val n = minOf(cap, silence.size - o2)
                        val r = runCatching { callback.audioAvailable(silence, o2, n) }
                            .getOrDefault(TextToSpeech.ERROR)
                        if (r != TextToSpeech.SUCCESS) return
                        o2 += n
                        fed += n
                    }
                }
            }
        } catch (_: Throwable) {
            // 断在半路也要把已经念出去的收个尾
        }

        if (fed <= 0) {
            runCatching { callback.error() }
            return
        }
        runCatching { callback.done() }
    }

    /** 按句号切开，一段别太长，免得调用方等太久。 */
    private fun chunk(text: String): List<String> {
        val src = if (text.length > MAX_TOTAL) text.substring(0, MAX_TOTAL) else text
        val out = ArrayList<String>(8)
        val sb = StringBuilder()
        src.forEach { c ->
            sb.append(c)
            val cut = c == '。' || c == '！' || c == '？' || c == '；' || c == '\n'
            if ((cut && sb.length >= 12) || sb.length >= MAX_ONE) {
                val one = sb.toString().trim()
                if (one.isNotEmpty()) out += one
                sb.setLength(0)
            }
        }
        val tail = sb.toString().trim()
        if (tail.isNotEmpty()) out += tail
        return if (out.isEmpty()) listOf(src) else out
    }

    /** 一律转成 24000 单声道 16 位。 */
    private fun to24k(raw: Pcm.Raw): ByteArray {
        val ch = if (raw.channels <= 0) 1 else raw.channels
        val srcRate = if (raw.rate <= 0) 16000 else raw.rate
        val frames = raw.pcm.size / (2 * ch)
        if (frames == 0) return ByteArray(0)

        val bb = java.nio.ByteBuffer.wrap(raw.pcm).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val mono = ShortArray(frames)
        for (i in 0 until frames) {
            var acc = 0
            for (c in 0 until ch) acc += bb.getShort((i * ch + c) * 2).toInt()
            mono[i] = (acc / ch).toShort()
        }
        if (srcRate == RATE) {
            val out = ByteArray(frames * 2)
            val ob = java.nio.ByteBuffer.wrap(out).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until frames) ob.putShort(mono[i])
            return out
        }
        val n = (frames.toLong() * RATE / srcRate).toInt().coerceAtLeast(1)
        val out = ByteArray(n * 2)
        val ob = java.nio.ByteBuffer.wrap(out).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val step = srcRate.toDouble() / RATE
        for (i in 0 until n) {
            val pos = i * step
            val i0 = pos.toInt().coerceIn(0, frames - 1)
            val i1 = (i0 + 1).coerceAtMost(frames - 1)
            val f = pos - i0
            val v = mono[i0] * (1 - f) + mono[i1] * f
            ob.putShort(v.toInt().coerceIn(-32768, 32767).toShort())
        }
        return out
    }
}
