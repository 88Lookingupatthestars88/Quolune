package com.pagefold.reader.book

/** 这个位置往前一个字都没有，或者前面是标点、引号、空白。 */
private fun atClauseHead(s: String, i: Int): Boolean {
    if (i <= 0) return true
    val c = s[i - 1]
    return !(c in '\u4e00'..'\u9fa5') || c == '，' || c == ','
}

/** 抽名用的一张表。名字和动词之间的宽度靠状语表卡住。 */
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

/** 一份名单上预先编好的几个正则。 */
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
    /** 自称 → 这个人。「朕」归谁、「妾身」归谁，由数人那一步投票投出来。 */
    val self: Map<String, String> = emptyMap(),
) {
    private val table = Words.merge(packs, extraVerb, extraAdv, extraTitle, off)

    /** 能当主语的那些名字。 */
    private val learnedAdv: Set<String> = extraAdv.toHashSet()

    val names: Set<String> = names.filter { it.isNotBlank() && it !in learnedAdv }.toSet()

    /** 第一人称的叙述者。 */
    val me: String = if ("我" in this.names) "我" else ""

    /** 把匹配到的叫法换成正名。查不到就是它自己。 */
    fun real(raw: String): String = canon[raw] ?: raw

    private val nr: String = this.names.sortedByDescending { it.length }.joinToString("|") {
        java.util.regex.Pattern.quote(it)
    }
    private val vr: String = run {
        val multi = table.verbs.filter { it.length > 1 }.sortedByDescending { it.length }
        val one = Words.verbs1.joinToString("|")
        (multi + "(?:$one)(?=[：:“”「」\\s，。！？]|\\z)").joinToString("|")
    }
    private val ar: String = table.advs.sortedByDescending { it.length }.joinToString("|")

    val ok: Boolean = this.names.isNotEmpty()

    /** 名字和说类动词之间允许夹什么：六字以内、不跨句末、不跨引号。 */
    private val GAP = "[^。！？；!?;\\n「」『』“”]{0,6}"

    val spk: Regex = if (!ok) Regex("(?!x)x") else Regex("($nr)(?:$GAP)(?:$vr)")
    val nameRx: Regex = if (!ok) Regex("(?!x)x") else Regex("($nr)")
    val titleRx: Regex = Regex(
        "(" + (table.titles + Words.selfs).sortedByDescending { it.length }.joinToString("|") +
            ")(?:$GAP)(?:$vr)"
    )
    val advVerb: Regex = Regex("(?:(?:$ar)){0,3}(?:$vr)")

    /** 这半句是不是一句引导语：以说类动词收尾（「……唏嘘道：」「……笑道。」）。 */
    val speechTail: Regex = Regex("(?:$vr)[\\s：:，,。！？；]*$")
    val pron: Regex = Regex("(" + Words.prons.joinToString("|") + ")(?:(?:$ar)){0,3}(?:$vr)")

    /** 引语开头的自称归谁。 */
    fun bySelf(quote: String): String? {
        if (self.isEmpty()) return null
        val t = quote.trimStart('\u3000', ' ', '“', '「', '『')
        for (s in Words.selfCalls) {
            if (!t.startsWith(s)) continue
            return self[s]
        }
        return null
    }

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

    /** 这一段里露过面的所有人，宾语位置也算。 */
    fun seen(t: String): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>(4)
        for (m in nameRx.findAll(t)) out.add(m.range.first to real(m.groupValues[1]))
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

/** 说话人判定。六层，从上往下，第一层命中的算。 */
object Speaker {

    /**
* 。
*
* @param before 引号前那一段旁白（原文，不是截断过的）
* @param after  引号后那一段旁白
* @param lead   这一段里除了当前这句引号之外的全部文字，用来兜「整段只有一个人」
*/
    fun at(before: String, after: String, lead: String, b: Ban, quote: String = ""): String? {
        if (!b.ok) return null

        /* L0 引语自己报了名：「草民地辰子拜见圣上」。 */
        if (quote.isNotEmpty()) {
            val q = quote.trimStart('\u3000', ' ', '“', '「', '『')
            for (tag in Words.selfTags) {
                if (!q.startsWith(tag)) continue
                val rest = q.substring(tag.length)
                val hit = b.names.filter { it.length >= 2 && rest.startsWith(it) }.maxByOrNull { it.length }
                if (hit != null) return b.real(hit)
            }
        }

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
            /* L1b 小句以说类动词收尾 —— 这一句是引导语，主语在整句开头。 */
            if (b.speechTail.containsMatchIn(cl)) {
                val st = sentTail(before)
                val hn = b.bare(st)
                if (hn.isNotEmpty()) return hn.first().second
                val ht = b.hits(st, b.titleRx)
                if (ht.isNotEmpty()) return ht.first().second
            }
        }

        // L1c 引号后第一个小句：「话」赵高问。
        if (after.isNotEmpty()) {
            val cl = clauseHead(after)
            val h = b.hits(cl)
            if (h.isNotEmpty()) return h.last().second
            if (b.speechTail.containsMatchIn(cl) || b.advVerb.containsMatchIn(cl)) {
                val hn = b.bare(cl)
                if (hn.isNotEmpty()) return hn.first().second
            }
        }

        /* L1d 第一人称：「我说：“…”」「我看了他一眼，道：“…”」。只认段首就是「我」的那一句 —— 主语在句首，说话的就是叙述者。 */
        if (b.me.isNotEmpty() && before.isNotEmpty()) {
            val st = sentTail(before).trimStart('\u3000', ' ', '\r')
            if (st.startsWith("我") && b.speechTail.containsMatchIn(st)) return b.me
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
            /* 性别对不上、或者这个代词压根分不出男女，就不要再猜了：猜出来的是段里
* 顺位第一的那个人，跟这一句没有关系。留给上层按对话交替推。 */
            if (want.isBlank()) continue
            val pick = pool.firstOrNull { b.gender[it.second] == want } ?: continue
            return pick.second
        }

        /* 相邻小句里有人名、但没有说类动词时，不取最近的：那句里第一个名字往往是器物或状语（「目光有一种劫后余生的感觉」）。 */

        /* L4 整段兜底：这一段从头到尾只提过一个名字，而且这个名字站在段首或分句首。 */
        if (lead.isNotEmpty()) {
            val h = b.bare(lead)
            val uniq = h.map { it.second }.distinct()
            if (uniq.size == 1 && atClauseHead(lead, h[0].first)) return uniq[0]
        }
        return null
    }
}

