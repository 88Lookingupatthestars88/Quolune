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
