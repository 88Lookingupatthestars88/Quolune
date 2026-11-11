package com.pagefold.reader.voice

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pagefold.reader.App
import com.pagefold.reader.book.CastName
import com.pagefold.reader.book.CastRole
import com.pagefold.reader.book.Lines
import com.pagefold.reader.book.RuleCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 「让 AI 排」这件事的状态机。
 *
 * 为什么放在 object 里而不是页面里：用户要能中途走开。派角是一张半屏的纸，
 * 退回书架、翻两页书，这一趟都该接着跑 —— 状态挂在 Composable 上，纸一收
 * 协程就没了，回来看到的是「什么都没发生」。
 *
 * 五步，一步一步来：
 *   PICK   开着的接口不止一路时先问用哪一路；只有一路就跳过
 *   DETAIL 详细（每人带一句台词）还是简单（只报名字）
 *   NAMES  这一趟发哪些人、不发哪些人；不发的人他那些话怎么办，这一页说清
 *   RUN    真发。一批一个请求，进度是实数
 *   PLAN   排回来的东西，一组一行，挨个点「用」还是「不用」
 *
 * 分批不为快，为三件事：进度报得出来、上下文不炸、断了能从断的地方接。
 */
object AiJob {

    enum class Step { PICK, DETAIL, NAMES, RUN, PLAN }

    /** 一批几个人。七十个名字加各自那句台词，一次请求两千多 token。 */
    const val BATCH = 70

    // ── 纸 ──
    var open by mutableStateOf(false)
    var step by mutableStateOf(Step.PICK)
    /** 阅读页看到这个才把派角纸撑开（顶上横幅点进来的那条路）。 */
    var wantCast by mutableStateOf(false)
    /** 结果好了、人还没看。顶上那条横幅看它。 */
    var fresh by mutableStateOf(false)

    // ── 这一趟排的是哪本书、哪份名单 ──
    var bookId by mutableStateOf("")
    var title by mutableStateOf("")
    private var app: App? = null

    // ── 第一步：用哪一路接口 ──
    var picks by mutableStateOf<List<ChatCfg>>(emptyList())
    var chatId by mutableStateOf("")

    // ── 第二步：详细还是简单 ──
    var deep by mutableStateOf(true)

    // ── 第三步：发谁 ──

    var pool by mutableStateOf<List<CastName>>(emptyList())

    /**
     * 门槛：几句话以上才发。用户自己写，默认 50。
     *
     * 上一版写死 3，退出再进来又变回 3 —— 它根本没存。现在这一格跟着
     * [Settings.aiGate] 走，进来读到什么就是什么。
     */
    var gate by mutableStateOf(50)

    /**
     * 这一次发出去的「怎么分」。空 = 出厂那份（[Assign.ASK]）。
     *
     * 三级：这本书自己的 > 全局（[Settings.aiPrompt]）> 出厂。
     */
    var ask by mutableStateOf("")

    /** 手改过的名字。调门槛不再覆盖它 —— 用户点过的就是他说了算。 */
    var touched by mutableStateOf<Map<String, Boolean>>(emptyMap())

    /** 实际发出去的那些人。由门槛和 [touched] 算出来。 */
    var sent by mutableStateOf<Set<String>>(emptySet())

    /** 每人挑给模型看的那一句。名字 → 那句话。空 = 还没挑，用默认。 */
    var picks1 by mutableStateOf<Map<String, String>>(emptyMap())

    /**
     * 允许模型自己造新嗓音。
     *
     * 上一版这一格等于「小米开着没有」——开着就一定造，且看不见现成的。
     * 现在是用户自己的选择：默认不造，先从库里挑。
     */
    var design by mutableStateOf(false)

    /** 允许模型挑的那些现成嗓音。全选默认。 */
    var voiceUse by mutableStateOf<Set<String>>(emptySet())

    /** 库里有这些嗓音可选（全量）。 */
    var voiceAll by mutableStateOf<List<String>>(emptyList())

    /** 真正发给模型的候选：用户点中的那些。 */
    private fun offered(): List<Voice> = voicePool.filter { it.name in voiceUse }

