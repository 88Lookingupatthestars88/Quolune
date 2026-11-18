package com.pagefold.reader.voice

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.pagefold.reader.book.CastBook
import com.pagefold.reader.book.CastName
import com.pagefold.reader.book.CastRole

/** 让一个大模型把名单分成组、给每组派一副嗓子。这里有三个不变量，任何一条坏了这一份结果就不能用：一、不许多出人来。 */
object Assign {

    /**
* 一组。
*
* @param name    组名。可能是一个人，也可能只是标签。
* @param members 这一组的人。含组名本人（如果他是人的话）。
* @param voice   这一组用的嗓子名。
* @param desc    design 那一路才有的：一句嗓音描述，直接当 MiMo 的 voice 发出去。
*/
    data class Group(
        val name: String,
        val members: List<String>,
        val voice: String,
        val desc: String = "",
        val gender: String = "",
        val age: String = "",
    )

    data class Drop(val name: String, val why: String)

    /**
* 一份排好的结果。只活在预览纸上 —— 用户点「用这份」之前，一个字都不落盘。
*
* @param design true = 让模型写描述、本地造音色（小米那一路）；false = 从现有音色里挑。
* @param notes  校验时替它改过的地方，摆给用户看。改了什么要说，不然他不知道该信哪儿。
*/
    data class Plan(
        val groups: List<Group>,
        val drop: List<Drop>,
        val design: Boolean,
        val notes: List<String> = emptyList(),
    ) {
        val heads: Int get() = groups.sumOf { it.members.size }
    }

    /** 造出来的音色 + 「组名 → 实际嗓子名」。名字撞了要加后缀，所以必须对一遍。 */
    data class Batch(val voices: List<Voice>, val voiceName: Map<String, String>) {
        companion object {
            val NONE = Batch(emptyList(), emptyMap())
        }
    }

    // ── 提示词 ────────────────────────────────────────────────

    /** 系统那一段。不演角色。只交代要做的事和不能破的那几条 —— 一回话带人格，JSON 就没了。 */
    const val SYSTEM = """你是一位有声书的编导。手上有一份角色名单（名字、句数）和一排能用的音色；你的活是给这些角色配念白的音色，让人听得出这一屋子有几个人。
只回一段 JSON，不要解释、不要寒暄、不要代码块围栏。
名单里的人一个不少、一个不多；不许编名单以外的名字，也不许把一个人分到两组。"""

    /** 出厂的「怎么分」。口径按用户原话写：先说身份和材料，再说「优先从我提供的音色里挑」，再说主角必须各有各的嗓子。 */
    const val ASK = """你现在是一位有声书的编导。名单里是这本书的角色，名字后面是他在这本书里说过多少句。
先从我已经提供给你的音色里挑：**句数最多的那几个主角必须一人一副，彼此要听得出差别**，不能两个主角共用一副。
配角可以几个人共用一副；句数很少的（十来句、几句话）按身份并进相近的一组，不要一个人单开一副。
组名优先用这一组里的一个名字（这个人也算这一组的成员，要写进 members）；凑不出来才用类称：众臣、武将、内侍、宫女、门客、士兵、百姓、医者、胡人、少年。
独用一副音色的组最多四十个；再多就并组 —— 独用的太多，听起来像一个人在换腔。
drop 只放「明显不是人名」的：形容词、器物、泛称、半截短语。拿不准的留着，不要 drop。"""

    // ── 另一档：把没认出是谁说的话分给人 ──────────────────────

    /** 一句没认出来的话，判给了谁。[who] 为空 = 它也判不出来。 */
    data class Said(
        val ch: Int,
        val at: Int,
        val text: String,
        val who: String,
    )

    /**
* 一道题。一句话加它前后那一段上下文。
*
* @param i      序号，从 1 起，全批连续
* @param ch     章序号。同一章的题在纸上排在一块，名单也只给这一章的
* @param where  章名
* @param page   这一页，目标句用 ⟦ ⟧ 框着
* @param say    目标句本身
* @param prevPage 前一页
* @param cast   这一章能开口的人。who 只能从这里挑
*/
    data class Ask(
        val i: Int,
        val ch: Int,
        val where: String,
        val page: String,
        val say: String,
        val prevPage: String = "",
        val cast: List<String> = emptyList(),
    )

