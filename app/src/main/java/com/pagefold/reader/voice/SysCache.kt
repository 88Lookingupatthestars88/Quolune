package com.pagefold.reader.voice

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.pagefold.reader.field.EngineCfg
import com.pagefold.reader.field.Engines
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 本机朗读引擎的话音表，按包名分开，落一份在 files/system-voices.json。
 *
 * 读一台引擎要初始化它、还要试念一句，所以读在后台。读到之前先给空表或上一次的表；
 * 读完 [rev] 加一，界面拿它当 remember 的键重画。
 */
object SysCache {

    /** 每读完一台加一。 */
    var rev by mutableIntStateOf(0)
        private set

    /**
     * 一台引擎的那一份。
     *
     * @param at 读到它的时刻。隔一天重验一次 —— 语音包装了、卸了都在引擎那边改。
     */
    private data class Held(val voices: List<Voice> = emptyList(), val at: Long = 0L)

    private const val DAY = 24L * 3600_000L

    private val gson = Gson()
    /* 读在后台、扫在界面线程，两边都会往里写。 */
    private val mem = ConcurrentHashMap<String, Held>()
    private val busy = HashSet<String>()
    private var file: File? = null
    private var sys: SysVoice? = null
    private var scope: CoroutineScope? = null

    fun attach(ctx: Context, voice: SysVoice, work: CoroutineScope) {
        if (file == null) file = File(ctx.filesDir, "system-voices.json")
        if (sys == null) sys = voice
        if (scope == null) scope = work
        if (mem.isEmpty()) load()
    }

    /** 把开着的那几台排进读取队列。启动时调一次。 */
    fun warm(engines: List<EngineCfg>) {
        engines.filter { it.protocol == "system" && it.on }
            .map { it.base.trim() }
            .distinct()
            .forEach { ask(it) }
    }

    /** 这一台引擎的话音。过期了先给旧的那一份，同时排一次重读。 */
    fun of(pkg: String): List<Voice> {
        mark()
        val p = pkg.trim()
        if (p.isEmpty()) return emptyList()
        val h = mem[p] ?: run { ask(p); return emptyList() }
        if (!fresh(h)) ask(p, force = true)
        return h.voices
    }

    /** 读到几副。还没读到给 -1，界面好写「正在读」。 */
    fun count(pkg: String): Int {
        mark()
        val p = pkg.trim()
        if (p.isEmpty()) return -1
        return mem[p]?.voices?.size ?: -1
    }

    /** 刚扫回来的那一份直接收下，不用再问一次。 */
    fun memorize(pkg: String, voices: List<Voice>) {
        val p = pkg.trim()
        if (p.isEmpty()) return
        val old = mem[p]
        if (old != null && old.voices == voices && fresh(old)) return
        mem[p] = Held(voices, System.currentTimeMillis())
        busy.remove(p)
        save()
        rev++
    }

    /** 已经读过这一台了（读到空表也算）。 */
    fun known(pkg: String): Boolean = mem.containsKey(pkg.trim())

    /** 让它重读一遍。装了新语音包之后用。 */
    fun reread(pkg: String) {
        val p = pkg.trim()
        if (p.isEmpty()) return
        mem.remove(p)
        busy.remove(p)
        ask(p)
    }

    /** 这一台从声源里拿掉了，它的话音也不必留着。 */
    fun forget(pkg: String) {
        val p = pkg.trim()
        if (p.isEmpty()) return
        mem.remove(p)
        busy.remove(p)
        save()
        rev++
    }

    /** 读一次 [rev]，调用方就订阅上了。 */
    private fun mark(): Int = rev

    private fun fresh(h: Held): Boolean = System.currentTimeMillis() - h.at < DAY

    private fun ask(pkg: String, force: Boolean = false) {
        if (pkg.isEmpty() || pkg in busy) return
        if (!force && mem.containsKey(pkg)) return
        val v = sys ?: return
        val sc = scope ?: return
        busy.add(pkg)
        sc.launch {
            val got = runCatching { v.usable(pkg) }.getOrDefault(emptyList())
            busy.remove(pkg)
            mem[pkg] = Held(got, System.currentTimeMillis())
            save()
            rev++
        }
    }

    private fun load() {
        runCatching {
            val f = file ?: return@runCatching
            if (!f.exists()) return@runCatching
            val t = object : TypeToken<HashMap<String, Held>>() {}.type
            val raw = gson.fromJson<HashMap<String, Held>>(f.readText(), t) ?: return@runCatching
            raw.forEach { (k, h) ->
                if (k.isBlank() || h == null) return@forEach
                mem[k] = Held(
                    voices = h.voices.mapNotNull { v ->
                        runCatching {
                            v.copy(
                                id = v.id ?: "",
                                name = v.name ?: "",
                                kind = v.kind ?: "system",
                                engine = v.engine ?: Engines.sysId(k),
                                param = v.param ?: "",
                                desc = v.desc ?: "",
                                tags = v.tags ?: emptyList(),
                            )
                        }.getOrNull()?.takeIf { it.name.isNotBlank() }
                    },
                    at = if (h.at > 0L) h.at else 0L,
                )
            }
        }
        rev++
    }

    private fun save() {
        runCatching {
            val f = file ?: return@runCatching
            f.writeText(gson.toJson(mem))
        }
    }
}
