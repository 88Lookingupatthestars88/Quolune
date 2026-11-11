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

/**
 * 后台数人。
 *
 * 书一进书架就派下去，用户不用点任何东西。跑在自己的单线程上，
 * 一次只数一本 —— 两本同时数，内存直接见底。
 *
 * 状态只有四个，界面靠 [version] 那个计数跟着刷新：
 * 还没动手、正在数、数完了、上次没数成。
 */
object Counts {

    const val IDLE = 0
    const val WORK = 1
    const val DONE = 2
    const val FAIL = 3

    /** 界面读它就知道该重画了。 */
    val version = mutableIntStateOf(0)

    /** 正在干活的那句话，摆给界面看。「第 12/46 章」这种。空 = 没在忙。 */
    val note = mutableStateOf("")

    /** 进度：0–100。0 = 没在跑。 */
    val pct = mutableIntStateOf(0)

    private val state = ConcurrentHashMap<String, Int>()
    private val pool = Executors.newSingleThreadExecutor { r ->
        /*
         * 数人的时候要让出 CPU。
         *
         * 一千万字的书要跑七八秒，线程按默认优先级抢，主线程的点击和滚动就会顿住 ——
         * 用户看到的是「加完组要等好几秒才出来」。压到最低优先级，它只在没人用它的时候跑。
         */
        Thread(r, "quolune-count").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + pool.asCoroutineDispatcher())

    fun of(id: String): Int = state[id] ?: IDLE

    /**
     * 进过一次就派下去。
     *
     * 已经数过、正在数、数失败过都不再自动重来；书里已经有名单的也不数 ——
     * 名单只决定「这本书有谁在说话」，不会因为多读一遍变准，重启一次重算一遍纯属白烧电。
     */
    fun ensure(app: App, meta: BookMeta) {
        if (state.containsKey(meta.id)) return
        val cur = castOf(app, app.shelf.find(meta.id) ?: meta)
        /*
         * 名单已经有了，但一个人一句台词样例都没有 —— 那是老版本数的。
         *
         * 样例是派角时让大模型判身份的唯一依据（「末将听令」是武将，「臣不可」是文臣）。
         * 没有它，AI 只能按名字的字面猜。所以碰到这种名单，补数一次；
         * 数完这一遍之后就有样例了，不会再数第三遍。
         */
        val noSample = cur.pool.isNotEmpty() && cur.pool.none { it.sample.isNotBlank() }
        /*
         * 名单有了、样例也有了，但句库没建过 —— 上一版没有句库。
         * 这一条是升版之后第一次进书的唯一代价：得再扫一遍。
         */
        val noLines = cur.pool.isNotEmpty() && !Lines.has(app, meta.id)
        if (cur.pool.isNotEmpty() && !noSample && !noLines) {
            state[meta.id] = DONE
            version.intValue++
            return
        }
        start(app, meta)
    }

    /** 用户自己按的「重新数」。失败过的那一档也靠它重来。 */
    fun redo(app: App, meta: BookMeta) {
        if (state[meta.id] == WORK) return
        start(app, meta)
    }

    fun forget(id: String) {
        state.remove(id)
        version.intValue++
    }

    private fun start(app: App, meta: BookMeta) {
        state[meta.id] = WORK
        version.intValue++
        note.value = ""
        pct.intValue = 0
        val id = meta.id
        scope.launch {
            var end = FAIL
            try {
                val raw = app.shelf.read(id)
                if (!raw.isNullOrBlank()) {
                    /*
                     * 章要先切出来。句库按章编号，没这张表就不知道该往哪一章记。
                     * 切一次会落盘（Chapters 自己有缓存），下次直接读。
                     */
                    note.value = "切章"
                    val chs = com.pagefold.reader.book.Chapters.of(app, meta, raw)
                    val list = Look.speakers(raw, 400)
                    val custom = Look.customs(raw)
                    if (list.isNotEmpty()) {
                        val fresh = app.shelf.find(id) ?: meta
                        val cur = castOf(app, fresh)
                        /*
                         * 勾选状态和叫法按名字带过去：重数不该把用户挑过的勾掉。
                         */
                        val old = cur.pool.associateBy { it.name }
                        val seen = HashSet<String>(list.size * 2)
                        val marked = ArrayList<CastName>(list.size + cur.pool.size)
                        list.forEach { n ->
                            if (!seen.add(n.name)) return@forEach
                            marked += n.copy(
                                on = old[n.name]?.on ?: true,
                                alt = old[n.name]?.alt.orEmpty(),
                                sample = if (n.sample.isNotBlank()) n.sample
                                else old[n.name]?.sample.orEmpty(),
                            )
                        }
                        /*
                         * 旧名单里没数到的人，只有「用户配过嗓子/写进角色表」的才留。
                         *
                         * 不这么限的话，上一版数出来的「嘶哑」「没好气」「肩膀」因为默认
                         * 勾着会被一直带下去 —— 换了算法也清不掉，用户看到的就是「规则
                         * 改了没用」。
                         */
                        val kept = HashSet<String>(cur.roles.size * 4)
                        /* 组员也算「配过嗓子的人」：并进组里的那些人不在名单上，句数会跟着丢。 */
                        cur.roles.forEach { r -> kept.add(r.name); r.alt.forEach { kept.add(it) } }
                        cur.pool.forEach { p ->
                            if (p.name.isBlank() || p.name !in kept || !seen.add(p.name)) return@forEach
                            marked += p
                        }
                        /* 名单整份换掉：老版本混进来的噪音不留。 */
                        val next = cur.copy(
                            pool = marked,
                            extraVerb = custom.first,
                            extraAdv = custom.second,
                            extraTitle = emptyList(),
                            sex = cur.sex.filterKeys { n -> marked.any { it.name == n } },
                        )
                        app.shelf.setCast(id, RuleCodec.toJson(next))

                        /*
                         * 句库。名单出来的那几个人才收 —— 名单外的不存，免得存一屋子垃圾。
                         * 走到这儿一定是「补扫」：要么没建过，要么用户点了「重新数」。
                         */
                        note.value = "记他的话"
                        Lines.build(
                            app, id, raw, chs, marked.map { it.name },
                        ) { done, all ->
                            pct.intValue = if (all == 0) 0 else done * 100 / all
                            note.value = "第 $done/$all 章"
                        }
                        /*
                         * 句数以句库为准。
                         *
                         * 数人那一遍数出来的是「他当主语的次数」，句库存的是「他真说的话」。
                         * 两个数不一样 —— 列表上写一个、点进去看见另一个，用户会以为
                         * 有一边在骗他。所以统一成句库那个：点进去数得出来的，就是列表上那个。
                         */
                        val cnt = Lines.index(app, id)
                        val sync = app.shelf.find(id) ?: meta
                        val cb = castOf(app, sync)
                        app.shelf.setCast(
                            id,
                            RuleCodec.toJson(
                                cb.copy(pool = cb.pool.map { p ->
                                    val n = cnt[p.name]
                                    if (n != null) p.copy(lines = n) else p
                                })
                            )
                        )
                        end = DONE
                    }
                }
            } catch (e: Exception) {
                end = FAIL
            } catch (t: Throwable) {
                // 连 OutOfMemoryError 也要落一个状态：不然界面永远停在「正在数」。
                end = FAIL
            }
            note.value = ""
            pct.intValue = 0
            state[id] = end
            version.intValue++
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
