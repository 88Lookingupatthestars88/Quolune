package com.pagefold.reader.book

/**
 * 从一本书里数出「谁在说话」，并在一句话旁边认出说话人。
 *
 * 只有两件事：
 *   1. [look] —— 导入时跑一遍，给出这本书的说话人名单，按台词数排。
 *   2. [Ban] —— 拿着名单，判断某一句对白是谁说的。
 */

/** 抽名时用的一张表。名字和动词之间的宽度靠状语表卡住，不再用「任意 6 个字」。 */
private fun clauseTail(s: String): String {
    var e = s.length
    while (e > 0 && (s[e - 1] == '：' || s[e - 1] == ':' || s[e - 1] == '，' || s[e - 1] == ',' ||
            s[e - 1] == '；' || s[e - 1] == ';' || s[e - 1] == ' ' || s[e - 1] == '\u3000' ||
            s[e - 1] == '\n' || s[e - 1] == '“' || s[e - 1] == '”' || s[e - 1] == '「' || s[e - 1] == '」')
    ) e--
    var i = e - 1
    while (i >= 0) {
        val c = s[i]
        if (c == '，' || c == '。' || c == '！' || c == '？' || c == '；' || c == '：') return s.substring(i + 1, e)
        i--
    }
    return s.substring(0, e)
}

private fun clauseHead(s: String): String {
    for (i in s.indices) {
        val c = s[i]
        if (c == '，' || c == '。' || c == '！' || c == '？' || c == '；' || c == '：') return s.substring(0, i + 1)
    }
    return s
}

/** 引号前那一段里的整句（到上一个句末为止）。中文是主语在前，回退要找句首。 */
private fun sentTail(s: String): String {
    var e = s.length
    while (e > 0 && (s[e - 1] == ' ' || s[e - 1] == '\u3000' || s[e - 1] == '\n')) e--
    var i = e - 1
    while (i >= 0) {
        val c = s[i]
        if (c == '。' || c == '！' || c == '？' || c == '；') return s.substring(i + 1, e)
        i--
    }
    return s.substring(0, e)
}

/**
 * 一份名单上预先编好的几个正则。名字变了才重建，不要每句重建。
 */
class Ban(
    names: Collection<String>,
    /** 名字 → "m"/"f"。代词那一条要靠它把候选砍一半。 */
    val gender: Map<String, String> = emptyMap(),
    /** 勾选的题材包。换包就得重建。 */
    val packs: List<String> = emptyList(),
    extraVerb: List<String> = emptyList(),
    extraAdv: List<String> = emptyList(),
    extraTitle: List<String> = emptyList(),
    /** 用户点掉的词。 */
    off: List<String> = emptyList(),
    /** 叫法 → 正名。「小陈」和「邮差小陈」是同一个人，配嗓子只认正名。 */
    val canon: Map<String, String> = emptyMap(),
) {
    val names: Set<String> = names.filter { it.isNotBlank() }.toSet()

    /** 把匹配到的叫法换成正名。查不到就是它自己。 */
    fun real(raw: String): String = canon[raw] ?: raw

    private val table = Words.merge(packs, extraVerb, extraAdv, extraTitle, off)

    private val nr: String = this.names.sortedByDescending { it.length }.joinToString("|") {
        java.util.regex.Pattern.quote(it)
    }
    private val vr: String = run {
        val multi = table.verbs.filter { it.length > 1 }.sortedByDescending { it.length }
        val one = Words.verbs1.joinToString("|")
        (multi + "(?:$one)(?=[：:“”「」\\s，。！？])").joinToString("|")
    }
    private val ar: String = table.advs.sortedByDescending { it.length }.joinToString("|")

    val ok: Boolean = this.names.isNotEmpty()

    val spk: Regex = if (!ok) Regex("(?!x)x") else Regex("($nr)(?:(?:$ar)){0,3}(?:$vr)")
    val nameRx: Regex = if (!ok) Regex("(?!x)x") else Regex("($nr)")
    val titleRx: Regex = Regex(
        "(" + (table.titles + Words.selfs).sortedByDescending { it.length }.joinToString("|") +
            ")(?:(?:$ar)){0,3}(?:$vr)"
    )
    val advVerb: Regex = Regex("(?:(?:$ar)){0,3}(?:$vr)")
    val pron: Regex = Regex("(" + Words.prons.joinToString("|") + ")(?:(?:$ar)){0,3}(?:$vr)")

    fun isObj(t: String, i: Int): Boolean {
        if (i <= 0) return false
        if (t[i - 1] in Words.obj1) return true
        return i >= 2 && Words.obj2.any { t.startsWith(it, i - 2) }
    }

    /** 一段里所有能当主语的名字，带位置。宾语位置上的剔掉。 */
    fun hits(t: String, rx: Regex = spk): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>(4)
        for (m in rx.findAll(t)) {
            val i = m.range.first
            if (isObj(t, i)) continue
            out.add(i to real(m.groupValues[1]))
        }
        return out
    }

    /** 只取名字本身，不看动词。 */
    fun bare(t: String): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>(4)
        for (m in nameRx.findAll(t)) {
            val i = m.range.first
            if (isObj(t, i)) continue
            out.add(i to real(m.groupValues[1]))
        }
        return out
    }
}

