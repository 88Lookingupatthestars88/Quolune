package com.pagefold.reader.book

import android.content.Context
import com.google.gson.JsonParser
import java.io.File

/**
 * 一句话：谁在书里的哪一处，说了什么。
 * @param ch 章号（0 起，跟 [Chapters] 那张表同一套编号）
 * @param at 章内偏移。指向引号里第一个字 —— 拿它就能回阅读页定位
 * @param text 引号里那一段，掐过 120 字。跳转用的是 [at]，不是它
 */
data class Quote(
    val ch: Int,
    val at: Int,
    val text: String,
)

/** 这本书里谁说了哪些话。和角色表分开存：角色表是用户的东西，句库是正文的索引，丢了能重建。 */
object Lines {

    /** 索引缓存。一本书一份，改完就换。 */
    private val head = HashMap<String, Map<String, Int>>()

    fun dir(ctx: Context, bookId: String): File = File(File(ctx.filesDir, "lines"), bookId)

    /** 这本书有没有建过句库。没有就得补扫一遍。 */
    fun has(ctx: Context, bookId: String): Boolean {
        val f = File(dir(ctx, bookId), "index.json")
        return f.exists() && f.length() > 2
    }

    fun drop(ctx: Context, bookId: String) {
        head.remove(bookId)
        runCatching { dir(ctx, bookId).deleteRecursively() }
    }

    /** 名字 → 总句数。索引里没有就是 0。 */
    fun count(ctx: Context, bookId: String, name: String): Int =
        index(ctx, bookId)[name] ?: 0

    fun index(ctx: Context, bookId: String): Map<String, Int> {
        head[bookId]?.let { return it }
        val f = File(dir(ctx, bookId), "index.json")
        val m = runCatching {
            val root = JsonParser.parseString(f.readText()).asJsonObject
            val out = HashMap<String, Int>(root.size() * 2)
            root.entrySet().forEach { e ->
                val n = runCatching { e.value.asInt }.getOrNull() ?: 0
                if (n > 0) out[e.key] = n
            }
            out
        }.getOrDefault(emptyMap())
        head[bookId] = m
        return m
    }

    private fun fileOf(ctx: Context, bookId: String, name: String): File =
        File(dir(ctx, bookId), Integer.toHexString(name.hashCode()) + ".jsonl")

    /** 这个人的全部话，按章、按位置排好。 */
    fun of(ctx: Context, bookId: String, name: String): List<Quote> {
        /* 先试二进制索引。旧书还没建索引，走 readLines 逐行读 —— 结果一样，只是慢一点。 */
        runCatching {
            val t = LineIndex.of(ctx, bookId, name)
            if (t != null) {
                val out = ArrayList<Quote>(t.first.size)
                for (i in t.first.indices) out.add(Quote(t.first[i], t.second[i], t.third[i]))
                return out
            }
        }
        val got = readLines(ctx, bookId, name)
        /* 顺手补一份索引。两件来由：一是旧书从来没建过；二是第一版的索引是坏的（偏移记的是字符下标，读出来一串 ��），读到就作废。 */
        if (got.isNotEmpty()) runCatching { LineIndex.write(ctx, bookId, name, got) }
        return got
    }

    /** 逐行读 jsonl。索引还没建的时候走这条。 */
    private fun readLines(ctx: Context, bookId: String, name: String): List<Quote> {
        val f = fileOf(ctx, bookId, name)
        if (!f.exists()) return emptyList()
        val out = ArrayList<Quote>(256)
        runCatching {
            f.bufferedReader().useLines { seq ->
                seq.forEach { ln ->
                    val a = ln.indexOf('\t')
                    if (a <= 0) return@forEach
                    val b = ln.indexOf('\t', a + 1)
                    if (b <= a) return@forEach
                    val ch = ln.substring(0, a).toIntOrNull() ?: return@forEach
                    val at = ln.substring(a + 1, b).toIntOrNull() ?: return@forEach
                    out += Quote(ch, at, ln.substring(b + 1))
                }
            }
        }
        out.sortWith(compareBy({ it.ch }, { it.at }))
        return out
    }