    /** 那一档读回来的东西：谁说的、还该加谁、该去谁。 */
    data class Judge(
        val at: Map<Int, String> = emptyMap(),
        val add: List<String> = emptyList(),
        val drop: List<String> = emptyList(),
    )

    /** 第三档的系统那一段。 */
    val SYSTEM2 = """你是一位有声书的编辑，负责判明小说里每一句对白是谁说的。只回 JSON，不回别的任何内容。"""

    /** 拼这一档要发出去的话：身份 → 例子（题面紧跟该怎么回）→ 每章名单 + 题 → 只回 JSON。 */
    fun prompt2(title: String, rows: List<Ask>): String {
        val b = StringBuilder(1 shl 16)
        b.append("你是一位有声书的编辑，正在把《").append(title).append("》录成多人播讲。\n")
        b.append("作者写的时候没给每句对白标是谁说的，你来补上。要判的是 ⟦ ⟧ 框住的那一句。\n\n")

        b.append("先看一道例子，照它的样子回（小红、小明是举例，不是这本书里的人）：\n\n")
        b.append("  小红把伞收起来，⟦「你怎么才来？」⟧小明没接话，把门关上了。\n")
        b.append("  门口站着一个穿雨衣的中年人，小红没认出他是谁。\n\n")
        b.append("  这一题这样回：\n")
        b.append("    {\"i\":1,\"who\":\"小红\",\"add\":[\"中年人\"],\"drop\":[]}\n\n")
        b.append("  who   这一句是谁说的。名字紧贴着 ⟦ ⟧，就把那个名字照抄进来。")
        b.append("他不在下面那份名单里也照抄 —— 下一轮他就进名单了。\n")
        b.append("  add   要加的名字。这一页里出现过、名单上没有的人，填在这里，一个人一个字符串。")
        b.append("who 里已经写过的名字，这里再写一遍。\n")
        b.append("  drop  要去掉的名字。名单上那些根本不是人名的词（形容词、器物、半截词），")
        b.append("填在这里，下一轮它就不在名单上了。\n\n")

        /* 同一章的题排在一块，名单只给这一章的。 */
        var last = Int.MIN_VALUE
        rows.forEach { r ->
            if (r.ch != last) {
                last = r.ch
                b.append("════════ ")
                b.append(if (r.where.isNotBlank()) r.where else "第 ${r.ch + 1} 章")
                b.append(" ════════\n")
                b.append("这一章能开口的人，一共 ").append(r.cast.size).append(" 个：\n")
                r.cast.chunked(10).forEach { b.append("  ").append(it.joinToString("、")).append('\n') }
                b.append('\n')
            }
            b.append("【题 ").append(r.i).append("】\n")
            if (r.prevPage.isNotBlank()) {
                b.append("前一页：\n")
                r.prevPage.lineSequence().filter { it.isNotBlank() }
                    .forEach { b.append("  ").append(it.trim()).append('\n') }
            }
            b.append("这一页：\n")
            r.page.lineSequence().filter { it.isNotBlank() }
                .forEach { b.append("  ").append(it.trim()).append('\n') }
            b.append('\n')
        }

        b.append("回满 ").append(rows.size).append(" 条，i 从 1 到 ").append(rows.size)
            .append("，一条都不能少。\n")
        b.append("只回这一段 JSON，不解释、不复述题目、不加代码块围栏：\n")
        b.append("{\"say\":[{\"i\":1,\"who\":\"…\",\"add\":[],\"drop\":[]},")
            .append("{\"i\":2,\"who\":\"…\",\"add\":[],\"drop\":[]}]}\n")
        return b.toString()
    }

