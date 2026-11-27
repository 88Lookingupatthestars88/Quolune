package com.pagefold.reader.voice

/** 按句切段、把几段 wav 接成一条。DeepSeek 与百炼都是「一段一句、回来是 wav」的口。 */
internal object Wav {

    /**
     * 把 RIFF 与 data 的长度按实际字节数重写一遍。
     *
     * 边合成边回的那几家，头里的长度是占位（0 或 0xFFFFFFFF）；播放器按头解析，
     * 读到 0 直接当坏文件。这里只改头，不动一个音频字节。
     */
    fun fix(bytes: ByteArray): ByteArray {
        if (bytes.size < 44) return bytes
        if (bytes[0] != 'R'.code.toByte() || bytes[1] != 'I'.code.toByte() ||
            bytes[2] != 'F'.code.toByte() || bytes[3] != 'F'.code.toByte()
        ) return bytes
        var i = 12
        var at = -1
        var guard = 0
        while (i + 8 <= bytes.size && guard++ < 64) {
            val id = String(bytes, i, 4, Charsets.US_ASCII)
            val size = le32(bytes, i + 4)
            if (id == "data") { at = i; break }
            if (size < 0 || i + 8 + size > bytes.size) return bytes
            i += 8 + size + (size and 1)
        }
        if (at < 0 || at + 8 >= bytes.size) return bytes
        val dataLen = bytes.size - (at + 8)
        val out = bytes.copyOf()
        putLe32(out, 4, out.size - 8)
        putLe32(out, at + 4, dataLen)
        return out
    }


    /** 归一化的目标电平。均方根，约合 −17 dBFS。 */
    private const val TARGET = 0.14f

    /** 峰值留的余量。顶到这儿就不再往上抬，免得削波。 */
    private const val CEIL = 0.97f

    /**
     * 把一段 16 位 PCM 的响度拉到同一个基准，再按 [pct] 增减。按均方根算增益，
     * 峰值顶到 [CEIL] 就不再抬。不是 16 位单/双声道 PCM 的原样返回。
     */
    fun level(bytes: ByteArray, pct: Int = 100): ByteArray {
        if (bytes.size < 64) return bytes
        if (bytes[0] != 'R'.code.toByte() || bytes[1] != 'I'.code.toByte()) return bytes

        var i = 12
        var dataAt = -1
        var dataLen = 0
        var channels = 0
        var bits = 0
        var format = -1
        var guard = 0
        while (i + 8 <= bytes.size && guard++ < 64) {
            val id = String(bytes, i, 4, Charsets.US_ASCII)
            val n = le32(bytes, i + 4)
            if (id == "fmt ") {
                if (i + 8 + 16 > bytes.size) return bytes
                format = (bytes[i + 8].toInt() and 0xFF) or ((bytes[i + 9].toInt() and 0xFF) shl 8)
                channels = (bytes[i + 10].toInt() and 0xFF) or ((bytes[i + 11].toInt() and 0xFF) shl 8)
                bits = (bytes[i + 22].toInt() and 0xFF) or ((bytes[i + 23].toInt() and 0xFF) shl 8)
            }
            if (id == "data") {
                dataAt = i + 8
                dataLen = if (n <= 0 || dataAt + n > bytes.size) bytes.size - dataAt else n
                break
            }
            if (n < 0 || i + 8 + n > bytes.size) return bytes
            i += 8 + n + (n and 1)
        }
        if (dataAt < 0 || dataLen < 32) return bytes
        if (format != 1 || bits != 16 || channels !in 1..2) return bytes

        /* 每八个样本取一个算均方根：一段几十万样本，全算一遍没必要。 */
        val step = channels * 2 * 8
        var sum = 0.0
        var n = 0
        var peak = 0
        var at = dataAt
        val end = dataAt + dataLen - 1
        while (at + 1 <= end) {
            val v = ((bytes[at].toInt() and 0xFF) or (bytes[at + 1].toInt() shl 8)).toShort().toInt()
            val a = if (v < 0) -v else v
            if (a > peak) peak = a
            sum += (v.toDouble() / 32768.0) * (v.toDouble() / 32768.0)
            n++
            at += step
        }
        if (n == 0 || peak == 0) return bytes
        val rms = Math.sqrt(sum / n).toFloat()
        if (rms < 1e-4f) return bytes

        val want = TARGET * pct.coerceIn(50, 200) / 100f
        var g = want / rms
        val cap = CEIL / (peak.toFloat() / 32768f)
        if (g > cap) g = cap
        g = g.coerceIn(0.05f, 6f)
        if (Math.abs(g - 1f) < 0.03f) return bytes

        val out = bytes.copyOf()
        var p = dataAt
        while (p + 1 < dataAt + dataLen) {
            var v = ((out[p].toInt() and 0xFF) or (out[p + 1].toInt() shl 8)).toShort().toInt()
            v = (v * g).toInt().coerceIn(-32768, 32767)
            out[p] = (v and 0xFF).toByte()
            out[p + 1] = ((v shr 8) and 0xFF).toByte()
            p += 2
        }
        return out
    }

