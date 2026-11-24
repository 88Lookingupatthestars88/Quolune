package com.pagefold.reader.book

import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.util.zip.ZipInputStream
import kotlin.math.max

/** 一章：标题 + 在全文中的字符区间。[auto] = 这一章是自动分出来的，书里没有这行标题。 */
data class Chapter(
    val index: Int,
    val title: String,
    val start: Int,
    val end: Int,
    val auto: Boolean = false,
) {
    val length: Int get() = (end - start).coerceAtLeast(0)
}

/** 解析结果。正文不入内存，只留长度与章节表。 */
data class Parsed(val chars: Long, val chapters: List<Chapter>, val encoding: String)

/** 流式扫一遍的结果。字节偏移给阅读页直接定位用。 */
class Scanned(
    val chars: Long,
    val chapters: List<Chapter>,
    /** 每章的 UTF-8 字节起点，与 [chapters] 一一对应。 */
    val byteStarts: LongArray,
    /** 每章的 UTF-8 字节终点。 */
    val byteEnds: LongArray,
    val encoding: String,
)

/** 章节标题长什么样。 */
object ChapterHead {

    /** 数字、全角数字、中文数字。 */
    private const val NUM = "0-9０-９零〇一二三四五六七八九十百千万两"

    /** 「第 X 章」这种，后面跟的计量单位。 */
    private val VOLUME = listOf("章", "回", "节", "卷", "集", "部", "篇", "幕", "话")

    private val ALL: List<Regex> = buildList {
        for (u in VOLUME) {
            /* 章号与章名之间可能没有分隔符（「第345章幸福来的太突然」），所以不用强制要求。 */
            add(Regex("""^第\s*[$NUM]{1,9}\s*$u\s*.{0,40}$"""))
        }
        /* 序、尾声这一类：本身就是一个短词，后面可能跟个名字。 */
        add(Regex("""^(序章|序言|序幕|序|楔子|引子|引言|前言|凡例|尾声|后记|终章|大结局|完本感言|后序|附录)$"""))
        add(Regex("""^(序章|序言|序幕|楔子|引子|引言|前言|凡例|尾声|后记|终章|大结局|完本感言|后序|附录)[\s:：.、·　]+.{1,30}$"""))
        /* 番外、外传。 */
        add(Regex("""^(番外|外传|特别篇)$"""))
        add(Regex("""^(番外|外传|特别篇)[\s:：.、·　]*.{1,30}$"""))
        /* 西文。 */
        add(Regex("""^(chapter|part|volume|book|act|episode|section)\b.{0,60}$""", RegexOption.IGNORE_CASE))
        add(Regex("""^(prologue|epilogue|preface|interlude)\b.{0,60}$""", RegexOption.IGNORE_CASE))
        /* 罗马数字 + 名字。 */
        add(Regex("""^[IVXLCDM]{1,7}[.、．]?\s+\S.{0,40}$"""))
        /* 裸数字开头：1. ／ 1、／ 01 ／ 1) */
        add(Regex("""^\d{1,4}\s*[.、．:：)）]\s*\S.{0,40}$"""))
        add(Regex("""^\d{1,4}\s+\S.{0,40}$"""))
    }

    /** 标题里不该出现的句末标点。出现即不是标题。书名号、问号、叹号是章名常见的写法，不在此列。 */
    private const val NO_END = "。；」』"

    /** 这一行是不是章节标题。传进来的应当是去掉首尾空白的一行。 */
    fun isLine(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty() || t.length > 48) return false
        if (t.any { it in NO_END }) return false
        if (t.last() in "，,、") return false
        return ALL.any { it.matches(t) }
    }

    /** 是不是分卷标题。分卷后面常常紧接第一章，不能被当成碎块合掉。 */
    fun isVolume(s: String): Boolean =
        Regex("""^第\s*[$NUM]{1,9}\s*[卷集部篇幕]""").containsMatchIn(s.trim())

    /** 是不是书里真写着的章名。自动分块出来的标题不算。 */
    fun isTitle(s: String): Boolean = isLine(s) && !AUTO.matches(s.trim())

    /** 自动分块的占位标题。 */
    private val AUTO = Regex("""^第\s*\d+\s*节$""")

    /** 章名里带「第几章」的（这类架上只写章号，不写全名）。 */
    fun startsWithNo(s: String): Boolean =
        Regex("""^第\s*[$NUM]{1,9}\s*[章回节卷集部篇幕话]""").containsMatchIn(s)
}

object Parse {

    /** 自动分块的目标长度。整章读完再翻下一章，翻页次数少。 */
    private const val AUTO_TARGET = 15000

    /** 自动分块时向前找句末标点，最多回溯这么远。 */
    private const val AUTO_BACK = 2000

    /** 断句用的字。 */
    private const val STOP = "\n。！？…」』”"

    // ─────────── 判定 ───────────

