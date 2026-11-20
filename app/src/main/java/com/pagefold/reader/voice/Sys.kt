package com.pagefold.reader.voice

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.pagefold.reader.field.Engines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 一台本机朗读引擎，和它底下能念的话音。 */
data class LocalEngine(val pkg: String, val label: String, val voices: List<Voice>)

/**
 * 扫一遍的结果。
 *
 * @param engines 念得出声的那几台
 * @param muted   扫到了、但一副也念不出来的台数
 */
data class Swept(val engines: List<LocalEngine>, val muted: Int)

/** 系统 TTS 通道：完全离线、不花钱、不联网。 */
class SysVoice(ctx: Context) {

    private companion object {
        const val BIND_TTS = "android.permission.BIND_TTS_SERVICE"

        /** 试念用的一句。短到随便哪台引擎都能出。 */
        const val PROBE = "嗯。"
    }

    private val appCtx: Context = ctx.applicationContext

    /** key = 引擎包名，"" 表示系统默认引擎。 */
    private val pool = ConcurrentHashMap<String, TextToSpeech>()

    // ───────────────────────── 初始化 ─────────────────────────

    private suspend fun initOne(key: String): TextToSpeech = suspendCancellableCoroutine { cont ->
        val slot = AtomicReference<TextToSpeech?>(null)
        val fired = AtomicBoolean(false)
        val cb = TextToSpeech.OnInitListener { status ->
            if (!fired.compareAndSet(false, true)) return@OnInitListener
            val t = slot.get()
            if (!cont.isActive) return@OnInitListener
            if (status == TextToSpeech.SUCCESS && t != null) cont.resume(t)
            else cont.resumeWithException(
                IllegalStateException("引擎 ${key.ifBlank { "默认" }} 起不来，状态 $status")
            )
        }
        val made = try {
            if (key.isBlank()) TextToSpeech(appCtx, cb) else TextToSpeech(appCtx, cb, key)
        } catch (e: Throwable) {
            if (cont.isActive) cont.resumeWithException(e)
            null
        }
        if (made != null) slot.set(made)
        cont.invokeOnCancellation { made?.let { runCatching { it.shutdown() } } }
    }

    private suspend fun obtain(pkg: String?): TextToSpeech {
        val key = pkg.orEmpty()
        pool[key]?.let { return it }
        val made = withTimeoutOrNull(9000) { initOne(key) }
            ?: throw IllegalStateException("引擎 ${key.ifBlank { "默认" }}等了半天没起来")
        val prev = pool.putIfAbsent(key, made)
        if (prev != null) {
            runCatching { made.shutdown() }
            return prev
        }
        return made
    }

    // ───────────────────────── 枚举 ─────────────────────────

    /**
     * 机器上装了的朗读引擎：包名 → 名字。
     *
     * 只做包名扫描，不初始化引擎。声明上要看三样：服务带 `android.speech.tts` 这条
     * meta-data（要 GET_META_DATA 才读得到），绑定权限是 `BIND_TTS_SERVICE`，
     * 或者包里有声明 `ACTION_CHECK_TTS_DATA` 的 activity。
     * 念不念得出声不在这里判，见 [usable]。
     */
    fun engines(): List<Pair<String, String>> {
        val pm = appCtx.packageManager
        val me = appCtx.packageName
        val out = LinkedHashMap<String, String>()
        val data = runCatching {
            pm.queryIntentActivities(Intent(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA), 0)
                .mapNotNull { it.activityInfo?.packageName }.toSet()
        }.getOrDefault(emptySet())
        runCatching {
            val q = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
            pm.queryIntentServices(q, PackageManager.GET_META_DATA).forEach { ri ->
                runCatching {
                    val si = ri.serviceInfo ?: return@runCatching
                    val pkg = (si.packageName ?: "").trim()
                    if (pkg.isEmpty() || pkg == me) return@runCatching
                    val tagged = si.metaData?.containsKey("android.speech.tts") == true
                    if (!tagged && pkg !in data) return@runCatching
                    /* 声明了别的权限的，不是朗读引擎。没声明权限的放行，交给 [usable] 判。 */
                    val perm = si.permission
                    if (!perm.isNullOrBlank() && perm != BIND_TTS) return@runCatching
                    val label = si.applicationInfo?.loadLabel(pm)?.toString().orEmpty()
                        .ifBlank { runCatching { ri.loadLabel(pm).toString() }.getOrDefault("") }
                    out[pkg] = label.ifBlank { pkg }
                }
            }
        }
        return out.map { it.key to it.value }
    }

