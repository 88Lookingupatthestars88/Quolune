package com.pagefold.reader.book

import androidx.compose.runtime.mutableIntStateOf
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
        if (cur.pool.isNotEmpty()) {
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
        val id = meta.id
        scope.launch {
            var end = FAIL
            try {
                val raw = app.shelf.read(id)
                if (!raw.isNullOrBlank()) {
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
                        end = DONE
                    }
                }
            } catch (e: Exception) {
                end = FAIL
            } catch (t: Throwable) {
                // 连 OutOfMemoryError 也要落一个状态：不然界面永远停在「正在数」。
                end = FAIL
            }
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