    private var voiceNames: Set<String> = emptySet()

    /** 现成的嗓子。**永远**先发给模型当候选，挑不出来（且用户允许）才让它造。 */
    private var voicePool: List<Voice> = emptyList()

    /** 这本书自己的「怎么分」，用来决定「存成默认」这件事是不是真的改了全局。 */
    private var bookBook: com.pagefold.reader.book.CastBook? = null

    // ── 第四步：进度 ──
    var stage by mutableStateOf("")
    var brief by mutableStateOf("")
    var done by mutableStateOf(0)
    var total by mutableStateOf(0)
    var err by mutableStateOf<String?>(null)
    var paused by mutableStateOf(false)
    var startedAt by mutableStateOf(0L)

    // ── 第五步：结果 ──
    var plan by mutableStateOf<Assign.Plan?>(null)
    /** 哪几组用。默认全用，用户可以一组一组摘。 */
    var use by mutableStateOf<Set<String>>(emptySet())
    /** 它判成不是人名、用户又点回「留」的那些。 */
    var keep by mutableStateOf<Set<String>>(emptySet())
    /** 落过一次盘就加一。派角页看它决定要不要重读角色表。 */
    var stamp by mutableStateOf(0)

    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /* 已经认下的组、已经丢掉的名字、已经算过的人。中途续跑靠这三坨。 */
    private val acc = ArrayList<Assign.Group>()
    private val gone = LinkedHashMap<String, String>()
    private val seen = HashSet<String>()
    private val extra = ArrayList<String>()

    /**
     * 摊开这张纸。入口只有两个：派角页那颗按钮，和顶上那条横幅。
     *
     * @return 空 = 开了；非空 = 开不了，那句话就是原因。
     */
    fun begin(
        a: App,
        book: com.pagefold.reader.book.BookMeta?,
        cast: com.pagefold.reader.book.CastBook,
    ): String? {
        app = a
        val now = a.field.now
        /*
         * 「能用」的那几路：开着、地址有、钥匙有、模型挑过。
         * 只开一路时这一步不问，直接用；开了两路以上才让用户挑 ——
         * 上回是排在前面那一路，用户根本不知道谁在替他拿主意。
         */
        val ready = now.chats.filter {
            it.on && Chats.why(it) == null && (it.model ?: "").isNotBlank()
        }
        if (ready.isEmpty()) return "先去定音里填一路接口"
        val list = cast.pool.filter { it.name.isNotBlank() }
        /* 名单空着也可能还有人在：单句改派指过的人不在名单里。 */
        if (list.isEmpty() && cast.pins.none { it.who.isNotBlank() }) return "名单是空的，先数一遍人"

        val des = now.engines.any { it.on && it.src == com.pagefold.reader.field.VoiceSource.MIMO }
        /*
         * 现成的嗓子，永远取。
         *
         * 上一版是「开着小米就当没有清单」，于是模型每组造一副新的，
         * 音色库里那几十副它一次都没看见。先让它挑，挑不出来再让它造。
         */
        val voices = Catalog.groups(now.engines, a.box.custom()).flatMap { it.second }
        if (voices.isEmpty() && !des) return "先去定音里开一路声源"

        bookId = book?.id.orEmpty()
        title = book?.title.orEmpty()
        picks = ready
        chatId = ready.first().id
        deep = true
        /*
         * 句数要带上「单句改派」。
         *
         * 名单里那个数是数人时扫出来的；用户在书里点一句改成某人，名单不知道。
         * 不带上的话这个人写着 0 句，门槛 100 一筛就发不出去 —— 而用户明明
         * 已经给他指了一句话（「发哪些人」那一页他看着就是 0 句）。
         */
        val pinN = HashMap<String, Int>(16)
        cast.pins.forEach { p -> if (p.who.isNotBlank()) pinN[p.who] = (pinN[p.who] ?: 0) + 1 }
        val withPins = ArrayList<com.pagefold.reader.book.CastName>(list.size + pinN.size)
        list.forEach { n -> withPins.add(n.copy(lines = n.lines + (pinN[n.name] ?: 0))) }
        pinN.forEach { (n, c) ->
            if (withPins.none { it.name == n }) {
                withPins.add(com.pagefold.reader.book.CastName(n, c, on = true))
            }
        }
        pool = withPins.sortedByDescending { it.lines }
        /* 老存档没有这一格，Gson 会塞 0 —— 0 在这件事上没有意义，退回出厂那个 50。 */
        gate = (if (now.aiGate > 0) now.aiGate else 50).coerceIn(1, 9999)
        touched = emptyMap()
        picks1 = emptyMap()
        auto.clear()
        sent = pool.filter { it.lines >= gate }.map { it.name }.toSet()
        /*
         * 起手这一档照**定音 · 派角**那一节来。
         *
         * 上一版是「小米开着就一定要造」，用户每本书都得进去把这件事按回来；
         * 现在两点都在设置里定：准不准它造、准它用哪几副。书里那张纸只能临时改。
         */
        val want = now.aiVoiceSet
        val poolNames = voices.map { it.name }
        design = des && now.aiDesign
        voicePool = voices
        voiceAll = poolNames
        voiceUse = if (want == null) poolNames.toSet() else poolNames.filter { it in want }.toSet()
        if (voiceUse.isEmpty() && !design) {
            return "一副嗓子都没准它用。去定音 · 派角里挑几副，或者打开「准它自己造嗓音」"
        }
        voiceNames = voiceUse.ifEmpty { poolNames.toSet() }
        /* 这本书自己写过的口径优先，其次是全局那份，都没有就是出厂那份。 */
        val own = book?.id?.let { id -> a.shelf.find(id)?.castJson.orEmpty() }.orEmpty()
        val cb = runCatching { com.pagefold.reader.book.RuleCodec.fromJson(own) }
            .getOrDefault(com.pagefold.reader.book.CastBook())
        bookBook = cb
        ask = cb.prompt.ifBlank { now.aiPrompt }

        plan = null
        err = null
        paused = false
        stage = ""
        brief = ""
        startedAt = 0L
        done = 0
        total = pool.size
        clear()

        step = if (ready.size > 1) Step.PICK else Step.DETAIL
        open = true
        return null
    }

