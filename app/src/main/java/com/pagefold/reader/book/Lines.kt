package com.pagefold.reader.book

import android.content.Context
import com.google.gson.JsonParser
import java.io.File

/**
* 一句话：谁在书里的哪一处，说了什么。
*
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

    /** 「没认出是谁说的」那一格。用控制字符当键：正文里的名字不可能带它，所以它不会跟任何一个角色撞。 */
    const val UNSURE = "\u0001"

    /** 这一格在界面上叫什么。 */
    fun label(name: String): String = if (name == UNSURE) "没认出是谁说的" else name

    /**
* 句库的版本。认人的判据、存法一改就加一 —— 老书进书架会重扫。
*/
    const val LINES_VER = 13

    /** 句库改过几次。界面拿它当 remember 的键：重扫完、删书、补建都要重算一次「没认出还有几句」。 */
    val tick = androidx.compose.runtime.mutableIntStateOf(0)

    /** index.json 里记「建库时名单上都有谁」的那一格。下划线打头，不和名字撞。 */
    private const val NAMES = "_names"

    /** 索引缓存。一本书一份，改完就换。 */
    private val head = HashMap<String, Map<String, Int>>()

    fun dir(ctx: Context, bookId: String): File = File(File(ctx.filesDir, "lines"), bookId)


    /**
* 这本书有没有建过句库。
*
* @param names 传了就还要看这些人建库时在不在
*/
    fun has(ctx: Context, bookId: String, names: Collection<String>? = null): Boolean {
        val d = dir(ctx, bookId)
        val f = File(d, "index.json")
        if (!f.exists() || f.length() <= 2) return false
        val v = runCatching { File(d, "ver").readText().trim().toInt() }.getOrNull() ?: 0
        if (v != LINES_VER) return false
        if (names == null) return true
        val built = runCatching {
            JsonParser.parseString(f.readText()).asJsonObject.getAsJsonArray(NAMES)
                ?.mapNotNull { runCatching { it.asString }.getOrNull() }
                ?.toHashSet()
        }.getOrNull() ?: return false
        return names.all { it.isBlank() || it in built }
    }


    fun drop(ctx: Context, bookId: String) {
        head.remove(bookId)
        chap.clear()
        tick.intValue++
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
                if (e.key.startsWith("_")) return@forEach
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
        /* 顺手补一份索引。缺索引、或索引读不出来的，按 jsonl 重建。 */
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
*
* @param cb 这本书的角色表：名单、题材包、性别、自称投票都从它来
* @param onEach 每扫完一章回调一次（已完成章数、总章数）
* @return 存下来的总句数
*/
    fun build(
        ctx: Context,
        bookId: String,
        text: String,
        chapters: List<Chapter>,
        cb: CastBook,
        /** 用户手划过的句子。重扫时照旧归他，不然划过的那几千句会退回「没认出」。 */
        pins: List<Pin> = emptyList(),
        onEach: ((Int, Int) -> Unit)? = null,
    ): Int {
        val d = dir(ctx, bookId)
        if (!d.exists()) d.mkdirs()
        /* 重扫：先清老文件，否则旧句子会跟着留下来。二进制索引也得清 —— 留着的话，读的人先读到旧账。 */
        runCatching {
            d.listFiles()?.forEach { if (it.name.endsWith(".jsonl") || it.name.endsWith(".idx")) it.delete() }
        }

        val names = namesOf(cb)
        /* 认人用全量名单，落库才按当前名单收口。 */
        val every = allNamesOf(cb)
        val live = names.toHashSet()
        /* 在名单上的人不算删掉：off 里可能还留着他，但名单是更晚的一次操作。 */
        val banned = cb.off.filter { it !in live }.toHashSet()
        /* 先投票：这本书里谁说「朕」、谁说「妾身」。投完才谈得上判人。 */
        val self = voteSelf(text, chapters, every)
        runCatching {
            val o = com.google.gson.JsonObject()
            self.forEach { (k, v) -> o.addProperty(k, v) }
            File(d, "self.json").writeText(o.toString())
        }
        selfMem[bookId] = self

        val prof = profileOf(cb, every, self, includeOff = true)
        val buf = HashMap<String, StringBuilder>(names.size * 2)
        val cnt = HashMap<String, Int>(names.size * 2)

        fun flush() {
            buf.forEach { (who, sb) ->
                val f = fileOf(ctx, bookId, who)
                runCatching {
                    java.io.FileOutputStream(f, f.exists()).use { o ->
                        o.write(sb.toString().toByteArray(Charsets.UTF_8))
                    }
                }
                sb.setLength(0)
            }
        }

        val n = chapters.size
        chapters.forEachIndexed { i, c ->
            val a0 = c.start.coerceIn(0, text.length)
            val b0 = c.end.coerceIn(0, text.length)
            if (b0 > a0) {
                val body = text.substring(a0, b0)
                val pinAt = pins.filter { it.ch == i }.associate { it.at to it.who }
                Cast.plan(body, prof, 0, pinAt).forEach { ln ->
                    if (!ln.isDialog || ln.srcStart < 0) return@forEach
                    /* 拟声、单字应声不是话，谁说的都不记 —— 混进来只会把真句子埋掉。 */
                    if (isNoise(ln.text)) return@forEach
                    /* 删掉的人（在 off 里）的话进「没认出」—— 名单上已经没有他，这句没人接。 */
                    val got0 = ln.speaker.ifBlank { UNSURE }
                    val who = if (got0 in banned) UNSURE else got0
                    buf.getOrPut(who) { StringBuilder(1 shl 12) }
                        .append(i).append('\t').append(ln.srcStart).append('\t')
                        .append(ln.text.take(120).replace('\t', ' ')).append('\n')
                    cnt[who] = (cnt[who] ?: 0) + 1
                }
            }
            if (i % 20 == 19) flush()
            onEach?.invoke(i + 1, n)
        }
        flush()

        val idx = com.google.gson.JsonObject()
        cnt.forEach { (k, v) -> idx.addProperty(k, v) }
        val arr = com.google.gson.JsonArray()
        names.forEach { arr.add(it) }
        idx.add(NAMES, arr)
        runCatching { File(d, "index.json").writeText(idx.toString()) }
        runCatching { File(d, "ver").writeText(LINES_VER.toString()) }
        head[bookId] = cnt.toMap()
        /* 老账作废：按章分好的那份是在旧句库上算的。 */
        chap.clear()
        tick.intValue++

        /* 顺手给每个人落一份二进制索引。 */
        runCatching {
            cnt.forEach { (who, _) -> LineIndex.write(ctx, bookId, who, readLines(ctx, bookId, who)) }
        }
        return cnt.values.sum()
    }

    /** 名单：勾上的人；一个都没勾就用角色表里那几个。 */
    /** 这本书数出来过的全部名字：勾上的、没勾的、删掉的，连别名。 */
    private fun allNamesOf(cb: CastBook): List<String> {
        val out = LinkedHashSet<String>(cb.pool.size * 3 + cb.off.size)
        cb.pool.forEach { n ->
            if (n.name.isNotBlank()) out += n.name
            n.alt.forEach { if (it.isNotBlank()) out += it }
        }
        cb.roles.forEach { r ->
            if (r.name.isNotBlank()) out += r.name
            r.alt.forEach { if (it.isNotBlank()) out += it }
        }
        cb.off.forEach { if (it.isNotBlank()) out += it }
        return out.toList()
    }

    /** 当前认的这批人：勾上的优先，一个都没勾就看角色表，再不行就把名单整份算上。 */
    private fun namesOf(cb: CastBook): List<String> {
        val on = cb.pool.filter { it.on }.map { it.name }.filter { it.isNotBlank() }
        if (on.isNotEmpty()) return on
        val roles = cb.roles.map { it.name }.filter { it.isNotBlank() }
        if (roles.isNotEmpty()) return roles
        return cb.pool.map { it.name }.filter { it.isNotBlank() }
    }

    /** 投票：谁说「朕」。 */
    private fun voteSelf(
        text: String,
        chapters: List<Chapter>,
        names: List<String>,
    ): Map<String, String> {
        if (names.isEmpty()) return emptyMap()
        val namer = Namer(names)
        val box = HashMap<String, HashMap<String, Int>>()
        chapters.forEach { c ->
            val end = c.end.coerceIn(0, text.length)
            var i = c.start.coerceIn(0, text.length)
            while (i < end) {
                if (!isOpen(text[i])) { i++; continue }
                val close = closeOf(text[i])
                var e = i + 1
                while (e < end && text[e] != close && text[e] != '\n') e++
                if (e - i > 2) {
                    val q = text.substring(i + 1, e).trimStart('\u3000', ' ')
                    val tag = Words.selfCalls.firstOrNull { q.startsWith(it) }
                    if (tag != null) {
                        val who = namer.at(text, c.start.coerceIn(0, text.length), i)
                        if (who != null) {
                            val m = box.getOrPut(tag) { HashMap() }
                            m[who] = (m[who] ?: 0) + 1
                        }
                    }
                }
                i = if (e < end && text[e] == close) e + 1 else e
            }
        }
        val out = HashMap<String, String>()
        box.forEach { (tag, m) ->
            val total = m.values.sum()
            val best = m.maxByOrNull { it.value } ?: return@forEach
            if (total >= 3 && best.value * 2 >= total) out[tag] = best.key
        }
        return out
    }

    /** 做一份最小可用的角色表剖面。嗓子这里用不上，留空。 */
    private fun profileOf(
        cb: CastBook,
        names: List<String>,
        self: Map<String, String>,
        /** 建句库时为 true：删掉的人也要认得出来，否则他那几句会被兜底链送给别人。 */
        includeOff: Boolean = false,
    ): CastProfile {
        val off = if (includeOff) emptySet() else cb.off.toSet()
        /* 别名映射从整份名单建：勾掉的人的表也得在，不然他的别名会被判成别人。 */
        val canon = HashMap<String, String>(names.size * 2)
        cb.pool.forEach { n ->
            n.alt.forEach { if (it.isNotBlank() && it !in off) canon[it] = n.name }
        }
        cb.roles.forEach { r ->
            r.alt.forEach { if (it.isNotBlank() && it !in off) canon[it] = r.name }
        }
        val ban = Ban(
            names = names.filter { it !in off },
            gender = cb.sex,
            packs = cb.packs,
            extraVerb = cb.extraVerb,
            extraAdv = cb.extraAdv,
            extraTitle = cb.extraTitle,
            off = cb.off,
            canon = canon,
            self = self,
        )
        return CastProfile(
            narratorVoice = "",
            defaultVoice = "",
            rules = cb.active(),
            ban = if (ban.ok) ban else null,
        )
    }

    /** 这本书的自称表（谁说「朕」）。没建过就是空表。 */
    fun selfOf(ctx: Context, bookId: String): Map<String, String> {
        selfMem[bookId]?.let { return it }
        val f = File(dir(ctx, bookId), "self.json")
        val m = runCatching {
            val root = JsonParser.parseString(f.readText()).asJsonObject
            val out = HashMap<String, String>(root.size() * 2)
            root.entrySet().forEach { e -> out[e.key] = e.value.asString }
            out
        }.getOrDefault(emptyMap())
        selfMem[bookId] = m
        return m
    }

    private val selfMem = HashMap<String, Map<String, String>>()

    /**
* 单独给一个人补扫。
*
* @return 他的全部话（含原有的）。
*/
    /**
* 把一个人从句库里摘掉：他的话并进「没认出」，他那两份文件删掉。
*
* @return 并进「没认出」的句数
*/
    fun evict(ctx: Context, bookId: String, name: String): Int {
        if (name.isBlank() || name == UNSURE) return 0
        if (!dir(ctx, bookId).exists()) return 0
        val mine = readLines(ctx, bookId, name)
        runCatching { fileOf(ctx, bookId, name).delete() }
        LineIndex.drop(ctx, bookId, name)
        if (mine.isNotEmpty()) {
            runCatching {
                java.io.BufferedOutputStream(
                    java.io.FileOutputStream(fileOf(ctx, bookId, UNSURE), true),
                ).use { o ->
                    mine.forEach { q ->
                        val ln = q.ch.toString() + '\t' + q.at + '\t' + q.text.replace('\t', ' ') + '\n'
                        o.write(ln.toByteArray(Charsets.UTF_8))
                    }
                }
            }
            LineIndex.drop(ctx, bookId, UNSURE)
        }
        tally(ctx, bookId, name, mine.size)
        head.remove(bookId)
        chap.clear()
        tick.intValue++
        return mine.size
    }

    /** 改 index.json：他的话记进「没认出」，名字从建库名单里摘掉。 */
    private fun tally(ctx: Context, bookId: String, name: String, n: Int) {
        val f = File(dir(ctx, bookId), "index.json")
        val root = runCatching { JsonParser.parseString(f.readText()).asJsonObject }
            .getOrElse { com.google.gson.JsonObject() }
        if (n > 0) root.addProperty(UNSURE, (runCatching { root.get(UNSURE).asInt }.getOrNull() ?: 0) + n)
        root.remove(name)
        val arr = com.google.gson.JsonArray()
        runCatching {
            root.getAsJsonArray(NAMES)?.forEach { e ->
                runCatching { e.asString }.getOrNull()?.let { if (it != name) arr.add(it) }
            }
        }
        root.add(NAMES, arr)
        runCatching { f.writeText(root.toString()) }
    }

    /** 补扫一个人，返回他的全部台词。 */
    fun addOne(
        ctx: Context,
        bookId: String,
        text: String,
        chapters: List<Chapter>,
        cb: CastBook,
        name: String,
    ): List<Quote> {
        if (name.isBlank()) return emptyList()
        build(ctx, bookId, text, chapters, cb, cb.pins)
        return of(ctx, bookId, name)
    }

    /** 按章分开。一本书一份，改完就换。 */
    fun byChapter(ctx: Context, bookId: String, name: String, pins: List<Pin>):
        java.util.SortedMap<Int, List<Quote>> {
        val k = bookId + "|" + name + "|" + pins.hashCode()
        chap[k]?.let { return it }
        val out = java.util.TreeMap<Int, List<Quote>>()
        val cur = ArrayList<Quote>(32)
        var ch = -1
        view(ctx, bookId, name, pins).forEach { q ->
            if (q.ch != ch) {
                if (cur.isNotEmpty()) out[ch] = cur.toList()
                cur.clear()
                ch = q.ch
            }
            cur += q
        }
        if (cur.isNotEmpty()) out[ch] = cur.toList()
        /* 空表不进缓存。句库还没建完的时候问一次「没认出有几句」，拿到的就是空的； */
        if (out.isNotEmpty()) {
            chap[k] = out
            while (chap.size > 6) chap.remove(chap.keys.first())
        }
        return out
    }

    private val chap = object : LinkedHashMap<String, java.util.SortedMap<Int, List<Quote>>>(8, 0.75f, true) {}

    /** 「没认出」还剩多少句。读了 [byChapter]，划一句不会重走全书。 */
    fun unsureCount(ctx: Context, bookId: String, pins: List<Pin>): Int =
        byChapter(ctx, bookId, UNSURE, pins).values.sumOf { it.size }

    /** 一句的前文：从这句往前切到上一个句号。 */
    fun before(text: String, at: Int, max: Int = 60): String {
        if (text.isEmpty() || at <= 0) return ""
        val lo = maxOf(0, at - max * 2)
        var s = text.substring(lo, at.coerceAtMost(text.length)).replace('\n', ' ')
        val cut = s.indexOfLast { it in "。！？；\n" }
        if (cut >= 0) s = s.substring(cut + 1)
        return s.trim().take(max)
    }

    /**
* 这一句所在的那一段，按段切（换行），说白那句话前后的原文都在。
*
* @param abs 这一句在全书里的绝对位置（章起点 + 句库里的 at）
* @param say 这一句本身。找得到就用它定位，找不到就按偏移硬切
* @param max 前后各留多少字
*/
    fun paraOf(text: String, abs: Int, say: String, max: Int = 200): String {
        if (text.isEmpty()) return ""
        val at = abs.coerceIn(0, text.length)
        val a = text.lastIndexOf('\n', maxOf(0, at - 1)) + 1
        var b = text.indexOf('\n', at)
        if (b < 0) b = text.length
        if (b <= a) return say
        /* 句库里存的是截到 120 字那一版，位置对得上才按它切成两半。 */
        val s0 = if (at + say.length <= b && text.startsWith(say, at)) at else at
        val s1 = (s0 + say.length).coerceAtMost(b)
        val left = text.substring(a, s0).trim()
        val right = text.substring(s1, b).trim()
        val head = if (left.length > max) "…" + left.takeLast(max) else left
        val tail = if (right.length > max) right.take(max) + "…" else right
        return (head + say + tail).trim()
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
                val body = text.substring(i + 1, e)
                    .replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
                    .trim()
                /* 认人三条路，按顺序：紧贴引号前、同段离引号最近、引号后那半句。 */
                var who = namer.at(text, maxOf(para, i - 90), i)
                if (who == null) who = namer.at(text, maxOf(para, i - 200), i)
                if (who == null) who = namer.after(text, e, minOf(to, e + 24))
                if (isNoise(body)) {
                    /* 拟声、单字应声：不是一句话，谁说的都不记。 */
                } else if (body.length >= 2) {
                    emit(who ?: UNSURE, s0, body.take(120))
                }
            }
            i = if (e < to && text[e] == close) e + 1 else e
        }
    }

    /** 这一串是不是「一句人话」。 */
    internal fun isNoise(body: String): Boolean {
        val t = body.filter { it in '\u4e00'..'\u9fa5' }
        if (t.isEmpty()) return true
        /* 带省略号的短句是被掐断的话：「陛……」「我……」。 */
        if (body.contains("……") || body.contains("...")) return false
        if (t in Words.noise) return true
        if (t in Words.answers) return false
        if (t.length >= 4) return false
        /* 单字：只有应答的那几个算话。 */
        if (t.length == 1) return t[0] !in "是不好对行诺喏嗯哦唉喂滚走来去"
        /* 两三个字：叠字是拟声（「喃喃」除外，那是人在低声说）。 */
        if (t.length == 2 && t[0] == t[1] && t != "喃喃") return true
        return false
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

    /** 称谓。挂在名字前面的那两个：「邮差小陈」「洛华医生」。表在 [Words.callPre]，数人那一路共用。 */
    private val CALL_PRE: Set<String> = Words.callPre

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

        /** [lo, hi) 里离 [hi] 最近的名字；后面紧跟逗号、顿号的是呼语，跳过。 */
        fun at(t: String, lo: Int, hi: Int): String? {
            var p = maxOf(lo, 0)
            val end = minOf(hi, t.length)
            var hit: String? = null
            while (p < end) {
                val c = t[p]
                if (c !in '\u4e00'..'\u9fa5') { p++; continue }
                /* 前面是汉字、又不是「的」——这一处不是词的开头。 */
                if (p > 0) {
                    val prev = t[p - 1]
                    /* 前面还挂着字：「邮差小陈」「洛华医生」。 */
                    val afterTitle = p >= 2 && t.substring(p - 2, p) in CALL_PRE
                    if (prev in '\u4e00'..'\u9fa5' && prev != '的' && !afterTitle) { p++; continue }
                    if (prev in PREP) { p++; continue }
                }
                val w = at4(t, p) ?: at3(t, p) ?: at2(t, p)
                if (w != null && p + w.length <= end) {
                    val nx = if (p + w.length < t.length) t[p + w.length] else ' '
                    if (nx != '，' && nx != '、' && nx != ',') hit = w
                    p += w.length
                    continue
                }
                p++
            }
            return hit
        }

        /** 引号后面那半句里的名字。 */
        fun after(t: String, from: Int, hi: Int): String? {
            var p = minOf(from, t.length)
            val end = minOf(hi, t.length)
            while (p < end) {
                val c = t[p]
                if (c !in '\u4e00'..'\u9fa5') { p++; continue }
                val w = at4(t, p) ?: at3(t, p) ?: at2(t, p)
                if (w != null && p + w.length <= end) {
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