    /**
     * 这一行是不是章节标题，连同位置约束。
     *
     * @param line      这一行（未 trim）。
     * @param prevBlank 上一行是不是空行。
     */
    private fun isHeadAt(line: String, prevBlank: Boolean): Boolean {
        if (!ChapterHead.isLine(line)) return false
        /* 标题要么另起一段，要么顶格写。正文里的引述句两头都不占。全角空格与不换行空格也算缩进。 */
        val noIndent = line.isNotEmpty() && line[0] !in INDENTS
        return prevBlank || noIndent
    }

    /** 缩进用的空白。有些源文件不转成半角空格，直接用全角。 */
    private const val INDENTS = " \t\u3000\u00A0"

    // ─────────── 载入 ───────────

    /** 读一本书并切好章。文件先就地转成 UTF-8。EPUB 例外，它本来就在内存里。 */
    fun load(file: File): Parsed {
        if (file.name.lowercase().endsWith(".epub")) {
            val txt = normalize(epub(file))
            val chs = split(txt)
            return Parsed(countChars(txt), chs, "EPUB")
        }
        val enc = Codec.rewrite(file).name
        val s = scan(file)
        return Parsed(s.chars, s.chapters, if (enc == "UTF-8") s.encoding else enc)
    }

    /**
     * 流式扫一遍已规范化的 UTF-8 文件，切章并计字数。不把全文读进内存。
     * 调用前文件必须已经过 [Codec.rewrite]。
     */
    fun scan(f: File): Scanned {

        val marks = ArrayList<Mark>(1024)

        var lineStartChar = 0L
        var lineStartByte = 0L
        var prevBlank = true

        /* 命中标题的行：字符起点、字节起点、标题。 */
        val hitsChar = ArrayList<Int>(1024)
        val hitsByte = ArrayList<Long>(1024)
        val hitsText = ArrayList<String>(1024)

        val line = java.io.ByteArrayOutputStream(1024)
        val buf = ByteArray(1 shl 16)

        RandomAccessFile(f, "r").use { raf ->
            while (true) {
                val fill = raf.read(buf)
                if (fill <= 0) break
                var start = 0
                for (k in 0 until fill) {
                    if (buf[k] != 0x0A.toByte()) continue
                    line.write(buf, start, k - start)
                    val s = String(line.toByteArray(), Charsets.UTF_8)
                    line.reset()
                    if (isHeadAt(s, prevBlank)) {
                        hitsChar.add(lineStartChar.toInt())
                        hitsByte.add(lineStartByte)
                        hitsText.add(s.trim().take(40))
                    }
                    prevBlank = s.isBlank()
                    lineStartChar += s.length + 1
                    lineStartByte += (k - start) + 1
                    start = k + 1
                }
                line.write(buf, start, fill - start)
            }
            if (line.size() > 0) {
                val s = String(line.toByteArray(), Charsets.UTF_8)
                if (isHeadAt(s, prevBlank)) {
                    hitsChar.add(lineStartChar.toInt())
                    hitsByte.add(lineStartByte)
                    hitsText.add(s.trim().take(40))
                }
                lineStartChar += s.length
                lineStartByte += line.size()
            }
        }

        if (hitsChar.isEmpty()) {
            /* 一个标题都没有：按长度分块。这时只能整本读一次来找断句点。 */
            val text = f.readText()
            val auto = autoBlocks(text)
            val outS = LongArray(auto.size)
            val outE = LongArray(auto.size)
            var prev = 0
            var b = 0L
            for (k in auto.indices) {
                val c = auto[k]
                if (c.start > prev) { b += utf8Len(text, prev, c.start); prev = c.start }
                outS[k] = b
                b += utf8Len(text, c.start, c.end)
                prev = c.end
                outE[k] = b
            }
            return Scanned(text.length.toLong(), auto, outS, outE, "UTF-8")
        }

        for (i in hitsChar.indices) {
            marks.add(Mark(hitsChar[i], hitsByte[i], hitsText[i]))
        }
        return cut(marks, lineStartChar.toInt(), lineStartByte, "UTF-8")
    }
    // ─────────── 切章（内存里的文本） ───────────

    /** 按标题行切章。切不出来就按长度分块。 */
    fun split(text: String): List<Chapter> {
        val lines = text.split('\n')
        val offsets = IntArray(lines.size)
        var acc = 0
        for (i in lines.indices) {
            offsets[i] = acc
            acc += lines[i].length + 1
        }

        val marks = ArrayList<Mark>()
        var prevBlank = true
        for (i in lines.indices) {
            val line = lines[i]
            if (isHeadAt(line, prevBlank)) {
                marks.add(Mark(offsets[i], 0L, line.trim().take(40)))
            }
            prevBlank = line.isBlank()
        }

        if (marks.isEmpty()) {
            if (text.length < AUTO_TARGET) return listOf(Chapter(0, "正文", 0, text.length))
            return autoBlocks(text).mapIndexed { i, c -> c.copy(index = i) }
        }

        val r = cut(marks, text.length, 0L, "UTF-8")
        return r.chapters
    }

    /** 一个标题行落在哪儿。 */
    private class Mark(val charAt: Int, val byteAt: Long, val title: String)

