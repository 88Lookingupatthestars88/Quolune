package com.pagefold.reader.book

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken

/**
 * 一句待朗读的文本。
 *
 * srcStart / srcEnd 是这一句在**本章正文**里的字符区间（闭开区间）。
 * 没有它，播放器只知道「第几行」，界面只知道「第几段」，
 * 两套坐标对不上 —— 从某处开始听就会偏、念到哪也没法高亮。
 */
data class Line(
    val text: String,
    val isDialog: Boolean,
    val speaker: String,
    val voice: String,
    val srcStart: Int = -1,
    val srcEnd: Int = -1,
    /** 这一句该用的调门。空 = 按旁白/对白各自的默认来。 */
    val style: String = "",
)

/**
 * 一条匹配规则：用什么正则认出一个人，认出来派给谁。
 *
 * @param character 这条规则派给谁。必须跟角色表里的名字一模一样。
 * @param pattern   正则。只在引号外面那半句里找，不碰正文。
 * @param afterDialog 看引号后面那半句（true）还是前面（false）
 * @param enabled   关掉的那条不参与
 */
data class CastRule(
    val character: String = "",
    val pattern: String = "",
    val afterDialog: Boolean = false,
    val enabled: Boolean = true,
    /** 只有「说白 / 白口」这两行用它：那一行第二列写的是嗓子。别的角色嗓子在 CastRole 上。 */
    val slotVoice: String = "",
) {
    private var compiled: Regex? = null
    private var tried = false

    fun regex(): Regex? {
        if (tried) return compiled
        tried = true
        compiled = runCatching { Regex(pattern) }.getOrNull()
        return compiled
    }

    fun bad(): Boolean = character.isBlank() || pattern.isBlank() || regex() == null
}

/**
 * 自动匹配时数出来的一个人。
 *
 * @param lines 这本书里他有多少句台词。用来排序，也用来决定「值不值得单独配一副嗓子」。
 * @param on    用户勾上没有。默认只勾台词多的那几个。
 */
data class CastName(
    val name: String = "",
    val lines: Int = 0,
    val on: Boolean = false,
    /** 代词这一格用得上：m / f / 空。界面上不再让人填，只在导入旧表时读进来。 */
    val sex: String = "",
    /** 别的叫法。书里一处写「邮差小陈」、一处只写「小陈」，两种都得认。 */
    val alt: List<String> = emptyList(),
)

/** 一个角色：名字 + 嗓子 + 调门。嗓子空 = 用白口那副。 */
data class CastRole(
    val name: String,
    val voice: String = "",
    val style: String = "",
    /** 别的叫法。认人的时候一起编进正则，配嗓子仍然只认 [name]。 */
    val alt: List<String> = emptyList(),
    /** 类别名。「秦·谋士」这种 —— 从老的配置里带进来的，只用来标注，不参与认人。 */
    val tag: String = "",
) {
    /** 全部叫法，第一个是显示名。空的和重的剔掉。 */
    fun ways(): List<String> =
        (listOf(name) + alt).map { it.trim() }.filter { it.isNotBlank() }.distinct()
}

/** 拿去认人的一条。plan 只认识这个，不关心它是怎么存下来的。 */
data class Rule(
    val scope: Scope,
    val role: String,
    val voice: String,
    val pattern: String,
) {
    enum class Scope { BEFORE, AFTER, ANY }

    private var compiled: Regex? = null

    fun regex(): Regex? {
        compiled?.let { return it }
        return runCatching { Regex(pattern) }.also { compiled = it.getOrNull() }.getOrNull()
    }

    fun bad(): Boolean = regex() == null
}

/**
 * 一本书的角色表。存成 .tlcast。
 *
 * 分两层：
 *   roles  —— 有谁，各用哪副嗓子
 *   rules  —— 怎么认出他，一条一行正则
 * 一个人可以有好几种叫法，所以是几条规则指向同一个人，不是一个人一条规则。
 */