    /**
     * 改门槛。
     *
     * 手点过的名字不跟门槛走 —— 他点过「发」，门槛调到 50 也不该把它摘掉。
     */
    fun gateTo(g: Int) {
        gate = g.coerceIn(1, 9999)
        /*
         * 存进全局那一格。
         *
         * 上一版只改了这一趟的内存值，纸一关就没了 —— 退出再进来又变回 3。
         * 门槛是口味，不是一次性的选择。
         */
        app?.field?.edit { it.copy(aiGate = gate) }
        val on = pool.filter { touched[it.name] ?: (it.lines >= gate) }.map { it.name }.toSet()
        sent = on
    }

    /** 返回格式那一段。只给看不给改，所以直接取 [Assign.part]。 */
    fun formText(): String = Assign.part(design)

    /** 这一段现在长什么样（给「给 AI 的话」那张纸读）。 */
    fun askNow(): String = ask.ifBlank { Assign.ASK }

    /** 用户改这一段。只改这一趟，落盘要显式点「存成默认」或「只存这本」。 */
    fun writeAsk(t: String) { ask = t.trim() }

    /** 存成全局默认 —— 以后每本书都照这段走（书里自己另存过的除外）。 */
    fun saveAskGlobal() {
        app?.field?.edit { it.copy(aiPrompt = askNow()) }
    }

    /** 只存进这本书。别的书不受影响。 */
    fun saveAskBook() {
        val a = app ?: return
        val id = bookId
        if (id.isBlank()) { saveAskGlobal(); return }
        val bm = a.shelf.find(id) ?: return
        val cb = runCatching { com.pagefold.reader.book.RuleCodec.fromJson(bm.castJson) }
            .getOrDefault(com.pagefold.reader.book.CastBook())
        /*
         * 存的是这一趟**实际写的**（可能是空）。
         *
         * 存 askNow() 的话，「照默认来」会把出厂那段抄进这本书 —— 以后改全局
         * 这本书就不跟着走了，看着像「改了没反应」。
         */
        a.shelf.setCast(id, com.pagefold.reader.book.RuleCodec.toJson(cb.copy(prompt = ask.trim())))
        bookBook = cb.copy(prompt = ask.trim())
    }