/**
 * 说话人判定。六层，从上往下，第一层命中的算。
 *
 * 顺序不是随便排的：越往前，证据越硬。
 */
object Speaker {

    /**
     * @param before 引号前那一段旁白（原文，不是截断过的）
     * @param after  引号后那一段旁白
     * @param lead   这一段里除了当前这句引号之外的全部文字，用来兜「整段只有一个人」
     */
    fun at(before: String, after: String, lead: String, b: Ban): String? {
        if (!b.ok) return null

        // L1 引号前最后一个小句：名字 + 状语 + 动词
        if (before.isNotEmpty()) {
            val cl = clauseTail(before)
            val h = b.hits(cl)
            if (h.isNotEmpty()) {
                for (e in Words.ends) {
                    val j = cl.lastIndexOf(e)
                    if (j >= 0) {
                        val later = h.filter { it.first > j }
                        if (later.isNotEmpty()) return later[0].second
                    }
                }
                return h.last().second
            }
            // L1b 小句里只有「状语 + 动词」，说明主语在整句开头
            if (b.advVerb.containsMatchIn(cl)) {
                val st = sentTail(before)
                val hn = b.bare(st)
                if (hn.isNotEmpty()) return hn[0].second
                val ht = b.hits(st, b.titleRx)
                if (ht.isNotEmpty()) return ht[0].second
            }
        }

        // L1c 引号后第一个小句：「话」赵高问。
        if (after.isNotEmpty()) {
            val cl = clauseHead(after)
            val h = b.hits(cl)
            if (h.isNotEmpty()) return h.last().second
            if (b.advVerb.containsMatchIn(cl)) {
                val hn = b.bare(cl)
                if (hn.isNotEmpty()) return hn[0].second
            }
        }

        // L2 代词。先看同一句里已经点过名的；没有就按性别砍候选。
        for ((txt, front) in listOf(before to true, after to false)) {
            if (txt.isEmpty()) continue
            val cl = if (front) clauseTail(txt) else clauseHead(txt)
            if (!b.pron.containsMatchIn(cl)) continue
            val prev = b.bare(sentTail(txt))
            if (prev.isNotEmpty()) return prev.last().second
            // 代词没主语可继承：从整段里按性别挑，挑不出就用段里第一个
            val pool = b.bare(lead)
            if (pool.isEmpty()) continue
            val p = Regex("(她|她们|他|他们|那人|此人|对方|众人)").find(cl)?.groupValues?.get(1).orEmpty()
            val want = if (p == "她" || p == "她们") "f" else if (p == "他" || p == "他们") "m" else ""
            val pick = if (want.isBlank()) pool.first()
            else pool.firstOrNull { b.gender[it.second] == want } ?: pool.first()
            return pick.second
        }

        // L3 相邻小句里有人名，但没有动词：取最近的
        for ((txt, front) in listOf(before to true, after to false)) {
            if (txt.isEmpty()) continue
            val cl = if (front) clauseTail(txt) else clauseHead(txt)
            val h = b.bare(cl)
            if (h.isNotEmpty()) return h.last().second
        }

        // L4/L5 整段：只有一个名字就是他，多个取段首第一个
        if (lead.isNotEmpty()) {
            val all = b.bare(lead)
            if (all.isNotEmpty()) {
                val uniq = all.map { it.second }.distinct()
                if (uniq.size == 1) return uniq[0]
                return all[0].second
            }
        }
        return null
    }
}