    /** 每台引擎各自能念的话音。一台要初始化、还要试念一句，所以最多同时开两路。 */
    suspend fun sweep(engines: List<Pair<String, String>>): Swept = coroutineScope {
        val gate = Semaphore(2)
        val got = engines.map { (pkg, label) ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    LocalEngine(pkg, label, runCatching { usable(pkg) }.getOrDefault(emptyList()))
                }
            }
        }.awaitAll()
        Swept(got.filter { it.voices.isNotEmpty() }, got.count { it.voices.isEmpty() })
    }

    /**
     * 这台引擎能念的话音。一副也念不出来就给空表。
     *
     * 判据是试念一句能不能落出一个完整文件 —— 引擎报出来的话音表里，
     * 只有语言没有语音包的那种照样列得出，不试就分不清。
     */
    suspend fun usable(pkg: String): List<Voice> = withContext(Dispatchers.IO) {
        val all = voices(pkg)
        if (all.isEmpty()) return@withContext emptyList()
        val tmp = File(appCtx.cacheDir, "tts-try-" + pkg.hashCode().toString(16) + ".wav")
        val ok = runCatching {
            file(PROBE, all.first().param, 100, 100, tmp, 6500)
            true
        }.getOrDefault(false)
        runCatching { tmp.delete() }
        if (ok) all else emptyList()
    }

    /** 这一台引擎底下的全部话音。要初始化它，最多等 9 秒。 */
    suspend fun voices(pkg: String): List<Voice> = withContext(Dispatchers.IO) {
        val engine = runCatching { obtain(pkg.ifBlank { null }) }.getOrNull()
            ?: return@withContext emptyList()
        val list = runCatching { engine.voices }.getOrNull().orEmpty()
        val out = ArrayList<Voice>(list.size)
        val seen = HashSet<String>()
        val seenFace = HashMap<String, Int>()

        list.forEach { v ->
            val raw = v.name ?: return@forEach
            if (!seen.add(raw)) return@forEach
            /* 引擎自己说的：这一副的语音数据没装。 */
            if (v.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true) {
                return@forEach
            }
            val loc = v.locale
            val tag = loc?.toLanguageTag()?.replace('-', '_').orEmpty()

            val clean = readableVoiceName(raw)
            // 同一语言里显示名撞了就加序号
            val faceKey = "$tag|$clean"
            val nth = (seenFace[faceKey] ?: 0) + 1
            seenFace[faceKey] = nth
            val shown = if (nth == 1) clean else "$clean ($nth)"

            out.add(
                Voice(
                    id = "sys_" + (pkg + raw).hashCode().toString(16),
                    name = shown,
                    kind = "system",
                    engine = Engines.sysId(pkg),
                    param = "$pkg|$raw|$tag",
                    desc = buildString {
                        if (loc != null) append(loc.displayName)
                        if (v.isNetworkConnectionRequired) {
                            if (isNotEmpty()) append(" · ")
                            append("需联网")
                        }
                    },
                    tags = listOfNotNull(loc?.displayName, tag),
                )
            )
        }

        // 中文排前，其次按名字。
        out.sortWith(compareBy({ if (it.tags.getOrNull(1).orEmpty().startsWith("zh") ||
            it.tags.getOrNull(1).orEmpty().startsWith("cmn")) 0 else 1 }, { it.name }))
        out
    }

    /** `zh-CN-xiaomi-model-1` → `xiaomi model 1`；`cmn-cn-x-ccc-local` → `ccc local`。 */
    private fun readableVoiceName(raw: String): String {
        val cleaned = raw.replace('_', ' ').replace('-', ' ')
        val parts = cleaned.split(' ').filter { it.isNotBlank() }
        // 去掉语言/地区前缀（zh CN cmn cn x …）
        val tail = parts.dropWhile { p ->
            p.length <= 3 || p.equals("voice", true) || p.equals("local", true) && parts.size > 2
        }
        val body = (if (tail.isEmpty()) parts else tail).joinToString(" ")
        return body.ifBlank { cleaned }
    }

    // ───────────────────────── 合成 ─────────────────────────

    /** param 形如 包名|话音名|zh_CN。 */
    private fun splitParam(param: String): Triple<String, String, String> {
        val a = param.split('|')
        return when (a.size) {
            1 -> Triple("", a[0], "")
            2 -> Triple(a[0], a[1], "")
            else -> Triple(a[0], a[1], a[2])
        }
    }

    suspend fun file(
        text: String,
        voiceParam: String,
        speed: Int,
        pitch: Int,
        out: File,
        waitMs: Long = 25_000,
    ): File =
        withContext(Dispatchers.IO) {
            val (pkg, vname, langTag) = splitParam(voiceParam)
            val engine = obtain(pkg.ifBlank { null })

            /* 话音和语种一起设。只设话音不设语种时，引擎会沿用上一个语种。 */
            if (vname.isNotBlank()) {
                val all = runCatching { engine.voices }.getOrNull().orEmpty()
                val hit = all.firstOrNull { it.name == vname }
                    ?: all.firstOrNull { it.name?.equals(vname, true) == true }
                if (hit != null) {
                    runCatching { engine.voice = hit }
                    runCatching { engine.setLanguage(hit.locale) }
                } else if (langTag.isNotBlank()) {
                    runCatching {
                        engine.setLanguage(java.util.Locale.forLanguageTag(langTag.replace('_', '-')))
                    }
                }
            }
            runCatching { engine.setSpeechRate((speed / 100f).coerceIn(0.3f, 3f)) }
            runCatching { engine.setPitch((pitch / 100f).coerceIn(0.3f, 2.5f)) }

            val id = "pf" + System.nanoTime()
            out.parentFile?.mkdirs()
            if (out.exists()) out.delete()

            val err = AtomicReference<String?>(null)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {}
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == id) err.compareAndSet(null, "合成失败")
                }
                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (utteranceId == id) err.compareAndSet(null, "没合成出来，错误码 $errorCode")
                }
            })

            val r = engine.synthesizeToFile(text, Bundle(), out, id)
            if (r != TextToSpeech.SUCCESS) throw IllegalStateException("引擎拒绝合成（返回 $r）")

            // synthesizeToFile 是异步的：轮询文件落地。
            val deadline = System.currentTimeMillis() + waitMs
            while (System.currentTimeMillis() < deadline) {
                err.get()?.let { throw IllegalStateException(it) }
                if (out.exists() && out.length() > 1024) return@withContext out
                Thread.sleep(60)
            }
            throw IllegalStateException(
                if (out.exists()) "只写出了 ${out.length()} 字节，引擎可能没装语音包"
                else "引擎没吐出文件，多半是没装中文语音包"
            )
        }

    // ───────────────────────── 收尾 ─────────────────────────

    fun shutdown() {
        pool.values.forEach {
            runCatching { it.stop() }
            runCatching { it.shutdown() }
        }
        pool.clear()
    }
}