    /** 这段是不是跟全局/出厂那份一模一样。用来决定「照默认来」要不要亮。 */
    fun askIsDefault(): Boolean {
        val own = bookBook?.prompt.orEmpty()
        val g = app?.field?.now?.aiPrompt.orEmpty()
        return ask.isBlank() || ask == own.ifBlank { g.ifBlank { Assign.ASK } }
    }

    /** 点某一行：发 ↔ 留。点过就记住，门槛再动也不覆盖它。 */
    fun flip(name: String) {
        val now = name !in sent
        touched = touched + (name to now)
        sent = if (now) sent + name else sent - name
    }

    /**
     * 这个人给模型看的那一句。
     *
     * 默认取句库里**最长**的头五句之一 —— 短句多是「嗯」「是」「好」，
     * 拿它判身份等于没给材料。用户挑过或改过的，以用户的为准。
     */
    fun sampleOf(p: CastName): String {
        picks1[p.name]?.let { return it }
        auto[p.name]?.let { return it }
        val c = app ?: return p.sample
        val got = runCatching { Lines.head(c, bookId, p.name, 5) }.getOrDefault(emptyList())
        if (got.isEmpty()) return p.sample
        val best = if (deep) got.maxByOrNull { it.text.length }?.text.orEmpty() else ""
        auto[p.name] = best
        return best
    }

    private val auto = HashMap<String, String>(64)

    /** 用户自己挑的那一句。空串 = 不给这一句（简单模式那样）。 */
    fun setPick1(name: String, text: String) {
        picks1 = picks1 + (name to text.replace("\n", " ").trim().take(60))
    }

    /** 挑回默认。 */
    fun clearPick1(name: String) {
        picks1 = picks1 - name
    }

    /**
     * 现在要发出去的那段话。
     *
     * 摆原文，不摆摘要 —— 「点进去看」要看到的就是模型真会收到的东西。
     * 上限一万二千字，再多界面也画不动，而且那时已经该分批发完了。
     */
    fun preview(): String {
        val who = pool.filter { it.name in sent }
        if (who.isEmpty()) return "一个人都没选。"
        val text = forWho(who)
        return if (text.length > 12000) text.take(12000) + "\n…（后面还有 ${text.length - 12000} 字）" else text
    }

    /** 这一批真要发出去的那段话。预览和真发走同一个函数，两边不会不一样。 */
    fun forWho(who: List<CastName>): String = Assign.prompt(
        title,
        who.map { it.copy(sample = if (deep) sampleOf(it) else "") },
        design,
        offered(),
        ask,
        withSample = deep,
    )

    /**
     * 这一趟还没结束 —— 纸关着但活儿还在，或者结果还没看。
     *
     * 派角页那颗按钮看它：在排的时候点进去要**回到那个任务**，
     * 不能再 begin 一次把进度清掉。上一版就是这么把在跑的活儿弄丢的。
     */
    val alive: Boolean
        get() = open || fresh || plan != null || job != null ||
            (step == Step.RUN && err == null)

    /** 重新摊开它（不重置任何东西）。 */
    fun resume() { open = true }

    /** 只是把纸摊开（横幅点进来那条路）。 */
    fun show() {
        open = true
    }

    fun close() {
        open = false
    }

    private fun clear() {
        acc.clear(); gone.clear(); seen.clear(); extra.clear()
    }

    /** 停住。已经排好的那几批留着，点「接着排」从断的地方走。 */
    fun pause() {
        job?.cancel()
        job = null
        paused = true
        stage = "停住了"
    }