/** 导入时跑一遍：找出这本书里真正在说话的人。 */
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
            // 五官、器物、组织、成句的词。它们在段首一闪而过，后面接的永远是「道」，
            // 统计上看着像名字，人一看就知道不是。
            "目光", "眼神", "美眸", "双眸", "眼中", "神色", "表情", "神情", "脸色", "面色",
            "声音", "语气", "语调", "话音", "语气", "心中", "心头", "心底", "嘴角", "身子",
            "大夏", "禁军", "女真", "大臣", "军士", "朝臣", "百官", "群臣", "众将", "诸将",
            "虚空深渊", "话锋一转", "转移话题", "话虽如此", "与此同时", "就在此时", "话音未落",
            /* 通用但不是人的：这几个在几百本书里都会冒出来，一次收干净。 */
            "泥团", "确定", "如雷", "决绝", "一般", "一条", "下人", "本来", "结果", "原来如此",
            "突然", "忽然", "立刻", "马上", "顿时", "瞬间", "刹那", "转眼", "片刻", "半晌",
            "见状", "闻言", "闻听", "听闻", "听罢", "说罢", "说完", "话音", "话落", "声落", "语气",
            "旁人", "有人", "此刻", "忽然间", "一刹那", "不知不觉", "不知何时",
            /* 四字的描写语。它们是句子里的谓语（「她娇躯一颤，泪眼婆娑」），
* 只在「泪眼婆娑道」那一次看着像名字。 */
            "泪眼婆娑", "泪流满面", "热泪盈眶", "目瞪口呆", "面面相觑", "心惊肉跳",
            "心中一凛", "心中一惊", "心中一喜", "松了口气", "松一口气", "叹了口气",
            "倒吸一口", "哑口无言", "无言以对", "不可置信", "难以置信", "若有所思",
            "恍然大悟", "不明所以", "不知所措", "如遭雷击", "如坠冰窟", "呆若木鸡",
            "满脸震惊", "满脸骇然", "脸色大变", "脸色铁青", "面色一沉", "眉头一皱",
            "沉默", "沉寂", "寂静", "安静", "安静了", "良久", "许久", "好一会", "好一会儿",
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
            /* 通称（男人、老者、少年…）不在这张表里，交给 keep() 按对白数收。 */
            "颤音", "破音", "沙音", "哑音", "喉音", "鼻音", "尾音", "收音", "元音", "辅音",
            "合音", "出声", "发声", "开腔", "破口", "面露", "睥睨",
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

    /** 名字不该以这些字收尾：它们是动词、虚词、方位词的字头。 */
    private const val VERBEND =
        "在到说看想去来走做有为把将被要会能该过成使给对从向于同和与及等道询呼赞喝斥责骂喊叫嚷吼叹念诵吟诉述议辩唤问应回答讲吩咐咐嘱喃咕嘟" +
            /* 文言单字。名字不会以「曰」「谓」收尾 —— 那是「操曰」「帝曰」的尾巴。 */
            "曰谓"

    /** 名字不该以这些字收尾：身体、五官、神色。这堆后面必接名词，不是人。 */
    private const val BODYEND = "脸眼手头声色气心身口鼻耳脚背眉腰腿臂眸瞳唇齿舌喉"

    /** 四字以上、尾巴挂着状语或动作的，是名字被切长了。 */
    private val TAIL_ADV: Set<String> = setOf(
        "忽然", "突然", "顿时", "立刻", "马上", "已经", "曾经", "刚才", "本来", "原来",
        "果然", "竟然", "居然", "依然", "仍然", "始终", "终于", "结果", "然后", "接着",
        "于是", "因此", "所以", "但是", "可是", "然而", "不过", "只是", "还是", "或者",
        "而且", "并且", "因为", "由于", "如果", "虽然", "即使", "无论", "不管", "既然",
        "何况", "况且", "免得", "以免", "以便", "顺便", "特意", "故意", "幸好", "幸亏",
        "反而", "反倒", "偏偏", "恰恰", "正好", "刚好", "约莫", "大约", "似乎", "仿佛",
        "好像", "大概", "也许", "恐怕", "难免", "未必", "难道", "究竟", "到底", "毕竟",
        "反正", "好歹", "几乎", "差点", "简直", "甚至", "尤其", "格外", "分外", "异常",
        "特别", "非常", "十分", "极其", "略微", "稍微", "稍稍", "一同", "一起", "一道",
        "准备", "接话", "警觉", "抬起", "紧握", "抬头", "低头", "转身", "点头", "摇头",
        "开口", "出声", "说话", "回答", "回应", "应声", "沉默", "苦笑", "冷笑", "大笑",
        "微笑", "叹气", "叹息", "冷哼", "闷哼", "皱眉", "挑眉", "眯眼", "瞪眼", "愣住",
        "一怔", "一顿", "一笑", "一声", "一眼", "一句", "一下", "一阵", "之后", "之前",
    )

    /** 部位收尾的两字串：只当尾字用，不当首字。 */
    private const val LIMB_END =
        "肩嘴拳屁疤膊鞭翼掌指腹胸脖骨肌筋肤皮肉血汗须颊额腕肘臀胯踝趾"

    /** 群体、身份收尾的是一类人，不是一个人。 */
    private const val CROWD_END = "兵者们"

    /** 方位词收尾的是地点。 */
    private const val LOC_END = "里上中下内外边前后面端旁侧间处口"

    /** 店铺、建筑收尾的是地点。 */
    private const val PLACE_END =
        "铺店楼街巷院屋房窗桥河镇村寺庙馆厂园场站桌椅床柜阶路墙市井庄寨堡亭塔洞峰岸滩渡驿廊檐顶台壁梁柱"

    /** 前面紧挨着这些字的，是宾语：把东西、拿蒲扇、送信。 */
    private const val OBJ_LEFT = "把将被让给拿送递抢偷买卖吃喝看听闻找叫喊骂夸"

    /** 集体名词。它们在书里出现得比谁都多，也天天当主语（「士兵们冲了上来」），但指的是一群人，不是某一个人。 */
    private val GROUPS: Set<String> by lazy {
        setOf(
            "士兵", "将士", "众将", "群臣", "百官", "亲兵", "侍卫", "守卫", "小厮", "丫鬟",
            "侍女", "宫人", "太监", "百姓", "官员", "商人", "文人", "武人", "众人", "众僧",
            "小兵", "兵士", "军士", "随从", "手下", "属下们", "长老们",
            /* 单人通称（老者、少年、掌柜）不在这张表里：它们指一个人，能挂对白就是角色。 */
            /* 身份职称。它们在书里天天当主语（「宫女们都识趣地低头」「海盗用的都是大船」），但指的是这一类人，不是某一个人。 */
            "宫女", "太监", "侍卫", "护卫", "海盗", "城主", "岛主", "寨主", "门客",
            "医者", "术士", "道士", "僧人", "尼姑", "道姑", "书生", "秀才", "捕快", "衙役",
            "更夫", "船夫", "车夫", "渔夫", "樵夫", "农夫", "商贩", "摊贩", "小二",
        )
    }

    /** 自称、谦称：说话人指自己，不是名字。 */
    private val SELFCALL: Set<String> by lazy {
        setOf(
            "老奴", "小的", "小人", "在下", "卑职", "末将", "微臣", "臣妾", "妾身", "贫道", "贫僧",
            "老衲", "老朽", "老身", "本座", "本官", "本王", "本宫", "草民", "民女", "奴才", "奴婢",
            "属下", "下官", "晚辈", "晚生", "小生", "愚兄", "愚弟", "为兄", "为弟", "咱家", "洒家",
        )
    }

    /** 单字副词、连词。以它们开头的短串不是名字。 */
    private const val LEAD_ADV = "便就又也还却才只都已正竟偏倒岂未没无另更最很太极甚颇略稍渐忽猛顿立即忙赶急索归反再却"

    /** 动作、喊叫的收尾字。 */
    private const val VERB_SOFT = "倒喊叫嚷吼喝喊跪扑跌坐站退进转抬低点摇皱叹笑哭泣骂问答说讲望盯瞥瞧"

    /** 以这些字收尾的是画面、声音、动作，不是人。 */
    private const val SIGHT_END = "影光色声味形样状报响似般态"

    /** 叠字。中文里叠出来的都是副词或拟声，没有人叫「笑嘿嘿」「白花花」。 */
    private fun doubled(w: String): Boolean {
        if (w.length < 3) return false
        for (i in 0 until w.length - 1) if (w[i] == w[i + 1]) return true
        return false
    }

    /** 音译名常见的收尾字：格里高利、亚格尼斯、坦格力斯。 */
    private const val TONE = "尔斯夫特娜娅琳利德克顿森伯奇姆玛拉耶勒娃莎蒂丝曼诺塔泽恩迪伊敦翰妮达雷沙瓦朗托普厄帕格妮"

    /** 名字不该以这些动作字收尾。 */
    private const val ACT_END =
        "抬低转点摇皱叹愣沉冷轻推拉拿放走站坐看听笑哭吼退进望盯瞥瞧瞟跺挪蹲跳跑爬扶抓握捏搁蹙挺昂垂俯侧转扭晃颤抖" +
            /* 下跪、扑倒、撞、冲这一批，三字串「扑通跪」「猛然跌」都以它们收尾。 */
            "跪扑跌撞冲喘哼嚎咽斥呸啐嗤" +
            "倒喊叫嚷喝哭泣骂问答说讲惊慌惧怒喜悲急恼狂愣怔呆迷醉晕颤"

    /** 以这些字开头的是介词短语，不是名字。 */
    private const val LEAD_PREP = "对朝冲跟同给替把被将为往在"

    /** 以这些字收尾的是身份、处所、集体，不是某一个人：城主、神机营、宫女、海盗、东瀛人、杀意。 */
    private const val FOLK_END = "女男盗匪寇贼营殿阁堂会帮派门宗教队团军主意气势念"

    /** 「东瀛人」「匈奴人」：地名加一个「人」字，是籍贯，不是名字。 */
    private val FOLK_PLACE: Set<String> = setOf(
        "东瀛", "中原", "西域", "匈奴", "突厥", "契丹", "女真", "高丽", "波斯", "大食",
        "回纥", "吐蕃", "蒙古", "满洲", "契丹", "鲜卑", "党项", "柔然", "乌孙", "楼兰",
        "南诏", "大理", "西夏", "辽国", "金国", "宋国", "胡人", "蛮人", "狄人", "夷人",
    )

    /** 趋向补语。两字串以它们收尾是动词（别开、坐下）；三字串也是（爆发出、冲上前）。 */
    private const val COMP_END = "开起下上出入回过走来去住掉"

    /** 三字以上又以器物收尾的，是短语：「花浇水」「烧火棍」。 */
    private const val THING_END =
        "水火花草木石米面肉菜酒茶饭书钱刀剑车马衣被灯钟表纸笔线布药碗杯盘壶枪镜戟图承盔盾" +
            "带副信封营岛"

    /** 句界上起的这一截能不能当候选。 */
    private fun cleanHead(t: String, p: Int, len: Int): Boolean {
        for (k in p until p + len) {
            val c = t[k]
            if (c !in '\u4e00'..'\u9fa5' || c in BAD) return false
        }
        val last = Han.of(t[p + len - 1])
        if (last in TAIL || last in VERBEND || last in BODYEND) return false
        val first = Han.of(t[p])
        if (first == Han.of(t[p + 1])) return false
        if (len == 2 && first in BODYEND) return false
        return true
    }

    /** 候选串的计数表。键是字本身：2 字占低 32 位、3 字占低 48 位、4 字占满 64 位，三种长度天然不撞，所以不用建 String，也不用拿哈希去比。 */
    private class Tally(cap: Int) {
        var keys = LongArray(cap)
        var vals = IntArray(cap)
        var size = 0
        private val mask = cap - 1

        private fun home(k: Long): Int {
            val h = k * -7046029254386353131L
            return (h xor (h ushr 32)).toInt() and mask
        }

        fun add(k: Long) {
            /* 表满了就直接丢：这张表是筛子，不是账本。不丢的话线性探测会在这里转死。 */
            if (size >= keys.size - 1) return
            var i = home(k)
            var probe = 0
            while (probe <= mask) {
                val kk = keys[i]
                if (kk == k) { vals[i]++; return }
                if (kk == 0L) { keys[i] = k; vals[i] = 1; size++; return }
                i = (i + 1) and mask
                probe++
            }
        }

        fun add(k: Long, v: Int) {
            if (size >= keys.size - 1) return
            var i = home(k)
            var probe = 0
            while (probe <= mask) {
                val kk = keys[i]
                if (kk == k) { vals[i] += v; return }
                if (kk == 0L) { keys[i] = k; vals[i] = v; size++; return }
                i = (i + 1) and mask
                probe++
            }
        }

        fun get(k: Long): Int {
            var i = home(k)
            while (true) {
                val kk = keys[i]
                if (kk == k) return vals[i]
                if (kk == 0L) return 0
                i = (i + 1) and mask
            }
        }

        /** 丢掉只露过一面的，把表压回去。 */
        fun compact() {
            val next = Tally(keys.size)
            for (i in keys.indices) {
                val k = keys[i]
                if (k != 0L && vals[i] >= 2) next.add(k, vals[i])
            }
            keys = next.keys
            vals = next.vals
            size = next.size
        }

        /** 计数最高的 [limit] 个键。 */
        fun top(limit: Int): LongArray {
            var m = 0
            for (i in keys.indices) if (keys[i] != 0L) m++
            val idx = IntArray(m)
            var j = 0
            for (i in keys.indices) if (keys[i] != 0L) idx[j++] = i
            /* 只排前 limit 个：先按计数做一轮选择。 */
            val order = idx.sortedByDescending { vals[it] }
            val take = minOf(limit, order.size)
            val out = LongArray(take)
            for (t in 0 until take) out[t] = keys[order[t]]
            return out
        }

        /** 键还原成串。2/3/4 字按最高那 16 位是不是 0 来分。 */
        fun decode(k: Long): String {
            val c0 = ((k ushr 48) and 0xFFFF).toInt()
            if (c0 != 0) return String(
                charArrayOf(
                    c0.toChar(),
                    ((k ushr 32) and 0xFFFF).toInt().toChar(),
                    ((k ushr 16) and 0xFFFF).toInt().toChar(),
                    (k and 0xFFFF).toInt().toChar(),
                )
            )
            val c1 = ((k ushr 32) and 0xFFFF).toInt()
            if (c1 != 0) return String(
                charArrayOf(
                    c1.toChar(),
                    ((k ushr 16) and 0xFFFF).toInt().toChar(),
                    (k and 0xFFFF).toInt().toChar(),
                )
            )
            return String(
                charArrayOf(((k ushr 16) and 0xFFFF).toInt().toChar(), (k and 0xFFFF).toInt().toChar())
            )
        }
    }

    /** 名字里出现这些字就不是名字。 */
    private const val BAD = "是的了着也都就还又却而但可能要只直和与把被将个么什怎样此那这我你他她它们"

    /** 名字不该以这些字收尾。 */
    private const val TAIL = "则的了着地得也都就还是没很又再才只并且或如若但却因所以于等被把将使是有过与他她它们请讲不笑"

    /** 名字前面常粘着的介词。 */
    private const val LEAD = "对向朝和跟与被把给让从及同替为"

    /** 词表索引。按长度编成整数键，查表不建 String。 */
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

    /** 一个 2–5 字的串能不能当名字。繁体的先归一成简体，收尾字表只有简体一份。 */
    private fun cleanName(raw: String): Boolean {
        val w = raw
        if (w.length < 2 || w.length > 5) return false
        for (c in w) if (!han(c) || c in BAD) return false
        /* 收尾字表、词表都只有简体一份，只看这一格归一 —— 整串换掉会误伤名字本身。 */
        val last = Han.of(w.last())
        if (last in TAIL) return false
        /* 以说类动词收尾的，多半是「应道」「報說」这种半截。 */
        if (last in VERBEND && w[0] !in Words.surname) return false
        if (last in BODYEND) return false
        /*
* 叠字多是副词或拟声。前面挂着「老」「小」「阿」、或整串本来就是称谓的，是人。
*/
        if (doubled(w) && w[0] !in "老小阿" && !Words.titles.contains(w) && !Words.people.contains(w))
            return false
        /* 「鼻子」「眼睛」：两字、头一个字是身体部位，是名词不是人。 */
        if (w.length == 2 && w[0] in BODYEND) return false
        /* 动词、状语、停用词表里有的，一个都不留。 */
        if (dict.has(w, 0, w.length)) return false
        if (w in STOPMORE) return false
        if (w in people) return true
        if (Words.titles.contains(w) || Words.prons.contains(w)) return false
        /* 「二人」「几位」：数量词，一张封闭的表就够了，不是打地鼠。 */
        if (COUNT.matches(w)) return false
        /* 「有大臣」「有军士」：段首一个「有」，那是「有人……」，不是名字。 */
        if (w[0] == '有') return false
        /* 「第七十」「第八十」：章节标题被切下来的一截。带「第」的一律不是人。 */
        if (w[0] == '第') return false
        /* 整串都是数字（七十、三百、一千）：量词或序号，不是人。 */
        if (w.all { it in "零〇一二三四五六七八九十百千万两" }) return false
        /* 以数词、指示词开头的两字串。 */
        if (w[0] in "一二三四五六七八九十百千万半几数每个各另其此该本全满整") return false
        /* 方位词。 */
        if (w.length == 2 && w[1] == '方' && w[0] in "后前左右对四各双两这那") return false
        /* 描写性的字收尾的是画面。 */
        if (last in SIGHT_END) return false
        /* 指示词。 */
        if (w in setOf("后者", "前者", "其余", "其他", "对方", "彼此", "双方", "两方")) return false
        /* 首字落在这张表里的是「状语 + 动词」。 */
        /* 四字串多是成语和描写语。音译名以译音字收尾，放行。 */
        if (w.length >= 4 && w[0] !in Words.surname && !isCall(w) && last !in TONE) {
            /* 「邮差小陈」「洛华医生」：前面挂着一个称谓，剥掉之后剩下那截才是名字。 */
            var named = false
            for (k in 2..3) {
                if (w.length - k !in 2..3) continue
                if (w.substring(0, k) !in Words.callPre) continue
                val rest = w.substring(k)
                if (rest[0] in Words.surname || rest[0] in "老小阿") { named = true; break }
            }
            if (!named) return false
        }
        if (w.length >= 3 && dict.has(w, 0, 2)) return false
        if (w.length >= 3 && dict.has(w, 0, w.length - 1)) return false
        return true
    }

    /** 数这本书里有谁在说话。堆顶顶不住就切段分开数，取「每段都认」的那批。 */
    fun speakers(text: String, max: Int = 400, onStep: (Int) -> Unit = {}): List<CastName> {
        val n = text.length
        if (n < 64) return emptyList()
        return try {
            count(text, n, max, onStep)
        } catch (e: OutOfMemoryError) {
            pieces(text, n, max, onStep)
        } catch (e: StackOverflowError) {
            pieces(text, n, max, onStep)
        }
    }

    /** 切段数人。段界往后挪到换行，别把一句话切成两半。 */
    private fun pieces(text: String, n: Int, max: Int, onStep: (Int) -> Unit): List<CastName> {
        val cut = ArrayList<Int>(6)
        cut.add(0)
        for (k in 1 until 4) {
            var p = (n.toLong() * k / 4).toInt()
            var step = 0
            while (p < n && step < 20_000 && text[p] != '\n') { p++; step++ }
            if (p in 1 until n - 1) cut.add(p)
        }
        cut.add(n)
        val rounds = cut.size - 1
        if (rounds < 2) return emptyList()

        val hit = HashMap<String, IntArray>(2048)
        val samp = HashMap<String, String>(2048)
        for (r in 0 until rounds) {
            val a = cut[r]
            val b = cut[r + 1]
            val one = try {
                count(text.substring(a, b), b - a, max) { }
            } catch (e: OutOfMemoryError) {
                continue
            } catch (e: StackOverflowError) {
                continue
            }
            for (c in one) {
                val v = hit.getOrPut(c.name) { IntArray(2) }
                v[0]++
                v[1] += c.lines
                if (c.sample.isNotBlank() && samp[c.name].isNullOrEmpty()) samp[c.name] = c.sample
            }
            onStep(5 + (r + 1) * 90 / rounds)
        }
        /* 四段里认三段以上。 */
        val need = if (rounds >= 4) rounds - 1 else rounds
        val out = ArrayList<CastName>(hit.size)
        for ((w, v) in hit) {
            if (v[0] < need) continue
            out.add(CastName(w, v[1], sample = samp[w].orEmpty()))
        }
        out.sortByDescending { it.lines }
        onStep(100)
        return if (max > 0 && out.size > max) out.subList(0, max) else out
    }

    private fun count(text: String, n: Int, max: Int, onStep: (Int) -> Unit): List<CastName> {

        /* 先量这本书的写法，判据按它走。 */
        val shape = Shape.of(text)

        /* 一本书最多数 90 秒，超时带着已有名单往下走。 */
        /* 章节标题整行不算：「第七十」在候选池里和人名长得一样。 */
        var headFrom = -1
        var headTo = -1
        fun markLine(p: Int) {
            if (p == 0 || (p > 0 && text[p - 1] == '\n')) {
                var e = text.indexOf('\n', p)
                if (e < 0) e = n
                if (ChapterHead.isLine(text.substring(p, e).trim())) {
                    headFrom = p
                    headTo = e
                } else {
                    headFrom = -1
                    headTo = -1
                }
            }
        }
        fun inHead(p: Int): Boolean = headFrom >= 0 && p >= headFrom && p < headTo

        val deadline = System.nanoTime() + 90_000_000_000L
        fun late(p: Int) = (p and 0xFFFF) == 0 && System.nanoTime() > deadline

        /** 中文书里认得出是引号的那几个。半角那个也算，有的书写一半。 */
        val QUOTES = "\u201C\u300C\u300E\u0022"


        val tbl = dict
        val adv = advDict

        /** 尾巴上挂着动词或状语的，不是名字：「赶紧说」「心中嘀」。 */
        fun badTail(p: Int, len: Int): Boolean {
            for (k in 1 until len) if (tbl.has(text, p + k, len - k)) return true
            return false
        }

        /* 说类动词。多字的也要看后面那个字 ——不看的话「房门开口处」「众人解释道」里的「开口」「解释」会被当成有人在说话。 */
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

        /** [lo, hi) 之间还有没有别的、像名字的串。 */
        fun noNameBetween(lo: Int, hi: Int): Boolean {
            var p = maxOf(lo, 0)
            val e2 = minOf(hi, n)
            while (p < e2) {
                val c = text[p]
                /* 只看「以姓氏开头」的那几个位置。 */
                if (han(c) && c in Words.surname && (p == 0 || !han(text[p - 1]) || text[p - 1] == '的')) {
                    for (L in 5 downTo 2) {
                        if (p + L <= e2 && cleanHead(text, p, L)) return false
                    }
                }
                p++
            }
            return true
        }

        fun k3At(q: Int): Long = text[q].code.toLong() shl 32 or
            (text[q + 1].code.toLong() shl 16) or text[q + 2].code.toLong()

        fun k4At(q: Int): Long = text[q].code.toLong() shl 48 or
            (text[q + 1].code.toLong() shl 32) or
            (text[q + 2].code.toLong() shl 16) or text[q + 3].code.toLong()

        /* 候选分两遍：第一遍只数字面出现次数，第二遍才对留下来的算细账。 */
        val capA = when {
            n < 200_000 -> 1 shl 17
            n < 1_000_000 -> 1 shl 19
            n < 4_000_000 -> 1 shl 20
            else -> 1 shl 21
        }
        val tally = Tally(capA)
        val headTally = Tally(1 shl 16)
        /* 五字名（艾尔德里克、爱新觉罗·某）。六十位装不下，单开一张小表。 */
        val tally5 = HashMap<String, Int>(256)
        run {
            var p = 0
            while (p < n) {
                if (late(p)) break
                markLine(p)
                if (inHead(p)) { p++; continue }
                if (!han(text[p])) { p++; continue }
                val prev = if (p > 0) text[p - 1] else '\u3000'
                /* 候选起头的位置：段首、标点之后，或「的」之后。 */
                if ((!han(prev) || prev == '的') &&
                    prev != '着' && prev != '了' && prev != '和' && prev != '与'
                ) {
                    if (p + 1 < n && han(text[p + 1]) && cleanHead(text, p, 2)) {
                        val k2 = text[p].code.toLong() shl 16 or text[p + 1].code.toLong()
                        tally.add(k2)
                        if (p + 2 < n && han(text[p + 2]) && cleanHead(text, p, 3)) {
                            val k3 = k2 shl 16 or text[p + 2].code.toLong()
                            tally.add(k3)
                            if (p + 3 < n && han(text[p + 3]) && cleanHead(text, p, 4)) {
                                tally.add(k3 shl 16 or text[p + 3].code.toLong())
                                if (p + 4 < n && han(text[p + 4]) && cleanHead(text, p, 5) &&
                                    Han.of(text[p + 4]) in TONE
                                ) {
                                    val w = text.substring(p, p + 5)
                                    tally5[w] = (tally5[w] ?: 0) + 1
                                }
                            }
                        }
                    }

                    /* 表快满了就丢掉只露过一面的 —— 它们不可能是这本书的角色。 */
                    if (tally.size > capA * 3 / 5) tally.compact()
                }
                p++
            }
        }
        onStep(5)

        /* 段首那一路单独数：本来就没几个，留一张小表。 */
        run {
            var i = 0
            while (i < n) {
                if (late(i)) break
                markLine(i)
                if (inHead(i)) { i++; continue }
                if (i == 0 || text[i - 1] == '\n') {
                    var q = i
                    while (q < n && (text[q] == '\u3000' || text[q] == ' ' || text[q] == '\r')) q++
                    if (q + 1 < n && han(text[q]) && han(text[q + 1]) && cleanHead(text, q, 2)) {
                        val k2 = text[q].code.toLong() shl 16 or text[q + 1].code.toLong()
                        headTally.add(k2)
                        if (q + 2 < n && han(text[q + 2]) && cleanHead(text, q, 3)) {
                            val k3 = k2 shl 16 or text[q + 2].code.toLong()
                            headTally.add(k3)
                            if (q + 3 < n && han(text[q + 3]) && cleanHead(text, q, 4))
                                headTally.add(k3 shl 16 or text[q + 3].code.toLong())
                        }
                    }
                    if (headTally.size > (1 shl 15) * 3 / 5) headTally.compact()
                }
                i++
            }
        }

        /* 别名那一路（「小陈」之于「邮差小陈」）和自称词那一路，量都不大，仍旧用 String 收。 */
        val alias = HashMap<String, Int>(512)

        /* 「名字 + 状语{0,3} + 说类动词」那一路：别名、简称专用。 */
        var i = 0
        while (i < n) {
            if (late(i)) break
            markLine(i)
            if (inHead(i)) { i++; continue }
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
                if (w.length >= 2 && cleanName(w)) alias[w] = (alias[w] ?: 0) + 1
            }
            i++
        }

        /* 「草民地辰子拜见圣上」：自称词后面紧跟的那几个字。 */
        val tagged = HashMap<String, Int>(256)
        Words.selfTags.forEach { tag ->
            var k = n
            while (true) {
                k = text.lastIndexOf(tag, k - 1)
                if (k < 0) break
                if (k > 0 && han(text[k - 1])) continue
                /* 落在章节标题那一行里的一律不算。 */
                var ls = k
                while (ls > 0 && text[ls - 1] != '\n') ls--
                if (k - ls <= 48) {
                    var le = text.indexOf('\n', ls)
                    if (le < 0) le = n
                    if (ChapterHead.isLine(text.substring(ls, le).trim())) continue
                }
                val e = k + tag.length
                var len = 0
                while (len < 5 && e + len < n && han(text[e + len])) len++
                if (len < 2) continue
                for (L in len downTo 2) {
                    val w = text.substring(e, e + L)
                    if (w in Words.selfNoise) continue
                    if (!cleanName(w) || tbl.has(text, e, L)) continue
                    val after = text.substring(e + L, minOf(n, e + L + 6))
                        .trimStart('，', '。', '：', '、', '；', ' ', '\u3000')
                    if (Words.selfAfter.any { after.startsWith(it) }) {
                        tagged[w] = (tagged[w] ?: 0) + 2
                        break
                    }
                }
            }
        }

        /* 短名单：计数最高的那几千个，加上段首出现过的、两路别名。 */
        val short = HashMap<String, Int>(1 shl 13)
        val pick = if (n < 1_000_000) 60000 else 15000
        for (k in tally.top(pick)) {
            val w = tally.decode(k)
            if (cleanName(w)) short[w] = tally.get(k)
        }
        for (k in headTally.top(8000)) {
            val w = headTally.decode(k)
            if (w !in short && cleanName(w)) short[w] = tally.get(k)
        }
        tally5.forEach { (w, c) -> if (cleanName(w)) short[w] = (short[w] ?: 0) + c }
        for ((w, c) in alias) if (cleanName(w)) short[w] = (short[w] ?: 0) + c
        for ((w, c) in tagged) if (c >= 2) short[w] = (short[w] ?: 0) + 3

        if (short.isEmpty()) return emptyList()
        onStep(25)

        /* 候选串编成号，正文里查一次就是几个哈希。 */
        val names = ArrayList<String>(short.size)
        short.keys.sortedByDescending { it.length }.let { names.addAll(it) }

        val id = HashMap<String, Int>(names.size * 2)
        names.forEachIndexed { idx, w -> id[w] = idx }
        val ix2 = HashMap<Int, Int>(names.size * 2)
        val ix3 = HashMap<Long, Int>(names.size * 2)
        val ix4 = HashMap<Long, Int>(names.size * 2)
        /* 五字：键是前四字，值指向 [tail5] 里那一位。同前缀同尾的只有一个，够用。 */
        val ix5 = HashMap<Long, Int>(64)
        val tail5 = CharArray(names.size)
        for (w in names) {
            val v = id.getValue(w)
            when (w.length) {
                2 -> ix2[w[0].code shl 16 or w[1].code] = v
                3 -> ix3[w[0].code.toLong() shl 32 or (w[1].code.toLong() shl 16) or w[2].code.toLong()] = v
                4 -> ix4[w[0].code.toLong() shl 48 or (w[1].code.toLong() shl 32) or
                    (w[2].code.toLong() shl 16) or w[3].code.toLong()] = v
                5 -> {
                    ix5[w[0].code.toLong() shl 48 or (w[1].code.toLong() shl 32) or
                        (w[2].code.toLong() shl 16) or w[3].code.toLong()] = v
                    tail5[v] = w[4]
                }
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
                5 -> {
                    if (ix5.isEmpty()) return -1
                    val v = ix5[text[p].code.toLong() shl 48 or (text[p + 1].code.toLong() shl 32) or
                        (text[p + 2].code.toLong() shl 16) or text[p + 3].code.toLong()] ?: return -1
                    if (text[p + 4] == tail5[v]) v else -1
                }
                else -> -1
            }
        }

        /* 首字位图。名字第一个字就那么几十个，其余的字不必往下试。 */
        val firstCh = BooleanArray(0x10000)
        for (w in names) if (w.isNotEmpty()) firstCh[w[0].code] = true

        /* 白名单：这个串在书里有没有以「说话人」的样子出现过 ——「名字 + 状语{0,3} + 说类动词」或者「名字 + ：」。 */
        val known = BooleanArray(names.size)

        /* 每人一句他说过的话，四十字以内。 */
        val samp = arrayOfNulls<String>(names.size)

        /** [p] 起往后找第一个引号，把引号里那一句掐四十字回来。没有就给空串。 */
        fun sampleAt(p: Int): String {
            var k = p
            val lim = minOf(n, p + 14)
            while (k < lim && text[k] !in QUOTES) k++
            if (k >= lim) return ""
            val open = text[k]
            val close = when (open) {
                '\u201C' -> '\u201D'
                '\u300C' -> '\u300D'
                '\u300E' -> '\u300F'
                else -> '"'
            }
            val end = minOf(n, k + 1 + 64)
            val sb = StringBuilder(48)
            var e2 = k + 1
            while (e2 < end) {
                val c = text[e2]
                if (c == close || c == '\n' || c == '\r') break
                sb.append(c)
                e2++
            }
            val t = sb.toString().trim()
            // 引号里只有一两个字的多半是「嗯」「是」，判不了身份，宁可给空。
            return if (t.length >= 4) t.take(40) else ""
        }

        /* 每个候选的账全在这几排数组里。 */
        val totalN = IntArray(names.size)
        val boundN = IntArray(names.size)
        val deN = IntArray(names.size)
        val headN = IntArray(names.size)
        val headComma = IntArray(names.size)
        val speechN = IntArray(names.size)
        val colonN = IntArray(names.size)
        val subjN = IntArray(names.size)
        val locN = IntArray(names.size)
        val objN = IntArray(names.size)
        /* 「名字(+状语) + 说类动词」之后紧跟引号的次数 —— 对白确实挂在这名字名下。 */
        val quoteN = IntArray(names.size)
        val rightN = arrayOfNulls<HashSet<Char>>(names.size)
        val leftN = arrayOfNulls<HashSet<Char>>(names.size)

        fun see(set: Array<HashSet<Char>?>, v: Int, c: Char) {
            val s = set[v]
            if (s == null) set[v] = HashSet<Char>(8).also { it.add(c) }
            else if (s.size < 32) s.add(c)
        }

        /* 一个位置上有几个名字就记几个。 */
        i = 0
        while (i < n) {
            if (late(i)) break
            markLine(i)
            if (inHead(i)) { i++; continue }
            if (!firstCh[text[i].code]) { i++; continue }
            val prev = if (i > 0) text[i - 1] else '\u3000'
            val atBound = !han(prev) || prev == '的' || prev == '着' || prev == '了'
            val atClause = i == 0 || !han(prev) || prev == '的'
            /* 段首：往前只允许有空白，再往前必须是换行。 */
            var atHead = false
            if (prev == '\n' || prev == '\u3000' || prev == ' ' || prev == '\r') {
                var q = i - 1
                while (q >= 0 && (text[q] == '\u3000' || text[q] == ' ' || text[q] == '\r')) q--
                atHead = q < 0 || text[q] == '\n'
            }
            for (len in 2..5) {
                val e = i + len
                if (e > n) break
                val v = findAt(i, len)
                if (v < 0) continue
                totalN[v]++
                if (atBound) boundN[v]++
                if (prev == '的') deN[v]++
                if (atHead) {
                    headN[v]++
                    if (e < n && (text[e] == '，' || text[e] == ',' || text[e] == '、')) headComma[v]++
                }
                if (e < n) {
                    see(rightN, v, text[e])
                    if (text[e] in LOC_END) locN[v]++
                }
                if (i > 0) {
                    see(leftN, v, text[i - 1])
                    if (text[i - 1] in OBJ_LEFT) objN[v]++
                }
                /* 下面是「当说话人」的判据，只在分句开头算 —— 「知道」里的「道」不算说。 */
                if (atClause) {
                    /* 名字后面隔一串动作再接冒号，也算他在说话。剧本、对白密集的书里是主流写法。 */
                    var far = e
                    if (!(e < n && (text[e] == '：' || text[e] == ':'))) {
                        var k2 = e
                        var commas = 0
                        val lim = minOf(n, e + 22)
                        while (k2 < lim) {
                            val c2 = text[k2]
                            if (c2 == '：' || c2 == ':') { far = k2; break }
                            if (c2 in "。！？；\n\u201C\u300C\u300E") break
                            if (c2 == '，' || c2 == ',') commas++
                            if (commas > 2) break
                            k2++
                        }
                        if (far == e) far = -1
                    }
                    /* 名字和冒号之间必须以动作字收尾，否则「扑通跪」这种半截会混进名单。
首字还得是姓氏、「老小阿」或称谓。 */
                    /* 头部：首字是姓氏、或「老小阿」这类叫法、或本来就是称谓。 */
                    val cand = names[v]
                    val likely = cand[0] in Words.surname || cand[0] in "老小阿" || isCall(cand)
                    if (far > e && shape.script && likely && text[far - 1] in VERB_SOFT &&
                        noNameBetween(e, far)
                    ) {
                        known[v] = true
                        speechN[v]++
                        colonN[v]++
                        if (samp[v].isNullOrEmpty()) sampleAt(far + 1).let { if (it.isNotEmpty()) samp[v] = it }
                    } else if (e < n && (text[e] == '：' || text[e] == ':')) {
                        known[v] = true
                        speechN[v]++
                        colonN[v]++
                        if (samp[v].isNullOrEmpty()) sampleAt(e + 1).let { if (it.isNotEmpty()) samp[v] = it }
                    } else {
                        var p2 = e
                        var skips = 0
                        while (skips < 3) {
                            var more = false
                            for (al in advLens) if (adv.has(text, p2, al)) { p2 += al; more = true; break }
                            if (!more) break
                            skips++
                        }
                        if (verbAt(p2)) {
                            known[v] = true
                            speechN[v]++
                            /* 说类动词后面紧跟引号（中间只许冒号和空白），这句对白就是他说的。 */
                            var q = p2 + 1
                            if (q < n && (text[q] == '：' || text[q] == ':')) q++
                            while (q < n && (text[q] == ' ' || text[q] == '\u3000')) q++
                            if (q < n && text[q] in OPEN_Q) quoteN[v]++
                            if (samp[v].isNullOrEmpty()) sampleAt(p2).let { if (it.isNotEmpty()) samp[v] = it }
                        }
                        /* 当主语：后面接虚词或动词。白描的书里没有引导语，只剩这一条线索。 */
                        if (e < n && (text[e] in PARTICLE || verbAt(e))) subjN[v]++
                    }
                }
            }
            i++
        }
        /* 倒装：引号后面紧跟名字。名字不在引号前，也不在段首，只在右引号后露一下。 */
        run {
            var p = 0
            while (p < n) {
                if (late(p)) break
                val c = text[p]
                if (c != '\u201D' && c != '\u300D' && c != '\u300F') { p++; continue }
                var q = p + 1
                while (q < n && (text[q] == '\u3000' || text[q] == ' ')) q++
                if (q >= n || !han(text[q])) { p++; continue }
                for (L in 2..5) {
                    val v = findAt(q, L)
                    if (v < 0) continue
                    var e = q + L
                    var skips = 0
                    while (skips < 2) {
                        var more = false
                        for (al in advLens) if (adv.has(text, e, al)) { e += al; more = true; break }
                        if (!more) break
                        skips++
                    }
                    if (verbAt(e)) {
                        known[v] = true
                        speechN[v]++
                        quoteN[v]++
                        if (samp[v].isNullOrEmpty()) sampleAt(p + 1).let { if (it.isNotEmpty()) samp[v] = it }
                    }
                }
                p++
            }
        }

        onStep(70)

        /** [lo, hi) 里第一个能当主语的名字。-1 = 没有。 */
        fun nameIn(lo: Int, hi: Int): Int {
            var k = lo
            while (k < hi) {
                if (!firstCh[text[k].code]) { k++; continue }
                var v = -1
                var use = 0
                for (len in 5 downTo 2) {
                    if (k + len > hi) continue
                    val x = findAt(k, len)
                    if (x >= 0) { v = x; use = len; break }
                }
                if (v < 0) { k++; continue }
                // 受话人：对/向/朝/给/被/把 后面那个不是主语。
                if (k > 0 && text[k - 1] in PREP) { k += use; continue }
                /* 名字后面紧跟逗号、顿号、的 —— 那是呼语或状语，不是主语。 */
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

        /* ── 三、归属。 */
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
                if (text[k] in OPEN_Q) { hasQuote = true; break }
            }
            if (hasQuote && pe - ps > 6) {
                var last = ps
                var k = ps
                while (k < pe) {
                    val c = text[k]
                    if (c in OPEN_Q) {
                        /* 三种引号都认，『』开头的段落也要对得上。 */
                        val closer = when (c) {
                            '“' -> '”'
                            '「' -> '」'
                            '『' -> '』'
                            else -> c
                        }
                        var e2 = k + 1
                        while (e2 < pe && text[e2] != closer) e2++
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
        onStep(85)

        /* 三路并进来排：当过说话人的、自称词带出来的、候选池里过了筛的。 */
        fun keepOf(w: String, spoke: Int): Boolean {
            val k = id[w] ?: return false
            return keep(
                w, spoke + speechN[k], tagged[w] ?: 0,
                boundN[k], short[w] ?: 0, rightN[k]?.size ?: 0, deN[k],
                headComma[k], totalN[k], locN[k], subjN[k], colonN[k], objN[k], headN[k],
                quoteN[k], shape,
            )
        }

        val padded = LinkedHashSet<String>(seg.size + names.size)
        for ((w, c) in seg) if (keepOf(w, c)) padded.add(w)
        /* 自称词那一路也要过最后一刀 —— 它捞到的不全是名字（「笑容」也在里头）。 */
        for ((w, c) in tagged) if (c >= 2 && keepOf(w, 0)) padded.add(w)
        for (w in names) {
            if (w in padded) continue
            if (keepOf(w, 0)) padded.add(w)
        }
        val sorted = padded.sortedWith(
            compareByDescending<String> { maxOf(seg[it] ?: 0, tagged[it] ?: 0, if (it in short) 1 else 0) }
                .thenBy { it.length }
        )
        /* 合并同一个人。频次按「当说话人 + 进候选池」两边一起看。 */
        fun weight(w: String): Int = (seg[w] ?: 0) * 2 + (short[w] ?: 0)
        /* 右邻种类。这一族里谁更「独立」看它 ——独立的那一个是名字本体，另一个是切多了。 */
        fun rightOf(w: String): Int {
            val k = id[w] ?: return 0
            return rightN[k]?.size ?: 0
        }
        val nameSet = HashSet<String>(sorted.size * 2)
        nameSet.addAll(sorted)
        val longer = HashMap<String, ArrayList<String>>(sorted.size)
        /* 名字挂到它所有 2 字以上的前缀下，四字长尾靠前缀桶找回来。 */
        for (w in sorted) {
            for (k in 2 until w.length) {
                longer.getOrPut(w.substring(0, k)) { ArrayList<String>(2) }.add(w)
            }
        }

        val dead = HashSet<String>(64)
        /* 通称后面多拖一个字，是被切长了：「老者停了停」切出「老者停」，「掌柜笑了一声」切出「掌柜笑」。 */
        for (a in sorted) {
            if (a !in people) continue
            longer[a]?.forEach { b -> if (b.length == a.length + 1) dead.add(b) }
        }
        /* 沿前缀链收敛一轮：一族人只留最独立的那个。 */
        for (a in sorted) {
            if (a in dead) continue
            val bs = longer[a] ?: continue
            val ra = rightOf(a)
            val wa = weight(a)
            /* 整条链都要看，不能只看第一个。 */
            for (b in bs) {
                if (b in dead) continue
                val rb = rightOf(b)
                /* 谁更独立谁活。一样独立、一样多时留短的：长的那一个是多切了一个字。 */
                if (ra > rb || wa > weight(b)) dead.add(b)
                else if (ra < rb || wa < weight(b)) dead.add(a)
                else dead.add(b)
            }
        }
        for (a in sorted) {
            if (a in dead) continue
            val ca = weight(a)
            var hit = false
            /* 一、a 里头包着的短名字。 */
            for (i in 0 until a.length - 1) {
                for (j in i + 2..a.length) {
                    if (i == 0 && j == a.length) continue
                    val b = a.substring(i, j)
                    if (b !in nameSet || b in dead) continue
                    /* 复姓本身就是名字的一截：公孙、慕容、欧阳。 */
                    if (b in Words.twoCharSurname) continue
                    val cb = weight(b)
                    /* 只有更短的那个确实更独立，才允许它吃掉长名字。一样独立时留长的。 */
                    if (ca <= cb * 1.15 && rightOf(b) > rightOf(a)) { dead.add(a); hit = true; break }
                }
                if (hit) break
            }
            if (hit) continue
            /* 二、a 是长名字的前半截：长名字明显更多，砍 a；a 明显更多，砍长名字。 */
            val bigs = longer[a] ?: continue
            for (b in bigs) {
                if (b in dead) continue
                val cb = weight(b)
                if (ca * 2 <= cb) { dead.add(a); hit = true; break }
            }
            if (hit) continue
            for (b in bigs) {
                if (b in dead) continue
                val cb = weight(b)
                if (cb * 3 >= ca * 4 && cb >= 5) { dead.add(a); hit = true; break }
            }
        }

        onStep(95)
        val out = ArrayList<CastName>(seg.size)
        for (w in sorted) {
            if (w in dead) continue
            if (!keepOf(w, seg[w] ?: 0)) continue
            val c = maxOf(seg[w] ?: 0, tagged[w] ?: 0)
            val k = id[w] ?: continue
            out.add(CastName(w, c, sample = samp[k].orEmpty()))
        }
        /* 男女。旁白点完名字，后面那一句里接着用「她」还是「他」，就是这个人。 */
        if (out.isNotEmpty()) {
            val slot = HashMap<String, Int>(out.size * 2)
            out.forEachIndexed { i, c -> slot[c.name] = i }
            val fem = IntArray(out.size)
            val mal = IntArray(out.size)
            var q = 0
            while (q < n) {
                if (late(q)) break
                markLine(q)
                if (inHead(q)) { q++; continue }
                if (q > 0 && text[q - 1] != '\n') { q++; continue }
                var h = q
                while (h < n && (text[h] == '\u3000' || text[h] == ' ' || text[h] == '\r')) h++
                /* findAt 给的是候选表里的号，不是 out 里的号，要转一道。 */
                var len = 0
                for (L in 5 downTo 2) {
                    if (findAt(h, L) >= 0) { len = L; break }
                }
                val v = if (len == 0) -1 else findAt(h, len)
                val si = if (v < 0) -1 else (slot[names[v]] ?: -1)
                if (si >= 0) {
                    val e2 = minOf(n, h + len + 40)
                    var k2 = h + len
                    while (k2 < e2) {
                        val ch = text[k2]
                        if (ch == '。' || ch == '\n') break
                        if (ch == '她') { fem[si]++; break }
                        if (ch == '他') { mal[si]++; break }
                        k2++
                    }
                    q = h + len
                } else q = h + 1
            }
            for (i in out.indices) {
                val sex = when {
                    fem[i] >= 3 && fem[i] >= mal[i] * 2 -> "f"
                    mal[i] >= 3 && mal[i] >= fem[i] * 2 -> "m"
                    else -> ""
                }
                if (sex.isNotEmpty()) out[i] = out[i].copy(sex = sex)
            }
        }
        /* 第一人称。段首一个「我」、这一句又挂着引号 —— 那是叙述者自己在说话，名字表里永远不会有他。 */
        run {
            var k = 0
            var i = 0
            while (i < n) {
                if (i == 0 || text[i - 1] == '\n') {
                    var j = i
                    while (j < n && (text[j] == '\u3000' || text[j] == ' ' || text[j] == '\r')) j++
                    if (j + 5 < n && text[j] == '我') {
                        var e = text.indexOf('\n', j)
                        if (e < 0) e = n
                        if (ME_HEAD.containsMatchIn(text.substring(j, minOf(e, j + 80)))) k++
                    }
                }
                i++
            }
            if (k >= 8) out.add(CastName("我", k, on = true))
        }
        out.sortByDescending { it.lines }
        /* 大书能数出上万个人名，绝大多数只露过一两面。 */
        if (n > 1_000_000) {
            /* 大书收口：通称要说过二十句才算角色 —— 「一个男人」「那个少年」常常只露一面。 */
            val keep = ArrayList<CastName>(minOf(out.size, 3000))
            for (c in out) {
                if (c.name in Words.people && c.lines < 20) continue
                keep.add(c)
                if (keep.size >= 3000) break
            }
            onStep(100)
            return keep
        }
        onStep(100)
        return if (max > 0 && out.size > max) out.subList(0, max) else out
    }

    /** 通称判定。整串是一个称谓（掌柜、城主），或者串里挂着称谓 ——前面挂着称谓的也算角色，不是路人。 */
    private fun isCall(w: String): Boolean {
        if (Words.people.contains(w) || Words.titles.contains(w)) return true
        if (w.length <= 2) return false
        for (i in 1 until w.length - 1) {
            val pre = w.substring(0, i)
            val suf = w.substring(i)
            if (pre.length >= 2 && (Words.titles.contains(pre) || Words.people.contains(pre))) return true
            if (suf.length >= 2 && (Words.titles.contains(suf) || Words.people.contains(suf))) return true
        }
        return false
    }

    /**
* 这一串是人名还是被切错的词。
*
* @param c        当过说话人的次数
* @param tagged   自称词后面捞到的次数
* @param bound    前面是句界（标点、引号、段首）的次数
* @param cand     进候选池的次数（同义词：它一共露过几次面）
* @param right    右邻字的种类数
* @param de       前面是「的」的次数
* @param headComma 段首紧跟逗号的次数 —— 呼语（「老李，你来了」）
* @param total    全书出现次数
* @param loc      后面跟方位词的次数
* @param subj     当主语的次数（后面接虚词或动词）
*/
    private fun keep(
        w: String, c: Int, tagged: Int, bound: Int, cand: Int, right: Int = 0, de: Int = 0,
        headComma: Int = 0, total: Int = 0, loc: Int = 0, subj: Int = 0, colon: Int = 0,
        obj: Int = 0, head: Int = 0, quote: Int = 0, shape: Shape = Shape(),
    ): Boolean {
        if (w.isEmpty()) return false

        /* 一、字面。这几张表都是「收尾字」，不是「含这个字」——「石阶上」的上、「钟表铺」的铺、「花浇水」的水，都在最后一格。 */
        /* 收尾字表只有简体一份，繁体串（應聲、人報）先归一这一格。 */
        val last = Han.of(w.last())
        if (last in LOC_END) return false
        /* 姓氏打头、说过三次以上的放过。 */
        if (last in PLACE_END && w !in people && w !in Words.titles &&
            !(w[0] in Words.surname && c >= 3)
        ) return false
        /* 以动作字收尾的，多半是从句子里切出来的半截（「王五转」「水云诗抬」）。 */
        if (last in ACT_END && w.length >= 2 && w[0] !in Words.surname && w[0] !in "老小阿" && !isCall(w))
            return false
        /* 以「声」「报」这类字收尾的是动作或声音。 */
        if (last in SIGHT_END) return false
        /* 以说话动词收尾的是「報說」「應道」这种半截：「报说」的「说」。 */
        if (last in VERBEND && w[0] !in Words.surname) return false
        if (last in THING_END && c < 3) return false
        /* 三字以上以身体部位收尾的是描写。 */
        if (w.length >= 3 && last in BODYEND) return false
        /* 「庞云缓缓」：尾巴上挂着状语，是名字被切长了。 */
        if (w.length >= 3 && advSet.contains(w.substring(w.length - 2))) return false
        /* 「对两人问」「对众人道」：前面那个「对」是介词，真正的说话人在它前面。 */
        if (Han.of(w[0]) in LEAD_PREP) return false
        /* 一群人的统称。 */
        if (w.length <= 3 && w[0] == '众') return false
        /* 身份、处所、集体收尾，而且首字不是姓氏：城主、神机营、宫女、海盗、东瀛人、杀意。 */
        if (last in FOLK_END && w.length <= 3 && w[0] !in Words.surname && !isCall(w)) return false
        /* 群体收尾且首字不是姓氏：信号兵、幸存者、两名士兵。 */
        if (last in LIMB_END && c < 3 && !isCall(w)) return false
        /* 四字以上，尾巴挂着状语或动作。 */
        if (w.length >= 4 && w.substring(w.length - 2) in TAIL_ADV) return false
        if (last in CROWD_END && w[0] !in Words.surname && !isCall(w)) return false
        /* 「东瀛人」「匈奴人」：地名加一个「人」。 */
        if (w.length == 3 && last == '人' && w.substring(0, 2) in FOLK_PLACE) return false
        /* 「爆发出」「冲上前」：三字串以趋向补语收尾，是动词。首字是姓氏的放过。 */
        if (last in COMP_END && w.length >= 3 && w[0] !in Words.surname) return false

        /*
* 词表挡一道，放在所有捷径前面 —— 自称词那一路也不该把「笑容」捞进来。
*/
        val sur0 = w[0] in Words.surname
        val nick0 = w.length >= 2 && w[0] in "老小阿"
        val call0 = isCall(w)
        /* 词表挡一道。繁体串查两遍：原文一遍、归一后一遍，任一命中都算 ——归一偶尔会把一个字换错，两遍都查才不会漏掉该挡的。 */
        if (!call0) {
            var kind = Lex.kind(w)
            if (kind == 0) {
                val plain = Han.simplify(w)
                if (plain != w) kind = Lex.kind(plain)
            }
            when (kind) {
                /* 副词、形容词、连词、动词、成语：一次都不放过。 */
                1 -> return false
                /* 普通名词：当主语的时候多就留着（店小二、掌柜），少就挡掉（屋顶、书架）。 */
                2 -> if (total >= 2 && subj * 100 < 8 * total) return false
            }
        }

        /* 自称、谦称。出现上千次也常当主语，但那是说话人在指自己。 */
        if (w in SELFCALL) return false
        if (w in GROUPS) return false
        /* 通称要真挂过对白才算角色；只在旁白里露面的「一个男人」是路人。 */
        if (w in people && c < 1) return false
        /* 两字串的第二字是趋向补语，那是动词。 */
        if (w.length == 2 && Han.of(w[1]) in COMP_END) {
            /* 姓氏打头的放过一半：「白起」这种得真挂着对白才留。 */
            if (w[0] !in Words.surname || quote < 2) return false
        }
        /* 以单字副词、连词开头的两字串。姓氏里的曾、莫、方、连不在表内。 */
        if (w.length <= 3 && Han.of(w[0]) in LEAD_ADV) return false
        /* 两字串，首字既不是姓氏、也不是「老小阿」、也不是称谓 —— 那它要拿出更多证据。 */
        val plainShape = w.length == 2 && !sur0 && !nick0 && !call0 && Han.of(w[0]) !in TONE

        /* 露过二十次往上、一次主语都没当过、也没有一句对白挂在名下 —— 不管从哪条路进来的都不留。 */
        if (!shape.small && !shape.script && total >= 20 && subj == 0 && quote == 0) return false
        /* 自称词后面那一截（「草民地辰子拜见圣上」）也要能对上：后面挂过冒号，或者确实有对白挂在他名下。 */
        if (tagged > 0 && (quote >= 1 || (shape.script && colon >= 1))) return true
        if (tagged >= 4 && total <= 8) return true

        /* 全书只露过一两次面的，是路人，不留。 */
        if (cand < 2 && bound < 2 && de < 2 && colon < 1) return false

        /* 「前面是词边界」的比例。 */
        val near = if (cand > 0) (bound + de).toDouble() / cand else 0.0
        /* 露面太少的分母没有意义：全书九次里有两次在句界，说明不了什么。 */
        val trans = w.length >= 4 && Han.of(w.last()) in TONE
        if (cand >= 8 && near < shape.nearMin && !trans) return false
        /* 全书出现几百次、却几乎不长在句界上的，是词里的半截。 */
        if (total >= 50 && (bound + de) * 4 < total) return false

        /* 三字以上、前两个字是现成的词、又没说过几句话。 */
        if (w.length >= 3 && c < 8) {
            val pre2 = Lex.kind(w.substring(0, 2))
            if (pre2 == 1 || (pre2 == 2 && subj * 4 < total)) return false
            /* 名字不以大、来、众、人收尾。 */
            if (last in "大来众人" && !isCall(w)) return false
        }

        val sur = sur0
        val nick = nick0
        val call = call0


        /* 出现几十次往上，却几乎不当主语、也没有一次像在说话 ——「别开」后面永远跟着「口」，「杀伐」后面永远跟着「果断」，「修女」后面跟着「袍」「虔诚」。 */
        if (!shape.small && total >= 30 && quote < 2 && c + colon < 3 && subj * 8 < total)
            return false
        /* 大半时候后面跟的是「里 / 上 / 中」—— 那是个地方，不是人。 */
        if (total >= 6 && loc * 2 > total) return false
        /* 大半时候前面是「把 / 拿 / 送」—— 那是被搬来搬去的东西，不是人。 */
        if (total >= 2 && obj * 2 > total) return false

        /* 名字后面直接一个冒号 —— 剧本、对白体里全靠这一条。 */
        if (colon >= 2) return true
        /* 只见过一次冒号、右邻还只有一种字的，多半是「霍然大笑道」切出来的半截。 */
        if (colon >= 1 && (sur || nick || call) && (shape.small || colon >= 2 || right >= 2)) return true
        /* 不成名字形状的两字串，靠「名字 + ：」单独一条不够，还得右邻也活。 */
        if (colon >= 1 && !plainShape && (shape.small || colon >= 2 || right >= 2)) return true

        /* 当过说话人。长书要两次以上、右邻三种以上 ——「跪倒道」「忐忑道」只记一次、右邻只有「道」和标点，过不了这一关。 */
        /* 四字、没当过主语、末字不是译音字：成语和短语。 */
        if (w.length == 4 && Han.of(w.last()) !in TONE && subj * 3 < total) return false
        /* 右邻种类少过全书出现次数的一半、几乎不当主语、也没有一句对白挂在名下：后面接的是固定的那一个字，那是词里的一截，不是人。 */
        if (!shape.small && total >= 3 && right <= 6 && right * 2 < total &&
            quote == 0 && subj * 4 < total
        ) return false
        if (!plainShape && c >= shape.speakMin && right >= shape.rightMin) return true
        /* 对白几乎全挂在这串名下：每句同一模子的书，右邻永远只有「道」一个字。
分母用全书出现次数，不用 c（c 掺了自称词加权）。 */
        /* 对白几乎全挂在这串名下。 */
        if (quote >= 2 && quote * 2 >= maxOf(total, 2)) return true
        if (c >= 2 && quote * 2 >= c) return true
        /* 两字串、首字不是姓氏、也不是「老小阿」或称谓 —— 光「像在说话」不够。 */
        if (plainShape && quote >= 2 && right >= 3) return true
        if (plainShape && c >= 3 && right >= 4 && quote >= 1 && subj * 8 >= total) return true

        /* 开口说过、挂过对白、见过冒号，或者当主语的次数够多。 */
        val solid = c >= 1 || colon >= 1 || quote >= 1 ||
            subj * 2 >= total ||
            (bound * 10 >= total * 9 && subj >= 3)
        if (!shape.small && !solid) return false

        if (sur && right >= 4 && (subj >= 1 || c >= 1 || de >= 1 || headComma >= 1)) return true
        /* 以常见姓氏开头、至少三次长在句界、右邻三样以上。 */
        if (sur && bound >= 3 && right >= 3) return true
        if (call && right >= 6 && (c + colon >= 2 || subj * 10 >= total)) return true
        /* 老X / 小X / 阿X 这种叫法太泛（「老汉」「小伙」也是），右邻要求更高。 */
        if (nick && right >= 5) return true
        /* 段首呼语：「老李，你来了」。 */
        if (headComma >= 1 && right >= 3 && (sur || nick || call)) return true
        /* 段首出场：「格里高利从灯塔的门里出来」「老太太坐在门口的竹椅上」。 */
        if (!plainShape && head >= shape.headMin && bound >= 2 && total >= 2 &&
            (right >= shape.headRight || (w.length >= 3 && right >= 2))
        ) return true
        if (plainShape && head >= 3 && right >= 4 && quote >= 1) return true
        /* 白描体：整段没有引导语，段首那个名字就是主语，门槛再降一档。 */
        if (shape.plain && head >= 1 && (sur || nick || call) && right >= 2) return true
        /* 「卖豆腐的老李」「那个来买旧书的男人」：前面挂着一串定语的名字。 */
        if (sur && de >= 3 && right >= 4) return true
        if (call && de >= 3 && right >= 4) return true
        /* 白描的书里没人带引导语，只剩「当主语」这一条：三次以上，右邻不是死的。 */
        /* 当主语三次以上，而且当主语占它出现的次数一成以上。 */
        if (!plainShape && subj >= 3 && right >= 4 && subj * 8 >= total) return true
        if (plainShape && subj >= 6 && right >= 5 && quote >= 1) return true
        /* 四字音译名。末字是译音字、右邻活、全书露面三次以上 ——「卡斯帕尔」「艾尔德里克」在中文里没有别的用法，而它们的引导语常写成「……的某某，那个……的人，低声道：」，前面几条都够不着。 */
        if (trans && right >= 3 && cand >= 3) return true
        /* 短篇：全书只出现三四次的人，右邻天然少，上面几条都够不着。 */
        if ((nick || call) && bound + de >= 2 && right >= 2 && (subj >= 1 || c >= 1)) return true
        if (sur && bound + de >= 3 && right >= 3 && (subj >= 1 || c >= 1)) return true

        return false
    }

    /** 第一人称的引导语：段首「我」，二十字以内挂着引号。 */
    private val ME_HEAD = Regex("^我[^\\n]{0,20}[说道问答喊叫嚷][：:]?[\\s\\u3000]*[“「『]")

    /** 中文里能开一段引语的几种引号。 */
    private const val OPEN_Q = "\u201c\u300c\u300e\u0022"

    /** 说类动词的开头那个字。学状语时看「名字 + 几个字 + 这个字」。 */
    private const val VERBHEAD = "道说问答喊叫吼喝叹嚷喃讲"

    /** 名字紧跟这些字，说明它是受话人或者宾语，不是说话人。 */
    private const val PREP = "对向朝被把给让替于同与和"

    /** 只能当数量词、从来不是名字的形状：二人、三人、几位、一个。 */
    private val COUNT = Regex(
        "^[一二三四五六七八九十百千万两几数单双半][人个位名者员群帮伙队丝缕抹道片些点分毫层阵束股条只件张座具柄把根枝匹头尾支页篇段节回句字声步招拳脚刀剑枪箭]" +
            "$"
    )

    /** 把一个串开头那个名字剥掉。「丰老说道」→「说」。没有名字就原样返回。 */
    private fun dropName(w: String, names: Map<String, Int>): String {
        if (names.isEmpty()) return w
        val hit = names.keys.filter { w.length > it.length && w.startsWith(it) }.maxByOrNull { it.length }
            ?: return w
        return w.removePrefix(hit)
    }

    fun customs(
        text: String,
        names: Map<String, Int> = emptyMap(),
        minCount: Int = 8,
        /** 扫到多久为止。书再长也只花这么多时间，学到的说法够用就行。 */
        millis: Long = 9000,
        /** 每扫这么多字报一次进度。0 = 不报。 */
        onTick: ((Int) -> Unit)? = null,
    ): Pair<List<String>, List<String>> {
        if (text.isBlank()) return emptyList<String>() to emptyList()
        val n = text.length
        val deadline = System.nanoTime() + millis * 1_000_000L

        val knowVerb = Words.verbs.toSet()
        val knowAdv = Words.advs.toSet()
        val newVerb = ArrayList<String>()
        val newAdv = ArrayList<String>()

        /* 一、「X：」。先把名字剥掉，剩下的才是说法。 */
        val head = HashMap<String, Int>(512)
        var i = 0
        while (i < n) {
            if ((i and 0x3FFFF) == 0) {
                if (System.nanoTime() > deadline) break
                onTick?.invoke(i * 40 / n)
            }
            val c = text[i]
            if (c == '：' || c == ':') {
                var st = i
                var len = 0
                while (len < 4 && st > 0 && han(text[st - 1])) { st--; len++ }
                /* 前面还是汉字，说明这四格被上限截断了，截出来的是名字的后半截
* （「赵六低声道」截成「六低声」）。这种一律不要。 */
                if (len > 0 && !(st > 0 && han(text[st - 1]))) {
                    val w = dropName(text.substring(st, i), names)
                    if (w.length >= 2 && !names.containsKey(w)) head[w] = (head[w] ?: 0) + 1
                }
            }
            i++
        }
        for ((w, c) in head) {
            if (c < minCount) continue
            if (w in knowVerb || w in knowAdv) continue
            if (w.endsWith("道") && w.length >= 3) {
                val h = dropName(w.dropLast(1), names)
                if (h.length >= 2 && h !in knowAdv) newAdv.add(h)
            } else if (w.endsWith("道") || w.endsWith("说") || w.endsWith("问")) {
                val h = dropName(w, names)
                if (h.length >= 2) newAdv.add(h)
            }
        }

        /* 二、名字后面那一截。一个字符一个字符地往前推，不建大正则。 */
        val learned = HashMap<String, Int>(256)
        if (names.isNotEmpty()) {
            val ix2 = HashSet<Int>(names.size * 2)
            val ix3 = HashSet<Long>(names.size * 2)
            val ix4 = HashSet<Long>(names.size * 2)
            val ix5 = HashSet<String>(16)
            for (w in names.keys) {
                when (w.length) {
                    2 -> ix2.add(w[0].code shl 16 or w[1].code)
                    3 -> ix3.add(w[0].code.toLong() shl 32 or (w[1].code.toLong() shl 16) or w[2].code.toLong())
                    4 -> ix4.add(
                        w[0].code.toLong() shl 48 or (w[1].code.toLong() shl 32) or
                            (w[2].code.toLong() shl 16) or w[3].code.toLong()
                    )
                    5 -> ix5.add(w)
                }
            }
            fun nameAt(p: Int): Int {
                if (p + 1 >= n) return 0
                if (ix2.contains(text[p].code shl 16 or text[p + 1].code)) return 2
                if (p + 2 < n && ix3.contains(
                        text[p].code.toLong() shl 32 or (text[p + 1].code.toLong() shl 16) or text[p + 2].code.toLong()
                    )
                ) return 3
                if (p + 3 < n && ix4.contains(
                        text[p].code.toLong() shl 48 or (text[p + 1].code.toLong() shl 32) or
                            (text[p + 2].code.toLong() shl 16) or text[p + 3].code.toLong()
                    )
                ) return 4
                if (p + 4 < n && ix5.contains(text.substring(p, p + 5))) return 5
                return 0
            }
            /* 名字的首字只有几百个。先按首字过一遍，九成位置直接跳过去。 */
            val firsts = HashSet<Char>(names.size * 2)
            for (w in names.keys) if (w.isNotEmpty()) firsts.add(w[0])

            i = 0
            while (i < n) {
                if ((i and 0x3FFFF) == 0) {
                    if (System.nanoTime() > deadline) break
                    onTick?.invoke(60 + i * 40 / n)
                }
                if (!han(text[i]) || text[i] !in firsts) { i++; continue }
                val L = nameAt(i)
                if (L == 0) { i++; continue }
                val e = i + L
                /* 名字后面 1–3 个字，再往后一个说类动词的开头。取最长的那个切法。 */
                var k = 3
                while (k >= 2) {
                    val q = e + k
                    if (q < n && han(text[q]) && text[q] in VERBHEAD) {
                        var ok = true
                        for (t in e until q) if (!han(text[t])) { ok = false; break }
                        if (ok) {
                            val w = text.substring(e, q)
                            if (w !in knowAdv && w !in knowVerb && w !in Words.prons &&
                                w !in Words.selfs && w !in Words.titles && w !in names &&
                                w.last() !in "的了着地得"
                            ) learned[w] = (learned[w] ?: 0) + 1
                            break
                        }
                    }
                    k--
                }
                i = e
            }
        }
        for ((w, c) in learned) {
            if (c < 5) continue
            if (w in knowAdv) continue
            newAdv.add(w)
        }

        newVerb.sort()
        newAdv.sort()
        return newVerb.take(40) to newAdv.distinct().take(80)
    }
}

    /** 从一句原话里给这个人拼一条规则：名字 + 十二字以内 + 说类动词。中间可带逗号，不跨句号、引号。 */
object Match {

    /** 单字动词后面能跟的字符。跟 [Ban] 里那条先手是同一套。 */
    private const val STOP = "：:，,。！？；;“”「」\n \u3000"

    /** 一句话里不许跨过去的东西：句末和引号。跨过去就成了一句管两句。 */
    private const val GAP = "[^。！？；!?;\n「」『』“”]"

    /** 从一句原话里给这个人拼一条规则：名字 + 十二字以内 + 说类动词。中间可带逗号，不跨句号、引号。 */
    fun fromSample(line: String, ways: List<String>): String {
        val body = line.trim()
        if (body.isEmpty()) return ""
        val all = ways.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (all.isEmpty()) return ""

        val at = pick(body, all) ?: return ""

        val head = "(" + all.sortedByDescending { it.length }
            .joinToString("|") { java.util.regex.Pattern.quote(it) } + ")"

        /* 单字动词后面必须紧跟标点，否则「知道」「味道」全算「名字 + 道」。 */
        val one = "(?:" + Words.verbs1.joinToString("|") + ")(?=[" + STOP + "])"

        val tail = body.substring(at.first + at.second.length)
        val adv = Words.advs.filter { tail.startsWith(it) }.maxByOrNull { it.length }
        val rest = if (adv != null) tail.substring(adv.length) else tail
        val verb = Words.verbs.filter { it.length > 1 && rest.startsWith(it) }.maxByOrNull { it.length }
            ?: Words.verbs1.firstOrNull { rest.startsWith(it) }?.takeIf { v ->
                val next = rest.getOrNull(v.length)
                next == null || next in STOP
            }

        fun ok(p: String) = runCatching { Regex(p).containsMatchIn(body) }.getOrDefault(false)

        // 一、名字、状语、动词一个挨一个：最紧，也最不会误伤。
        if (verb != null && verb.length > 1) {
            val tight = head +
                (if (adv != null) "(?:" + java.util.regex.Pattern.quote(adv) + ")?" else "") +
                "(?:" + verb + ")"
            if (ok(tight)) return tight
        }

        // 二、同一个句子里隔了几个字。名字和动词之间允许夹逗号，但不许跨句末和引号。
        val withVerb = if (verb != null && verb.length > 1) "|(?:" + verb + ")" else ""
        val loose = head + "(?:" + GAP + "{0,12}?)(?:" + one + withVerb + ")"
        if (ok(loose)) return loose

        return ""
    }

    /** 这句话里这个名字从哪儿开始。优先取引号外面那一处 —— 引号里的是在叫他，不是他在说。 */
    private fun pick(body: String, ways: List<String>): Pair<Int, String>? {
        var best: Pair<Int, String>? = null
        var inside = false
        var i = 0
        var quoted = 0
        while (i < body.length) {
            if (body[i] == '“' || body[i] == '「' || body[i] == '『') quoted++
            if (body[i] == '”' || body[i] == '」' || body[i] == '』') quoted = (quoted - 1).coerceAtLeast(0)
            if (quoted == 0) {
                for (w in ways) {
                    if (w.isNotEmpty() && body.startsWith(w, i)) {
                        if (best == null) best = i to w
                        inside = true
                        break
                    }
                }
            }
            if (inside) break
            i++
        }
        if (best != null) return best
        return ways.mapNotNull { w -> body.indexOf(w).let { if (it < 0) null else it to w } }
            .minByOrNull { it.first }
    }

    /** 这句话里有没有这几个叫法。没有的话，规则写了也白写。 */
    fun found(line: String, ways: List<String>): Boolean =
        ways.map { it.trim() }.filter { it.isNotBlank() }.any { line.contains(it) }
}