data class CastBook(
    val v: Int = 4,
    val name: String = "",
    val narrator: String = "",
    val dialog: String = "",
    val narratorStyle: String = "",
    val dialogStyle: String = "",
    val roles: List<CastRole> = emptyList(),
    val rules: List<CastRule> = emptyList(),
    /** 自动匹配数出来的名单。导入时跑一次，之后照着它勾。 */
    val pool: List<CastName> = emptyList(),
    /** 勾了的题材包。只影响怎么认人，不影响谁用哪副嗓子。 */
    val packs: List<String> = emptyList(),
    /** 从书里抽出来的说法，比题材准。 */
    val extraVerb: List<String> = emptyList(),
    val extraAdv: List<String> = emptyList(),
    val extraTitle: List<String> = emptyList(),
    /** 名字 → m/f。代词那一层要用。 */
    val sex: Map<String, String> = emptyMap(),
    /** 用户点掉的词。题材包里点了不要的，收在这一串里，合成词表时减掉。 */
    val off: List<String> = emptyList(),
) {
    /** 全部叫法：角色自身的名字与别名。 */
    fun ways(): List<String> = roles.flatMap { it.ways() }.distinct()

    fun voiceOf(role: String): String =
        roles.firstOrNull { it.name == role }?.voice.orEmpty()

    fun styleOf(role: String): String =
        roles.firstOrNull { it.name == role }?.style.orEmpty()

    /** 交给 plan 的那串规则。关掉的、写坏的全部剔掉。 */
    fun active(): List<Rule> = rules
        .filter { it.enabled && !it.bad() }
        .map {
            Rule(
                scope = if (it.afterDialog) Rule.Scope.AFTER else Rule.Scope.ANY,
                role = it.character,
                voice = voiceOf(it.character),
                pattern = it.pattern,
            )
        }

    fun with(role: CastRole): CastBook {
        val list = roles.toMutableList()
        val i = list.indexOfFirst { it.name == role.name }
        if (i >= 0) list[i] = role else list.add(role)
        return copy(roles = list)
    }

    fun without(role: String): CastBook = copy(
        roles = roles.filter { it.name != role },
        rules = rules.filter { it.character != role },
    )
}

/**
 * 角色表的读写。
 *
 * 写出去的长这样：
 *   {"narration":{"voice":"白桦","style":""},"dialogue":{…},
 *    "characters":{"林瑾":{"voice":"云健"}},"matchRules":[{"character":"林瑾","pattern":"…"}]}
 *
 * 读进来三种都认：上面这个、带 v 的老格式、和一行一条的文本。
 * 三种最后都汇成 [CastBook]，界面只认那一个。
 */
object RuleCodec {

    private val gson = Gson()

    private fun arr(list: List<String>): com.google.gson.JsonArray =
        com.google.gson.JsonArray().apply { list.forEach { add(it) } }

    private fun str(o: com.google.gson.JsonObject?, k: String): String =
        runCatching { o?.get(k)?.takeIf { !it.isJsonNull }?.asString }.getOrNull().orEmpty()

    private fun styleOf(o: com.google.gson.JsonObject?): Pair<String, String> =
        str(o, "voice") to str(o, "style")

    /** 别的叫法。缺了就空表，老存档里没有这个键。 */
    private fun alts(o: com.google.gson.JsonObject?): List<String> = runCatching {
        o?.getAsJsonArray("alt")?.mapNotNull { e -> runCatching { e.asString }.getOrNull() }
            ?.filter { it.isNotBlank() }.orEmpty()
    }.getOrDefault(emptyList())

    fun toJson(cast: CastBook): String {
        val root = com.google.gson.JsonObject()
        root.addProperty("v", 4)
        fun slot(voice: String, style: String) = com.google.gson.JsonObject().apply {
            addProperty("voice", voice)
            addProperty("style", style)
        }
        root.add("narration", slot(cast.narrator, cast.narratorStyle))
        root.add("dialogue", slot(cast.dialog, cast.dialogStyle))
        val chars = com.google.gson.JsonObject()
        cast.roles.forEach { r ->
            val o = slot(r.voice, r.style)
            if (r.alt.isNotEmpty()) o.add("alt", arr(r.alt))
            if (r.tag.isNotBlank()) o.addProperty("tag", r.tag)
            chars.add(r.name, o)
        }
        root.add("characters", chars)
        val rules = com.google.gson.JsonArray()
        cast.rules.forEach { r ->
            rules.add(com.google.gson.JsonObject().apply {
                addProperty("character", r.character)
                addProperty("pattern", r.pattern)
                addProperty("afterDialog", r.afterDialog)
                addProperty("enabled", r.enabled)
            })
        }
        root.add("matchRules", rules)
        if (cast.pool.isNotEmpty()) {
            val pool = com.google.gson.JsonArray()
            cast.pool.forEach { n ->
                pool.add(com.google.gson.JsonObject().apply {
                    addProperty("name", n.name)
                    addProperty("lines", n.lines)
                    addProperty("on", n.on)
                    if (n.sex.isNotBlank()) addProperty("sex", n.sex)
                    if (n.alt.isNotEmpty()) add("alt", arr(n.alt))
                })
            }
            root.add("pool", pool)
        }
        if (cast.packs.isNotEmpty()) {
            root.add("packs", com.google.gson.JsonArray().apply { cast.packs.forEach { add(it) } })
        }
        if (cast.extraVerb.isNotEmpty()) {
            root.add("extraVerb", com.google.gson.JsonArray().apply { cast.extraVerb.forEach { add(it) } })
        }
        if (cast.extraAdv.isNotEmpty()) {
            root.add("extraAdv", com.google.gson.JsonArray().apply { cast.extraAdv.forEach { add(it) } })
        }
        if (cast.extraTitle.isNotEmpty()) {
            root.add("extraTitle", com.google.gson.JsonArray().apply { cast.extraTitle.forEach { add(it) } })
        }
        if (cast.sex.isNotEmpty()) {
            val sex = com.google.gson.JsonObject()
            cast.sex.forEach { (k, v) -> sex.addProperty(k, v) }
            root.add("sex", sex)
        }
        if (cast.off.isNotEmpty()) root.add("off", arr(cast.off))
        return gson.toJson(root)
    }