    /**
* 读回「第几题归谁」。
*
* @param namesOf 题号 → 那一题的名单。每一章的名单不同，所以按题号查，不按整批。
* @param count   这一批发出去几道。有的模型从 0 起编号，靠它把序号抬回 1 起。
* @return 题号 → 名字。名字对不上名单的那一项直接丢掉 —— 宁可留着没认出来，也不能凭空多出一个人。
*/
    fun parse2(raw: String, namesOf: (Int) -> Set<String>, count: Int = 0): Judge {
        val a = raw.indexOf('{')
        val z = raw.lastIndexOf('}')
        val b = raw.indexOf('[')
        val y = raw.lastIndexOf(']')
        /* 两种形状都认：外面套一层对象（{"say":[...]}），或者干脆只回一个数组。 */
        val obj = if (a >= 0 && z > a) runCatching {
            JsonParser.parseString(raw.substring(a, z + 1)).asJsonObject
        }.getOrNull() else null
        val arr = (obj?.get("say")?.let { runCatching { it.asJsonArray }.getOrNull() })
            ?: (if (b >= 0 && y > b) runCatching {
                JsonParser.parseString(raw.substring(b, y + 1)).asJsonArray
            }.getOrNull() else null)
        val list = arr ?: return Judge()

        val out = HashMap<Int, String>(list.size() * 2)
        list.forEach { e ->
            val o = runCatching { e.asJsonObject }.getOrNull() ?: return@forEach
            /* 序号那几个键名各家写法不一样，都认。 */
            val i = runCatching {
                (o.get("i") ?: o.get("id") ?: o.get("index") ?: o.get("n")).asInt
            }.getOrNull() ?: return@forEach
            /* 「who」「speaker」「name」都见过。 */
            val w = runCatching {
                (o.get("who") ?: o.get("speaker") ?: o.get("name")).asString
            }.getOrNull().orEmpty().trim()
            if (w.isNotEmpty()) out[i] = w
        }
        /* 只认 1..count 的题号，多出来的丢掉：模型会照上一批的条数多回几条，
* 多出来的会盖住真正的第 1 题。0 起编号由 [prompt2] 钉住，这里不猜。 */
        if (count > 0) out.keys.retainAll { it in 1..count }

        /* 名字对到名单上：多了「道」「说」这种尾巴、或者只写了半截，都往回收一格。 */
        val canon = HashMap<Int, String>(out.size * 2)
        out.forEach { (k, v) -> canon[k] = tie(v, namesOf(k)) ?: v }
        fun list(key: String): List<String> {
            val v = obj?.get(key) ?: return emptyList()
            val ar = runCatching { v.asJsonArray }.getOrNull() ?: return emptyList()
            return ar.mapNotNull { runCatching { it.asString }.getOrNull()?.trim() }
                .filter { it.length in 2..5 }
        }
        return Judge(canon, list("add").distinct(), list("drop").distinct())
    }

    /** 模型写回来的名字像不像个人名。判给他、可他不在这份名单上时，程序会把名字写进名单 —— 得先挡住它塞进来的半截句子和标点。 */
    fun plausible(w: String): Boolean = w.length in 2..5 && w.all { it in '\u4e00'..'\u9fa5' }

    /** 把模型写的名字对到名单上。 */
    fun tie(raw: String, names: Set<String>): String? {
        if (raw in names) return raw
        if (names.isEmpty()) return null
        val w = raw.trim().trim('「', '」', '“', '”', '"', '：', ':')
            .substringBefore('（').substringBefore('(').trim()
        if (w in names) return w
        val cut = w.dropLastWhile { it in "道说问答喊叫笑骂喝叹" }
        if (cut != w && cut in names) return cut
        if (w.length >= 2) {
            val hits = names.filter { it.contains(w) || w.contains(it) }
            if (hits.size == 1) return hits[0]
        }
        return null
    }