    /**
     * 把标题行列表变成章节表。三件事：第一个标题之前的当「序」，
     * 短的与重名的合并到上一章，分卷标题不合并。
     */
    private fun cut(marks: List<Mark>, totalChar: Int, totalByte: Long, enc: String): Scanned {
        val chs = ArrayList<Chapter>(marks.size + 1)
        val bs = ArrayList<Long>(marks.size + 1)
        val be = ArrayList<Long>(marks.size + 1)

        if (marks[0].charAt > 40) {
            chs.add(Chapter(0, "序", 0, marks[0].charAt))
            bs.add(0L)
            be.add(marks[0].byteAt)
        }

        for (i in marks.indices) {
            val st = marks[i].charAt
            val en = if (i + 1 < marks.size) marks[i + 1].charAt else totalChar
            val bst = marks[i].byteAt
            val ben = if (i + 1 < marks.size) marks[i + 1].byteAt else totalByte
            val last = chs.lastOrNull()
            val same = last != null && last.title == marks[i].title
            /* 这一块除了标题行本身没有别的字。误判的标题行后面紧跟下一个标题，就会是这个样子。 */
            val empty = en - st - marks[i].title.length - 1 <= 1
            if (last != null && !ChapterHead.isVolume(marks[i].title) && (same || empty)) {
                chs[chs.lastIndex] = last.copy(end = en)
                be[be.lastIndex] = ben
            } else {
                chs.add(Chapter(chs.size, marks[i].title, st, en))
                bs.add(bst)
                be.add(ben)
            }
        }
        if (chs.isEmpty()) return Scanned(0, listOf(Chapter(0, "正文", 0, 0)), LongArray(1), LongArray(1), enc)
        return Scanned(totalChar.toLong(), chs, bs.toLongArray(), be.toLongArray(), enc)
    }

    /**
     * 没有章节标题的书按长度分块。断口优先落在句末，落不下才硬切。
     * 块长在 [AUTO_TARGET] 上下浮动，最长不超过 +[AUTO_BACK]。
     */
    private fun autoBlocks(text: String): List<Chapter> {
        if (text.isEmpty()) return listOf(Chapter(0, "正文", 0, 0))
        if (text.length <= AUTO_TARGET) return listOf(Chapter(0, "正文", 0, text.length))
        val out = ArrayList<Chapter>(text.length / AUTO_TARGET + 2)
        var s = 0
        while (s < text.length) {
            var e = (s + AUTO_TARGET).coerceAtMost(text.length)
            if (e < text.length) {
                /* 从目标点往回找最近的句末；找不到就硬切。 */
                val floor = (e - AUTO_BACK).coerceAtLeast(s + AUTO_TARGET / 2)
                var cut = -1
                var i = e - 1
                while (i >= floor) {
                    if (text[i] in STOP) { cut = i + 1; break }
                    i--
                }
                if (cut > s) e = cut
            }
            val title = "第 ${out.size + 1} 节"
            out.add(Chapter(out.size, title, s, e, auto = true))
            s = e
        }
        return out
    }

    // ─────────── EPUB ───────────

    /** EPUB 只取 xhtml 的文字，按文件名排序拼起来。够用，不实现完整 OPF 解析。 */
    private fun epub(f: File): String {
        val entries = LinkedHashMap<String, ByteArray>()
        runCatching {
            FileInputStream(f).use { ins ->
                ZipInputStream(ins).use { zin ->
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
        }
        val sb = StringBuilder(1 shl 20)
        entries.entries.sortedBy { it.key }.forEach { (_, bytes) ->
            val html = Codec.text(bytes).first
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

    /** 统一换行、去零宽字符、压缩空行。EPUB 那条路用，文件那条在 [Codec.rewrite] 里做。 */
    fun normalize(s: String): String = s
        .replace("\r\n", "\n").replace('\r', '\n')
        .replace("\uFEFF", "")
        .replace("\u3000", "  ")
        .replace(Regex("[ \t]+\n"), "\n")
        .replace(Regex("\n{3,}"), "\n\n")

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

    // ─────────── 字符偏移 → 字节偏移 ───────────

    /** 从 [from] 到 [to] 这段在 UTF-8 下占多少字节。代理对按 4 字节算。 */
    fun utf8Len(s: String, from: Int, to: Int): Long {
        var n = 0L
        var i = from
        val end = to.coerceAtMost(s.length)
        while (i < end) {
            val c = s[i]
            n += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isHighSurrogate(c) -> 4
                else -> 3
            }
            i += if (Character.isHighSurrogate(c)) 2 else 1
        }
        return n
    }

    /** 每一章的字节起点。给已切好章的文本算，一次线性扫描。 */
    fun byteStarts(text: String, chapters: List<Chapter>): LongArray {
        val out = LongArray(chapters.size)
        var ci = 0
        var i = 0
        var b = 0L
        while (i < text.length && ci < chapters.size) {
            while (ci < chapters.size && chapters[ci].start <= i) { out[ci] = b; ci++ }
            if (ci >= chapters.size) break
            val c = text[i]
            b += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isHighSurrogate(c) -> 4
                else -> 3
            }
            i += if (Character.isHighSurrogate(c)) 2 else 1
        }
        while (ci < chapters.size) { out[ci] = b; ci++ }
        return out
    }
}
