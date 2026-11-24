package com.pagefold.reader.book

import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** 编码识别与转码。判定一次，落成 UTF-8，之后所有读点只管 UTF-8。 */
object Codec {

    /** 判定结果。[bom] 是 BOM 的字节数，0 = 没有。 */
    data class Hit(val name: String, val charset: Charset, val bom: Int)

    /** 抽样块大小。 */
    private const val CHUNK = 64 * 1024

    /** 抽样块数。头尾各一块，中间均分。 */
    private const val PROBES = 8

    /** 中文里最常用的一批字。用来分「解对了」和「解成乱字」。 */
    private const val FREQ =
        "的一是了我不人在他有这个上们来到时大地为子中你说生国年着就那和要她出也得里后自以会家可下而过天" +
            "去能对小多然于心学么之都好看起发当没成只如事把还用第样道想作种开美总从无情己面最女但现前些所同" +
            "日手又行意动方期它头经长儿回位分爱老因很给名法间斯知世什两次使身者被高已亲其进此话常与活正感"

    private val FREQMASK = BooleanArray(0x10000).also { a -> FREQ.forEach { if (it.code < 0x10000) a[it.code] = true } }

    /** 无 BOM 时按这个顺序打分，取最高的。UTF-8 与 BOM 先单独判。 */
    private val CANDIDATES = listOf("GB18030", "Big5", "Shift_JIS")

    // ─────────── 判定 ───────────

    /** 看一份文件是什么编码。只抽样读几段，不整本进内存。 */
    fun sniff(f: File): Hit {
        val size = f.length()
        if (size <= 0) return Hit("UTF-8", Charsets.UTF_8, 0)

        /* BOM 优先，它比任何猜测都硬。 */
        val head = readAt(f, 0, 4)
        bomHit(head)?.let { return it }

        val spots = if (size <= CHUNK) longArrayOf(0L)
        else LongArray(PROBES) { (size - CHUNK) * it / (PROBES - 1) }

        val blocks = spots.map { readAt(f, it, CHUNK) }.filter { it.isNotEmpty() }
        if (blocks.isEmpty()) return Hit("UTF-8", Charsets.UTF_8, 0)

        /* 严格 UTF-8 全过，就是 UTF-8。Big5 与 GBK 的字节对极少凑成合法 UTF-8。 */
        if (blocks.all { strictOk(it, Charsets.UTF_8, 0) }) return Hit("UTF-8", Charsets.UTF_8, 0)

        /* 没有 BOM 的 UTF-16：零字节成片且落在固定奇偶位。 */
        utf16Guess(blocks)?.let { return it }

        var best = Hit("ISO-8859-1", Charsets.ISO_8859_1, 0)
        var bestScore = Double.NEGATIVE_INFINITY
        for (name in CANDIDATES) {
            val cs = runCatching { Charset.forName(name) }.getOrNull() ?: continue
            val s = blocks.map { score(it, cs, 0) }.average()
            if (s > bestScore) { bestScore = s; best = Hit(name, cs, 0) }
        }
        /* 全是乱字（分数低到地板）时退回 ISO-8859-1，至少不崩。 */
        return if (bestScore < 0.05) Hit("ISO-8859-1", Charsets.ISO_8859_1, 0) else best
    }

    private fun bomHit(h: ByteArray): Hit? {
        fun at(i: Int): Int = if (i < h.size) h[i].toInt() and 0xFF else -1
        if (at(0) == 0xEF && at(1) == 0xBB && at(2) == 0xBF) return Hit("UTF-8 BOM", Charsets.UTF_8, 3)
        if (at(0) == 0xFF && at(1) == 0xFE && at(2) == 0x00 && at(3) == 0x00) {
            val cs = runCatching { Charset.forName("UTF-32LE") }.getOrNull()
            if (cs != null) return Hit("UTF-32LE", cs, 4)
        }
        if (at(0) == 0x00 && at(1) == 0x00 && at(2) == 0xFE && at(3) == 0xFF) {
            val cs = runCatching { Charset.forName("UTF-32BE") }.getOrNull()
            if (cs != null) return Hit("UTF-32BE", cs, 4)
        }
        if (at(0) == 0xFF && at(1) == 0xFE) return Hit("UTF-16LE", Charsets.UTF_16LE, 2)
        if (at(0) == 0xFE && at(1) == 0xFF) return Hit("UTF-16BE", Charsets.UTF_16BE, 2)
        return null
    }

