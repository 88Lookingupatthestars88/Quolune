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

    /** 段首常出现的词，看着像名字其实不是。 */
    private val STOPMORE: Set<String> by lazy {
        setOf(
            "不由", "不由自", "一旁", "时候", "想到", "听到", "看到", "说完", "说罢", "许久", "半晌",
            "肩膀", "不由", "一会", "片刻", "一时", "一阵", "一声", "一句", "一名", "一位", "一个",
            "两人", "三人", "众人", "有人", "那人", "此人", "对方", "大家", "别人", "所有人",
            "话音", "语毕", "说到", "说着", "说到这", "这时", "那时", "当下", "随即", "接着", "随后",
            "至于", "关于", "由于", "于是", "总之", "反正", "果然", "竟然", "居然", "自然", "当然",
            "原来", "本来", "后来", "起初", "起初", "终于", "到底", "毕竟", "难道", "岂不", "如何",
            "少年", "少女", "老者", "老头", "老太", "中年", "青年", "男子", "女子", "男人", "女人",
            // 情绪和表情。这些在段首一闪而过，后面接的全是动作，从来不是谁在说话。
            "安慰", "疑惑", "惊喜", "惊讶", "愤怒", "无奈", "苦笑", "冷笑", "沉默", "犹豫", "迟疑",
            "好奇", "紧张", "焦急", "尴尬", "得意", "冷笑", "恼火", "欣喜", "感叹", "感慨", "庆幸",
            "无奈", "释然", "愕然", "恍然", "哑然", "骇然", "悻悻", "讪讪", "悻然", "愤然", "默然",
            "另外", "其余", "其他", "别处", "远处", "近处", "四周", "周围", "附近", "当地", "各地",
            "半空", "空中", "地上", "天上", "窗外", "门外", "屋内", "院里", "屋外", "身后", "面前",
            "当下", "方才", "方才", "刚才", "刚刚", "立刻", "马上", "忽然", "忽然间", "转眼",
            "总之", "毕竟", "到底", "究竟", "莫非", "难道", "岂能", "何尝", "何必", "如何",
            "似乎", "仿佛", "好像", "大概", "大约", "或许", "也许", "显然", "分明", "明明",
            /* 段首的状语。它们后面永远跟着动词，从来不是谁在说话。 */
            "赶忙", "连忙", "急忙", "赶紧", "匆忙", "慌忙", "兴奋", "平人", "好一", "好半",
            "一道", "一声", "一语", "一口", "一眼", "一手", "一头", "一边", "一同", "一贯",
            "赞许", "赞叹", "感慨", "沉思", "沉吟", "思索", "想到", "想起", "觉得", "认为",
        )
    }

    /**
     * 名字不该以这些字收尾：它们是动词、虚词、方位词的字头。
     *
     * 「走到」「正在」「另外」「在看待」都是这么漏进来的 —— 段首那个 2–4 字串
     * 看着像名字，其实是句子的谓语开头。
     */
    private const val VERBEND = "在到说看想去来走做有为把将被要会能该过成使给对从向于同和与及等道询呼赞喝斥责骂喊叫嚷吼叹念诵吟诉述议辩唤问应回答讲吩咐咐嘱喃咕嘟"

    /** 名字不该以这些字收尾：身体、五官、神色。这堆后面必接名词，不是人。 */
    private const val BODYEND = "脸眼手头声色气心身口鼻耳脚背眉腰腿臂"

    /** 名字里出现这些字就不是名字。 */
    private const val BAD = "是的了着也都就还又却而但可能要只直和与把被将个么什怎样此那这我你他她它们"

    /** 名字不该以这些字收尾。 */
    private const val TAIL = "则的了着地得也都就还是没很又再才只并且或如若但却因所以于等被把将使是有过与他她它们请讲不笑"

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
        Dict(Words.verbs.toSet() + Words.verbs1 + advSet + STOP + STOPMORE)
    }

    private val advDict: Dict by lazy { Dict(advSet) }

    /**
     * 一个 2–5 字的串能不能当名字。
     *
     * 这里的每一条都是踩出来的：书里段首那个 2–4 字串可以是名字，也可以是副词、
     * 情绪词、身体部位、谓语开头。判据只用词形，不认语义 —— 认语义就得有一张
     * 永远补不完的表。
     */
    private fun cleanName(w: String): Boolean {
        if (w.length < 2 || w.length > 5) return false
        for (c in w) if (!han(c) || c in BAD) return false
        if (w.last() in TAIL) return false
        if (w.last() in VERBEND) return false
        if (w.last() in BODYEND) return false
        /* 「弱弱」「默默」「悻悻」：叠字是副词，没有人叫这个。 */
        if (w[0] == w[1]) return false
        /* 「鼻子」「眼睛」：两字、头一个字是身体部位，是名词不是人。 */
        if (w.length == 2 && w[0] in BODYEND) return false
        /* 动词、状语、停用词表里有的，一个都不留。 */
        if (dict.has(w, 0, w.length)) return false
        if (w in STOPMORE) return false
        if (w in people) return true
        if (Words.titles.contains(w) || Words.prons.contains(w)) return false
        /* 「二人」「几位」：数量词，一张封闭的表就够了，不是打地鼠。 */
        if (COUNT.matches(w)) return false
        /*
         * 「低声训」「急忙劝」「思索片刻」：砍掉最后一个字，前面那截是状语或动词 ——
         * 那它自己就是「状语 + 动词」，不是名字。不靠表，靠结构。
         */
        if (w.length >= 3 && dict.has(w, 0, 2)) return false
        if (w.length >= 3 && dict.has(w, 0, w.length - 1)) return false
        return true
    }

    /**
     * 数这本书里有谁在说话。
     *
     * 三件事分开做，判据全在「位置」：
     *
     *   一、候选串。段首那 2–4 个汉字，切几字靠全书频次定 ——
     *       「李谲安银」全书 1 次、「李谲安」34 次，落差就是名字的边界。
     *   二、归属。一句对白只有一个说话人，所以按「句」算，不按「词」算。
     *       中文是主谓宾，主语在**最后一个分句的最前面**，不是离引号最近的那个名词 ——
     *       「扶苏走上前，关上房门，沉声道：“……”」里离引号最近的是「房门」，
     *       但他不是人。老写法取最近的，于是「房门」「二人」「一丝」全进了名单。
     *       现在取最后一个分句的第一个名字；那一段没有名字，才退回整句第一个。
     *   三、合格。台词数 ≥3 才留。噪声词即使当过一次主语，也凑不到三句。
     */
    fun speakers(text: String, max: Int = 400): List<CastName> {
        val n = text.length
        if (n < 64) return emptyList()
        /*
         * 大书（几百万字往上）在 4GB 手机上数人，会撞到堆顶。
         *
         * 原来是直接崩：OutOfMemoryError 不是 Exception，调用方 catch 不住，
         * 整个应用跟着退出 —— 用户看到的是「导进一本大书，点开派角就闪退」。
         * 这里兜一层：数不出来就给空名单，让界面照「还没数」的样子摆着，
         * 用户还能手动配嗓子，不至于被一本书废掉整个应用。
         */
        return try {
            count(text, n, max)
        } catch (e: OutOfMemoryError) {
            emptyList()
        } catch (e: StackOverflowError) {
            emptyList()
        }
    }

    private fun count(text: String, n: Int, max: Int): List<CastName> {

        val tbl = dict
        val adv = advDict

        /** 尾巴上挂着动词或状语的，不是名字：「赶紧说」「心中嘀」。 */
        fun badTail(p: Int, len: Int): Boolean {
            for (k in 1 until len) if (tbl.has(text, p + k, len - k)) return true
            return false
        }

        /*
         * 说类动词。多字的也要看后面那个字 ——
         * 不看的话「房门开口处」「众人解释道」里的「开口」「解释」会被当成有人在说话。
         */
        fun verbAt(p: Int): Boolean {
            if (p >= n) return false
            val c = text[p]
            verbBuckets[c]?.forEach { v ->
                if (text.startsWith(v, p)) {
                    val q = p + v.length
                    if (q >= n) return true
                    val d = text[q]
                    if (d in AFTER_VERB) return true
                    if (verbBuckets[d] != null && verbBuckets[d]!!.any { text.startsWith(it, q) }) return true
                    return false
                }
            }
            if (c in verbOne) {
                val q = p + 1
                return q >= n || text[q] in AFTER_VERB
            }
            return false
        }

        // ── 一、段首 2/3/4 字的频次。名字切到第几个字，全看这张表。 ──
        val p2 = HashMap<Int, Int>(1 shl 12)
        val p3 = HashMap<Long, Int>(1 shl 12)
        val p4 = HashMap<Long, Int>(1 shl 13)
        var i = 0
        while (i < n) {
            if (i == 0 || text[i - 1] == '\n') {
                var q = i
                while (q < n && (text[q] == '\u3000' || text[q] == ' ' || text[q] == '\r')) q++
                if (q + 1 < n && han(text[q]) && han(text[q + 1])) {
                    val k2 = text[q].code shl 16 or text[q + 1].code
                    p2[k2] = (p2[k2] ?: 0) + 1
                    if (q + 2 < n && han(text[q + 2])) {
                        val k3 = text[q].code.toLong() shl 32 or
                            (text[q + 1].code.toLong() shl 16) or text[q + 2].code.toLong()
                        p3[k3] = (p3[k3] ?: 0) + 1
                        if (q + 3 < n && han(text[q + 3])) {
                            val k4 = text[q].code.toLong() shl 48 or
                                (text[q + 1].code.toLong() shl 32) or
                                (text[q + 2].code.toLong() shl 16) or text[q + 3].code.toLong()
                            p4[k4] = (p4[k4] ?: 0) + 1
                        }
                    }
                }
            }
            i++
        }

        fun k3At(q: Int): Long = text[q].code.toLong() shl 32 or
            (text[q + 1].code.toLong() shl 16) or text[q + 2].code.toLong()

        fun k4At(q: Int): Long = text[q].code.toLong() shl 48 or
            (text[q + 1].code.toLong() shl 32) or
            (text[q + 2].code.toLong() shl 16) or text[q + 3].code.toLong()

        /** 段首出现次数。跟 [cand] 分开记：cand 还混着「名字 + 动词」那一路。 */
        val headN = HashMap<String, Int>(2048)

        /**
         * 段首那个串后面紧跟逗号的次数。
         *
         * 「同时，赵惊鸿道：」「甚至，……」—— 段首就断开，是状语在开场，不是主语。
         * 真名段首后面跟的是动作，「季布抬头」「夏青笑道」，极少跟着逗号。
         */
        val headComma = HashMap<String, Int>(1024)

        /** 段首那个名字有多长。0 = 不是名字。 */
        fun headLen(q: Int): Int {
            if (q + 1 >= n || !han(text[q]) || !han(text[q + 1])) return 0
            val c2 = p2[text[q].code shl 16 or text[q + 1].code] ?: 0
            if (c2 < 2) return 0
            if (q + 2 >= n || !han(text[q + 2])) return 2
            val c3 = p3[k3At(q)] ?: 0
            if (c3 * 10 < c2 * 4) return 2
            if (q + 3 >= n || !han(text[q + 3])) return 3
            val c4 = p4[k4At(q)] ?: 0
            return if (c4 * 10 >= c3 * 4) 4 else 3
        }

        // ── 二、候选池。段首那一段 + 「名字 + 状语{0,3} + 说类动词」那一路。 ──
        val cand = HashMap<String, Int>(2048)
        i = 0
        while (i < n) {
            if (i == 0 || text[i - 1] == '\n') {
                var q = i
                while (q < n && (text[q] == '\u3000' || text[q] == ' ' || text[q] == '\r')) q++
                val len = headLen(q)
                if (len > 0) {
                    val w = text.substring(q, q + len)
                    if (cleanName(w)) {
                        cand[w] = (cand[w] ?: 0) + 1
                        headN[w] = (headN[w] ?: 0) + 1
                        val nx = q + len
                        if (nx < n && (text[nx] == '，' || text[nx] == ',' || text[nx] == '、'))
                            headComma[w] = (headComma[w] ?: 0) + 1
                    }
                }
            }
            i++
        }
        i = 0
        while (i < n) {
            if (!han(text[i])) { i++; continue }
            val prev = if (i > 0) text[i - 1] else ' '
            if (i > 0 && han(prev) && prev != '的') { i++; continue }
            var got = 0
            var L = 4
            while (L >= 2) {
                val e = i + L
                if (e <= n) {
                    var ok = true
                    for (k in i until e) if (!han(text[k]) || text[k] in BAD) { ok = false; break }
                    if (ok && !tbl.has(text, i, L) && !badTail(i, L) && text[e - 1] !in TAIL) {
                        var p = e
                        var skips = 0
                        while (skips < 3) {
                            var more = false
                            for (al in advLens) if (adv.has(text, p, al)) { p += al; more = true; break }
                            if (!more) break
                            skips++
                        }
                        if (verbAt(p)) { got = L; break }
                    }
                }
                L--
            }
            if (got > 0) {
                var w = text.substring(i, i + got)
                while (w.isNotEmpty() && w[0] in LEAD) w = w.drop(1)
                if (w.length >= 2 && cleanName(w)) cand[w] = (cand[w] ?: 0) + 1
            }
            i++
        }
        if (cand.isEmpty()) return emptyList()

        /*
         * 候选串编成号，正文里查一次就是三个哈希。
         *
         * 长名字优先：同一处「百里无名」和「百里」都在表里，要的是长的那个。
         */
        val names = ArrayList<String>(cand.size)
        cand.keys.sortedByDescending { it.length }.let { names.addAll(it) }

        val id = HashMap<String, Int>(names.size * 2)
        names.forEachIndexed { idx, w -> id[w] = idx }
        val ix2 = HashMap<Int, Int>(names.size * 2)
        val ix3 = HashMap<Long, Int>(names.size * 2)
        val ix4 = HashMap<Long, Int>(names.size * 2)
        for (w in names) {
            val v = id.getValue(w)
            when (w.length) {
                2 -> ix2[w[0].code shl 16 or w[1].code] = v
                3 -> ix3[w[0].code.toLong() shl 32 or (w[1].code.toLong() shl 16) or w[2].code.toLong()] = v
                4 -> ix4[w[0].code.toLong() shl 48 or (w[1].code.toLong() shl 32) or
                    (w[2].code.toLong() shl 16) or w[3].code.toLong()] = v
            }
        }

        /** [p] 起长 [len] 的那一段是第几号名字。-1 = 不是。 */
        fun findAt(p: Int, len: Int): Int {
            if (p < 0 || p + len > n) return -1
            return when (len) {
                2 -> ix2[text[p].code shl 16 or text[p + 1].code] ?: -1
                3 -> ix3[text[p].code.toLong() shl 32 or (text[p + 1].code.toLong() shl 16) or
                    text[p + 2].code.toLong()] ?: -1
                4 -> ix4[text[p].code.toLong() shl 48 or (text[p + 1].code.toLong() shl 32) or
                    (text[p + 2].code.toLong() shl 16) or text[p + 3].code.toLong()] ?: -1
                else -> -1
            }
        }

        /*
         * 白名单：这个串在书里有没有以「说话人」的样子出现过 ——
         * 「名字 + 状语{0,3} + 说类动词」或者「名字 + ：」。
         *
         * 真名一定会：赵惊鸿道、扶苏：、蒙毅问道。
         * 「农耕」「很快」「外面」「见状」不会 —— 它们能挤进段首，却一次也没当过引导语。
         * 这一条不靠排除表，所以换一本书也成立。
         */
        val known = BooleanArray(names.size)
        /* 全书总出现、后跟「地/得」、当引导语的次数。三样都在这一遍里数完。 */
        val allN = IntArray(names.size)
        val diN = IntArray(names.size)
        val cntN = IntArray(names.size)
        i = 0
        while (i < n) {
            if (!han(text[i])) { i++; continue }
            if (i > 0 && han(text[i - 1]) && text[i - 1] != '的') { i++; continue }
            var v = -1
            var use = 0
            for (len in 4 downTo 2) {
                if (i + len > n) continue
                val x = findAt(i, len)
                if (x >= 0) { v = x; use = len; break }
            }
            if (v < 0) { i++; continue }
            val e = i + use
            allN[v]++
            if (e < n && (text[e] == '地' || text[e] == '得')) diN[v]++
            if (e < n && (text[e] == '：' || text[e] == ':')) { known[v] = true; cntN[v]++; i = e; continue }
            var p2 = e
            var skips = 0
            while (skips < 3) {
                var more = false
                for (al in advLens) if (adv.has(text, p2, al)) { p2 += al; more = true; break }
                if (!more) break
                skips++
            }
            if (verbAt(p2)) { known[v] = true; cntN[v]++ }
            i = e
        }

        /** [lo, hi) 里第一个能当主语的名字。-1 = 没有。 */
        fun nameIn(lo: Int, hi: Int): Int {
            var k = lo
            while (k < hi) {
                if (!han(text[k])) { k++; continue }
                var v = -1
                var use = 0
                for (len in 4 downTo 2) {
                    if (k + len > hi) continue
                    val x = findAt(k, len)
                    if (x >= 0) { v = x; use = len; break }
                }
                if (v < 0) { k++; continue }
                // 受话人：对/向/朝/给/被/把 后面那个不是主语。
                if (k > 0 && text[k - 1] in PREP) { k += use; continue }
                /*
                 * 名字后面紧跟逗号、顿号、的 —— 那是呼语或状语，不是主语。
                 * 「郁闷，他继续道：」里「郁闷」后面就是逗号；「离开的时候」后面是的。
                 */
                val nx = if (k + use < n) text[k + use] else ' '
                if (nx == '，' || nx == ',' || nx == '、') { k += use; continue }
                /* 不在「名字 + 说类动词」或「名字 + ：」里露过面的，不是人。 */
                if (!known[v]) { k += use; continue }
                return v
            }
            return -1
        }

        /** 从 [hi] 往回找最后一个分句的起点。分句按逗号、句号、问号、感叹号、分号切。 */
        fun clauseStart(lo: Int, hi: Int): Int {
            var k = hi
            while (k > lo) {
                val c = text[k - 1]
                if (c == '，' || c == '。' || c == '！' || c == '？' || c == '；' ||
                    c == ',' || c == ';' || c == '!'
                ) return k
                k--
            }
            return lo
        }

        /** 从 [hi] 往回找最后一个整句的起点。 */
        fun sentStart(lo: Int, hi: Int): Int {
            var k = hi
            while (k > lo) {
                val c = text[k - 1]
                if (c == '。' || c == '！' || c == '？' || c == '；' || c == '\n') return k
                k--
            }
            return lo
        }

        /*
         * ── 三、归属。
         *
         * 一次对白一个说话人。引号前面看最后一个分句（「李谲安横枪：」），
         * 那一段里没有名字才退回整句开头（「扶苏走上前，关上房门，沉声道：」）。
         * 引号后面也看，管「“……！”赵高问」这种。
         *
         * [from] 是上一个右引号之后的位置：同一段里第二句对白，不能拿第一句里的名字当主语。
         */
        fun owner(from: Int, qs: Int, qe: Int, pe: Int): Int {
            if (qs > from) {
                val lo = maxOf(from, qs - 80)
                val cs = clauseStart(lo, qs)
                val a = nameIn(cs, qs)
                if (a >= 0) return a
                val ss = sentStart(lo, qs)
                val b = nameIn(ss, qs)
                if (b >= 0) return b
            }
            /* 引号后面从右引号的下一格开始 —— 从 qs+1 开始是引号里面的字。 */
            val from2 = minOf(pe, qe + 1)
            if (from2 >= pe) return -1
            var ce = from2
            while (ce < pe && text[ce] !in "，。！？；,;") ce++
            val c = nameIn(from2, ce)
            if (c >= 0) return c
            var ee = from2
            while (ee < pe && text[ee] != '\n') ee++
            return nameIn(from2, minOf(pe, ee))
        }

        val seg = HashMap<String, Int>(1024)
        var ps = 0
        while (ps <= n) {
            var pe = ps
            while (pe < n && text[pe] != '\n') pe++
            // 段里得有引号才谈得上说话人。
            var hasQuote = false
            for (k in ps until pe) {
                val c = text[k]
                if (c == '“' || c == '「') { hasQuote = true; break }
            }
            if (hasQuote && pe - ps > 6) {
                var last = ps
                var k = ps
                while (k < pe) {
                    val c = text[k]
                    if (c == '“' || c == '「') {
                        var e2 = k + 1
                        while (e2 < pe && text[e2] != '”' && text[e2] != '」') e2++
                        val qe = if (e2 < pe) e2 else pe - 1
                        val v = owner(last, k, qe, pe)
                        if (v >= 0) {
                            val w = names[v]
                            seg[w] = (seg[w] ?: 0) + 1
                        }
                        last = if (e2 < pe) e2 + 1 else pe
                        k = last
                    } else k++
                }
            }
            ps = pe + 1
        }

        if (seg.isEmpty()) return emptyList()

        val sorted = seg.keys.sortedWith(
            compareByDescending<String> { seg[it] }.thenBy { it.length }
        )
        /*
         * 三刀：
         *   一、A 包着 B，且 A 不比 B 多 —— A 是「B + 一个多余尾巴」。
         *   二、B 是 A 的前缀，且 B 不比 A 多 —— B 是 A 被切断的一截（司马 / 司马寒）。
         *   三、B 是 A 的尾巴，且 B 连 A 的零头都不到 —— 「惊鸿」是「赵惊鸿」的后半截。
         */
        val dead = HashSet<String>(64)
        for (a in sorted) {
            if (a in dead) continue
            val ca = seg.getValue(a)
            for (b in sorted) {
                if (a == b || b in dead) continue
                val cb = seg.getValue(b)
                if (a.length > b.length && a.contains(b) && ca <= cb * 1.15) { dead.add(a); break }
                if (b.length > a.length && b.startsWith(a) && ca <= cb * 1.2) { dead.add(a); break }
                if (a.endsWith(b) && cb * 5 <= ca) { dead.add(b); break }
            }
        }

        val out = ArrayList<CastName>(seg.size)
        for (w in sorted) {
            if (w in dead) continue
            val c = seg.getValue(w)
            if (c < 3) continue
            /*
             * 台词数和「这个名字起头的段数」要在一个数量级。
             *
             * 真名当主语，段首是要出场的：赵惊鸿 13979 句 / 段首 11332 段。
             * 「郁闷」全书段首只出现 3 次，台词却有 35 句 —— 多出来的那些是
             * 「（主语省略）郁闷道：」，主语不是它。这个比值和词义无关，换本书也成立。
             */
            val head = cand[w] ?: 0
            if (head < 1 || c > head * 3 + 8) continue
            val k = id.getValue(w)
            if (!passes(w, c, allN[k], diN[k], headN[w] ?: 0, cntN[k], headComma[w] ?: 0)) continue
            out.add(CastName(w, c))
        }
        out.sortByDescending { it.lines }
        return if (max > 0 && out.size > max) out.subList(0, max) else out
    }

    /**
     * 最后一道筛：这一串到底是人，还是被当成了主语的状语、器物、半截短语。
     *
     * 四条判据全用「它在书里怎么被用」，不看词义 —— 词义那一路得永远补表，换本书就废。
     * 每一条都在《大秦》358 万字上量过，真名与噪声的分布不重叠（数据见设计/派角-归属算法.md）。
     *
     * @param c     台词数（这一个串当说话人的次数）
     * @param all   全书出现次数
     * @param di    后面紧跟「地」或「得」的次数 —— 状语或形容词的标记
     * @param head  段首出现次数
     * @param guide 「名字 + 状语 + 说类动词」或「名字：」的次数
     */
    private fun passes(
        w: String, c: Int, all: Int, di: Int, head: Int, guide: Int, comma: Int,
    ): Boolean {
        /*
         * 一、状语、形容词。
         *
         * 「郁闷道：」「担忧地看着」—— 这类词后面常跟「地」「得」，真名一次都不会：
         * 郁闷 95/562、担忧 40/184、惊恐 84/156；赵惊鸿 4/29004、扶苏 12/13470。
         */
        if (di >= 3 && di * 20 >= all) return false
        /*
         * 二、器物、泛称、只在段首露过一面的碎词。
         *
         * 真名当主语，段首是要出场的：夏青 43/228、巴清 34/98、邹茜 35/59。
         * 反过来宣纸 9/468、脑袋 6/497、环首刀 2/181 —— 它们能挤进段首一次两次，
         * 绝大多数时候出现在别的位置。台词多的不杀（主角偶尔被别称顶掉段首）。
         */
        if (c <= 40 && head * 100 < all * 16) return false
        /*
         * 三、三字以上的半截短语。
         *
         * 「齐声呐」「一字一顿」「满脸亢奋」：段首按 2–4 字硬切，切在词中间。
         * 它们全书只出现个位数，真配角再少也有十几二十次。
         */
        if (w.length >= 3 && all <= 10) return false
        /*
         * 四、段首一大堆、当说话人却没几回的。
         *
         * 「甚至」段首 228 次、当说话人 2 次 —— 它开头的是「甚至有些…」这种句子。
         * 真名的段首数和台词数差不到一个数量级：赵惊鸿 15376/3857。
         */
        if (c <= 30 && head > c * 8) return false
        /*
         * 五、段首就断开的状语开场。
         *
         * 「同时，赵惊鸿道：」「甚至，他也……」。名字在段首很少自己跟一个逗号。
         * 台词多的不杀 —— 主角被喊「赵惊鸿，你过来」的时候也会这样。
         */
        if (c <= 40 && head > 2 && comma * 2 >= head) return false
        return guide >= 1
    }

    /** 名字紧跟这些字，说明它是受话人或者宾语，不是说话人。 */
    private const val PREP = "对向朝被把给让替于同与和"

    /** 只能当数量词、从来不是名字的形状：二人、三人、几位、一个。 */
    private val COUNT = Regex("^[一二三四五六七八九十百千万两几数单双半][人个位名者员群帮伙队丝缕抹道片些点分毫层阵束股]$")

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