    fun fromJson(raw: String): CastBook {
        val root = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
            ?: return CastBook()
        // 新格式：narration / dialogue / characters / matchRules 四个键里有一个就算
        val hasNew = root.has("characters") || root.has("matchRules") ||
            root.has("narration") || root.has("dialogue")
        if (hasNew) {
            val (nv, ns) = styleOf(root.getAsJsonObject("narration"))
            val (dv, ds) = styleOf(root.getAsJsonObject("dialogue"))
            val roles = ArrayList<CastRole>()
            runCatching {
                root.getAsJsonObject("characters")?.entrySet()?.forEach { e ->
                    val o = e.value.takeIf { it.isJsonObject }?.asJsonObject
                    val (v, s) = styleOf(o)
                    roles += CastRole(e.key, v, s, alts(o), str(o, "tag"))
                }
            }
            val rules = ArrayList<CastRule>()
            runCatching {
                root.getAsJsonArray("matchRules")?.forEach { el ->
                    val o = el.asJsonObject
                    val who = str(o, "character")
                    val pat = str(o, "pattern")
                    if (who.isNotBlank() && pat.isNotBlank()) {
                        rules += CastRule(
                            character = who,
                            pattern = pat,
                            /*
                             * 两个键名都认：我们自己写的是 afterDialog，别处生成的配置
                             * 写的是 afterDialogue。只认一个的话，导进来的规则会全部
                             * 当成「前后都看」—— 看着能用，其实换了套判法。
                             */
                            afterDialog = runCatching {
                                o.get("afterDialog")?.asBoolean ?: o.get("afterDialogue")?.asBoolean
                            }.getOrNull() ?: false,
                            enabled = runCatching { o.get("enabled")?.asBoolean }.getOrNull() ?: true,
                        )
                    }
                }
            }
            val pool = ArrayList<CastName>()
            runCatching {
                root.getAsJsonArray("pool")?.forEach { el ->
                    val o = el.asJsonObject
                    val n = str(o, "name")
                    if (n.isNotBlank()) {
                        pool += CastName(
                            name = n,
                            lines = runCatching { o.get("lines")?.asInt }.getOrNull() ?: 0,
                            on = runCatching { o.get("on")?.asBoolean }.getOrNull() ?: false,
                            sex = str(o, "sex"),
                            alt = alts(o),
                        )
                    }
                }
            }
            fun strList(k: String): List<String> = runCatching {
                root.getAsJsonArray(k)?.mapNotNull { runCatching { it.asString }.getOrNull() }.orEmpty()
            }.getOrDefault(emptyList())
            val sex = LinkedHashMap<String, String>()
            runCatching {
                root.getAsJsonObject("sex")?.entrySet()?.forEach { e ->
                    runCatching { e.value.asString }.getOrNull()?.let { sex[e.key] = it }
                }
            }
            return CastBook(
                narrator = nv, dialog = dv, narratorStyle = ns, dialogStyle = ds,
                roles = roles, rules = rules,
                pool = pool,
                packs = strList("packs"),
                extraVerb = strList("extraVerb"),
                extraAdv = strList("extraAdv"),
                extraTitle = strList("extraTitle"),
                sex = sex,
                off = strList("off"),
            ).let { b0 ->
                /*
                 * 类别名换真名。
                 *
                 * 老配置里 characters 的键是「秦·帝王」，真人名在 pattern 里。
                 * 照类别名建组，界面上就全是类别名，用户认不出那是谁。
                 * 有 pattern 就用里面那第一个名字当组名，类别名收进别名。
                 */
                val b = b0.copy(
                    roles = b0.roles.map { r ->
                        val members = b0.rules.filter { it.character == r.name }
                            .flatMap { membersIn(it.pattern) }
                            .distinct()
                        val real = members.firstOrNull()
                        if (real != null && real != r.name) {
                            r.copy(name = real, alt = members.drop(1), tag = r.name)
                        } else r
                    }.distinctBy { it.name },
                )
                // 规则提到了、角色表里却没有的人，补一个空嗓子，否则永远配不上人
                val missing = b.rules.map { it.character }.distinct().filter { n ->
                    b.roles.none { it.name == n }
                }
                val withRoles = b.copy(roles = b.roles + missing.map { CastRole(it) })
                /*
                 * 别处生成的配置只有 characters，没有 pool。列表是照 pool 画的，
                 * 于是「导进来有角色，界面上却一个都没有，只剩规则」。
                 * 名单空着就照角色表补一份，全部勾上 —— 人是他自己配好的，不用再挑。
                 */
                if (withRoles.pool.isEmpty() && withRoles.roles.isNotEmpty()) {
                    withRoles.copy(
                        pool = withRoles.roles.map { CastName(it.name, 0, on = true, alt = it.alt) },
                    )
                } else withRoles
            }
        }
        // 我们自己 v2/v3 的老格式：roles 里带着 pattern/scope
        val out = CastBook(
            narrator = str(root, "narrator"),
            dialog = str(root, "dialog"),
            narratorStyle = str(root, "narratorStyle"),
            dialogStyle = str(root, "dialogStyle"),
        )
        val roles = ArrayList<CastRole>()
        val rules = ArrayList<CastRule>()
        runCatching {
            root.getAsJsonArray("roles")?.forEach { el ->
                val o = el.asJsonObject
                val n = str(o, "name")
                if (n.isBlank()) return@forEach
                roles += CastRole(n, str(o, "voice"), str(o, "style"))
                val pat = str(o, "pattern")
                if (pat.isNotBlank()) {
                    rules += CastRule(
                        character = n,
                        pattern = pat,
                        afterDialog = str(o, "scope").equals("after", true),
                        enabled = runCatching { o.get("enabled")?.asBoolean }.getOrNull() ?: true,
                    )
                }
            }
        }
        // 老格式里另一处：顶层的 rules 数组
        runCatching {
            root.getAsJsonArray("rules")?.forEach { el ->
                val o = el.asJsonObject
                val who = str(o, "role").ifBlank { str(o, "voice") }
                val pat = str(o, "pattern")
                if (who.isNotBlank() && pat.isNotBlank()) {
                    rules += CastRule(who, pat, str(o, "scope").equals("after", true), true)
                }
            }
        }
        return out.copy(roles = roles, rules = rules)
    }