    /**
     * 往回退一步。
     *
     * 退到没有上一级了就**把这张纸收掉**，回到派角。
     *
     * 上一版这里只改 `step`：只开了一路接口时，「怎么分配」的上一级就是它自己，
     * 按多少下都停在同一屏 —— 用户说的「怎么分配这里的返回，点击多次没有用」。
     */
    fun back() {
        when (step) {
            Step.PICK -> open = false
            Step.DETAIL -> if (picks.size > 1) step = Step.PICK else open = false
            Step.NAMES -> step = Step.DETAIL
            Step.RUN -> step = Step.NAMES
            Step.PLAN -> step = Step.RUN
        }
    }

    /** 真发。分批，一批一个请求，回一批记一批。 */
    fun run() {
        val a = app ?: return
        val cfg = picks.firstOrNull { it.id == chatId } ?: picks.firstOrNull() ?: return
        val who = pool.filter { it.name in sent }
        if (who.isEmpty()) { err = "一个人都没选"; return }

        step = Step.RUN
        err = null
        paused = false
        if (job != null) return
        if (startedAt == 0L) startedAt = System.currentTimeMillis()

        val parts = who.chunked(BATCH)
        total = parts.size
        if (done >= total) { finish(); return }
        stage = "第 ${done + 1} 批，正在发"

        job = scope.launch {
            var fail = ""
            for (i in done until parts.size) {
                val part = parts[i]
                val known = part.map { it.name }.toSet()
                val body = forWho(part)
                stage = "第 ${i + 1} 批，正在发"
                val got = runCatching {
                    var raw = Chat.ask(cfg, Assign.SYSTEM, body)
                    var r = Assign.parse(raw, known, design, voiceNames)
                    if (r.first == null) {
                        stage = "第 ${i + 1} 批，格式不对，让它重来"
                        raw = Chat.ask(
                            cfg, Assign.SYSTEM,
                            body + "\n\n上一次的回复不合要求（" + r.second +
                                "）。严格按返回格式，只回那段 JSON，不要任何别的话。"
                        )
                        r = Assign.parse(raw, known, design, voiceNames)
                    }
                    r
                }
                val p = got.getOrNull()?.first
                if (p == null) {
                    val said = got.getOrNull()?.second.orEmpty()
                    fail = if (said.isNotBlank()) said else Chat.short(got.exceptionOrNull()?.message)
                    break
                }
                merge(p)
                done = i + 1
                stage = "第 $done 批好了"
                brief = "第 $done/$total 批 · 已排 ${acc.sumOf { it.members.size }} 人"
            }
            job = null
            if (fail.isNotBlank()) {
                err = fail
                paused = true
                stage = if (done == 0) "没发出去" else "第 $done 批没成"
            } else if (done >= parts.size) {
                finish()
            }
        }
    }

    private fun finish() {
        val groups = acc.toList()
        if (groups.isEmpty()) {
            err = "一组都没分出来"
            paused = true
            return
        }
        val p = Assign.Plan(
            groups = groups,
            drop = gone.map { (n, w) -> Assign.Drop(n, w) },
            design = design,
            notes = extra.toList(),
        )
        plan = p
        use = groups.map { it.name }.toSet()
        keep = emptySet()
        step = Step.PLAN
        stage = ""
        fresh = true
        brief = "排好了：${groups.size} 组，${p.heads} 人"
    }

    /** 把一批回话并进大局。同一个人只留第一次，同一组名就并成一伙。 */
    private fun merge(p: Assign.Plan) {
        p.groups.forEach { g ->
            val mine = g.members.filter { seen.add(it) }
            if (mine.isEmpty()) return@forEach
            val at = acc.indexOfFirst { it.name == g.name }
            if (at < 0) acc += g.copy(members = mine)
            else acc[at] = acc[at].copy(members = (acc[at].members + mine).distinct())
        }
        p.drop.forEach { d -> if (d.name !in seen && d.name !in gone) gone[d.name] = d.why }
    }

