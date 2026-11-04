package com.pagefold.reader.voice

import com.pagefold.reader.book.Reading
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 微软 Edge 朗读通道。走 WebSocket，返回 MP3 原始字节（不解码，交给播放器）。
 *
 * 情绪走 SSML 的 prosody（rate / pitch / volume 三根轴），不走 express-as。
 * 这条通道不认 express-as：发了它不报错，只是整段没有音频，一直到超时才退回来 ——
 * 听着就是「选什么风格读的都一样」，还把播放拖死。
 */
object Edge {

    private const val WSS = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
    /*
     * 曾经这里还有第二条路：api.msedgeservices.com。
     *
     * 2026-11 实测：那条握手直接回 403，带不带 Ocp-Apim-Subscription-Key 都一样。
     * 它已经不是备胎，是第三个必然失败的尝试 —— 前两次 403 之后再来一次，
     * 只是把用户多晾八秒，最后还是同一个错。删掉。
     */
    private const val CHROME = "143.0.3650.75"

    /**
     * 这条通道的身份是**写死的 Edge**，不跟用户在「请求头」里选的预设走。
     *
     * 原因：它的 Origin 是 Edge 大声朗读那个扩展（chrome-extension://jdiccldim…），
     * 服务端把 UA 和 Origin 对一对，Chrome 的 UA 配 Edge 的 Origin 就是 403。
     * 所以哪怕预设选的是 Chrome，这里也发 Edge 的 UA。
     */
    private const val EDGE_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0"

    /** 这两个头可以用户改，默认值放在 field/Headers.kt 里。 */
    @Volatile var ua: String = EDGE_UA
    @Volatile var lang: String = "zh-CN,zh;q=0.9"
    /** 空闲多久算卡住。收到任何一帧都会重新计时，所以这是「对面还活着」的判据。 */
    @Volatile var timeoutSec: Int = 10

    private val AUDIO_MARK = "Path:audio\r\n".toByteArray(Charsets.US_ASCII)

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile
    private var skewMs = 0L

    /**
     * 合成一段，返回 mp3 字节。失败抛 IOException。
     *
     * @param r     段落判定结果；null 表示不按段落走
     * @param speed 用户在音色页调的总语速，100 = 原速
     * @param pitch 用户在音色页调的总音高，100 = 原调
     */
    fun mp3(
        text: String,
        voiceId: String,
        r: Reading? = null,
        speed: Int = 100,
        pitch: Int = 100,
    ): ByteArray {
        var last: Denied? = null
        /*
         * 最多两次：原样发一次，403 就照着对面 Date 把钟拨正再发一次。
         *
         * 403 只有一个原因 —— 时间对不上。令牌是「三百秒一格」算出来的，
         * 手机和对面差超过一格（五分钟）就每格都错。测过：偏 ±300 秒还能过，
         * 偏 ±600 秒必 403，且 403 的回包里带着对面的 Date，正好用来对表。
         *
         * 别的失败（超时、连不上、音频空）**不重试**：同样的条件再跑一遍，
         * 结果一样，只是把用户多晾十几秒。慢就是慢，如实报出来。
         */
        repeat(2) { round ->
            try {
                return send(text, voiceId, r, speed, pitch)
            } catch (e: Denied) {
                last = e
                val server = e.serverMs
                if (server == null) {
                    // 没拿到对面的时间，第二次也是同一个错令牌，别浪费。
                    throw IOException("Edge 不认这台手机的钟，403。把系统时间调成自动再试")
                }
                // 第一次就用对面的时间校准，第二次必然是对的。
                skewMs = server - System.currentTimeMillis()
                if (round == 1) throw IOException("Edge 403，对过表还是被拒。过一会儿再来")
            } catch (e: Exception) {
                throw (e as? IOException) ?: IOException(e.message ?: "Edge 没念出来")
            }
        }
        throw (last as? IOException) ?: IOException(last?.message ?: "Edge 没念出来")
    }