    /**
     * 一行一条：谁 | 嗓子 | 正则 [| 前/后]
     *
     * 第二、三列可以空。正则里带竖线是常态（`(说道|沉声道)`），
     * 所以只从左往右切两刀，后面整段都算正则。split 全切会把正则切碎。
     */
    fun fromText(raw: String): List<CastRule> {
        val out = ArrayList<CastRule>()
        raw.lines().forEach { line ->
            val s = line.trim()
            if (s.isEmpty() || s.startsWith("#") || s.startsWith("//")) return@forEach
            val t = s.replace('｜', '|').replace('\t', '|')
            val i1 = t.indexOf('|')
            if (i1 <= 0) return@forEach
            val i2 = t.indexOf('|', i1 + 1)
            val who = t.substring(0, i1).trim()
            val voice = if (i2 < 0) t.substring(i1 + 1).trim() else t.substring(i1 + 1, i2).trim()
            var pat = if (i2 < 0) "" else t.substring(i2 + 1).trim()
            var after = false
            // 末尾单独挂一个「前 / 后」，其余全算正则
            if (pat.endsWith("|前") || pat.endsWith("|后")) {
                after = pat.endsWith("|后")
                pat = pat.dropLast(2).trim()
            }
            if (who.isBlank()) return@forEach
            out += CastRule(who, pat, after, true, slotVoice = voice)
        }
        return out
    }


    /** 括号里的分隔符：空白、顿号、逗号、斜杠都算。 */
    private val SPLIT = Regex("[\\s\\u3000、,，/／｜|]+")

