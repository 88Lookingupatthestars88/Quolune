package com.pagefold.reader.voice

import android.content.Context
import com.pagefold.reader.field.EngineCfg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 拿一段录音，做成一副能一直用的音色。
 *
 * 各家做完之后**给出的东西不一样**，这一点决定了后面谁去念书，所以这里分三条路：
 *
 * | 家 | 做完给什么 | 之后谁念 |
 * |---|---|---|
 * | MiMo | 什么都不给。音色就是那段录音本身 | **克隆模型自己念**，每句都重发整段样本 |
 * | 硅基流动 | 一个 uri（`speech:名字:账号:id`） | 朗读模型念，样本只在服务端存了一份 |
 * | MiniMax | 一个 voice_id | 朗读模型念（t2a_v2），样本只在服务端存了一份 |
 *
 * 差别不是小事：MiMo 那条每念一句都要把几十兆的样本重发一遍，
 * 一部长篇不可能这么走；硅基和 MiniMax 只发一个短 id，才是有声书能用的样子。
 * 所以**做完的音色在这三种情况下 kind 不同**：MiMo 是 clone（每次带样本），
 * 另外两家是 preset（就是一个名字，跟内置音色一样对待）。
 *
 * 火山、百炼、OpenAI 目前没有「一段录音换一副音色」的公开接口（火山要走声音复刻
 * 产品线单独开通，百炼要单独申请权限），这里不装作有。
 */
object CloneApi {

    private val json = "application/json".toMediaType()
    private val octet = "application/octet-stream".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** 一次克隆做出来的东西。 */
    data class Made(
        /** 写进 Voice.param 的那一截。 */
        val param: String,
        /** 写进 Voice.kind 的那一截。决定念书时用哪个模型。 */
        val kind: String,
        /** 给用户看的一句话：这次到底生成了什么。 */
        val note: String,
    )

    /**
     * 这副嗓源能不能做克隆，需要用户另外填什么。
     *
     * @return null = 这家做不了；否则返回「还要不要那句参考文本」
     */
    fun shape(proto: String, base: String): Shape? {
        val b = base.lowercase()
        return when {
            proto == "mimo" || b.contains("xiaomimimo") -> Shape(localOnly = true, needsText = false)
            proto == "minimax" || b.contains("minimax") -> Shape(localOnly = false, needsText = false)
            b.contains("siliconflow") -> Shape(localOnly = false, needsText = true)
            // 其余几家没有对外公开的复刻口，不给入口。
            else -> null
        }
    }

    /**
     * @param localOnly true = 样本只存本地，不往对面传（小米这条路）
     * @param needsText true = 还要用户填「录音里说的那句话」（硅基要）
     */
    data class Shape(val localOnly: Boolean, val needsText: Boolean)

    /**
     * 做一副音色。
     *
     * @param record  录音原文，硅基必须给，别的家不用
     * @param mime    录音的 MIME，判不出来的给空串
     */
    suspend fun make(
        ctx: Context,
        e: EngineCfg,
        name: String,
        bytes: ByteArray,
        mime: String,
        record: String,
    ): Made = withContext(Dispatchers.IO) {
        if (bytes.size < 4096) throw IOException("这段录音太短了，听不出音色。")
        val proto = e.protocol
        val base = (e.base ?: "").trim()
        val key = (e.key ?: "").trim()
        val id = "u_" + System.currentTimeMillis().toString(36)

        when {
            // ── 小米：样本就是音色，存本地，每次念书重发 ──
            proto == "mimo" || base.lowercase().contains("xiaomimimo") -> {
                if (key.isBlank()) throw IOException("还没填钥匙")
                val param = CloneStore.save(ctx, id, bytes, mime)
                    ?: throw IOException("这段录音存不下（超过 ${CloneStore.LIMIT / 1048576} MB）。")
                Made(
                    param = param, kind = "clone",
                    note = "做好了。它会用克隆模型念，每句都要重发这段录音。",
                )
            }

            // ── 硅基流动：传上去，换一个 uri，之后用朗读模型念 ──
            base.lowercase().contains("siliconflow") -> {
                if (key.isBlank()) throw IOException("还没填钥匙")
                if (record.isBlank()) throw IOException("还要填一句：这段录音里说的是什么。")
                // 上传口只认 CosyVoice2；用「克隆模型」那一格，不是朗读那一格。
                val model = e.cloneOr().ifBlank { "FunAudioLLM/CosyVoice2-0.5B" }
                val uri = uploadSilicon(base, key, model, name, record, bytes, mime)
                Made(
                    param = uri, kind = "preset",
                    note = "做好了。之后用朗读模型念，不再重传这段录音。",
                )
            }

            // ── MiniMax：先传文件，换 file_id，再换 voice_id ──
            proto == "minimax" || base.lowercase().contains("minimax") -> {
                if (key.isBlank()) throw IOException("还没填钥匙")
                val vid = cloneMiniMax(base, key, name, bytes, mime, e.cloneOr())
                Made(
                    param = vid, kind = "preset",
                    note = "做好了。之后用朗读模型念，不再重传这段录音。",
                )
            }

            else -> throw IOException(
                "这家没有「一段录音换一副音色」的接口。它自己的后台建好，再把名字填进来。"
            )
        }
    }

