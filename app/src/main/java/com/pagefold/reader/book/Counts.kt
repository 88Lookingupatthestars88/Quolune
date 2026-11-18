package com.pagefold.reader.book

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.pagefold.reader.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** 后台数人。书一进书架就派下去，用户不用点任何东西。 */
object Counts {

    const val IDLE = 0
    const val WORK = 1
    const val DONE = 2
    const val FAIL = 3

    /** 界面读它就知道该重画了。 */
    val version = mutableIntStateOf(0)

    /** 每本书各自一条进度：进度按书 id 分开存。 */
    private val notes = ConcurrentHashMap<String, String>()

    /** 进度：0–100，每本一份。0 = 没在跑。 */
    private val pcts = ConcurrentHashMap<String, Int>()

    fun noteOf(id: String): String = notes[id].orEmpty()

    fun pctOf(id: String): Int = pcts[id] ?: 0

    private fun say(id: String, n: String) {
        if (n.isBlank()) notes.remove(id) else notes[id] = n
        version.intValue++
    }

    /** 进度只在整数百分比变了才落一次 —— 一本书几千章，每章都刷会把界面拖住。 */
    private fun mark(id: String, p: Int): Boolean {
        val v = p.coerceIn(0, 100)
        if (pcts[id] == v) return false
        pcts[id] = v
        version.intValue++
        return true
    }

    private val state = ConcurrentHashMap<String, Int>()

    /** 自动试过几次。失败一次以后再进书，自动再来一次；两次都不成才等用户点。 */
    private val tries = ConcurrentHashMap<String, Int>()
    private val pool = Executors.newSingleThreadExecutor { r ->
        /* 数人的时候要让出 CPU。一千万字的书要跑七八秒，线程按默认优先级抢。 */
        Thread(r, "quolune-count").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + pool.asCoroutineDispatcher())

    /** 每本书一个任务。重数时掐掉旧的，不然它排在单线程队列里，点几下都没动静。 */
    private val jobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    fun of(id: String): Int = state[id] ?: IDLE

    /** 进过一次就派下去。已经数过、正在数、数失败过都不再自动重来；书里已经有名单的也不数 ——名单只决定「这本书有谁在说话」。 */
    fun ensure(app: App, meta: BookMeta) {
        /* 句库建好了就是 DONE，用户点「重数」才重来。 */
        val was = state[meta.id]
        if (was != null && was != FAIL) return
        if (was == FAIL && (tries[meta.id] ?: 0) >= 2) return
        val cur = castOf(app, app.shelf.find(meta.id) ?: meta)
        if (cur.pool.isEmpty() && cur.roles.isEmpty()) {
            start(app, meta)
        } else if (!Lines.has(app, meta.id)) {
            start(app, meta)
        } else {
            state[meta.id] = DONE
            version.intValue++
        }
    }

    /** 用户自己按的「重新数」。失败过的那一档也靠它重来。 */
    fun redo(app: App, meta: BookMeta) {
        /* 掐掉这本书没跑完的那一趟，再开新的。 */
        jobs.remove(meta.id)?.cancel()
        /* 用户自己点了，就把自动重试的次数归零 —— 他愿意等，再试两次也该给。 */
        tries[meta.id] = 0
        /* 用户按的「重数」：清掉删人记录再从正文重数。[ensure] 那次不清。 */
        val raw = app.shelf.find(meta.id)?.castJson.orEmpty()
        if (raw.isNotBlank()) {
            val cb = runCatching { RuleCodec.fromJson(raw) }.getOrNull()
            if (cb != null && cb.off.isNotEmpty()) {
                app.shelf.setCast(meta.id, RuleCodec.toJson(cb.copy(off = emptyList())))
            }
        }
        start(app, meta)
    }

    fun forget(id: String) {
        state.remove(id)
        tries.remove(id)
        notes.remove(id)
        pcts.remove(id)
        version.intValue++
    }