    /** 「旁白」「对白」这两个词，导入时可省略冒号或空格。 */
    private val SLOT_NARR = setOf("旁白", "说白", "narrator", "narration", "叙述")
    private val SLOT_DIAL = setOf("对白", "白口", "dialog", "dialogue", "对话")

    /**
     * 一行一组：
     *
     *     （赵惊鸿）苏打
     *     （嬴政 扶苏 胡亥）秦·帝王
     *     旁白 白桦
     *     对白 苏打
     *
     * 括号里是这一组的人（书里怎么写就怎么写），括号后面是这一组用的嗓子。
     * 组名默认取括号里第一个人的名字 —— 那是书里的真名，不是「秦·帝王」这种类别名。
     * 不带括号的「名字 嗓子」也认，就是一个人一组。
     */
    fun fromGroups(raw: String): CastBook {
        var nar = ""
        var dia = ""
        val roles = ArrayList<CastRole>()
        raw.lines().forEach { line ->
            val s = line.trim()
            if (s.isEmpty() || s.startsWith("#") || s.startsWith("//")) return@forEach

            // 括号：全角半角方括号都认
            val open = s.indexOfFirst { it == '（' || it == '(' || it == '【' || it == '[' }
            if (open >= 0) {
                val close = s.indexOfFirst { it == '）' || it == ')' || it == '】' || it == ']' }
                if (close > open) {
                    val inside = s.substring(open + 1, close)
                    val voice = s.substring(close + 1).trim().trimStart('|', '：', ':', '-', ' ')
                    val ways = inside.split(SPLIT).map { it.trim() }.filter { it.isNotBlank() }
                    if (ways.isNotEmpty()) {
                        val name = ways.first()
                        roles.removeAll { it.name == name }
                        roles.add(CastRole(name, voice, alt = ways.drop(1)))
                    }
                    return@forEach
                }
            }

            // 不带括号：第一个词是名字，其余是嗓子。中间用什么隔都行。
            val parts = s.split(SPLIT).map { it.trim() }.filter { it.isNotBlank() }
            if (parts.isEmpty()) return@forEach
            val head = parts[0]
            val rest = parts.drop(1).joinToString(" ")
            when (head) {
                in SLOT_NARR -> { nar = rest; return@forEach }
                in SLOT_DIAL -> { dia = rest; return@forEach }
            }
            roles.removeAll { it.name == head }
            roles.add(CastRole(head, rest))
        }
        // 名单：正名一行（勾上），别名不必各占一行 —— 别名在角色里，认人的时候一起编进去。
        val pool = roles.map { CastName(it.name, 0, on = true, alt = it.alt) }
        return CastBook(narrator = nar, dialog = dia, roles = roles, pool = pool)
    }

    /**
     * 导出一行一组。
     *
     * 只写名字和嗓子，不写正则 —— 正则是一次性的兜底，换个应用、换本书都没用，
     * 混在文件里只会让人以为那是必须的。
     */
    fun toGroups(cast: CastBook, title: String = ""): String = buildString {
        appendLine("# 派角" + if (title.isNotBlank()) " · $title" else "")
        appendLine("# 一行一组：括号里是这一组的人，括号后面是这一组用的嗓子。")
        appendLine("# 组里可以只有一个人。这些行可以随便改，再导回来就行。")
        appendLine()
        appendLine("旁白 " + cast.narrator)
        appendLine("对白 " + cast.dialog)
        appendLine()
        cast.roles.forEach { r ->
            if (r.ways().isEmpty()) return@forEach
            append("（").append(r.ways().joinToString(" ")).append("）").append(r.voice).appendLine()
        }
    }

    /**
     * 从一条老正则里，把这一组的人扒出来。
     *
     * 早先那套配置的 characters 写的是「秦·谋士」这种类别名，真名在 pattern 里：
     * `(?:(?:姜元让|黄石公|张良|林瑾|萧何|彭堰|韩信|范增|李斯))(?:道|说道|…)`。
     * 照类别名建组，界面上就全是类别名 —— 「他都不是真名」说的就是这个。
     *
     * 取的是第一个「全部由 2–4 字人名用竖线连起来」的组，跳过前面那个否定预查
     * （`(?!(?:A|B|C))` 里也全是人名，但那是「除了这些人以外」的意思，不能当成员）。
     */
    /** 长得像一组、其实不是人的那些。 */
    private val NOISE = setOf(
        "传来", "响起", "传出", "声音", "话音", "说道", "开口", "喃喃", "低声", "沉声",
        "说道", "自语", "嘀咕", "冷声", "轻声", "大声", "齐声", "喝道",
    )