    /**
     * 硅基流动：POST {base}/v1/uploads/audio/voice（multipart）
     *
     * 回 `{"uri":"speech:名字:账号:id"}`，这个 uri 就是之后念书时塞进 `voice` 的那一截。
     * `text` 是**录音里说的那句话**，它的作用是让服务端对齐音色，不填会被拒。
     */
    private fun uploadSilicon(
        base: String,
        key: String,
        model: String,
        name: String,
        record: String,
        bytes: ByteArray,
        mime: String,
    ): String {
        val root = base.trimEnd('/')
        val url = when {
            root.contains("/uploads/audio/voice") -> root
            root.endsWith("/v1") -> "$root/uploads/audio/voice"
            else -> "$root/v1/uploads/audio/voice"
        }
        val ext = when {
            mime.contains("wav") -> "wav"
            mime.contains("mpeg") || mime.contains("mp3") -> "mp3"
            mime.contains("m4a") || mime.contains("mp4") -> "m4a"
            else -> "mp3"
        }
        val tmp = File.createTempFile("clone_", ".$ext")
        try {
            tmp.writeBytes(bytes)
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", model)
                .addFormDataPart("customName", name.take(40))
                .addFormDataPart("text", record.take(400))
                .addFormDataPart("file", "sample.$ext", tmp.asRequestBody(octet))
                .build()
            val req = Request.Builder()
                .url(url)
                .header("Authorization", Auth.bearer(key))
                .post(body)
                .build()
            http.newCall(req).execute().use { r ->
                val s = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    throw IllegalStateException("克隆 ${r.code}: ${s.take(180)}")
                }
                val uri = runCatching {
                    com.google.gson.JsonParser.parseString(s).asJsonObject.get("uri").asString
                }.getOrNull()
                if (uri.isNullOrBlank()) throw IOException("克隆没给出音色：${s.take(180)}")
                return uri
            }
        } finally {
            runCatching { tmp.delete() }
        }
    }

    /**
     * MiniMax：两步。
     *
     *   1. POST {base}/v1/files/upload  purpose=voice_clone  → file_id
     *   2. POST {base}/v1/voice_clone   {file_id, voice_id, model} → 激活
     *
     * voice_id 有格式要求：8–256 字符、首字符英文字母、只用字母数字和 - _。这里自己造一个。
     * 注意它**七天内不用就会被删**，所以做完立刻试念一次，把它激活。
     */
    private fun cloneMiniMax(
        base: String,
        key: String,
        name: String,
        bytes: ByteArray,
        mime: String,
        model: String,
    ): String {
        val root = base.trimEnd('/').let { if (it.endsWith("/v1")) it.removeSuffix("/v1") else it }
        val ext = when {
            mime.contains("wav") -> "wav"
            mime.contains("m4a") || mime.contains("mp4") -> "m4a"
            else -> "mp3"
        }
        val tmp = File.createTempFile("clone_", ".$ext")
        try {
            tmp.writeBytes(bytes)

            // 1. 传文件
            val up = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("purpose", "voice_clone")
                .addFormDataPart("file", "sample.$ext", tmp.asRequestBody(octet))
                .build()
            val fid = http.newCall(
                Request.Builder()
                    .url("$root/v1/files/upload")
                    .header("Authorization", Auth.bearer(key))
                    .post(up)
                    .build()
            ).execute().use { r ->
                val s = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw IllegalStateException("上传录音 ${r.code}: ${s.take(180)}")
                runCatching {
                    com.google.gson.JsonParser.parseString(s)
                        .asJsonObject.getAsJsonObject("file").get("file_id").asString
                }.getOrNull() ?: throw IOException("上传录音没给出 file_id：${s.take(180)}")
            }

            // 2. 复刻。voice_id 自己起，首字母必须是英文字母。
            val vid = "tl" + System.currentTimeMillis().toString(36)
            val payload = buildString {
                append("{\"file_id\":").append(quote(fid))
                append(",\"voice_id\":").append(quote(vid))
                // 复刻要指定存成哪个朗读模型下的音色；不填它按默认那档存。
                if (model.isNotBlank()) append(",\"model\":").append(quote(model))
                append("}")
            }
            http.newCall(
                Request.Builder()
                    .url("$root/v1/voice_clone")
                    .header("Authorization", Auth.bearer(key))
                    .header("Content-Type", "application/json")
                    .post(payload.toRequestBody(json))
                    .build()
            ).execute().use { r ->
                val s = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw IllegalStateException("复刻 ${r.code}: ${s.take(180)}")
                // 业务码在 base_resp.status_code 里，HTTP 200 也可能是失败
                val code = runCatching {
                    com.google.gson.JsonParser.parseString(s)
                        .asJsonObject.getAsJsonObject("base_resp").get("status_code").asInt
                }.getOrNull()
                if (code != null && code != 0) {
                    val msg = runCatching {
                        com.google.gson.JsonParser.parseString(s)
                            .asJsonObject.getAsJsonObject("base_resp").get("status_msg").asString
                    }.getOrNull().orEmpty()
                    throw IllegalStateException("复刻失败 $code: $msg")
                }
            }
            return vid
        } finally {
            runCatching { tmp.delete() }
        }
    }

    private fun quote(s: String): String {
        val sb = StringBuilder(s.length + 8)
        sb.append('"')
        s.forEach {
            when (it) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (it < ' ') sb.append(String.format("\\u%04x", it.code)) else sb.append(it)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
