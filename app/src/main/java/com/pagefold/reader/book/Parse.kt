package com.pagefold.reader.book

import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.math.max

/** 一章：标题 + 在全文中的字符区间。 */
data class Chapter(val index: Int, val title: String, val start: Int, val end: Int) {
    val length: Int get() = (end - start).coerceAtLeast(0)
}

/** 解析结果。只保留章节索引，正文按需从文件切片。 */
data class Parsed(val text: String, val chapters: List<Chapter>, val encoding: String)

/** 章节标题长什么样。 */
object ChapterHead {

    /** 宁可多切，切错了还能靠「合并过短章节」补救。 */
    private val ALL = listOf(
        Regex("""^第[一二三四五六七八九十百千万零〇两0-9]{1,9}章[\s　]*.{0,40}$"""),
        Regex("""^第[一二三四五六七八九十百千万零〇两0-9]{1,9}回[\s　]*.{0,40}$"""),
        Regex("""^第[一二三四五六七八九十百千万零〇两0-9]{1,9}节[\s　]*.{0,40}$"""),
        Regex("""^Chapter\s+\d{1,4}.*$""", RegexOption.IGNORE_CASE),
        Regex("""^\d{1,4}[\s　、.．]\s*\S.{0,30}$"""),
    )

    /** 这一行是不是章节标题。传进来的应当是去掉首尾空白的一行。 */
    fun isLine(s: String): Boolean = s.isNotEmpty() && s.length <= 48 && ALL.any { it.matches(s) }

    /** 是不是书里真写着的章名。硬切出来的「第 N 节」不算。 */
    fun isTitle(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty() || t.length > 48) return false
        if (!isLine(t)) return false
        return !HARD.matches(t)
    }

    /** 硬切出来占位标题。 */
    private val HARD = Regex("""^第\s*\d+\s*节$""")

    /** 章名里带「第几章」的（这类架上只写章号，不写全名）。 */
    fun startsWithNo(s: String): Boolean =
        Regex("""^第\s*[0-9一二三四五六七八九十百千万零〇两]{1,9}\s*[章回节]""").containsMatchIn(s)
}

object Parse {

    private val HEAD = listOf(
        Regex("""^第[一二三四五六七八九十百千万零〇两0-9]{1,9}章[\s　]*.{0,40}$"""),
        Regex("""^第[一二三四五六七八九十百千万零〇两0-9]{1,9}回[\s　]*.{0,40}$"""),
        Regex("""^第[一二三四五六七八九十百千万零〇两0-9]{1,9}节[\s　]*.{0,40}$"""),
        Regex("""^Chapter\s+\d{1,4}.*$""", RegexOption.IGNORE_CASE),
        Regex("""^\d{1,4}[\s　、.．]\s*\S.{0,30}$"""),
    )

    /** 编码探测：BOM → UTF-8 严格校验 → GB18030 兜底。 */
    fun decode(raw: ByteArray): Pair<String, String> {
        if (raw.size >= 3 && raw[0] == 0xEF.toByte() && raw[1] == 0xBB.toByte() && raw[2] == 0xBF.toByte()) {
            return String(raw, 3, raw.size - 3, Charsets.UTF_8) to "UTF-8 BOM"
        }
        if (raw.size >= 2 && raw[0] == 0xFF.toByte() && raw[1] == 0xFE.toByte()) {
            return String(raw, 2, raw.size - 2, Charsets.UTF_16LE) to "UTF-16LE"
        }
        val utf8 = runCatching {
            val dec = Charsets.UTF_8.newDecoder()
            dec.decode(java.nio.ByteBuffer.wrap(raw)).toString()
        }.getOrNull()
        if (utf8 != null) return utf8 to "UTF-8"
        val gb = runCatching { String(raw, charset("GB18030")) }.getOrNull()
        if (gb != null) return gb to "GB18030"
        return String(raw, Charsets.ISO_8859_1) to "ISO-8859-1"
    }

    fun load(file: File): Parsed {
        val raw = file.readBytes()
        if (file.name.lowercase().endsWith(".epub")) {
            val txt = normalize(epub(raw))
            return Parsed(txt, split(txt), "EPUB")
        }
        val (decoded, enc) = decode(raw)
        val text = normalize(decoded)
        return Parsed(text, split(text), enc)
    }