/**
 * 导入时跑一遍：找出这本书里真正在说话的人。
 *
 * 判据是位置，不是词义 —— 一个词如果只在动词前面出现、后面接不了「的」、也当不了主语，
 * 它就是状语（缓缓、拱手、沉声），不是人。所以不需要一张排除表。
 */
object Look {

    private fun han(c: Char) = c in '\u4e00'..'\u9fa5'

    private const val HAN = "\u4e00-\u9fa5"

    /** 说类动词，按首字分桶、桶内长的在前。命名和识别两处共用。 */
    private val verbBuckets: Map<Char, List<String>> by lazy {
        Words.verbs.filter { it.length > 1 }
            .groupBy { it[0] }
            .mapValues { (_, v) -> v.sortedByDescending { it.length } }
    }
    private val verbOne: Set<Char> by lazy { Words.verbs1.mapNotNull { it.firstOrNull() }.toSet() }
    private val advSet: Set<String> by lazy { Words.advs.toSet() }
    private val advLens: List<Int> by lazy { advSet.map { it.length }.distinct().sortedDescending() }
    private val people: Set<String> by lazy { Words.people.toSet() }

    /** 「很太不」这类程度副词后面能挂的字，是形容词或动词，不是人。 */
    private const val DEG = "很太更最真挺"

    /** 名字后面跟这些字，说明它能当主语。这是「它真是个人」的唯一硬证据。 */
    private const val PARTICLE = "在把被将也都就便还又却才已经正的"

    /** 单字动词后面必须紧跟这些，才算动词。 */
    private const val AFTER_VERB = "：:，,。！？；、“”「」\n \u3000"

    private val STOP: Set<String> by lazy {
        setOf(
            "难道", "还是", "就是", "也是", "于是", "但是", "可是", "因为", "所以", "如果", "虽然",
            "只是", "不过", "已经", "现在", "当时", "之后", "之前", "心中", "眼中", "手中", "脸上",
            "头上", "口中", "忽然", "突然", "顿时", "立刻", "马上", "连忙", "赶紧", "一边", "一面",
            "不是", "没有", "什么", "怎么", "这样", "那样", "一样", "一直", "一起", "一定", "一些",
            "有些", "有人", "别人", "大家", "众人", "自己", "你们", "我们", "他们", "她们", "咱们",
            "起来", "出来", "下来", "过来", "回来", "上去", "下去", "进去", "出去", "抬头", "低头",
            "回头", "点头", "摇头", "转身", "开口", "听说", "此时", "此刻", "这里", "那里", "眼里",
            "心里", "嘴里", "手上", "身上", "声音", "激动", "询问", "回答", "起身", "挥手", "示意",
            "上前", "开始", "最终", "然后", "并且", "或者", "比如", "如何", "无法", "内容", "语气",
            "眼睛", "胳膊", "纷纷", "不敢", "最后", "闻言", "谁知", "哈哈", "好一阵", "叩首", "瞪眼",
            "其中一人", "另外一人", "一人", "两人", "三人", "先生", "大哥", "管家", "诧异", "嘲讽",
            "叮嘱", "哭喊", "咆哮", "咕哝", "嘟哝", "嘀咕", "呢喃",
        )
    }