    // ── 建库 ────────────────────────────────────────────────

    /** 只读头几句。挑「发哪一句给他看」不需要他那三百句 —— 头五句里挑最长的那句就够。 */
    fun head(ctx: Context, bookId: String, name: String, n: Int = 5): List<Quote> {
        runCatching {
            val t = LineIndex.of(ctx, bookId, name)
            if (t != null) {
                val m = minOf(n, t.first.size)
                val out = ArrayList<Quote>(m)
                for (i in 0 until m) out.add(Quote(t.first[i], t.second[i], t.third[i]))
                return out
            }
        }
        val f = fileOf(ctx, bookId, name)
        if (!f.exists()) return emptyList()
        val out = ArrayList<Quote>(n)
        runCatching {
            f.bufferedReader().useLines { seq ->
                for (ln in seq) {
                    val a = ln.indexOf('\t')
                    if (a <= 0) continue
                    val b = ln.indexOf('\t', a + 1)
                    if (b <= a) continue
                    val ch = ln.substring(0, a).toIntOrNull() ?: continue
                    val at = ln.substring(a + 1, b).toIntOrNull() ?: continue
                    out += Quote(ch, at, ln.substring(b + 1))
                    if (out.size >= n) break
                }
            }
        }
        return out
    }

    /** 这个人的话，扣掉已改派的、加上改派给他的。 */
    fun view(ctx: Context, bookId: String, name: String, pins: List<Pin>): List<Quote> {
        val mine = of(ctx, bookId, name)
        val away = java.util.HashSet<Long>(8)
        pins.forEach { if (it.who != name) away.add(it.ch.toLong() * 100_000_000L + it.at) }
        val have = java.util.HashSet<Long>(mine.size * 2)
        val out = ArrayList<Quote>(mine.size + 8)
        mine.forEach { q ->
            val k = q.ch.toLong() * 100_000_000L + q.at
            have.add(k)
            if (!away.contains(k)) out += q
        }
        pins.forEach { p ->
            val k = p.ch.toLong() * 100_000_000L + p.at
            if (p.who == name && p.text.isNotBlank() && have.add(k)) {
                out += Quote(p.ch, p.at, p.text)
            }
        }
        out.sortWith(compareBy({ it.ch }, { it.at }))
        return out
    }

    /**
     * 扫一遍全书，把每个人的话都存下来。
     * @param names 要收的人。名单外的句子一律不存 —— 那本《大秦》里有上千个
     * @param onEach 每扫完一章回调一次，参数是（已完成章数、总章数）。界面拿它显示进度。
     * @return 存下来的总句数。
     */
    fun build(
        ctx: Context,
        bookId: String,
        text: String,
        chapters: List<Chapter>,
        names: List<String>,
        onEach: ((Int, Int) -> Unit)? = null,
    ): Int {
        val d = dir(ctx, bookId)
        if (!d.exists()) d.mkdirs()
        val namer = Namer(names)
        val buf = HashMap<String, StringBuilder>(names.size * 2)
        val cnt = HashMap<String, Int>(names.size * 2)

        fun flush() {
            buf.forEach { (who, sb) ->
                val f = fileOf(ctx, bookId, who)
                // 第一轮是新建，后面几轮追加 —— 每二十章刷一次，避免几十 MB 堆在堆里。
                runCatching {
                    java.io.FileOutputStream(f, f.exists()).use { o ->
                        o.write(sb.toString().toByteArray(Charsets.UTF_8))
                    }
                }
                sb.setLength(0)
            }
        }

        // 重扫：先清老文件，否则旧句子会跟着留下来。
        runCatching { d.listFiles()?.forEach { if (it.name.endsWith(".jsonl")) it.delete() } }

        val n = chapters.size
        chapters.forEachIndexed { i, c ->
            val a = c.start.coerceIn(0, text.length)
            val b = c.end.coerceIn(0, text.length)
            if (b > a) {
                scan(text, a, b, i, namer) { who, at, body ->
                    buf.getOrPut(who) { StringBuilder(1 shl 14) }
                        .append(i).append('\t').append(at).append('\t').append(body).append('\n')
                    cnt[who] = (cnt[who] ?: 0) + 1
                    Unit
                }
            }
            if (i % 20 == 19) flush()
            onEach?.invoke(i + 1, n)
        }
        flush()

        val idx = com.google.gson.JsonObject()
        cnt.forEach { (k, v) -> idx.addProperty(k, v) }
        runCatching { File(d, "index.json").writeText(idx.toString()) }
        head[bookId] = cnt.toMap()

        /* 顺手给每个人落一份二进制索引。 */
        runCatching {
            cnt.forEach { (who, _) ->
                /* 直接从刚写完的 jsonl 逐行读一遍，不经过 of()（它现在会先找索引）。 */
                LineIndex.write(ctx, bookId, who, readLines(ctx, bookId, who))
            }
        }
        return cnt.values.sum()
    }

