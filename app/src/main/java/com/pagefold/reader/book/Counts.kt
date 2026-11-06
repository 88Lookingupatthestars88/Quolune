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
        Thread(r, "quolune-count").apply { isDaemon = true }
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
                    val list = Look.speakers(raw, 120)
                    val custom = Look.customs(raw)
                    if (list.isNotEmpty()) {
                        val fresh = app.shelf.find(id) ?: meta
                        val cur = castOf(app, fresh)
                        /*
                         * 只补不删。
                         *
                         * 已经勾过的保持勾着、写过的叫法留着；名单里没有的那些也留着 ——
                         * 手写加的人、上一份配置带进来的人，都不在这次数出来的结果里，
                         * 整个盖掉的话，点一下重新数自己建的人就没了。
                         */
                        val old = cur.pool.associateBy { it.name }
                        val marked = list.map { n ->
                            n.copy(on = old[n.name]?.on ?: true, alt = old[n.name]?.alt.orEmpty())
                        } + cur.pool.filter { p -> list.none { it.name == p.name } }
                        val next = cur.copy(pool = marked, extraVerb = custom.first, extraAdv = custom.second)
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