    private val GROUP_IN_PATTERN =
        Regex("\\(\\?:([\\u4e00-\\u9fa5]{2,4}(?:\\|[\\u4e00-\\u9fa5]{2,4})*)\\)")

    fun membersIn(pattern: String): List<String> {
        for (m in GROUP_IN_PATTERN.findAll(pattern)) {
            /*
             * `(?!(?:A|B|C))` 里也全是人名，但那是「除了这些人以外」的意思，不能当成员。
             * 它的长相是 `(?!(?:` —— 判断这四个字符，而不是判断 `(?:` 前面那两个字。
             */
            val st = m.range.first
            if (st >= 2 && pattern[st - 1] in "!=<" && pattern[st - 2] == '?') continue
            val list = m.groupValues[1].split('|').map { it.trim() }
                // 「（传来|响起|传出声音）」这种也是 `(?:A|B)`，但它说的是声音，不是人。
                .filter { it.isNotBlank() && it !in NOISE }
            if (list.isNotEmpty()) return list
        }
        return emptyList()
    }

    fun encode(rules: List<CastRule>): String = buildString {
        appendLine("# 一行一条：谁 | 嗓音 | 正则 [| 后]")
        appendLine("# 竖线只切前两刀，正则里的竖线不会被切坏。以 # 开头的行不算。")
        rules.forEach {
            append(it.character).append('|').append(it.slotVoice).append('|').append(it.pattern)
            if (it.afterDialog) append("|后")
            appendLine()
        }
    }

    /** 把一串规则收成一张表：说到「说白 / 白口」的那两行，改的是旁白和对白。 */
    fun toBook(rules: List<CastRule>, narrator: String, dialog: String): CastBook {
        var nar = narrator
        var dia = dialog
        val roles = LinkedHashMap<String, CastRole>()
        val keep = ArrayList<CastRule>()
        rules.forEach { r ->
            when (r.character) {
                "说白", "旁白", "narrator" -> { nar = r.slotVoice.ifBlank { nar }; return@forEach }
                "白口", "对白", "dialog" -> { dia = r.slotVoice.ifBlank { dia }; return@forEach }
            }
            val v = r.slotVoice
            // 名字那一格可以写好几个叫法：「邮差小陈 / 小陈」。第一个是正名，其余是别名。
            val ways = r.character.split('/', '／', '、').map { it.trim() }.filter { it.isNotBlank() }
            val name = ways.firstOrNull() ?: r.character
            val alt = ways.drop(1)
            if (!roles.containsKey(name)) roles[name] = CastRole(name, v, alt = alt)
            keep += if (name == r.character) r else r.copy(character = name)
        }
        return CastBook(narrator = nar, dialog = dia, roles = roles.values.toList(), rules = keep)
    }
}

/** 一份完整的角色方案，交给 plan 用。 */
data class CastProfile(
    val narratorVoice: String,
    val defaultVoice: String,
    val narratorStyle: String = "",
    val dialogStyle: String = "",
    val rules: List<Rule>,
    /** 角色名 → 嗓子。查不到就用 defaultVoice，绝不拿名字当嗓子用。 */
    val voices: Map<String, String> = emptyMap(),
    /** 角色名 → 调门。空串和查不到都按对白那档走。 */
    val styles: Map<String, String> = emptyMap(),
    /** 这本书认得的全部叫法，预先编好的。空 = 只走手写规则。 */
    val ban: Ban? = null,
)

/**
 * 分角色引擎。
 *
 * 处理顺序：
 *   段落 → 切成「旁白 / 对白」交替片段 → 每句对白找说话人 → 找不到就交替推断 → 再不行用默认音色
 *
 * 比「只看引号前 256 字」的做法多两层兜底：
 *   1. 连续对白：第一句定了人，第二句如果没线索，按 A→B→A 交替推
 *   2. 段落级的「某某说」会被整段共享，不再局限于紧邻的一句
 */
object Cast {

    private val QUOTE_PAIRS = listOf(
        '“' to '”', '「' to '」', '『' to '』', '"' to '"', '\'' to '\''
    )