    private fun start(app: App, meta: BookMeta) {
        state[meta.id] = WORK
        val id = meta.id
        tries[id] = (tries[id] ?: 0) + 1
        /* 还在队列里排着。 */
        notes[id] = "排队中"
        pcts[id] = 0
        version.intValue++
        jobs[id] = scope.launch {
            var end = FAIL
            try {
                /* 轮到它了。 */
                mark(id, 1)
                val raw = app.shelf.read(id)
                if (!raw.isNullOrBlank()) {
                    /* 章要先切出来。句库按章编号，没这张表就不知道该往哪一章记。 */
                    say(id, "切章")
                    val chs = com.pagefold.reader.book.Chapters.of(app, meta, raw)
                    say(id, "数人")
                    /* 名单不封顶：只说过一句的配角也是人，砍掉他那一句就永远判不出来。 */
                    val raw0 = Look.speakers(raw, 3000) { v -> mark(id, 1 + v * 34 / 100) }
                    say(id, "学说法")
                    /* 学说法只拿「说得多的」那批当锚点：长尾里混着噪声，
* 拿它们去学，学出来的状语也是噪声。 */
                    val custom = Look.customs(
                        raw, raw0.filter { it.lines >= 20 }.associate { it.name to it.lines },
                    ) { v -> mark(id, 35 + v * 10 / 100) }
                    /* 学出来的状语从名单里剔掉一份。 */
                    val learnedAdv = custom.second.toHashSet()
                    val list = raw0.filter { it.name !in learnedAdv || it.lines >= 20 }
                    if (list.isNotEmpty()) {
                        val fresh = app.shelf.find(id) ?: meta
                        val cur = castOf(app, fresh)
                        /* 勾选状态和叫法按名字带过去：重数不该把用户挑过的勾掉。 */
                        val old = cur.pool.associateBy { it.name }
                        val seen = HashSet<String>(list.size * 2)
                        val marked = ArrayList<CastName>(list.size + cur.pool.size)
                        /* 数出来的人一律勾上。只开前几十个的话，后面几百章的人
* 会在名单上挂着「没勾」，读的时候一个都认不出来。 */
                        list.forEach { n ->
                            if (!seen.add(n.name)) return@forEach
                            marked += n.copy(
                                on = old[n.name]?.on ?: true,
                                alt = old[n.name]?.alt.orEmpty(),
                                sample = if (n.sample.isNotBlank()) n.sample
                                else old[n.name]?.sample.orEmpty(),
                            )
                        }
                        /* 旧名单里没数到的人，只有「用户配过嗓子/写进角色表」的才留。 */
                        val kept = HashSet<String>(cur.roles.size * 4)
                        /* 组员也算「配过嗓子的人」：并进组里的那些人不在名单上，句数会跟着丢。 */
                        cur.roles.forEach { r -> kept.add(r.name); r.alt.forEach { kept.add(it) } }
                        cur.pool.forEach { p ->
                            if (p.name.isBlank() || p.name !in kept || !seen.add(p.name)) return@forEach
                            marked += p
                        }
                        /* 名单整份换掉。 */
                        val next = cur.copy(
                            pool = marked,
                            poolAt = NAME_VER,
                            extraVerb = custom.first,
                            extraAdv = custom.second,
                            extraTitle = emptyList(),
                            /* 男女按这次数出来的重填：代词那一层全靠这一格。 */
                            sex = list.filter { it.sex.isNotBlank() && marked.any { m -> m.name == it.name } }
                                .associate { it.name to it.sex },
                        )
                        app.shelf.setCast(id, RuleCodec.toJson(next))
                        /*
* 名单换了，句库按新名单重扫。
*/
                        Lines.drop(app, id)

                        /* 句库。名单出来的那几个人才收 —— 名单外的不存，免得存一屋子垃圾。 */
                        say(id, "记他的话")
                        Lines.build(
                            app, id, raw, chs, next, next.pins,
                        ) { done, all ->
                            if (mark(id, if (all == 0) 96 else 45 + done * 51 / all)) {
                                say(id, "第 $done/$all 章")
                            }
                        }
                        /* 句数以句库为准，名单也在这时候收一次口。 */
                        mark(id, 97)
                        say(id, "收口")
                        val cnt = Lines.index(app, id)
                        val sync = app.shelf.find(id) ?: meta
                        val cb = castOf(app, sync)
                        val cut = cb.copy(
                            poolAt = NAME_VER,
                            pool = cb.pool.mapNotNull { p ->
                                val n = cnt[p.name] ?: 0
                                if (n < 2 && p.name !in kept) null
                                else p.copy(lines = n)
                            },
                            roles = cb.roles,
                        )
                        app.shelf.setCast(id, RuleCodec.toJson(cut))
                        end = DONE
                    }
                }
            } catch (e: Exception) {
                end = FAIL
            } catch (t: Throwable) {
                // 连 OutOfMemoryError 也要落一个状态：不然界面永远停在「正在数」。
                end = FAIL
            }
            /* 失败了把进度留在一半再往回退 —— 直接抹成 0 的话，界面上跟「还没开始数」一模一样，用户只能自己猜到该点重数。 */
            if (end == FAIL) {
                notes[id] = if ((tries[id] ?: 0) < 2) "没过成，正在重试" else "没数成 · 点这里重数 ›"
                pcts[id] = 1
            } else {
                notes.remove(id)
                pcts[id] = 0
            }
            state[id] = end
            jobs.remove(id)
            version.intValue++
            /* 失败一次就自己再来一次，别让用户点。 */
            if (end == FAIL && (tries[id] ?: 0) < 2) {
                state[id] = WORK
                start(app, meta)
            }
        }
    }

    private fun castOf(app: App, meta: BookMeta): CastBook {
        val own = meta.castJson
        if (own.isNotBlank()) return RuleCodec.fromJson(own)
        val g = app.field.now.castJson
        if (g.isNotBlank()) return RuleCodec.fromJson(g)
        return CastBook(narrator = app.field.now.narratorVoice, dialog = app.field.now.defaultVoice)
    }
}
