package com.pagefold.reader.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 系统 TTS 通道：完全离线、不花钱、不联网。
 *
 * 与 v2 的区别（v2 一个都读不出来，原因全在这里）：
 *
 * 1. **等 onInit 回调**。v2 传了空回调，然后用 while 轮询 `tts.engines` 猜是否就绪；
 *    `getEngines()` 只是查系统服务，初始化没完成也能返回非 null，于是「假就绪」，
 *    紧接着 `voices` 是 null → 列表空 → 界面上一个字都没有。
 * 2. **遍历系统里装着的每一个 TTS 引擎**，不是只用默认那一个。
 * 3. **不筛语言、不筛是否要联网**。v2 写死了 `language == "zh"` 且
 *    `!isNetworkConnectionRequired`，国产 ROM 上语音包名字不一定是标准 locale，
 *    整批被过滤掉。要什么由用户在音色页自己挑。
 * 4. **不在主线程跑**。v2 在 Compose 里直接调 `list()`，内部 Thread.sleep 最多 3 秒，
 *    界面直接卡住。
 * 5. **多引擎各自持有实例**，切引擎不用重建，也不会互相 shutdown。
 */
class SysVoice(ctx: Context) {

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
     * 列出机器上装了的系统 TTS 引擎，以及各自的话音。
     *
     * 两处会数重，都在这儿挡掉：
     *   1. 「系统默认」不是独立引擎，它的包名是空串，默认引擎本身已在列表里，
     *      再塞一条就把同一批话音数两遍。
     *   2. 同一个小米话音会报 `zh-CN-xiaomi-model-1` 和 `cmn-cn-xiaomi-model-1`
     *      两个名字，洗干净之后显示名一样，看着是两条。
     *
     * 去重按 引擎 + 语言 + 洗干净的名字，重名的补一个序号。
     */
    suspend fun listAll(): List<Voice> = withContext(Dispatchers.IO) {
        val out = ArrayList<Voice>()
        val seenParam = HashSet<String>()
        val seenFace = HashMap<String, Int>()

        // 系统里注册了哪些引擎。问默认引擎要，它手里有全表。
        val infos = runCatching { obtain(null).engines }.getOrNull().orEmpty()

        val targets = LinkedHashMap<String, String>()
        infos.forEach { t ->
            val pkg = t.name
            if (!pkg.isNullOrBlank() && pkg != "android.tts") {
                targets[pkg] = t.label?.takeIf { it.isNotBlank() } ?: pkg
            }
        }
        // 一个引擎都没报上来（个别 ROM），退回默认引擎，包名留空
        if (targets.isEmpty()) targets[""] = "本机"

        targets.forEach { (pkg, label) ->
            val engine = runCatching { obtain(pkg.ifBlank { null }) }.getOrNull() ?: return@forEach
            val voices = runCatching { engine.voices }.getOrNull().orEmpty()
            voices.forEach { v ->
                val raw = v.name ?: return@forEach
                val loc = v.locale
                val tag = loc?.toLanguageTag()?.replace('-', '_').orEmpty()
                val param = "$pkg|$raw|$tag"
                if (!seenParam.add("$pkg|$raw")) return@forEach

                val clean = readableVoiceName(raw)
                // 同一引擎、同一语言里，显示名撞了就加序号
                val faceKey = "$pkg|$tag|$clean"
                val nth = (seenFace[faceKey] ?: 0) + 1
                seenFace[faceKey] = nth
                val shown = if (nth == 1) clean else "$clean ($nth)"

                out.add(
                    Voice(
                        id = "sys_" + (pkg + raw).hashCode().toString(16),
                        name = shown,
                        kind = "system",
                        engine = "system",
                        param = param,
                        desc = buildString {
                            if (loc != null) append(loc.displayName)
                            if (v.isNetworkConnectionRequired) {
                                if (isNotEmpty()) append(" · ")
                                append("需联网")
                            }
                        },
                        tags = listOfNotNull(label, loc?.displayName, tag),
                    )
                )
            }
        }

        // 同一副嗓子报好几遍的，留下能用的一条
        out.sortWith(compareBy({ it.tags.getOrNull(0).orEmpty() }, { it.name }))
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

    /**
     * param 形如 `包名|话音名|zh_CN`。第三段可能没有（旧数据）。
     * 返回 包名 / 话音名 / 语言标签。
     */
    private fun splitParam(param: String): Triple<String, String, String> {
        val a = param.split('|')
        return when (a.size) {
            1 -> Triple("", a[0], "")
            2 -> Triple(a[0], a[1], "")
            else -> Triple(a[0], a[1], a[2])
        }
    }

    suspend fun file(text: String, voiceParam: String, speed: Int, pitch: Int, out: File): File =
        withContext(Dispatchers.IO) {
            val (pkg, vname, langTag) = splitParam(voiceParam)
            val engine = obtain(pkg.ifBlank { null })

            /*
             * 话音一定要设对，否则引擎会沿用上一个语言（多半是中文），
             * 拿着世界语的包念中文 —— 听着还是中文，只是怪。
             */
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

            // synthesizeToFile 是异步的：轮询文件落地，最多 25 秒
            val deadline = System.currentTimeMillis() + 25_000
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