    /**
     * 把一章正文切成带说话人、带字符区间的行序列。
     *
     * @param chunk 单行上限。超过就找最近的句末切一刀 —— 太长的段一次合成等得久，
     *              而且服务端会静默截断。0 = 不切。
     */
    fun plan(text: String, p: CastProfile, chunk: Int = 800): List<Line> {
        val out = ArrayList<Line>(256)
        var lastSpeaker: String? = null
        var beforeLastSpeaker: String? = null

        var pStart = 0
        while (pStart <= text.length) {
            val base = pStart
            var pEnd = text.indexOf('\n', base)
            if (pEnd < 0) pEnd = text.length
            val para = text.substring(base, pEnd)
            pStart = pEnd + 1

            if (para.isBlank()) { if (pEnd >= text.length) break else continue }

            val segs = splitQuotes(para)
            val hasDialog = segs.any { it.isDialog }
            if (!hasDialog) {
                // 整段没有对白：纯旁白
                val whole = trimRange(para, 0, para.length)
                if (whole != null) {
                    cut(para, whole.first, whole.second, chunk) { a, b ->
                        Wash.of(para.substring(a, b)).takeIf { it.isNotBlank() }?.let { t ->
                            out.add(Line(t, false, "", p.narratorVoice, base + a, base + b, p.narratorStyle))
                        }
                    }
                }
                if (pEnd >= text.length) break else continue
            }

            val paraNames = namesIn(para, p.rules)
            for ((idx, seg) in segs.withIndex()) {
                val body = para.substring(seg.from, seg.to)
                if (body.isBlank()) continue
                val absA = base + seg.from
                val absB = base + seg.to
                if (!seg.isDialog) {
                    cut(para, seg.from, seg.to, chunk) { a, b ->
                        Wash.of(para.substring(a, b)).takeIf { it.isNotBlank() }?.let { t ->
                            out.add(Line(t, false, "", p.narratorVoice, base + a, base + b, p.narratorStyle))
                        }
                    }
                    continue
                }
                val beforeCtx = if (idx > 0) para.substring(segs[idx - 1].from, segs[idx - 1].to).takeLast(140) else ""
                val afterCtx = if (idx + 1 < segs.size) para.substring(segs[idx + 1].from, segs[idx + 1].to).take(140) else ""
                // 这一段里除了当前这句引号之外的全部文字。用来兜「整段只有一个人」。
                val lead = StringBuilder(para.length).apply {
                    append(para, 0, seg.from)
                    if (seg.to <= para.length) append(para, seg.to, para.length)
                }.toString().take(400)

                var hit = matchRules(beforeCtx, afterCtx, p.rules)
                var name = hit?.role
                /*
                 * 手写规则没命中，走六层判定。
                 *
                 * 六层的证据比「整段里提到过谁」硬得多：先看引号紧邻那一小句的主谓，
                 * 再看代词，再看整段唯一，最后才退到段首。规则在前是因为那是用户自己写的，
                 * 写错了也应该由他自己看见结果 —— 不是被引擎偷偷盖掉。
                 */
                if (name == null) name = p.ban?.let { Speaker.at(beforeCtx, afterCtx, lead, it) }
                if (name == null && paraNames.size > 1) {
                    name = paraNames.firstOrNull { it != lastSpeaker } ?: paraNames.last()
                }
                if (name == null) {
                    name = when {
                        beforeLastSpeaker != null && beforeLastSpeaker != lastSpeaker -> beforeLastSpeaker
                        lastSpeaker == null -> null
                        else -> lastSpeaker
                    }
                }
                // 规则里写了嗓子就用它；没写就按名字查一次；再没有就用白口那副。
                val voice = hit?.voice?.takeIf { it.isNotBlank() }
                    ?: (if (name != null) voiceFor(name, p) else p.defaultVoice)
                cut(para, seg.from, seg.to, chunk) { a, b ->
                    Wash.of(para.substring(a, b)).takeIf { it.isNotBlank() }?.let { t ->
                        out.add(Line(t, true, name.orEmpty(), voice, base + a, base + b, name?.let { p.styles[it] }.orEmpty()))
                    }
                }
                if (name != null) {
                    beforeLastSpeaker = lastSpeaker
                    lastSpeaker = name
                }
                if (absB <= absA) continue
            }
            if (pEnd >= text.length) break
        }
        return out
    }