    /**
* 拼这一次要发出去的话。
*
* @param ask 用户改写过的口径。空 = 用 [ASK]。
*/
    fun prompt(
        title: String,
        pool: List<CastName>,
        design: Boolean,
        voices: List<Voice> = emptyList(),
        ask: String = "",
        /** 详细那一路：每人后面带一句他说过的话。简单那一路只报名字和句数。 */
        withSample: Boolean = true,
    ): String = buildString {
        append("书名：").append(title.ifBlank { "这本书" }).append('\n')
        append("说话人一共 ").append(pool.size).append(" 个。名单如下，每行是：")
        append(if (withSample) "名字|台词数|他说过的一句话" else "名字|台词数")
        append('\n')
        append("---\n")
        pool.sortedByDescending { it.lines }.forEach { p ->
            append(p.name.trim()).append('|').append(p.lines)
            if (withSample) append('|').append(one(p.sample))
            append('\n')
        }
        append("---\n\n")
        append(ask.ifBlank { ASK }).append('\n').append('\n')
        /* 现成的嗓子永远先给，随名单一起发出去。 */
        if (voices.isNotEmpty()) {
            append("准用的音色（voice 一栏把名字原样抄过去，一个字都别改）：\n")
            voices.forEach { v ->
                append(v.name)
                append("｜").append(sexOf(v.gender)).append("｜").append(ageOf(v.age))
                    .append("｜").append(v.desc)
                append('\n')
            }
            append('\n')
        }
        if (design) {
            if (voices.isEmpty()) {
                append("库里一副现成的都没有，这一趟全部由你自己开：voice 填组名，desc 写一句嗓音描述。\n")
            } else {
                append("上面这些实在挑不出合适的，你可以自己开一副：voice 填组名，desc 写一句嗓音描述，六十字以内，只写「什么年纪、什么性别、什么音色、什么语气」。\n")
            }
            append("例：中年男声，低沉偏沙，语速慢，压着说话，带一点不耐烦。\n")
            append("不许写「声音」「嗓音」这类没信息的话；同一伙的配角用相近的描述，只改年纪和音色，让人听得出他们是一路的。\n")
            append("gender 填 m 或 f，age 填 young（少年）/ middle（中年）/ old（老年）。\n\n")
        } else {
            append("只能从上面这些里挑。挑不出一模一样的，挑最接近的；真挑不出来就把最像的那一副给这一组，别自造。\n\n")
        }
        append(part(design))
    }

    /** 返回格式。不给改：改一个键名，整份结果就解析不出来。 */
    fun part(design: Boolean): String = if (design) {
        """返回格式（这一段不许改）：
{"groups":[{"name":"组名","members":["人","人"],"voice":"嗓子或组名","desc":"一句嗓音描述","gender":"m","age":"middle"}],"drop":[{"name":"名字","why":"八个字以内"}]}"""
    } else {
        """返回格式（这一段不许改）：
{"groups":[{"name":"组名","members":["人","人"],"voice":"上面清单里的名字"}],"drop":[{"name":"名字","why":"八个字以内"}]}"""
    }

    private fun one(s: String): String {
        val t = s.replace('\n', ' ').replace('\r', ' ').replace('|', ' ')
            .replace('\u3000', ' ').trim()
        return if (t.isEmpty()) "（无）" else t.take(40)
    }

    private fun sexOf(g: String): String = when (g) {
        "m" -> "男"
        "f" -> "女"
        else -> "?"
    }

    private fun ageOf(a: String): String = when (a) {
        "young" -> "少年"
        "old" -> "老年"
        "middle" -> "中年"
        else -> "?"
    }

    // ── 解析与校验 ─────────────────────────────────────────────