    /** 名字里出现这些字就不是名字。 */
    private const val BAD = "是的了着也都就还又却而但可能要只直和与把被将个么什怎样此那这我你他她它们"

    /** 名字不该以这些字收尾。 */
    private const val TAIL = "则的了着也都就还是没很又再才只并且或如若但却因所以于等被把将让使是有过与他她它们请讲不笑"

    /** 名字前面常粘着的介词。 */
    private const val LEAD = "对向朝和跟与被把给让从及同替为"

    /**
     * 数这本书里有谁在说话。
     *
     * 做法是「找动词、往回取名字」，不是「拿一张大正则扫全书」。
     *
     * 老写法把 179 个动词和 142 个状语拼成一条巨长的正则，再让它在三百万字里
     * 反复回溯 —— 一本百万字的书要等好几十秒，用户等不到，看着就是「识别不出来」。
     * 现在动词按首字分桶，每个位置只比对同首字的几个词；状语用哈希查、
     * 名字往回只试 4/3/2 三个长度。整本书一遍过，不回溯。
     *
     * 名字的起点定在「前一个字不是汉字，或者是『的』」：
     * 「卖豆腐的老李说」「那个来买旧书的男人说」这类句子，名字前面粘着一串修饰语，
     * 只认标点边界的话整本书一个人都抽不出来 —— 这是《测试》只数出两个人的原因。
     */
    /**
     * 词表索引。
     *
     * 原来每个位置都 substring 出来往 HashSet 里丢，一本 358 万字的书要造 251MB 垃圾，
     * 手机上一秒就 GC 到卡死。这里改成把词编成数字：1 字一个 int、2 字两个 char 拼一个
     * int、3/4 字拼一个 long、更长的单独放一小组用 startsWith 比。
     * 同一个位置查一次，不产生任何对象。
     */
    private class Dict(words: Collection<String>) {
        private val k1: IntArray
        private val k2: IntArray
        private val k3: LongArray
        private val k4: LongArray

        /** 长度 5 字以上的词，按长度分组。加起来只有几个，不值得编码。 */
        private val deep: Array<MutableList<String>?> = arrayOfNulls(9)

        init {
            val a1 = ArrayList<Int>(16)
            val a2 = ArrayList<Int>(128)
            val a3 = ArrayList<Long>(64)
            val a4 = ArrayList<Long>(64)
            for (w in words) {
                when (w.length) {
                    0 -> {}
                    1 -> a1.add(w[0].code)
                    2 -> a2.add(w[0].code shl 16 or w[1].code)
                    3 -> a3.add(
                        w[0].code.toLong() shl 32 or (w[1].code.toLong() shl 16) or w[2].code.toLong()
                    )
                    4 -> a4.add(
                        w[0].code.toLong() shl 48 or (w[1].code.toLong() shl 32) or
                            (w[2].code.toLong() shl 16) or w[3].code.toLong()
                    )
                    else -> if (w.length < deep.size) {
                        (deep[w.length] ?: ArrayList<String>(2).also { deep[w.length] = it }).add(w)
                    }
                }
            }
            k1 = a1.toIntArray().also { it.sort() }
            k2 = a2.toIntArray().also { it.sort() }
            k3 = a3.toLongArray().also { it.sort() }
            k4 = a4.toLongArray().also { it.sort() }
        }

        /** 从 [p] 开始、长 [len] 的那一段，是不是这张表里的词。 */
        fun has(t: String, p: Int, len: Int): Boolean {
            if (len <= 0) return false
            if (p < 0 || p + len > t.length) return false
            return when (len) {
                1 -> k1.binarySearch(t[p].code) >= 0
                2 -> k2.binarySearch(t[p].code shl 16 or t[p + 1].code) >= 0
                3 -> k3.binarySearch(
                    t[p].code.toLong() shl 32 or (t[p + 1].code.toLong() shl 16) or t[p + 2].code.toLong()
                ) >= 0
                4 -> k4.binarySearch(
                    t[p].code.toLong() shl 48 or (t[p + 1].code.toLong() shl 32) or
                        (t[p + 2].code.toLong() shl 16) or t[p + 3].code.toLong()
                ) >= 0
                else -> deep.getOrNull(len)?.any { t.startsWith(it, p) } ?: false
            }
        }
    }