    /**
     * 落盘。
     *
     * 只写点着「用」的那几组；点掉的那几组不当没出现过 —— 并进「旁白」那一组。
     * 那几个人原来散在别的组里，若不一并摘出来，改嗓子时看着像「改了没反应」。
     */
    fun apply() {
        val a = app ?: return
        val p = plan ?: return
        val base = run {
            val bm = a.shelf.find(bookId)
            val raw = bm?.castJson.orEmpty().ifBlank { a.field.now.castJson }
            if (raw.isBlank()) com.pagefold.reader.book.CastBook(
                narrator = a.field.now.narratorVoice,
                dialog = a.field.now.defaultVoice,
            ) else RuleCodec.fromJson(raw)
        }

        val taken = buildSet {
            addAll(a.box.custom().map { it.name })
            addAll(Catalog.MIMO.map { it.name })
        }
        val squad = Assign.batch(p, taken)
        val kept = p.groups.filter { it.name in use }
        val off = p.groups.filter { it.name !in use }
        val rest = p.copy(groups = kept)

        // 造出来的音色只存用到的那几副，没用上的不往音色库里塞。
        val want = kept.mapNotNull { squad.voiceName[it.name] }.toSet()
        val made = squad.voices.filter { it.name in want }
        if (made.isNotEmpty()) a.box.save(a.box.custom() + made)

        var next = Assign.commit(base, rest, squad, keep)
        if (off.isNotEmpty()) {
            val names = off.flatMap { it.members }.toSet()
            next = next.copy(
                roles = next.roles.map { r ->
                    if (r.name !in names) r else r.copy(alt = r.alt.filter { it !in names })
                }
            )
            next = next.copy(
                roles = next.roles + CastRole(
                    name = "旁白",
                    voice = next.narrator.ifBlank { a.field.now.narratorVoice },
                    alt = names.toList(),
                )
            )
        }

        val json = RuleCodec.toJson(next)
        if (bookId.isNotBlank()) a.shelf.setCast(bookId, json)
        else a.field.edit { it.copy(castJson = json) }

        stamp++
        fresh = false
        plan = null
        open = false
        step = Step.PICK
        startedAt = 0L
        done = 0
        stage = ""
        brief = ""
        clear()
    }

    /** 把这一趟整个丢掉。 */
    fun reset() {
        job?.cancel()
        job = null
        open = false
        fresh = false
        plan = null
        err = null
        paused = false
        stage = ""
        brief = ""
        startedAt = 0L
        done = 0
        clear()
    }

    /** 过了几秒。纸上那个计时器读它。 */
    fun seconds(): Long =
        if (startedAt == 0L) 0L else (System.currentTimeMillis() - startedAt) / 1000

    /** 用户改某人那一句台词样例。发出去之前改，模型看到的就是改过的。 */
    fun setSample(name: String, text: String) {
        pool = pool.map {
            if (it.name == name) it.copy(sample = text.replace("\n", " ").trim().take(40)) else it
        }
    }

    /** 重排一遍：这一趟的结果全丢掉，从头发。 */
    /**
     * 退出这一份结果，什么都不改。
     *
     * 跟 [again] 不一样：again 是「再排一次」（要再等一轮请求），这个是「这份不要了」。
     * 上一版这一格叫「重排」，用户点下去只是想退出去，结果又发起一整趟请求。
     */
    fun discard() {
        job?.cancel()
        job = null
        plan = null
        use = emptySet()
        keep = emptySet()
        open = false
        fresh = false
        err = null
        paused = false
        stage = ""
        brief = ""
        startedAt = 0L
        done = 0
        total = 0
        step = Step.PICK
        clear()
    }

    /** 改某一组的音色。换成现成的就把 desc 清掉；写描述就是把 voice 清掉。 */
    fun setGroupVoice(name: String, voice: String, desc: String) {
        val p = plan ?: return
        plan = p.copy(
            groups = p.groups.map {
                if (it.name != name) it else it.copy(voice = voice, desc = desc)
            }
        )
    }

    /** 改成别的组名（不做，留一个空壳会让人以为能改）。 */
    fun again() {
        clear()
        done = 0
        total = 0
        startedAt = 0L
        plan = null
        err = null
        paused = false
        step = Step.RUN
        run()
    }
}