    /** 统一换行、去零宽字符、压缩空行。 */
    fun normalize(s: String): String = s
        .replace("\r\n", "\n").replace('\r', '\n')
        .replace("\uFEFF", "")
        .replace("\u3000", "  ")
        .replace(Regex("[ \\t]+\\n"), "\n")
        .replace(Regex("\\n{3,}"), "\n\n")

    /** 按标题行切章。切不出来就按固定字数硬分。 */
    fun split(text: String): List<Chapter> {
        val lines = text.split('\n')
        val offsets = IntArray(lines.size)
        var acc = 0
        for (i in lines.indices) {
            offsets[i] = acc
            acc += lines[i].length + 1
        }
        val hits = ArrayList<Int>()
        lines.forEachIndexed { i, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.length > 48) return@forEachIndexed
            if (HEAD.any { it.matches(line) }) hits.add(i)
        }

        if (hits.size < 2) {
            // 没有章节标记：按 3000 字硬切
            if (text.length < 3000) {
                return listOf(Chapter(0, "正文", 0, text.length))
            }
            val out = ArrayList<Chapter>()
            var s = 0
            var n = 0
            while (s < text.length) {
                val e = (s + 3000).coerceAtMost(text.length)
                out.add(Chapter(n, "第 ${n + 1} 节", s, e))
                n++
                s = e
            }
            return out
        }

        val out = ArrayList<Chapter>()
        // 第一个标题之前的内容作为「序」
        if (hits.first() > 0 && offsets[hits.first()] > 40) {
            out.add(Chapter(0, "序", 0, offsets[hits.first()]))
        }
        hits.forEachIndexed { k, li ->
            val start = offsets[li]
            val end = if (k + 1 < hits.size) offsets[hits[k + 1]] else text.length
            val title = lines[li].trim().take(40)
            out.add(Chapter(out.size, title, start, end))
        }
        /* 合并没有真标题的碎块：硬切的「第 N 节」，或标题和上一章重复。 */
        val merged = ArrayList<Chapter>()
        for (c in out) {
            val last = merged.lastOrNull()
            val auto = !ChapterHead.isTitle(c.title)
            val same = last != null && last.title == c.title
            if (last != null && (auto || same) && c.length < 200) {
                merged[merged.lastIndex] = last.copy(end = c.end)
            } else {
                merged.add(Chapter(merged.size, c.title, c.start, c.end))
            }
        }
        return merged.ifEmpty { listOf(Chapter(0, "正文", 0, text.length)) }
    }

    // ─────────── EPUB ───────────

    /** EPUB 只取 xhtml 的文字，按文件名排序拼起来。够用，不实现完整 OPF 解析。 */
    private fun epub(raw: ByteArray): String {
        val entries = LinkedHashMap<String, ByteArray>()
        runCatching {
            ZipInputStream(ByteArrayInputStream(raw)).use { zin ->
                var e = zin.nextEntry
                while (e != null) {
                    val name = e.name.lowercase()
                    if (!e.isDirectory && (name.endsWith(".xhtml") || name.endsWith(".html") || name.endsWith(".htm"))) {
                        entries[e.name] = zin.readBytes()
                    }
                    e = zin.nextEntry
                }
            }
        }
        val sb = StringBuilder()
        entries.entries.sortedBy { it.key }.forEach { (name, bytes) ->
            val html = runCatching { String(bytes, Charsets.UTF_8) }.getOrDefault("")
            sb.append(stripHtml(html)).append("\n\n")
        }
        return if (sb.isBlank()) "" else normalize(sb.toString())
    }

    private fun stripHtml(html: String): String {
        var s = html
        s = s.replace(Regex("(?is)<(script|style|head)[^>]*>.*?</\\1>"), "")
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
        s = s.replace(Regex("(?i)</(p|div|h[1-6]|li)>"), "\n")
        s = s.replace(Regex("(?s)<[^>]+>"), "")
        s = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        return s.lines().joinToString("\n") { it.trim() }
    }

    /** 估算字数：汉字 + 英文单词。 */
    fun countChars(text: String): Long {
        var n = 0L
        var inWord = false
        for (c in text) {
            if (c.code in 0x4E00..0x9FFF || c.code in 0x3400..0x4DBF) {
                n++; inWord = false
            } else if (c.isLetterOrDigit()) {
                if (!inWord) { n++; inWord = true }
            } else {
                inWord = false
            }
        }
        return max(n, 1)
    }
}