    /** 没有 BOM 的 UTF-16。零字节占到三成以上、且集中在同一侧，才认。 */
    private fun utf16Guess(blocks: List<ByteArray>): Hit? {
        var zeroEven = 0L
        var zeroOdd = 0L
        var total = 0L
        for (b in blocks) {
            var i = 0
            while (i + 1 < b.size) {
                if (b[i].toInt() == 0) zeroEven++
                if (b[i + 1].toInt() == 0) zeroOdd++
                i += 2
            }
            total += b.size / 2
        }
        if (total == 0L) return null
        val e = zeroEven.toDouble() / total
        val o = zeroOdd.toDouble() / total
        return when {
            o > 0.35 && e < 0.05 -> Hit("UTF-16LE", Charsets.UTF_16LE, 0)
            e > 0.35 && o < 0.05 -> Hit("UTF-16BE", Charsets.UTF_16BE, 0)
            else -> null
        }
    }

    /** 严格解码能不能全过。段首段尾可能截断一个多字节字符，各让 3 字节。 */
    private fun strictOk(body: ByteArray, cs: Charset, bom: Int): Boolean {
        val b = if (bom in 1..body.size) body.copyOfRange(bom, body.size) else body
        if (b.isEmpty()) return true
        val dec = cs.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        for (s in 0..3) {
            if (s >= b.size) break
            for (cut in 0..3) {
                val n = b.size - s - cut
                if (n <= 0) continue
                runCatching {
                    dec.reset()
                    dec.decode(java.nio.ByteBuffer.wrap(b, s, n))
                    return true
                }
            }
        }
        return false
    }

    /** 宽松解一遍，按「汉字 + 常用字 - 乱码」打分。 */
    private fun score(bytes: ByteArray, cs: Charset, bom: Int): Double {
        val b = if (bom in 1..bytes.size) bytes.copyOfRange(bom, bytes.size) else bytes
        if (b.isEmpty()) return 0.0
        val s = runCatching { String(b, cs) }.getOrNull() ?: return -1.0
        var good = 0
        var bad = 0
        for (c in s) {
            when {
                c == '\uFFFD' -> bad++
                c.code < 0x20 && c != '\n' && c != '\r' && c != '\t' -> bad++
                c.code in 0x4E00..0x9FFF -> good += if (FREQMASK[c.code]) 2 else 1
                c.code in 0x3400..0x4DBF -> good++
            }
        }
        return (good - 3.0 * bad) / s.length
    }

    private fun readAt(f: File, off: Long, len: Int): ByteArray {
        val n = minOf(len.toLong(), maxOf(0L, f.length() - off)).toInt()
        if (n <= 0) return ByteArray(0)
        val b = ByteArray(n)
        RandomAccessFile(f, "r").use { it.seek(off); it.readFully(b) }
        return b
    }

    // ─────────── 转码 ───────────

    /** 转码结果。[changed] = 文件被重写过，原有的章节索引作废。 */
    data class Done(val name: String, val changed: Boolean)

    /**
     * 把文件就地转成 UTF-8 并规范化（换行统一、去 BOM、压空行）。
     * 先写到同目录的 .part，成功之后才换名；中途失败原文件不动。
     */
    fun rewrite(f: File): Done {
        val hit = sniff(f)
        if (hit.name == "UTF-8" && !needsNorm(f)) return Done("UTF-8", false)

        val tmp = File(f.parentFile, f.name + ".part")
        val dec = hit.charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val norm = Norm()
        val out = StringBuilder(1 shl 17)
        try {
            java.io.BufferedWriter(
                java.io.OutputStreamWriter(java.io.FileOutputStream(tmp), Charsets.UTF_8),
                1 shl 16,
            ).use { w ->
                FileInputStream(f).use { ins ->
                    InputStreamReader(ins, dec).use { r ->
                        val cb = CharArray(1 shl 15)
                        while (true) {
                            val n = r.read(cb)
                            if (n <= 0) break
                            for (i in 0 until n) norm.feed(cb[i], out)
                            if (out.length >= (1 shl 16)) {
                                w.append(out)
                                out.setLength(0)
                            }
                        }
                    }
                }
                norm.done(out)
                if (out.isNotEmpty()) w.append(out)
                out.setLength(0)
            }
        } catch (e: Throwable) {
            runCatching { tmp.delete() }
            throw e
        }
        if (!tmp.renameTo(f)) {
            tmp.copyTo(f, true)
            tmp.delete()
        }
        return Done(hit.name, true)
    }