    private fun le32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

    private fun putLe32(b: ByteArray, at: Int, v: Int) {
        b[at] = (v and 0xFF).toByte()
        b[at + 1] = ((v ushr 8) and 0xFF).toByte()
        b[at + 2] = ((v ushr 16) and 0xFF).toByte()
        b[at + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    /** 按句切成每段不超过 [max] 字。 */
    fun split(text: String, max: Int): List<String> {
        val s = text.trim()
        if (s.length <= max) return listOf(s)
        val out = ArrayList<String>(4)
        var from = 0
        while (from < s.length) {
            val room = s.length - from
            if (room <= max) { out.add(s.substring(from)); break }
            val end = from + max
            val floor = from + max / 3
            var cut = -1
            for (i in end downTo floor) {
                if (s[i - 1] in "。！？；…!?;") { cut = i; break }
            }
            if (cut < 0) {
                for (i in end downTo floor) {
                    if (s[i - 1] in "，、,：: ") { cut = i; break }
                }
            }
            if (cut < 0) cut = end
            out.add(s.substring(from, cut))
            from = cut
        }
        return out
    }

    /** 几段 wav 接成一条。不是 wav 的，顺着把字节接起来。 */
    fun join(parts: List<ByteArray>): ByteArray {
        if (parts.size == 1) return parts[0]
        if (parts.any { it.size < 44 || it[0] != 'R'.code.toByte() }) {
            val out = java.io.ByteArrayOutputStream()
            parts.forEach { out.write(it) }
            return out.toByteArray()
        }
        val fmt = fmtChunk(parts[0]) ?: run {
            val out = java.io.ByteArrayOutputStream()
            parts.forEach { out.write(it) }
            return out.toByteArray()
        }
        val body = java.io.ByteArrayOutputStream()
        parts.forEach { p -> dataChunk(p)?.let { body.write(it) } }
        val data = body.toByteArray()
        if (data.isEmpty()) return parts[0]

        // 44 = RIFF 头 12 + fmt 块 8+16 + data 头 8
        val total = 36 + fmt.size + 8 + data.size
        val head = java.io.ByteArrayOutputStream(fmt.size + 44)
        fun le32(v: Int) {
            head.write(v and 0xFF); head.write((v ushr 8) and 0xFF)
            head.write((v ushr 16) and 0xFF); head.write((v ushr 24) and 0xFF)
        }
        fun le16(v: Int) { head.write(v and 0xFF); head.write((v ushr 8) and 0xFF) }
        head.write("RIFF".toByteArray(Charsets.US_ASCII)); le32(total)
        head.write("WAVE".toByteArray(Charsets.US_ASCII))
        head.write("fmt ".toByteArray(Charsets.US_ASCII)); le32(fmt.size); head.write(fmt)
        head.write("data".toByteArray(Charsets.US_ASCII)); le32(data.size)
        val out = java.io.ByteArrayOutputStream(head.size() + data.size)
        out.write(head.toByteArray()); out.write(data)
        return out.toByteArray()
    }

    private fun chunk(b: ByteArray, want: String): ByteArray? {
        var i = 12
        var guard = 0
        while (i + 8 <= b.size && guard++ < 64) {
            val id = String(b, i, 4, Charsets.US_ASCII)
            val size = (b[i + 4].toInt() and 0xFF) or ((b[i + 5].toInt() and 0xFF) shl 8) or
                ((b[i + 6].toInt() and 0xFF) shl 16) or ((b[i + 7].toInt() and 0xFF) shl 24)
            /* 边合成边回的那几家，头里的长度是占位。data 块一律截到文件末尾。 */
            if (id == want) {
                val end = if (size <= 0 || i + 8 + size > b.size) b.size else i + 8 + size
                return b.copyOfRange(i + 8, end)
            }
            if (size < 0 || i + 8 + size > b.size) return null
            i += 8 + size + (size and 1)
        }
        return null
    }

    private fun fmtChunk(b: ByteArray): ByteArray? = chunk(b, "fmt ")

    private fun dataChunk(b: ByteArray): ByteArray? = chunk(b, "data")
}