    private fun send(
        text: String,
        voiceId: String,
        r: Reading?,
        speed: Int,
        pitch: Int,
    ): ByteArray {
        val req = Request.Builder()
            .url(buildUrl())
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header("User-Agent", if (ua.contains("Edg/")) ua else EDGE_UA)
            .header("Accept-Language", lang)
            .header(
                "Cookie",
                "muid=" + UUID.randomUUID().toString().replace("-", "").uppercase(Locale.ROOT) + ";"
            )
            .build()

        val out = ByteArrayOutputStream(64 * 1024)
        val err = AtomicReference<Throwable?>(null)
        val done = CountDownLatch(1)
        val rid = UUID.randomUUID().toString().replace("-", "")
        /** 已经收到过 Path:audio 那一帧。之后的二进制帧全是裸音频。 */
        val audioStarted = AtomicBoolean(false)
        /** 对面正经说了「念完了」。用来把「close 之后 OkHttp 回调的 Socket closed」区分开。 */
        val ended = AtomicBoolean(false)
        /** 最后一帧到达的时刻。等的时候看它，判断是「慢」还是「死了」。 */
        val lastFrame = AtomicLong(System.currentTimeMillis())

        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                runCatching {
                    ws.send(configFrame())
                    ws.send(ssmlFrame(rid, voiceId, text, r, speed, pitch))
                }.onFailure {
                    err.set(it)
                    done.countDown()
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                lastFrame.set(System.currentTimeMillis())
                if (text.contains("Path:turn.end")) {
                    ended.set(true)
                    // 先放行再关连接：close() 会等对面回帧，拖着没意义。
                    done.countDown()
                    runCatching { ws.close(1000, null) }
                }
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                lastFrame.set(System.currentTimeMillis())
                val d = bytes.toByteArray()
                synchronized(out) {
                    /*
                     * 带 Path:audio 头的只有**第一帧**，后面十几帧是纯音频。
                     *
                     * 以前每一帧都去找这个头，找不到就把整帧丢掉 —— 一段一百来字的话，
                     * 三十几 KB 只留下开头那一小块，交出去的是断在中间的 mp3，
                     * 播放器解不开，听着就是「Edge 不出声」。
                     */
                    if (audioStarted.get()) {
                        out.write(d)
                        return
                    }
                    val at = indexOf(d, AUDIO_MARK)
                    if (at >= 0) {
                        audioStarted.set(true)
                        val from = at + AUDIO_MARK.size
                        if (from < d.size) out.write(d, from, d.size - from)
                    }
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                // 我们自己 close/cancel 带出来的失败，不能盖掉已经收全的音频。
                if (ended.get()) return
                err.set(if (response?.code == 403) Denied(dateOf(response.header("Date"))) else t)
                done.countDown()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                done.countDown()
            }
        }

        val ws = http.newWebSocket(req, listener)
        /*
         * 等多久，按「还动不动」算，不按「总共几秒」算。
         *
         * 实测：+0% 正常语速，一句 30 字 1.9 秒；同一句话拖到 −50%，
         * 音频时长翻倍，服务端要 12.5 秒才推完 —— 原来那条 12 秒的死线正好把它切在中间，
         * 于是「慢速听书」这一路必然超时，重试三次还是超时，用户听到的是一直转圈。
         *
         * 现在的判据是两条：
         * · 空闲超过 10 秒没有任何一帧过来，才算卡住；
         * · 总预算 = 12 秒底 + 每字 120 毫秒，给长段落和慢速留够。
         */
        val idleMs = timeoutSec.coerceIn(5, 60) * 1000L
        val budgetMs = 12_000L + text.length.coerceAtMost(2_000) * 120L
        val startedAt = System.currentTimeMillis()
        var ok = false
        while (true) {
            if (done.await(200, TimeUnit.MILLISECONDS)) { ok = true; break }
            val now = System.currentTimeMillis()
            if (now - lastFrame.get() > idleMs) break
            if (now - startedAt > budgetMs) break
        }
        /*
         * 先取结论，再断连接。
         *
         * cancel() 会让 OkHttp 回调 onFailure(Socket closed)；反过来的话，音频明明收全了，
         * 也会被这一下判成失败 —— 而且这个先后是竞态，看机器忙不忙，表现成「有时能念有时不念」。
         */
        val failure = err.get()
        runCatching { ws.cancel() }
        if (ok) {
            failure?.let { throw it }
        } else {
            val got = synchronized(out) { out.size() }
            // 音频已经收了一大截，只是对面没发 turn.end：交付已收到的，比整段丢掉强。
            if (got < 8192) {
                failure?.let { throw it }
                throw IOException(if (System.currentTimeMillis() - lastFrame.get() > idleMs) "Edge 卡住不动了" else "Edge 超时")
            }
        }
        val bytes = synchronized(out) { out.toByteArray() }
        if (bytes.isEmpty()) throw IOException("Edge 没有返回音频")
        return bytes
    }

    private class Denied(val serverMs: Long?) : RuntimeException("Edge 403")

    private fun buildUrl(): String {
        val cid = UUID.randomUUID().toString().replace("-", "")
        val gec = Stamp.generate(System.currentTimeMillis() + skewMs)
        return "$WSS?TrustedClientToken=${Stamp.CLIENT}" +
            "&ConnectionId=$cid&Sec-MS-GEC=$gec&Sec-MS-GEC-Version=2-$CHROME"
    }

    private fun stamp(): String {
        val f = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date())
    }

    private fun configFrame(): String {
        val json = """{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":false,"wordBoundaryEnabled":false},"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}"""
        return "X-Timestamp:${stamp()}\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n$json"
    }

    private fun ssmlFrame(
        rid: String,
        voiceId: String,
        text: String,
        r: Reading?,
        speed: Int,
        pitch: Int,
    ): String {
        val lg = langOf(voiceId)
        // 总语速/音高 + 段落判定，一起折算成 prosody
        val ratePct = (speed - 100) + (r?.rate ?: 0)
        val pitchHz = ((pitch - 100) * 0.5).toInt() + (r?.pitch ?: 0)
        val volPct = r?.volume ?: 0

        val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='$lg'>" +
            "<voice name='$voiceId'>" +
            "<prosody rate='${signed(ratePct)}%' pitch='${signed(pitchHz)}Hz' volume='${signed(volPct)}%'>" +
            xml(text) +
            "</prosody></voice></speak>"
        return "X-RequestId:$rid\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:${stamp()}\r\nPath:ssml\r\n\r\n$ssml"
    }

    private fun signed(v: Int): String = if (v >= 0) "+$v" else "$v"

    private fun langOf(voice: String): String {
        val a = voice.indexOf('-')
        if (a <= 0) return "zh-CN"
        val b = voice.indexOf('-', a + 1)
        return if (b <= 0) voice else voice.substring(0, b)
    }

    private fun xml(s: String): String {
        if (s.none { it == '&' || it == '<' || it == '>' || it == '"' || it == '\'' }) return s
        val sb = StringBuilder(s.length + 16)
        s.forEach {
            when (it) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> sb.append(it)
            }
        }
        return sb.toString()
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun dateOf(raw: String?): Long? = runCatching {
        val f = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        f.timeZone = TimeZone.getTimeZone("GMT")
        f.parse(raw ?: return null)?.time
    }.getOrNull()
}