    /**
     * 文件里还有没有需要规范化的东西：裸 CR、全角空格、零宽字符、BOM。
     * 已经规范化过的文件不用再写一遍 —— 这一步在每次开书时都要走一趟。
     */
    private fun needsNorm(f: File): Boolean {
        FileInputStream(f).use { ins ->
            val b = ByteArray(1 shl 16)
            var p1 = -1
            var p2 = -1
            while (true) {
                val n = ins.read(b)
                if (n <= 0) return false
                for (i in 0 until n) {
                    val v = b[i].toInt() and 0xFF
                    if (v == 0x0D) return true
                    /* 三字节序列逐个比，不看首字节就判 —— 中文每个字的首字节都在 E2..EF。 */
                    if (p2 == 0xE3 && p1 == 0x80 && v == 0x80) return true
                    if (p2 == 0xE2 && p1 == 0x80 && (v in 0x8B..0x8D)) return true
                    if (p2 == 0xEF && p1 == 0xBB && v == 0xBF) return true
                    p2 = p1
                    p1 = v
                }
            }
        }
    }

    /** 一小段字节的内存版，EPUB 的成员文件用。 */
    fun text(bytes: ByteArray): Pair<String, String> {
        val hit = bomOf(bytes) ?: run {
            if (strictOk(bytes, Charsets.UTF_8, 0)) Hit("UTF-8", Charsets.UTF_8, 0)
            else {
                var best = Hit("ISO-8859-1", Charsets.ISO_8859_1, 0)
                var bestScore = Double.NEGATIVE_INFINITY
                for (name in CANDIDATES) {
                    val cs = runCatching { Charset.forName(name) }.getOrNull() ?: continue
                    val s = score(bytes, cs, 0)
                    if (s > bestScore) { bestScore = s; best = Hit(name, cs, 0) }
                }
                best
            }
        }
        val body = if (hit.bom in 1..bytes.size) bytes.copyOfRange(hit.bom, bytes.size) else bytes
        return (runCatching { String(body, hit.charset) }.getOrDefault("")) to hit.name
    }

    private fun bomOf(h: ByteArray): Hit? = bomHit(h)
}

/**
 * 流式规范化。换行统一成 \n、去 BOM、全角空格换两个半角、行尾空白删掉、连续空行压到一个。
 * 逐字符喂，跨块边界不会出错。
 */
private class Norm {
    private var skipLF = false
    /** 挂起的空行数，上限 1（也就是最多两个连续换行）。 */
    private var blank = 0
    private var written = false
    private val line = StringBuilder(512)

    fun feed(c: Char, out: StringBuilder) {
        if (skipLF) {
            skipLF = false
            if (c == '\n') return
        }
        when (c) {
            '\r' -> { skipLF = true; endLine(out) }
            '\n' -> endLine(out)
            '\uFEFF' -> {}
            /* 零宽连接符这一类：复制粘贴带进来的，肉眼看不见，却会把「第一章」切碎。 */
            '\u200B', '\u200C', '\u200D', '\u2060' -> {}
            '\u3000' -> { line.append(' ').append(' ') }
            else -> line.append(c)
        }
    }

    fun done(out: StringBuilder) {
        endLine(out)
        line.setLength(0)
    }

    private fun endLine(out: StringBuilder) {
        var e = line.length
        while (e > 0 && (line[e - 1] == ' ' || line[e - 1] == '\t')) e--
        if (e == 0) {
            line.setLength(0)
            if (written && blank < 1) blank++
            return
        }
        if (written) {
            repeat(blank) { out.append('\n') }
            out.append('\n')
        }
        blank = 0
        out.append(line, 0, e)
        line.setLength(0)
        written = true
    }
}