    /** 把 [a,b) 按 chunk 上限切成几块，切点尽量落在句末。 */
    private inline fun cut(
        para: String,
        a0: Int,
        b0: Int,
        chunk: Int,
        emit: (Int, Int) -> Unit,
    ) {
        if (chunk <= 0 || b0 - a0 <= chunk) { emit(a0, b0); return }
        var a = a0
        while (a < b0) {
            var b = (a + chunk).coerceAtMost(b0)
            if (b < b0) {
                var k = b
                while (k > a + chunk / 3) {
                    val c = para[k - 1]
                    if (c == '。' || c == '！' || c == '？' || c == '；' || c == '…') break
                    k--
                }
                if (k > a + chunk / 3) b = k
            }
            val t = trimRange(para, a, b)
            if (t != null) emit(t.first, t.second)
            a = b
        }
    }

    /** 去掉首尾空白，返回新区间；全空白回 null。 */
    private fun trimRange(s: String, a0: Int, b0: Int): Pair<Int, Int>? {
        var a = a0
        var b = b0
        while (a < b && s[a].isWhitespace()) a++
        while (b > a && s[b - 1].isWhitespace()) b--
        return if (b > a) a to b else null
    }

    private class Seg(val isDialog: Boolean, val from: Int, val to: Int)

    /** 把一段拆成（旁白 / 对白）交替的片段，带段内区间。 */
    private fun splitQuotes(s: String): List<Seg> {
        val res = ArrayList<Seg>()
        var i = 0
        var segStart = 0
        while (i < s.length) {
            val c = s[i]
            val pair = QUOTE_PAIRS.firstOrNull { it.first == c }
            if (pair == null) { i++; continue }
            trimRange(s, segStart, i)?.let { res.add(Seg(false, it.first, it.second)) }
            val from = i + 1
            var j = from
            var close = -1
            while (j < s.length) {
                if (s[j] == pair.second) { close = j; break }
                j++
            }
            if (close < 0) {
                // 引号没配对：剩下的都当旁白
                trimRange(s, i, s.length)?.let { res.add(Seg(false, it.first, it.second)) }
                return res
            }
            trimRange(s, from, close)?.let { res.add(Seg(true, it.first, it.second)) }
            i = close + 1
            segStart = i
        }
        trimRange(s, segStart, s.length)?.let { res.add(Seg(false, it.first, it.second)) }
        return res
    }

    /** 找出文本里命中规则的音色（用于段落级共享）。 */
    private fun namesIn(text: String, rules: List<Rule>): List<String> {
        val hit = ArrayList<Pair<Int, String>>()
        for (r in rules) {
            val re = r.regex() ?: continue
            val m = re.find(text) ?: continue
            hit.add(m.range.first to r.role)
        }
        return hit.sortedBy { it.first }.map { it.second }.distinct()
    }

    /** 按顺序匹配规则，返回音色。 */
    fun matchRules(before: String, after: String, rules: List<Rule>): Rule? {
        for (r in rules) {
            val re = r.regex() ?: continue
            val hay = when (r.scope) {
                Rule.Scope.BEFORE -> before
                Rule.Scope.AFTER -> after
                Rule.Scope.ANY -> "$before\n$after"
            }
            if (hay.isBlank()) continue
            if (re.containsMatchIn(hay)) return r
        }
        return null
    }

    /**
     * 这个角色用哪副嗓子。
     *
     * 查角色表，查不到才用白口那副。返回 `name.ifBlank { defaultVoice }` 不行：
     * 那样每一行 voice 都是「林瑾」「赵高」这种人名，音色库里没有，合成时全落回默认。
     */
    private fun voiceFor(name: String, p: CastProfile): String =
        p.voices[name]?.takeIf { it.isNotBlank() } ?: p.defaultVoice

    // ─────────── 内置模板 ───────────

    /**
     * 通用中文网文模板。
     * 覆盖：说道 / 道 / 笑道 / 冷声道 / 沉声 / 怒道 / 低声 / 开口 / 问 / 答 / 喝道 …
     * 名字用「2–4 个汉字」捕获，不依赖具体人名，所以任何书都能先跑起来。
     */
    val TEMPLATE: String = """
# 一行一条：谁 | 嗓音 | 正则 [| 后]
# 正则里带竖线没问题，只切前两刀。

# 名字在引号后面：「……，某某说道」
某某 |  | ([\u4e00-\u9fa5]{2,4})(说道|问道|答道|笑道|冷笑道|沉声道|低声道|怒道|喝道|叹道|开口)

# 名字在引号前面：「某某说道：“……”」
某某 |  | ([\u4e00-\u9fa5]{2,4})(说道|问道|笑道|沉声道)|后

# 只有称呼
陛下 |  | (陛下|殿下|将军|大人|先生|姑娘|公子)(说道|道|开口)
""".trim()

    fun templateRules(): List<Rule> = listOf()
}
