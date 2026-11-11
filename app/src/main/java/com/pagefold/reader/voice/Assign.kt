package com.pagefold.reader.voice

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.pagefold.reader.book.CastBook
import com.pagefold.reader.book.CastName
import com.pagefold.reader.book.CastRole

/**
 * 让一个大模型把名单分成组、给每组派一副嗓子。
 *
 * 这里有三个不变量，任何一条坏了这一份结果就不能用：
 *
 * 一、**不许多出人来。** 模型最爱干的事是顺手编几个名字。名单外的名字一律丢掉，
 *     一个都不往表里写 —— 写进去就是一堆念不出声的幽灵角色。
 * 二、**一个人只在一组。** 撞了归前一组。同一个人挂两副嗓子，念起来就是两个人。
 * 三、**嗓子必须是真有的那几副。** 造出来的（design）另算，那一路是自己建的，名字自己起。
 *
 * 其余的（组名起得难听、分得粗）都是可以改的，不值得为它把整份结果扔掉。
 */
object Assign {

    /**
     * 一组。
     *
     * @param name    组名。可能是人（「嬴政」），也可能只是标签（「众臣」）——
     *                这件事不在这里判，交给界面的 isPerson，按名单查。
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

    /**
     * 系统那一段。
     *
     * 不演角色。只交代要做的事和不能破的那几条 —— 一回话带人格，JSON 就没了。
     *
     * 口吻按用户原话定：「你现在是一个有声书的编导，对于我接下来提供的角色，还有句数，
     * 优先从我提供的音色里选择」。
     */
    const val SYSTEM = """你是一位有声书的编导。手上有一份角色名单（名字、句数）和一排能用的音色；你的活是给这些角色配念白的音色，让人听得出这一屋子有几个人。
只回一段 JSON，不要解释、不要寒暄、不要代码块围栏。
名单里的人一个不少、一个不多；不许编名单以外的名字，也不许把一个人分到两组。"""

    /**
     * 出厂的「怎么分」。
     *
     * 口径按用户原话写：先说身份和材料，再说「优先从我提供的音色里挑」，
     * 再说主角必须各有各的嗓子。后面那几条（组名、四十组上限、drop 口径）
     * 是防结果崩掉的硬规矩，不属于风格。
     */
    const val ASK = """你现在是一位有声书的编导。名单里是这本书的角色，名字后面是他在这本书里说过多少句。
先从我已经提供给你的音色里挑：**句数最多的那几个主角必须一人一副，彼此要听得出差别**，不能两个主角共用一副。
配角可以几个人共用一副；句数很少的（十来句、几句话）按身份并进相近的一组，不要一个人单开一副。
组名优先用这一组里的一个名字（这个人也算这一组的成员，要写进 members）；凑不出来才用类称：众臣、武将、内侍、宫女、门客、士兵、百姓、医者、胡人、少年。
独用一副音色的组最多四十个；再多就并组 —— 独用的太多，听起来像一个人在换腔。
drop 只放「明显不是人名」的：形容词、器物、泛称、半截短语。拿不准的留着，不要 drop。"""

    /**
     * 拼这一次要发出去的话。
     *
     * 每人一行「名字|台词数|他说过的一句」。那一句是判身份的全部依据 ——
     * 「臣以为不可」是文臣，「末将听令」是武将，「主子」是内侍；
     * 只给名字，模型只能按字面猜，猜出来全是「听起来像武将的名字」。
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
        /*
         * 现成的嗓子永远先给。
         *
         * 上一版是「开着小米就一副清单都不给，只让它自己造」—— 结果它每组
         * 造一副新的，音色库里现成的那几十副一次都没被看见。先给清单，
         * 挑不出合适的才让它造。
         */
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

    /**
     * 返回格式。这一段**不给改**。
     *
     * 改一个键名整份结果就解析不出来，而它不是口味问题 —— 是接口约定。
     * 界面上摆成灰底只读，就是为了让这件事一眼看得出来。
     */
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

            /*
             * 组名本人算不算成员，看名单。
             * 名单里有他（「嬴政」），就得把他写进 members —— 不然这一组少一个人，人数算不对。
             */
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
                /*
                 * 现成的清单里挑不出来、它自己开的那一副，才走 design 这条路。
                 *
                 * 挑到清单里有的名字时它就是一个普通组 —— 上一版是只要开着小米
                 * 就一律当成「要新造」，于是模型从清单里挑的东西全被改写成了新音色。
                 */
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


        /*
         * 名单里有、模型却没提到的（drop 里也没有）—— 不能就这么丢掉。
         * 并进「其余」一组：它们的嗓子跟「对白」那副走，比凭空消失好。
         */
        val left = known.filter { it !in claimed && it !in dropped }
        if (left.isNotEmpty()) {
            notes.add("${left.size} 个没分到的并进了「其余」")
            groups.add(Group("其余", left, if (design) "其余" else "", desc = if (design) "一群人，声音平平，念得清楚" else ""))
        }
        /*
         * 组名不许重。
         *
         * 模型偶尔给两个组起同一个名字（都叫「众臣」，或都叫「余下」）。
         * 重了会出两件事：角色表里出现同名的两条（列表画两遍、改嗓子只改到第一条），
         * 造音色时按名字映射，两组合用一副。所以重名的一律加个后缀。
         */
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
            /*
             * 挑到现成清单里的名字 → 直接用那一副，不新造。
             *
             * 这一条是「先给清单」那件事的收尾：光在提示词里给清单没用，
             * 这里还是会照着组名给它造一副新的，清单等于白给。
             */
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
     * 跟 3.0 的组口径一致：
     * · `alt` 只装组名以外的人；
     * · 组名本人算不算人，看名单（台词数 > 0，或勾着）—— 所以名单里要留下他，勾给他撤掉（他在组里）；
     * · 组员在名单里 `on = false` —— 他们已经挂在组上，再单独占一行就是同一个人出现两次。
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