    /**
     * 单独给一个人补扫。
     * @return 他的全部话（含原有的）。
     */
    fun addOne(
        ctx: Context,
        bookId: String,
        text: String,
        chapters: List<Chapter>,
        name: String,
    ): List<Quote> {
        if (name.isBlank()) return emptyList()
        val d = dir(ctx, bookId)
        if (!d.exists()) d.mkdirs()
        val namer = Namer(listOf(name))
        val sb = StringBuilder(1 shl 14)
        val got = ArrayList<Quote>(64)
        chapters.forEachIndexed { i, c ->
            val a = c.start.coerceIn(0, text.length)
            val b = c.end.coerceIn(0, text.length)
            if (b <= a) return@forEachIndexed
            scan(text, a, b, i, namer) { who, at, body ->
                if (who == name) {
                    sb.append(i).append('\t').append(at).append('\t').append(body).append('\n')
                    got += Quote(i, at, body)
                }
                Unit
            }
        }
        if (got.isEmpty()) return emptyList()
        // 合并：老的还在，新的按位置去重。
        val old = of(ctx, bookId, name).associateBy { it.ch to it.at }
        val merged = LinkedHashMap<Pair<Int, Int>, Quote>()
        old.forEach { (k, q) -> merged[k] = q }
        got.forEach { merged[it.ch to it.at] = it }
        runCatching {
            fileOf(ctx, bookId, name).bufferedWriter().use { w ->
                merged.values.sortedWith(compareBy({ it.ch }, { it.at })).forEach { q ->
                    w.write(q.ch.toString())
                    w.write("\t")
                    w.write(q.at.toString())
                    w.write("\t")
                    w.write(q.text)
                    w.write("\n")
                }
            }
        }
        val idx = index(ctx, bookId).toMutableMap()
        idx[name] = merged.size
        head[bookId] = idx
        runCatching {
            val o = com.google.gson.JsonObject()
            idx.forEach { (k, v) -> o.addProperty(k, v) }
            File(dir(ctx, bookId), "index.json").writeText(o.toString())
        }
        val done = merged.values.sortedWith(compareBy({ it.ch }, { it.at }))
        runCatching { LineIndex.write(ctx, bookId, name, done) }
        return done
    }

    // ── 扫 ──────────────────────────────────────────────────

    /** 引号对。只会往这三种开。 */
    private fun closeOf(c: Char): Char = when (c) {
        '\u201C' -> '\u201D'
        '\u300C' -> '\u300D'
        '\u300E' -> '\u300F'
        else -> '\u0000'
    }

    private fun isOpen(c: Char): Boolean = c == '\u201C' || c == '\u300C' || c == '\u300E'

