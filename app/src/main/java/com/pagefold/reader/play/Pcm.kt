package com.pagefold.reader.play

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File

/** 把一段音频拆成裸 PCM。系统 TTS 只收裸 PCM，不收 wav/mp3 文件。 */
object Pcm {

    data class Raw(val pcm: ByteArray, val rate: Int, val channels: Int)

    fun read(f: File): Raw {
        if (!f.exists() || f.length() < 64) return Raw(ByteArray(0), 16000, 1)
        val head = f.inputStream().use { s -> ByteArray(12).also { s.read(it) } }
        val isWav = head.size == 12 &&
            head[0] == 'R'.code.toByte() && head[1] == 'I'.code.toByte() &&
            head[8] == 'W'.code.toByte() && head[9] == 'A'.code.toByte()
        return if (isWav) wav(f) else codec(f)
    }

    private fun u16(b: ByteArray, i: Int): Int = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
            ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)

    private fun wav(f: File): Raw {
        val b = f.readBytes()
        var i = 12
        var rate = 16000
        var ch = 1
        var bits = 16
        while (i + 8 <= b.size) {
            val id = String(b, i, 4, Charsets.US_ASCII)
            val size = u32(b, i + 4)
            if (size < 0) break
            when (id) {
                "fmt " -> {
                    if (i + 24 <= b.size) {
                        ch = u16(b, i + 10)
                        rate = u32(b, i + 12)
                        bits = u16(b, i + 22)
                    }
                }
                "data" -> {
                    val n = minOf(size, b.size - i - 8).coerceAtLeast(0)
                    var pcm = b.copyOfRange(i + 8, i + 8 + n)
                    if (bits != 16) pcm = to16(pcm, bits)
                    return Raw(pcm, if (rate <= 0) 16000 else rate, if (ch <= 0) 1 else ch)
                }
            }
            i += 8 + size + (size and 1)
        }
        return Raw(ByteArray(0), 16000, 1)
    }

    /** 24/32 位的整数样本降到 16 位。32 位浮点按 [−1,1] 处理。 */
    private fun to16(src: ByteArray, bits: Int): ByteArray {
        val step = bits / 8
        if (step <= 0) return src
        val n = src.size / step
        val out = ByteArray(n * 2)
        for (k in 0 until n) {
            val o = k * step
            val sample = when (bits) {
                24 -> ((src[o].toInt() and 0xFF) or ((src[o + 1].toInt() and 0xFF) shl 8) or ((src[o + 2].toInt() and 0xFF) shl 16)) shr 8
                32 -> {
                    val i32 = (src[o].toInt() and 0xFF) or ((src[o + 1].toInt() and 0xFF) shl 8) or
                        ((src[o + 2].toInt() and 0xFF) shl 16) or ((src[o + 3].toInt() and 0xFF) shl 24)
                    (i32 shr 16).coerceIn(-32768, 32767)
                }
                else -> 0
            }
            out[k * 2] = (sample and 0xFF).toByte()
            out[k * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun codec(f: File): Raw {
        val ex = MediaExtractor()
        runCatching { ex.setDataSource(f.absolutePath) }.getOrElse { return Raw(ByteArray(0), 16000, 1) }
        var track = -1
        for (i in 0 until ex.trackCount) {
            val m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
            if (m.startsWith("audio/")) { track = i; break }
        }
        if (track < 0) { ex.release(); return Raw(ByteArray(0), 16000, 1) }

        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val mime = fmt.getString(MediaFormat.KEY_MIME) ?: run { ex.release(); return Raw(ByteArray(0), 16000, 1) }
        val rate = runCatching { fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(16000)
        val ch = runCatching { fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(1)
        val isFloat = runCatching {
            fmt.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
        }.getOrDefault(false)

        val dec = runCatching { MediaCodec.createDecoderByType(mime) }.getOrNull()
            ?: run { ex.release(); return Raw(ByteArray(0), rate, ch) }

        val out = ByteArrayOutputStream(1 shl 16)
        val info = MediaCodec.BufferInfo()
        try {
            dec.configure(fmt, null, null, 0)
            dec.start()
            var inDone = false
            var outDone = false
            var guard = 0
            while (!outDone && guard < 6000) {
                guard++
                if (!inDone) {
                    val idx = dec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val buf = dec.getInputBuffer(idx)
                        val n = if (buf == null) -1 else ex.readSampleData(buf, 0)
                        if (n < 0) {
                            dec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            dec.queueInputBuffer(idx, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = dec.dequeueOutputBuffer(info, 10_000)
                if (oi >= 0) {
                    val buf = dec.getOutputBuffer(oi)
                    if (buf != null && info.size > 0) {
                        val arr = ByteArray(info.size)
                        buf.position(info.offset)
                        buf.get(arr)
                        out.write(if (isFloat) floatTo16(arr) else arr)
                    }
                    dec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                } else if (oi == MediaCodec.INFO_TRY_AGAIN_LATER && inDone) {
                    outDone = true
                }
            }
        } catch (_: Throwable) {
            // 解一半也比崩强
        } finally {
            runCatching { dec.stop() }
            runCatching { dec.release() }
            ex.release()
        }
        return Raw(out.toByteArray(), rate, ch)
    }

    private fun floatTo16(src: ByteArray): ByteArray {
        val n = src.size / 4
        val out = ByteArray(n * 2)
        val bb = java.nio.ByteBuffer.wrap(src).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (k in 0 until n) {
            val v = (bb.getFloat(k * 4) * 32767f).coerceIn(-32768f, 32767f).toInt()
            out[k * 2] = (v and 0xFF).toByte()
            out[k * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }
}
