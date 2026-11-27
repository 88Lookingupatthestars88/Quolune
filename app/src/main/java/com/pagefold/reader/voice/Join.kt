package com.pagefold.reader.voice

import java.io.File

/**
 * 播放侧拼接。合成仍按块走，放的时候把同嗓子的几块接成一条，
 * 段与段之间不起 MediaPlayer，也就没有起播那一截空白。
 */
object Join {

    /** 一条最多接这么多块。接得越多，界面上那一行的位置停得越久。 */
    const val MAX_PARTS = 5

    /** 一条最多这么大。24 MB 的 wav 约合八分钟。 */
    const val MAX_BYTES = 24L * 1024 * 1024

    /**
     * 这两段能不能接在一起。
     *
     * 只认容器和波形参数，不认嗓子和调门 —— 两个人一来一回正是最该接起来的时候。
     */
    fun same(a: Clip, b: Clip): Boolean {
        if (a.ext != b.ext) return false
        if (a.ext != "wav") return true
        val fa = fmtOf(a.file) ?: return false
        val fb = fmtOf(b.file) ?: return false
        return fa.contentEquals(fb)
    }

    /**
     * 接成一条，写到 [out]。
     *
     * [marks] 是每块接完之后的字节位置，末尾补一个总长。播放时拿它算这块念到哪儿：
     * 同一容器、同一采样格式下，字节比例就是时长比例。
     *
     * @return 接好的文件；只有一段或接不上时返回 null，调用方照原样播。
     */
    fun of(parts: List<Clip>, out: File, marks: MutableList<Long>? = null): File? {
        if (parts.size < 2) return null
        val bodies = ArrayList<ByteArray>(parts.size)
        val sizes = ArrayList<Int>(parts.size)
        for (p in parts) {
            val b = runCatching { p.file.readBytes() }.getOrNull() ?: return null
            if (b.size < 512) return null
            val body = stripTag(b)
            bodies.add(body)
            /* 头只留第一份，后面的头不计进时长。 */
            sizes.add(payload(body))
        }
        val merged = runCatching { Wav.join(bodies) }.getOrNull() ?: return null
        if (merged.size < 512) return null
        if (marks != null) {
            marks.clear()
            var at = 0L
            for (n in sizes) { at += n; marks.add(at) }
            marks.add(merged.size.toLong())
        }
        return runCatching {
            val tmp = File(out.parentFile, out.name + "." + System.nanoTime().toString(36) + ".part")
            tmp.writeBytes(merged)
            if (!tmp.renameTo(out)) {
                tmp.copyTo(out, true)
                tmp.delete()
            }
            out
        }.getOrNull()
    }

    /** 这一段里真正是音频的字节数。wav 取 data 块，别的取全长。 */
    private fun payload(b: ByteArray): Int {
        if (b.size < 12) return b.size
        if (b[0] != 'R'.code.toByte() || b[1] != 'I'.code.toByte()) return b.size
        var i = 12
        var guard = 0
        while (i + 8 <= b.size && guard++ < 64) {
            val id = String(b, i, 4, Charsets.US_ASCII)
            val n = (b[i + 4].toInt() and 0xFF) or ((b[i + 5].toInt() and 0xFF) shl 8) or
                ((b[i + 6].toInt() and 0xFF) shl 16) or ((b[i + 7].toInt() and 0xFF) shl 24)
            if (id == "data") {
                val real = if (n <= 0 || i + 8L + n > b.size) b.size - i - 8 else n
                return real
            }
            if (n < 0 || i + 8 + n > b.size) return b.size
            i += 8 + n + (n and 1)
        }
        return b.size
    }

    /* ID3 头只能出现在文件开头，接在中间会被当成一帧去解。 */
    private fun stripTag(b: ByteArray): ByteArray {
        if (b.size < 10) return b
        if (b[0] != 'I'.code.toByte() || b[1] != 'D'.code.toByte() || b[2] != '3'.code.toByte()) return b
        val size = (b[6].toInt() and 0x7F shl 21) or (b[7].toInt() and 0x7F shl 14) or
            (b[8].toInt() and 0x7F shl 7) or (b[9].toInt() and 0x7F)
        val head = 10 + size + if (b[5].toInt() and 0x10 != 0) 10 else 0
        if (head <= 0 || head >= b.size) return b
        return b.copyOfRange(head, b.size)
    }

    private fun fmtOf(f: File): ByteArray? = runCatching {
        val b = f.inputStream().use { s -> ByteArray(minOf(256L, f.length()).toInt()).also { s.read(it) } }
        if (b.size < 12) return null
        if (b[0] != 'R'.code.toByte() || b[1] != 'I'.code.toByte()) return null
        var i = 12
        var guard = 0
        while (i + 8 <= b.size && guard++ < 8) {
            val id = String(b, i, 4, Charsets.US_ASCII)
            val n = (b[i + 4].toInt() and 0xFF) or ((b[i + 5].toInt() and 0xFF) shl 8) or
                ((b[i + 6].toInt() and 0xFF) shl 16) or ((b[i + 7].toInt() and 0xFF) shl 24)
            if (id == "fmt ") {
                if (n <= 0 || i + 8 + n > b.size) return null
                return b.copyOfRange(i + 8, i + 8 + n)
            }
            if (n < 0 || i + 8 + n > b.size) return null
            i += 8 + n + (n and 1)
        }
        null
    }.getOrNull()
}