    /** 在一章里找出「谁说了哪句话」。 */
    private fun scan(
        text: String,
        from: Int,
        to: Int,
        ch: Int,
        namer: Namer,
        emit: (who: String, at: Int, body: String) -> Unit,
    ) {
        var para = from
        var i = from
        while (i < to) {
            val c = text[i]
            if (c == '\n') { para = i + 1; i++; continue }
            if (!isOpen(c)) { i++; continue }
            val close = closeOf(c)
            var e = i + 1
            while (e < to && text[e] != close && text[e] != '\n') e++
            if (e - i > 2) {
                /* at 记的是引号里第一个非空字的位置。 */
                var s0 = i + 1
                while (s0 < e && (text[s0] == ' ' || text[s0] == '\u3000' || text[s0] == '\r')) s0++
                val lo = maxOf(para, i - 90)
                val cs = clauseStart(text, lo, i)
                var who = namer.at(text, cs, i)
                if (who == null) who = namer.at(text, maxOf(para, i - 140), i)
                if (who != null) {
                    val body = text.substring(i + 1, e)
                        .replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
                        .trim()
                    if (body.length >= 2) emit(who, s0, body.take(120))
                }
            }
            i = if (e < to && text[e] == close) e + 1 else e
        }
    }

    /** 从 [hi] 往回找最后一个分句的起点。分句按逗号、句号、问号、感叹号、分号切。 */
    private fun clauseStart(text: String, lo: Int, hi: Int): Int {
        var k = hi
        while (k > lo) {
            val c = text[k - 1]
            if (c == '，' || c == '。' || c == '！' || c == '？' || c == '；' ||
                c == ',' || c == ';' || c == '!' || c == '?'
            ) return k
            k--
        }
        return lo
    }

    /** 受话人标记：这些字后面那个名字不是主语。 */
    private val PREP = "对向朝给被把跟和与同"

    /** 名字查表。拿三个哈希表（二字 / 三字 / 四字）代替「每个位置试一遍 substring」。 */
    private class Namer(names: List<String>) {

        private val two = HashMap<Int, String>(names.size * 2)
        private val three = HashMap<Long, String>(names.size * 2)
        private val four = HashMap<Long, String>(names.size * 2)

        init {
            names.forEach { w ->
                when (w.length) {
                    2 -> two[w[0].code shl 16 or w[1].code] = w
                    3 -> three[
                        w[0].code.toLong() shl 32 or (w[1].code.toLong() shl 16) or w[2].code.toLong()
                    ] = w
                    4 -> four[
                        w[0].code.toLong() shl 48 or (w[1].code.toLong() shl 32) or
                            (w[2].code.toLong() shl 16) or w[3].code.toLong()
                    ] = w
                }
            }
        }

        private fun at2(t: String, p: Int): String? =
            if (p + 2 > t.length) null else two[t[p].code shl 16 or t[p + 1].code]

        private fun at3(t: String, p: Int): String? =
            if (p + 3 > t.length) null else three[
                t[p].code.toLong() shl 32 or (t[p + 1].code.toLong() shl 16) or t[p + 2].code.toLong()
            ]

        private fun at4(t: String, p: Int): String? =
            if (p + 4 > t.length) null else four[
                t[p].code.toLong() shl 48 or (t[p + 1].code.toLong() shl 32) or
                    (t[p + 2].code.toLong() shl 16) or t[p + 3].code.toLong()
            ]

        /** [lo, hi) 里第一个像名字的那一段。长名字优先。 */
        fun at(t: String, lo: Int, hi: Int): String? {
            var p = lo
            while (p < hi) {
                val c = t[p]
                if (c !in '\u4e00'..'\u9fa5') { p++; continue }
                // 前面是汉字而且不是「的」——这一处不是词的开头，跳过。
                if (p > 0) {
                    val prev = t[p - 1]
                    if (prev in '\u4e00'..'\u9fa5' && prev != '的') { p++; continue }
                    if (prev in PREP) { p++; continue }
                }
                val w = at4(t, p) ?: at3(t, p) ?: at2(t, p)
                if (w != null) {
                    // 名字后面紧跟逗号顿号 —— 那是呼语，不是主语。
                    val nx = if (p + w.length < t.length) t[p + w.length] else ' '
                    if (nx != '，' && nx != '、' && nx != ',') return w
                    p += w.length
                    continue
                }
                p++
            }
            return null
        }
    }
}

/** 单句改派。用户看见「这句其实是老王说的」，就把它从老李那儿划到老王名下。 */
data class Pin(
    val ch: Int,
    val at: Int,
    val who: String,
    val text: String = "",
)