    /** 名字和状语之间夹着的东西，加上它们本身都是词。 */
    private val dict: Dict by lazy {
        Dict(Words.verbs.toSet() + Words.verbs1 + advSet + STOP)
    }

    private val advDict: Dict by lazy { Dict(advSet) }

    /**
     * 数这本书里有谁在说话。
     *
     * 做法是「找动词、往回取名字」，不是「拿一张大正则扫全书」。
     *
     * 老写法把 179 个动词和 142 个状语拼成一条巨长的正则，再让它在三百万字里
     * 反复回溯 —— 一本百万字的书要等好几十秒，用户等不到，看着就是「识别不出来」。
     * 现在动词按首字分桶，每个位置只比对同首字的几个词；状语用查表、
     * 名字往回只试 4/3/2 三个长度。整本书一遍过，不回溯，也不造垃圾。
     *
     * 名字的起点定在「前一个字不是汉字，或者是『的』」：
     * 「卖豆腐的老李说」「那个来买旧书的男人说」这类句子，名字前面粘着一串修饰语，
     * 只认标点边界的话整本书一个人都抽不出来 —— 这是《测试》只数出两个人的原因。
     */
    fun speakers(text: String, max: Int = 120): List<CastName> {
        val n = text.length
        if (n < 64) return emptyList()

        val tbl = dict
        val adv = advDict

        /** 尾巴上挂着动词或状语的，不是名字：「赶紧说」「心中嘀」。 */
        fun badTail(p: Int, len: Int): Boolean {
            for (k in 1 until len) if (tbl.has(text, p + k, len - k)) return true
            return false
        }

        fun verbAt(p: Int): Boolean {
            if (p >= n) return false
            val c = text[p]
            verbBuckets[c]?.let { list -> if (list.any { text.startsWith(it, p) }) return true }
            if (c in verbOne) {
                val q = p + 1
                return q >= n || text[q] in AFTER_VERB
            }
            return false
        }

        // ── 第一遍：谁 + 状语{0,3} + 动词 ──
        val said = HashMap<String, Int>(256)
        var i = 0
        while (i < n) {
            val prev = if (i > 0) text[i - 1] else ' '
            if (i > 0 && han(prev) && prev != '的') { i++; continue }
            if (!han(text[i])) { i++; continue }

            var got = 0
            var L = 4
            while (L >= 2) {
                val e = i + L
                if (e <= n) {
                    var allHan = true
                    for (k in i until e) if (!han(text[k])) { allHan = false; break }
                    if (allHan && !tbl.has(text, i, L) && !badTail(i, L)) {
                        var clean = true
                        for (k in i until e) if (text[k] in BAD) { clean = false; break }
                        if (clean && text[e - 1] !in TAIL) {
                            var p = e
                            var skips = 0
                            while (skips < 3) {
                                var more = false
                                for (al in advLens) {
                                    if (adv.has(text, p, al)) { p += al; more = true; break }
                                }
                                if (!more) break
                                skips++
                            }
                            if (verbAt(p)) { got = L; break }
                        }
                    }
                }
                L--
            }
            if (got > 0) {
                val s = text.substring(i, i + got)
                said[s] = (said[s] ?: 0) + 1
            }
            i++
        }
        if (said.isEmpty()) return emptyList()

        // 够格的候选：说得多、砍掉前置介词
        val cand = HashMap<String, Int>(said.size)
        for ((k, c) in said) {
            if (c < 3) continue
            var w = k
            while (w.isNotEmpty() && w[0] in LEAD) w = w.drop(1)
            if (w.length >= 2) cand[w] = (cand[w] ?: 0) + c
        }
        if (cand.isEmpty()) return emptyList()

        // 按前两个字建索引，后两遍都靠它
        val idx = HashMap<Int, MutableList<String>>(cand.size * 2)
        for (w in cand.keys) {
            val a = w[0].code shl 16 or w[1].code
            idx.getOrPut(a) { ArrayList(2) }.add(w)
        }
        fun keyAt(p: Int): Int = if (p + 1 < n) text[p].code shl 16 or text[p + 1].code else -1

        // ── 第二遍：「名字 + 的/在/把/也…」，说明它是个能当主语的名词 ──
        val proof = HashMap<String, Int>(256)
        var p2 = 0
        while (p2 < n) {
            idx[keyAt(p2)]?.let { list ->
                for (w in list) {
                    if (text.startsWith(w, p2)) {
                        val e = p2 + w.length
                        if (e < n && text[e] in PARTICLE) proof[w] = (proof[w] ?: 0) + 1
                    }
                }
            }
            p2++
        }

        // ── 第三遍：「很X」「太X」—— 那是形容词，不是人 ──
        val deg = HashMap<String, Int>(64)
        for (d in DEG) {
            var q = text.indexOf(d)
            while (q >= 0) {
                val s = q + 1
                if (q == 0 || !han(text[q - 1])) {
                    idx[keyAt(s)]?.let { list ->
                        for (w in list) if (text.startsWith(w, s)) deg[w] = (deg[w] ?: 0) + 1
                    }
                }
                if (s >= n) break
                q = text.indexOf(d, s)
            }
        }

        val keep = HashMap<String, Int>(cand.size)
        for ((w, c) in cand) {
            if (w in people) { if ((deg[w] ?: 0) == 0) keep[w] = c; continue }
            if ((proof[w] ?: 0) < 2) continue
            if ((deg[w] ?: 0) > 0) continue
            keep[w] = c
        }
        if (keep.isEmpty()) return emptyList()

        /*
         * 尾巴砍掉：A 包着 B，而且 B 几乎占了 A 全部的出场，A 就是「B + 一个多余的尾巴」。
         * 「赵惊鸿则」对「赵惊鸿」、「嬴政心中」对「嬴政」，都靠这一刀切掉。
         */
        val names = keep.keys.sortedWith(
            compareByDescending<String> { keep[it] }.thenBy { it.length }
        )
        val dead = HashSet<String>(16)
        for (a in names) {
            if (a in dead) continue
            val ca = keep.getValue(a)
            for (b in names) {
                if (a == b || b in dead || !a.contains(b)) continue
                if (ca <= keep.getValue(b) * 1.15) { dead.add(a); break }
            }
        }

        val out = ArrayList<CastName>(keep.size)
        for (w in names) {
            if (w in dead) continue
            out.add(CastName(w, keep.getValue(w)))
        }
        out.sortByDescending { it.lines }
        return if (max > 0 && out.size > max) out.subList(0, max) else out
    }