    /**
* 把回话拆成一份能用的结果。
*
* @param known      名单里真有谁。名单外的名字一律丢掉 —— 这是「不许编人」那道闸。
* @param voiceNames 现在音色库里有哪些嗓子。挑嗓子那一路用它拦假名字。
* @return (结果, 一句话)。结果为 null 时那句话就是失败原因；不为 null 时那句话是「替它改了什么」。
*/
    fun parse(
        raw: String,
        known: Set<String>,
        design: Boolean,
        voiceNames: Set<String> = emptySet(),
    ): Pair<Plan?, String> {
        val text = Chat.tidy(raw)
        val root = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            ?: return null to "对面回的这段不是 JSON"

        val notes = ArrayList<String>(4)
        val dropped = HashMap<String, String>(32)
        val unknown = HashSet<String>(16)

        val dropArr = arrayOf("drop", "drops", "剔除", "noise", "noiseList")
            .firstNotNullOfOrNull { k -> root.get(k)?.takeIf { it.isJsonArray }?.asJsonArray }
        dropArr?.forEach { el ->
            if (!el.isJsonObject) return@forEach
            val o = el.asJsonObject
            val n = str(o, "name").ifBlank { str(o, "名字") }.trim()
            val w = str(o, "why").ifBlank { str(o, "reason") }.ifBlank { str(o, "理由") }.trim()
            if (n.isBlank()) return@forEach
            if (n !in known) { unknown.add(n); return@forEach }
            dropped[n] = w.take(12)
        }

        val grpArr = arrayOf("groups", "group", "分组", "roles")
            .firstNotNullOfOrNull { k -> root.get(k)?.takeIf { it.isJsonArray }?.asJsonArray }
            ?: return null to "回话里没有 groups 这一段"

        val claimed = HashSet<String>(known.size * 2)
        val groups = ArrayList<Group>(48)
        var fakeVoice = 0
        var fakeName = 0

        grpArr.forEach { el ->
            if (!el.isJsonObject) return@forEach
            val o = el.asJsonObject
            val memArr: JsonArray? = arrayOf("members", "people", "成员", "who")
                .firstNotNullOfOrNull { k -> o.get(k)?.takeIf { it.isJsonArray }?.asJsonArray }
            val raw = memArr?.mapNotNull {
                runCatching { it.asString }.getOrNull()?.trim()
            }.orEmpty()
            var name = str(o, "name").ifBlank { str(o, "组名") }.trim()
            if (name.isBlank()) name = raw.firstOrNull().orEmpty()
            if (name.isBlank()) return@forEach
            // 组名太长不像名字，截一下；它只会当标签用。
            if (name.length > 8) name = name.take(8)

            /* 组名本人算不算成员，看名单。 */
            val cand = ArrayList<String>(raw.size + 1)
            if (name in known) cand.add(name)
            raw.forEach { n ->
                if (n.isBlank()) return@forEach
                if (n !in known) { unknown.add(n); return@forEach }
                cand.add(n)
            }

            val mine = cand.filter { claimed.add(it) }
            if (mine.isEmpty()) return@forEach
            if (mine.size != cand.distinct().size) fakeName++

            var voice = str(o, "voice").ifBlank { str(o, "嗓子") }.trim()
            var desc = ""
            if (design && (voice.isBlank() || voice == name)) {
                /* 现成的清单里挑不出来、它自己开的那一副，才走 design 这条路。 */
                desc = str(o, "desc").ifBlank { str(o, "描述") }.trim()
                // 描述缺失就拿组名当描述 —— 也比空着好，空着这一组就是默认声音。
                if (desc.isBlank()) desc = "${sexOf(str(o, "gender"))}声，${ageOf(str(o, "age"))}，念得清楚"
                voice = ""
            } else {
                if (voice.isBlank() || (voiceNames.isNotEmpty() && voice !in voiceNames)) {
                    fakeVoice++
                    voice = ""
                }
            }
            groups.add(
                Group(
                    name = name,
                    members = mine,
                    voice = voice,
                    desc = desc.take(60),
                    gender = sex(strict(o, "gender")),
                    age = age(strict(o, "age")),
                )
            )
        }

        if (groups.isEmpty()) return null to "一组都没分出来"


        /* 名单里有、模型却没提到的（drop 里也没有）—— 不能就这么丢掉。 */
        val left = known.filter { it !in claimed && it !in dropped }
        if (left.isNotEmpty()) {
            notes.add("${left.size} 个没分到的并进了「其余」")
            groups.add(Group("其余", left, if (design) "其余" else "", desc = if (design) "一群人，声音平平，念得清楚" else ""))
        }
        /* 组名不许重。模型偶尔给两个组起同一个名字（都叫「众臣」，或都叫「余下」）。 */
        val title = HashMap<String, Int>(groups.size * 2)
        val unique = ArrayList<Group>(groups.size)
        groups.forEach { g ->
            val n = title.getOrDefault(g.name, 0)
            title[g.name] = n + 1
            unique.add(if (n == 0) g else g.copy(name = g.name + " " + (n + 1)))
        }

        if (fakeVoice > 0) notes.add("$fakeVoice 组挑了不存在的嗓子，改成对白那副")
        if (fakeName > 0) notes.add("$fakeName 处重复的人归到了前一组")
        if (unknown.isNotEmpty()) notes.add("丢掉了 ${unknown.size} 个名单外的名字")

        return Plan(
            groups = unique,
            drop = dropped.map { (n, w) -> Drop(n, w) },
            design = design,
            notes = notes,
        ) to notes.joinToString("；")
    }

