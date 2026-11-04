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
    private const val WSS2 = "wss://api.msedgeservices.com/tts/cognitiveservices/websocket/v1"
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
    /** 一次请求最多等多久。不要设得太长，卡住时没法退回来。 */
    @Volatile var timeoutSec: Int = 12

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
        var last: Throwable? = null
        // 三次：原端点 → 校正时钟后原端点 → 备用端点（不需要 GEC 令牌的那条）。
        // 403 只在握手阶段出现，换端点是一次干净的重新开始。
        val plans = listOf(true, true, false)
        for (gec in plans) {
            try {
                return send(text, voiceId, r, speed, pitch, gec)
            } catch (e: Denied) {
                last = e
                e.serverMs?.let { skewMs = it - System.currentTimeMillis() }
            } catch (e: Exception) {
                last = e
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
        gec: Boolean,
    ): ByteArray {
        val req = Request.Builder()
            .url(if (gec) buildUrl() else buildUrl2())
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
                if (text.contains("Path:turn.end")) {
                    runCatching { ws.close(1000, null) }
                    done.countDown()
                }
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                val d = bytes.toByteArray()
                val at = indexOf(d, AUDIO_MARK)
                if (at >= 0) {
                    val from = at + AUDIO_MARK.size
                    if (from < d.size) synchronized(out) { out.write(d, from, d.size - from) }
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                err.set(if (response?.code == 403) Denied(dateOf(response.header("Date"))) else t)
                done.countDown()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                done.countDown()
            }
        }

        val ws = http.newWebSocket(req, listener)
        try {
            if (!done.await(timeoutSec.toLong(), TimeUnit.SECONDS)) throw IOException("Edge 超时")
        } finally {
            runCatching { ws.cancel() }
        }
        err.get()?.let { throw it }
        val bytes = synchronized(out) { out.toByteArray() }
        if (bytes.isEmpty()) throw IOException("Edge 没有返回音频")
        return bytes
    }

    private class Denied(val serverMs: Long?) : RuntimeException("Edge 403")

    /** 备用端点：不需要 Sec-MS-GEC，令牌走 Ocp-Apim-Subscription-Key。 */
    private fun buildUrl2(): String =
        "$WSS2?Ocp-Apim-Subscription-Key=${Stamp.CLIENT}"

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