    /**
     * 从「名字：」这种写法里，把这本书自己那套说法捞出来。
     *
     * 原来用一条正则扫全书，358 万字要 421ms、1.5MB 垃圾。这里改成碰到冒号往回取最多
     * 四个字，一遍走完，不建匹配对象。
     */
    fun customs(text: String, minCount: Int = 8): Pair<List<String>, List<String>> {
        if (text.isBlank()) return emptyList<String>() to emptyList()
        val tail = HashMap<String, Int>(512)
        val n = text.length
        var i = 0
        while (i < n) {
            val c = text[i]
            if (c == '：' || c == ':') {
                var s = i
                var len = 0
                while (len < 4 && s > 0 && han(text[s - 1])) { s--; len++ }
                if (len > 0) {
                    val w = text.substring(s, i)
                    tail[w] = (tail[w] ?: 0) + 1
                }
            }
            i++
        }
        val knowVerb = Words.verbs.toSet()
        val knowAdv = Words.advs.toSet()
        val newVerb = ArrayList<String>()
        val newAdv = ArrayList<String>()
        for ((w, c) in tail) {
            if (c < minCount) continue
            if (w in knowVerb || w in knowAdv) continue
            // 「某某道」/「缓缓道」：前两个字是名字就跳过，是状语就收
            if (w.endsWith("道") && w.length >= 3) {
                val head = w.dropLast(1)
                if (head.length >= 2 && head !in knowAdv) newAdv.add(head)
            } else if (w.endsWith("道") || w.endsWith("说") || w.endsWith("问")) {
                if (w.length >= 2) newAdv.add(w)
            } else if (w in knowVerb) {
                // 已在表内
            } else {
                newVerb.add(w)
            }
        }
        newVerb.sort()
        newAdv.sort()
        return newVerb.take(40) to newAdv.take(60)
    }
}

