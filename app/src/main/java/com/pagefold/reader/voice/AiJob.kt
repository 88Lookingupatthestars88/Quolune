package com.pagefold.reader.voice

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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

/** 「让 AI 排」这件事的状态机。 */
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

    /** 这一趟排什么。VOICE = 给名单上的人配音色； */
    enum class Mode { VOICE, UNSURE }

    var mode by mutableStateOf(Mode.VOICE)

    /** UNSURE 那一档判出来的归属。落盘之前只活在纸上。 */
    var says by mutableStateOf<List<Assign.Said>>(emptyList())

    /** 这一档让模型加/删的人名。用户点「用」才落到名单上。 */
    var addNames by mutableStateOf<List<String>>(emptyList())
    var dropNames by mutableStateOf<List<String>>(emptyList())

    /** 这一档的用户点掉的（不想发、也不想删的）。 */
    var skipAdd by mutableStateOf<Set<String>>(emptySet())
    var skipDrop by mutableStateOf<Set<String>>(emptySet())

    /** 「没认出」一共多少句、分别在哪些章。按章挑发给谁。 */
    var unsureAll by mutableStateOf<List<com.pagefold.reader.book.Quote>>(emptyList())
    /** 勾上的章。整章勾，章里再逐句摘。 */
    var unsureOn by mutableStateOf<Set<Int>>(emptySet())
    /** 章里被摘掉的句子，键是 [qk]。 */
    var unsureOff by mutableStateOf<Set<String>>(emptySet())
    /** 已经发过的句子。一轮发不完，下一轮从这里接着挑。 */
    var unsureSkip by mutableStateOf<Set<String>>(emptySet())

    /** 这一轮定下来的那几句。开跑时算一次，「接着发」沿用这一份。 */
    var round by mutableStateOf<List<com.pagefold.reader.book.Quote>>(emptyList())

    /** 范围框的两个数，是书里写的章号，不是列表位置。换算见 [noOf]、[inRange]。 */
    var unsureFrom by mutableIntStateOf(1)
    var unsureTo by mutableIntStateOf(Int.MAX_VALUE)

    /** 这本书每章的号（书里写的那个）。0 = 没编号。范围框和界面上那一行读的是同一份。 */
    var chNos: IntArray = IntArray(0)

    /** 章序号 → 书里写的章号。0 = 这一章没编号（序、楔子、卷首）。 */
    fun noOf(ch: Int): Int = chNos.getOrElse(ch) { 0 }

    /** 这个章序号落不落在范围里。 */
    fun inRange(ch: Int): Boolean {
        val no = noOf(ch)
        return if (no <= 0) unsureFrom <= 1 else no in unsureFrom..unsureTo
    }

    /** 一轮最多多少道题。五十道一批，三百道就是六批：一次点下去，几分钟走完，走完能看结果。 */
    const val ROUND_ASKS = 300

    /**
* 一张对话纸：这一批发出去的话在上、它回的话在下。
*
* @param note 这一批发的是什么，折起来时给人看的那一行（「50 句 · 9400 字」）。
* @param got  这一批它判出来几句。-1 = 还没读完。纸上要写，不然回一串空 JSON 也看不出是没用还是没跑。
*/
    data class Turn(
        val ask: String,
        val say: String,
        val done: Boolean = false,
        val note: String = "",
        val got: Int = -1,
    )

    var turns by mutableStateOf<List<Turn>>(emptyList())

    /** 一句在句库里的键。章 + 章内偏移，跟 Pin 同一把尺子。 */
    fun qk(ch: Int, at: Int): String = "$ch:$at"

    /** 这次真要发的那些句子：勾了的章、没被摘掉、也没发过。 */
    fun unsureRows(): List<com.pagefold.reader.book.Quote> = unsureAll.filter {
        it.ch in unsureOn && qk(it.ch, it.at) !in unsureOff && qk(it.ch, it.at) !in unsureSkip
    }

    // ── 第三步：发谁 ──

    var pool by mutableStateOf<List<CastName>>(emptyList())

    /** 门槛：几句话以上才发。用户自己写，默认 50。 */
    var gate by mutableStateOf(50)

    /** 这一次发出去的「怎么分」。空 = 出厂那份（[Assign.ASK]）。 */
    var ask by mutableStateOf("")

    /** 手改过的名字。调门槛不覆盖它。 */
    var touched by mutableStateOf<Map<String, Boolean>>(emptyMap())

    /** 实际发出去的那些人。由门槛和 [touched] 算出来。 */
    var sent by mutableStateOf<Set<String>>(emptySet())

    /** 每人挑给模型看的那一句。名字 → 那句话。空 = 还没挑，用默认。 */
    var picks1 by mutableStateOf<Map<String, String>>(emptyMap())

    /** 允许模型自己造新嗓音。 */
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

    /** 这一批已收到的原文。界面每 90ms 刷一次；请求被取消时靠它二次解析。 */
    private var raw = ""

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
        /* 「能用」的那几路：开着、地址有、钥匙有、模型挑过。 */
        val ready = now.chats.filter {
            it.on && Chats.why(it) == null && (it.model ?: "").isNotBlank()
        }
        if (ready.isEmpty()) return "先去定音里填一路接口"
        val list = cast.pool.filter { it.name.isNotBlank() }
        /* 名单空着也可能还有人在：单句改派指过的人不在名单里。 */
        if (list.isEmpty() && cast.pins.none { it.who.isNotBlank() }) return "名单是空的，先数一遍人"

        val des = now.engines.any { it.on && it.src == com.pagefold.reader.field.VoiceSource.MIMO }
        /* 现成的嗓子永远取，跟名单一起发给模型。 */
        val voices = Catalog.groups(now.engines, a.box.custom()).flatMap { it.second }
        if (voices.isEmpty() && !des) return "先去定音里开一路声源"

        bookId = book?.id.orEmpty()
        title = book?.title.orEmpty()
        picks = ready
        chatId = ready.first().id
        deep = true
        /* 句数 = 名单里的数 + 用户单句改派到他那儿的句数（见 Pin）。 */
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
        /* 起手这一档照定音 · 派角那一节来。 */
        val want = now.aiVoiceSet
        val poolNames = voices.map { it.name }
        design = des && now.aiDesign
        voicePool = voices
        voiceAll = poolNames
        voiceUse = if (want == null) poolNames.toSet() else poolNames.filter { it in want }.toSet()
        if (voiceUse.isEmpty() && !design) {
            return "一副嗓子都没准它用。去定音 · 派角里挑几副，或者打开「让 AI 新造嗓音」"
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
        mode = Mode.VOICE
        says = emptyList()
        addNames = emptyList()
        dropNames = emptyList()
        skipAdd = emptySet()
        skipDrop = emptySet()
        unsureAll = emptyList()
        unsureOn = emptySet()
        round = emptyList()
        open = true
        return null
    }

    /** 改门槛。手点过的名字不跟门槛走 —— 他点过「发」，门槛调到 50 也不该把它摘掉。 */
    fun gateTo(g: Int) {
        gate = g.coerceIn(1, 9999)
        /* 存进全局那一格，纸关掉也不丢。 */
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
        /* 存的是这一趟实际写的（可能是空）。 */
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

    /** 这个人给模型看的那一句。默认取句库里最长的头五句之一，短句没有信息量。拿它判身份等于没给材料。 */
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

    /** 现在要发出去的那段话。摆原文，不摆摘要 —— 「点进去看」要看到的就是模型真会收到的东西。 */
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

    /** 这一趟还没结束 —— 纸关着但活儿还在，或者结果还没看。 */
    val alive: Boolean
        get() = open || fresh || plan != null || says.isNotEmpty() || job != null ||
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

    /** 不排了。这一趟整个丢掉，派角那颗按钮回到「让 AI 排」。 */
    fun abort() {
        job?.cancel()
        job = null
        open = false
        fresh = false
        plan = null
        use = emptySet()
        keep = emptySet()
        err = null
        paused = false
        stage = ""
        brief = ""
        startedAt = 0L
        done = 0
        total = 0
        step = Step.PICK
        says = emptyList()
        addNames = emptyList()
        dropNames = emptyList()
        skipAdd = emptySet()
        skipDrop = emptySet()
        unsureAll = emptyList()
        unsureOn = emptySet()
        unsureOff = emptySet()
        unsureSkip = emptySet()
        round = emptyList()
        turns = emptyList()
        mode = Mode.VOICE
        clear()
    }

    /** 停住。已经排好的那几批留着，点「接着排」从断的地方走。 */
    fun pause() {
        job?.cancel()
        job = null
        paused = true
        stage = "停住了"
    }

    /** 往回退一步。退到没有上一级了就把这张纸收掉，回到派角。 */
    fun back() {
        when (step) {
            Step.PICK -> open = false
            Step.DETAIL -> if (picks.size > 1) step = Step.PICK else open = false
            Step.NAMES -> step = Step.DETAIL
            /* 没认出那一档没有「发哪些人」这一步，退回去就是选档那一屏。 */
            /* 第三档「返回」回到挑句那一屏 —— 发到一半想改范围，退回去改。 */
            Step.RUN -> step = Step.NAMES
            Step.PLAN -> step = if (mode == Mode.UNSURE) Step.DETAIL else Step.RUN
        }
    }

    /** 真发。分批，一批一个请求，回一批记一批。 */
    fun run() {
        if (mode == Mode.UNSURE) { runUnsure(); return }
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
        /* 从第一批起跑才清纸；续跑留着上一回的那些，用户能往上翻。 */
        if (done == 0) turns = emptyList()
        stage = "第 ${done + 1} 批，正在发"

        job = scope.launch {
            var fail = ""
            for (i in done until parts.size) {
                val part = parts[i]
                val known = part.map { it.name }.toSet()
                val body = forWho(part)
                stage = "第 ${i + 1} 批，正在发"
                /* 发出去的那一批和它回的话都摆到纸上，用户不用守着也能回头看他干了什么。 */
                turns = turns + Turn(body, "")
                val got = runCatching {
                    var raw = streamOne(cfg, Assign.SYSTEM, body)
                    var r = Assign.parse(raw, known, design, voiceNames)
                    if (r.first == null) {
                        stage = "第 ${i + 1} 批，格式不对，让它重来"
                        val again = body + "\n\n上一次的回复不合要求（" + r.second +
                            "）。严格按返回格式，只回那段 JSON，不要任何别的话。"
                        turns = turns + Turn("（重发一遍：上一次的格式不对）\n" + again.take(200), "")
                        raw = streamOne(cfg, Assign.SYSTEM, again)
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
                markTurns()
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

    /** 发一批，字一个一个往纸上铺。 */
    private suspend fun streamOne(cfg: ChatCfg, system: String, body: String): String {
        val buf = StringBuilder(2048)
        var pushed = 0L
        raw = ""
        val got = Chat.stream(cfg, system, body) { d ->
            buf.append(d)
            val now = System.currentTimeMillis()
            if (now - pushed >= 90) {
                pushed = now
                val shot = buf.toString()
                raw = shot
                val t = turns
                if (t.isNotEmpty()) turns = t.dropLast(1) + t.last().copy(say = shot)
            }
        }
        raw = got
        val t = turns
        if (t.isNotEmpty()) turns = t.dropLast(1) + t.last().copy(say = got, done = true)
        return got
    }

    /** 最后一张纸标成「回完了」。模型报错那一批不标，纸上留着半截。 */
    private fun markTurns() {
        val t = turns
        if (t.isNotEmpty()) turns = t.dropLast(1) + t.last().copy(done = true)
    }

    /** 进「没认出」那一档前先摊一次账：多少句、各在哪几章。只读句库，不发请求。 */
    fun loadUnsure() {
        val a = app ?: return
        val id = bookId
        if (id.isBlank()) { unsureAll = emptyList(); return }
        /* 章号先备好：范围框按书里写的章号走，不按列表位置。 */
        chNos = runCatching {
            val chs = com.pagefold.reader.book.Chapters.cached(a, id).orEmpty()
            com.pagefold.reader.book.ChapterNo.of(chs)
        }.getOrDefault(IntArray(0))
        val rawCast = a.shelf.find(id)?.castJson.orEmpty().ifBlank { a.field.now.castJson }
        val pins = if (rawCast.isBlank()) emptyList()
        else runCatching { RuleCodec.fromJson(rawCast).pins }.getOrDefault(emptyList())
        val rows = runCatching { Lines.view(a, id, Lines.UNSURE, pins) }.getOrDefault(emptyList())
        unsureAll = rows
        round = emptyList()
        unsureOn = rows.map { it.ch }.toSet()
        unsureOff = emptySet()
        unsureSkip = emptySet()
        /* 范围默认铺满：用户多半是「先全发一轮看看」，要收窄自己改那两格。 */
        unsureFrom = rows.map { noOf(it.ch) }.filter { it > 0 }.minOrNull() ?: 1
        unsureTo = rows.map { noOf(it.ch) }.filter { it > 0 }.maxOrNull() ?: Int.MAX_VALUE
        turns = emptyList()
    }

    /** 已选的那几章里，一共多少句。 */
    fun unsureChosen(): Int = unsureRows().size

    /** 这一批要发出去的大概字数。 */
    fun unsureChars(): Int = unsureRows().sumOf { it.text.length }

    /** 按章分好的句数。章号升序。 */
    fun unsureByCh(): java.util.SortedMap<Int, Int> {
        val m = java.util.TreeMap<Int, Int>()
        unsureAll.forEach { m[it.ch] = (m[it.ch] ?: 0) + 1 }
        return m
    }

    /** 这一章勾上/点掉。 */
    fun flipChapter(ch: Int) {
        unsureOn = if (ch in unsureOn) unsureOn - ch else unsureOn + ch
        /* 整章重新勾上时，里面逐句摘掉的也跟着复原。 */
        if (ch in unsureOn) unsureOff = unsureOff.filterTo(HashSet()) { !it.startsWith("$ch:") }
    }

    /** 章里那一句勾上/摘掉。 */
    fun flipLine(ch: Int, at: Int) {
        val k = qk(ch, at)
        unsureOff = if (k in unsureOff) unsureOff - k else unsureOff + k
    }

    fun lineOn(ch: Int, at: Int): Boolean = qk(ch, at) !in unsureOff

    fun allChapters(on: Boolean) {
        unsureOn = if (on) unsureAll.map { it.ch }.toSet() else emptySet()
        if (on) unsureOff = emptySet()
    }

    /** 范围内全勾上。两头是书里写的章号。 */
    fun rangeOn() {
        unsureOn = unsureAll.filter { inRange(it.ch) }.map { it.ch }.toSet()
        unsureOff = emptySet()
    }

    fun setFrom(no: Int) {
        unsureFrom = no.coerceAtLeast(1)
        if (unsureFrom > unsureTo) unsureTo = unsureFrom
    }

    fun setTo(no: Int) {
        unsureTo = no.coerceAtLeast(1)
        if (unsureTo < unsureFrom) unsureFrom = unsureTo
    }

    private fun runUnsure() {
        val a = app ?: return
        val cfg = picks.firstOrNull { it.id == chatId } ?: picks.firstOrNull() ?: return
        val id = bookId
        if (id.isBlank()) { err = "这本书还没进书架"; return }

        val base = run {
            val bm = a.shelf.find(id)
            val raw = bm?.castJson.orEmpty().ifBlank { a.field.now.castJson }
            if (raw.isBlank()) com.pagefold.reader.book.CastBook() else RuleCodec.fromJson(raw)
        }
        val names = base.pool.filter { it.on }.map { it.name }
        if (names.isEmpty()) { err = "名单是空的，先让 AI 排一份名单"; return }

        val pins = base.pins
        /* 这一轮发哪几句只在开跑那一刻定一次，之后认 [round]。 */
        if (!com.pagefold.reader.book.Lines.has(a, id)) {
            err = "句库还在建，等它建完再发 —— 现在发，只会把同一个开头重发很多遍。"
            return
        }
        if (done == 0) {
            /* 每一次开新轮都重算一遍：上一轮认出来的句子已经从「没认出」里挪走了。 */
            round = emptyList()
        }
        if (done == 0 || round.isEmpty()) {
            val picked = unsureRows()
            if (picked.isEmpty()) { err = "一句都没选"; return }
            round = picked.take(ROUND_ASKS)
        }
        val rows = round
        if (rows.isEmpty()) { err = "一句都没选"; return }

        /* 正文读一遍，切出每道题的上下文。 */
        val text = runCatching { a.shelf.read(id) }.getOrNull().orEmpty()
        val chs = runCatching { com.pagefold.reader.book.Chapters.cached(a, id).orEmpty() }
            .getOrDefault(emptyList())
        val chStart = chs.map { it.start }
        val chTitle = chs.map { it.title.trim() }

        /* 每一章各自一份名单，按章缓存：一批五十道多半落在十来个章里。 */
        val castByCh = HashMap<Int, List<String>>()
        fun castOf(ch: Int): List<String> =
            castByCh.getOrPut(ch) {
                com.pagefold.reader.book.Excerpt.castOf(text, chStart, ch, names)
            }

        val byI = rows.associateByIndexed()
        val asks = rows.mapIndexed { i, q ->
            val abs = (chStart.getOrNull(q.ch) ?: 0) + q.at
            Assign.Ask(
                i = i + 1,
                ch = q.ch,
                where = chTitle.getOrNull(q.ch).orEmpty().take(24),
                page = com.pagefold.reader.book.Excerpt.page(text, abs, q.text),
                say = q.text,
                prevPage = com.pagefold.reader.book.Excerpt.prevPage(text, abs),
                cast = castOf(q.ch),
            )
        }
        /* 题号 → 那一题的名单。回话按题号校验，不按整批。 */
        fun namesOf(i: Int): Set<String> = asks.getOrNull(i - 1)?.cast?.toSet().orEmpty()

        /* 续跑时把上一次已经判出来的带上，别丢。 */
        val got = ArrayList<Assign.Said>(rows.size)
        got.addAll(says)

        step = Step.RUN
        err = null
        paused = false
        if (job != null) return
        if (startedAt == 0L) startedAt = System.currentTimeMillis()

        /* 这一轮已经跑完又点「接着发」：判出来过东西就开新一轮（剩下的还没发）； */
        val parts = asks.chunked(BATCH2)
        if (done >= parts.size && parts.isNotEmpty()) {
            if (says.isNotEmpty()) {
                againRound()
                return
            }
            done = 0
            turns = emptyList()
        }
        total = parts.size

        /* 断点续跑：已完成的批次接着往后发。 */
        if (done > parts.size) done = 0
        if (done == 0) {
            says = emptyList()
            addNames = emptyList()
            dropNames = emptyList()
            turns = emptyList()
        }
        if (done >= parts.size) { finishUnsure(); return }
        stage = "第 ${done + 1} 批，正在发"

        job = scope.launch {
            var fail = ""
            for (i in done until parts.size) {
                val part = parts[i]
                stage = "第 ${i + 1} 批，正在发"
                val body = Assign.prompt2(title, part)
                val note = "${part.size} 句 · ${part.sumOf { it.page.length }} 字"
                turns = turns + Turn(body, "", note = note)
                val r = runCatching {
                    var raw = streamOne(cfg, Assign.SYSTEM2, body)
                    var m = Assign.parse2(raw, { k -> namesOf(k) }, part.size)
                    if (m.at.isEmpty()) {
                        /* 一句都没判出来，先当格式没听懂，把格式再钉一遍重发。 */
                        stage = "第 ${i + 1} 批，一条都没回，让它重来"
                        val again = body +
                            "\n\n上一次的回复不能用：这一批 " + part.size + " 句，say 里就该有 " +
                            part.size + " 条，i 从 1 到 " + part.size +
                            "。只回那段 JSON，不要别的话。"
                        /* 重发那一张纸也摆原文，折起来那一行仍然先摆句子 —— 只写「重发一遍」等于没写。 */
                        turns = turns + Turn(body, "", note = "重发 · " + note)
                        raw = streamOne(cfg, Assign.SYSTEM2, again)
                        m = Assign.parse2(raw, { k -> namesOf(k) }, part.size)
                    }
                    m
                }
                /* 请求失败时拿 [raw] 里已收到的原文再解一次：收尾被取消时答案多半已经齐了。 */
                val m = r.getOrNull() ?: runCatching { Assign.parse2(raw, { k -> namesOf(k) }, part.size) }
                    .getOrNull()?.takeIf { it.at.isNotEmpty() }
                if (m == null) {
                    val t = turns
                    if (t.isNotEmpty()) turns = t.dropLast(1) + t.last().copy(done = true)
                    fail = Chat.short(r.exceptionOrNull()?.message)
                    break
                }
                if (r.isFailure) fail = ""
                m.at.forEach { (k, who) ->
                    val q = byI[k] ?: return@forEach
                    got += Assign.Said(q.ch, q.at, q.text, who)
                    /* who 不在这一题的名单上：把它补进 [addNames]，下一轮名单里就有他。 */
                    if (who !in namesOf(k) && Assign.plausible(who)) {
                        addNames = (addNames + who).distinct()
                    }
                }
                if (m.add.isNotEmpty()) addNames = (addNames + m.add).distinct()
                if (m.drop.isNotEmpty()) dropNames = (dropNames + m.drop).distinct()
                val t = turns
                if (t.isNotEmpty()) turns = t.dropLast(1) + t.last().copy(done = true, got = m.at.size)
                /* 只把真判出来的那几句记成「发过」。 */
                val judged = m.at.keys.mapNotNull { byI[it] }.map { qk(it.ch, it.at) }
                if (judged.isNotEmpty()) unsureSkip = unsureSkip + judged
                done = i + 1
                says = got.toList()
                stage = "第 $done 批好了"
                /* 这一轮多少句、分几批、认出几句，三样一起写 —— 数字对不上当场就能看出来。 */
                brief = "这一轮 ${rows.size} 句 · 第 $done/$total 批 · 已认出 ${got.size} 句"
                if (m.at.isEmpty()) stage = "第 $done 批一句没判出来，往下走"
            }
            job = null
            if (fail.isNotBlank()) {
                err = fail
                paused = true
                stage = if (done == 0) "没发出去" else "第 $done 批没成"
            } else {
                finishUnsure()
            }
        }
    }

    /** 序号回查。一行一句，序号是 1 起的。 */
    private fun List<com.pagefold.reader.book.Quote>.associateByIndexed():
        Map<Int, com.pagefold.reader.book.Quote> {
        val m = HashMap<Int, com.pagefold.reader.book.Quote>(size * 2)
        forEachIndexed { i, q -> m[i + 1] = q }
        return m
    }

    private fun finishUnsure() {
        /* 模型自己报上来的人名，先在纸上摆着，等用户点。 */
        val known = runCatching {
            val bm = app?.shelf?.find(bookId)
            val raw = bm?.castJson.orEmpty().ifBlank { app?.field?.now?.castJson.orEmpty() }
            if (raw.isBlank()) emptySet() else RuleCodec.fromJson(raw).pool.map { it.name }.toSet()
        }.getOrDefault(emptySet())
        addNames = addNames.filter { it !in known }
        dropNames = dropNames.filter { it in known }
        if (says.isEmpty() && addNames.isEmpty() && dropNames.isEmpty()) {
            /* 一整轮一句都没判出来。 */
            err = "这一轮它一句都没回。翻一翻上面的纸看它到底回了什么；" +
                "要是满屏解释、没有那段 JSON，换一个模型再试。"
            paused = true
            return
        }
        step = Step.PLAN
        stage = ""
        fresh = true
        val left = unsureRows().size
        brief = "认出 ${says.size} 句" + if (left > 0) " · 还剩 $left 句没发" else ""
        notifyDone("认出 ${says.size} 句" + if (left > 0) "，还剩 $left 句" else "")
    }

    /** 排完了，发一条通知。 */
    private fun notifyDone(text: String) {
        val c = app ?: return
        runCatching {
            val nm = c.getSystemService(android.content.Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            if (nm.getNotificationChannel(CH_AI) == null) {
                nm.createNotificationChannel(
                    android.app.NotificationChannel(
                        CH_AI, "让 AI 排", android.app.NotificationManager.IMPORTANCE_DEFAULT,
                    )
                )
            }
            val pi = android.app.PendingIntent.getActivity(
                c, 0,
                c.packageManager.getLaunchIntentForPackage(c.packageName)
                    ?: return@runCatching,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = android.app.Notification.Builder(c, CH_AI)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("排完了")
                .setContentText(text)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(NID_AI, n)
        }
    }

    private const val CH_AI = "ai-cast"
    private const val NID_AI = 4301

    /** 还没发的还剩多少句。 */
    fun unsureLeft(): Int = unsureRows().size

    /** 把这一轮判出来的划进角色表。 */
    private fun commitSays() {
        val a = app ?: return
        val rows = says
        if (rows.isEmpty()) return
        val bm = a.shelf.find(bookId)
        val raw = bm?.castJson.orEmpty().ifBlank { a.field.now.castJson }
        val base = if (raw.isBlank()) com.pagefold.reader.book.CastBook() else RuleCodec.fromJson(raw)
        val spots = rows.map { it.ch to it.at }.toSet()
        val pins = base.pins.filterNot { (it.ch to it.at) in spots } +
            rows.map { com.pagefold.reader.book.Pin(it.ch, it.at, it.who, it.text) }
        /* 模型报上来的新人名：用户没点掉的，加到名单上，句数按这次判到的算。 */
        val add = addNames.filter { it !in skipAdd }
        val drop = dropNames.filter { it !in skipDrop }.toSet()
        val oldPool = base.pool.filterNot { it.name in drop }
        val pooled = oldPool.toMutableList()
        add.forEach { n ->
            val at = pooled.indexOfFirst { it.name == n }
            if (at >= 0) {
                /* 名单上挂着、只是没勾的：模型判到他了，就该勾上，句数也补到这会儿。 */
                val old = pooled[at]
                pooled[at] = old.copy(on = true, lines = maxOf(old.lines, rows.count { it.who == n }))
            } else {
                pooled += com.pagefold.reader.book.CastName(
                    name = n,
                    lines = rows.count { it.who == n },
                    on = true,
                )
            }
        }
        val next = base.copy(pins = pins, pool = pooled)
        val json = RuleCodec.toJson(next)
        if (bookId.isNotBlank()) a.shelf.setCast(bookId, json)
        else a.field.edit { it.copy(castJson = json) }
        stamp++
    }

    /** 「就用这些」：落盘，收起这张纸，回派角。 */
    fun applyUnsure() {
        if (says.isEmpty()) return
        commitSays()
        fresh = false
        says = emptyList()
        addNames = emptyList()
        dropNames = emptyList()
        skipAdd = emptySet()
        skipDrop = emptySet()
        unsureAll = emptyList()
        unsureOn = emptySet()
        round = emptyList()
        open = false
        step = Step.PICK
        startedAt = 0L
        done = 0
        total = 0
        stage = ""
        brief = ""
    }

    /** 「再来一轮」：先 [commitSays] 落盘，再重摊一次账从头发。 */
    fun againRound() {
        commitSays()
        says = emptyList()
        addNames = emptyList()
        dropNames = emptyList()
        skipAdd = emptySet()
        skipDrop = emptySet()
        done = 0
        total = 0
        stage = ""
        brief = ""
        loadUnsure()
        if (unsureAll.isEmpty()) {
            err = "书上没有没认出的句子了"
            step = Step.PLAN
            paused = true
            return
        }
        run()
    }

    /** 这一档一批发多少句。 */
    private val BATCH2 = 50

    /** 「这一页」里，目标段前后各带几段。 */
    private const val PAGE_AROUND = 3
    /** 「前一页」带几段。上一页通常是这场对话的开头，谁开的口就在那一页里。 */
    private const val PREV_PARAS = 6



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

    /** 落盘。只写点着「用」的那几组；点掉的那几组不当没出现过 —— 并进「旁白」那一组。 */
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
        says = emptyList()
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

    /** 退出这一份结果，什么都不改。 */
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
        step = Step.PICK
        says = emptyList()
        addNames = emptyList()
        dropNames = emptyList()
        skipAdd = emptySet()
        skipDrop = emptySet()
        unsureAll = emptyList()
        unsureOn = emptySet()
        unsureOff = emptySet()
        unsureSkip = emptySet()
        turns = emptyList()
        total = 0
        mode = Mode.VOICE
        clear()
    }

    /** 改某一组的音色：换成现成的清 desc，写描述清 voice。 */
    fun setGroupVoice(name: String, voice: String, desc: String) {
        val p = plan ?: return
        plan = p.copy(
            groups = p.groups.map {
                if (it.name != name) it else it.copy(voice = voice, desc = desc)
            }
        )
    }

    /** 改组名。空实现：界面不给入口。 */
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