    /** 音色名收尾：撞了加「 2」「 3」。名字是用户认得出的那个东西，不许被覆盖。 */
    fun batch(plan: Plan, taken: Set<String>): Batch {
        if (!plan.design) {
            return Batch(emptyList(), plan.groups.associate { it.name to it.voice })
        }
        val used = taken.toMutableSet()
        val out = ArrayList<Voice>(plan.groups.size)
        val map = HashMap<String, String>(plan.groups.size * 2)
        plan.groups.forEach { g ->
            /* 挑到现成清单里的名字 → 直接用那一副，不新造。 */
            if (g.voice.isNotBlank() && g.voice in taken) {
                map[g.name] = g.voice
                return@forEach
            }
            val base = g.name.trim().ifBlank { "群" }.take(9)
            var nm = base
            var i = 2
            while (nm in used) { nm = "$base $i"; i++ }
            used += nm
            map[g.name] = nm
            out += Voice(
                id = "u_ai_" + nm.hashCode().toString(16) + "_" + g.desc.take(4).hashCode().toString(16),
                name = nm,
                kind = "design",
                engine = "mimo",
                param = g.desc.take(60),
                desc = g.desc.take(24),
                gender = g.gender,
                age = g.age,
                tags = listOf("AI"),
            )
        }
        return Batch(out, map)
    }

    /**
* 把这份结果落成一张派角表。
*
* @param left 用户决定留下的（本来要被剔掉的）名字。这些不进 off，也不算 drop。
*/
    fun commit(cast: CastBook, plan: Plan, squad: Batch, left: Set<String> = emptySet()): CastBook {
        val lines = cast.pool.associate { it.name to it.lines }
        val samples = cast.pool.associate { it.name to it.sample }
        val gone = plan.drop.map { it.name }.filter { it !in left }.toSet()

        val roles = ArrayList<CastRole>(plan.groups.size)
        val pool = ArrayList<CastName>(cast.pool.size + 8)
        val seen = HashSet<String>(cast.pool.size * 2)

        plan.groups.forEach { g ->
            val voice = squad.voiceName[g.name].orEmpty()
            roles += CastRole(
                name = g.name,
                voice = voice,
                alt = g.members.filter { it != g.name },
            )
            g.members.forEach { n ->
                if (n in gone || !seen.add(n)) return@forEach
                pool += CastName(
                    name = n,
                    lines = lines[n] ?: 0,
                    // 组员不单独占一行；组名本人更是（他自己就是那一组的名字）。
                    on = false,
                    sample = samples[n].orEmpty(),
                )
            }
        }
        // 名单里剩下的人（模型没提到、也没被剔掉的）照旧留着，一个都不许凭空消失。
        cast.pool.forEach { p ->
            if (p.name in gone || !seen.add(p.name)) return@forEach
            pool += p
        }
        return cast.copy(
            roles = roles,
            pool = pool,
            off = (cast.off + gone).distinct(),
        )
    }

    // ── 小工具 ────────────────────────────────────────────────

    private fun str(o: com.google.gson.JsonObject, k: String): String =
        runCatching { o.get(k)?.takeIf { it.isJsonPrimitive }?.asString }.getOrNull().orEmpty()

    private fun strict(o: com.google.gson.JsonObject, k: String): String =
        str(o, k).trim().lowercase()

    private fun sex(raw: String): String = when {
        raw.startsWith("m") || raw.contains("男") -> "m"
        raw.startsWith("f") || raw.contains("女") -> "f"
        else -> ""
    }

    private fun age(raw: String): String = when {
        raw.contains("young") || raw.contains("少") || raw.contains("童") -> "young"
        raw.contains("old") || raw.contains("老") -> "old"
        raw.contains("middle") || raw.contains("中") -> "middle"
        else -> ""
    }
}