/**
 * 从一句原话里，给这个人拼一条规则。
 *
 * 手写正则不是用户该干的活：他知道的是「书里就是这么写的」，不知道什么是捕获组。
 * 所以让他粘一句，这里把状语和动词挑出来，拼成 `(叫法…)(?:状语)?(?:动词)`。
 * 拼完立刻拿原句试一遍，试不过就往宽里放一格 —— 宁可多认一句，也别让他自己调。
 */
object Match {

    /** 单字动词后面能跟的字符。跟 [Ban] 里那条先手是同一套。 */
    private const val STOP = "：:，,。！？；;“”「」\n \u3000"

    /**
     * @return 生成的规则；这个人在这句话里没有任何说话痕迹时返回空串。
     */
    fun fromSample(line: String, ways: List<String>): String {
        val body = line.trim()
        if (body.isEmpty()) return ""
        val all = ways.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (all.isEmpty()) return ""

        // 谁先出现在句子最前面就用谁，不按名字长短排 —— 「小陈」先出现，就用小陈那一处。
        val at = all.mapNotNull { w -> body.indexOf(w).let { if (it < 0) null else it to w } }
            .minByOrNull { it.first } ?: return ""

        val tail = body.substring(at.first + at.second.length)
        val adv = Words.advs.filter { tail.startsWith(it) }.maxByOrNull { it.length }
        val rest = if (adv != null) tail.substring(adv.length) else tail
        val verb = Words.verbs.filter { it.length > 1 && rest.startsWith(it) }.maxByOrNull { it.length }
            ?: Words.verbs1.firstOrNull { rest.startsWith(it) }?.takeIf { v ->
                val next = rest.getOrNull(v.length)
                next == null || next in STOP
            }

        val alts = all.sortedByDescending { it.length }
            .joinToString("|") { java.util.regex.Pattern.quote(it) }
        val head = "($alts)"

        fun ok(p: String) = runCatching { Regex(p).containsMatchIn(body) }.getOrDefault(false)

        // 一、名字后面紧跟着状语和动词：最紧，也最不会误伤。
        if (verb != null) {
            val tight = head +
                (if (adv != null) "(?:" + java.util.regex.Pattern.quote(adv) + ")?" else "") +
                "(?:" + verb + ")"
            if (ok(tight)) return tight
        }

        // 二、中间夹了点别的（「看了一眼，说」）。放宽到一个短缝，仍然要求落在说类动词上。
        val anyVerb = Words.verbs.filter { it.length > 1 }.sortedByDescending { it.length } +
            "(?:" + Words.verbs1.joinToString("|") + ")(?=[" + STOP + "])"
        val loose = head + "(?:[^。！？；「」\\n]{0,10}?)(?:" + anyVerb.joinToString("|") + ")"
        if (ok(loose)) return loose

        return ""
    }

    /** 这句话里有没有这几个叫法。没有的话，规则写了也白写。 */
    fun found(line: String, ways: List<String>): Boolean =
        ways.map { it.trim() }.filter { it.isNotBlank() }.any { line.contains(it) }
}
