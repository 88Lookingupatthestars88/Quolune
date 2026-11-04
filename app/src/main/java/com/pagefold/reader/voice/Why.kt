package com.pagefold.reader.voice

/**
 * 测试失败之后，屏幕上出现的那一句。
 *
 * 写法固定为三段：**判据 → 结论 → 处置**。
 * 判据是技术事实（状态码、缺哪一格、哪个字段），结论是这个模型/这次请求怎么了，
 * 处置是一步能做完的动作。没有这三样的句子不进这个文件。
 *
 * 不写「可能」「建议您」「请检查配置」—— 说得出原因就说原因，说不出来就说说不出来。
 * 也不把网络问题说成模型问题：401 和 403 跟选了哪个模型无关，那两处不许出现模型名。
 */
object Why {

    /**
     * 名字里带这些的，一定不做语音合成。
     *
     * 只收「一定不是」，不收「大概不是」。判错的代价是双向的：
     * 把能念的说成不能念，用户会去换一家；把不能念的交上去试，只是白点一次。
     * 所以拿不准的一律不进来 —— `gpt-4o`、`mimo-v2.6-pro` 这种就交给对面的响应去说。
     */
    private val NOT_SPEECH = listOf(
        // 识别，方向反了
        "asr", "whisper", "transcri", "sensevoice", "paraformer",
        // 向量、排序、审核
        "embed", "rerank", "moderation",
        // 画图、看视频
        "dall-e", "image", "imagen", "stable-diffusion", "sdxl", "flux",
        "vision", "ocr", "video", "sora",
        // 写代码
        "coder", "-code",
    )

    /** 名字里带这些的，一定是语音。压在上面那张表之前，防止 `gpt-4o-audio-preview` 被 `audio` 之外的字误伤。 */
    private val IS_SPEECH = listOf(
        "tts", "text-to-speech", "speech", "voice", "audio",
        "cosyvoice", "chattts", "sambert", "megatts", "fish-speech", "f5-tts",
    )

    /**
     * 这套协议下，语音模型的名字必须带哪个词。null = 这家没有名字规律，不判。
     *
     * 小米这套口（`chat/completions` + `audio` 参数）是唯一必须走白名单的一家：
     * 它收到不认识的 model **不会报错**，而是直接按默认 TTS 念出来、回一段完整音频。
     * 于是 `mimo-v2.6-flash` 这种写字模型也能「出声」，客户端拿到音频就当成功 ——
     * 用户看到的就是「不是语音模型也能用」和「换哪个模型声音都一样」。
     * 既然对面不说，只能我们这边先说：名字不带 `tts` 的一律不发。
     *
     * 别的协议不进这张表。`/v1/audio/speech` 那个端点收到非语音模型一定回 400/404，
     * 服务端自己会说，我们照原话翻就行，不用猜。
     */
    private fun mustContain(proto: String): String? = when (proto) {
        "mimo" -> "tts"
        else -> null
    }

    /** 这个模型名是否明确不能合成语音。拿不准一律返回 false。 */
    fun notSpeech(model: String): Boolean = notSpeech(model, "")

    fun notSpeech(model: String, proto: String): Boolean = check(model, proto) != null

    /**
     * 每个用途各自认哪个字。空 = 这一格不判。
     *
     * 朗读、造嗓音、复刻嗓音是三个不同的口，名字里的字也不同。
     * 在「造嗓音」那一格填成朗读模型，请求不会报错 —— 只是造出来的东西不是他填的那句描述。
     * 所以每一格只认自己那个字：填错就是填错，不用等对面。
     */
    private fun needOf(role: String): String? = when (role) {
        "design" -> "design"
        "clone" -> "clone"
        else -> "tts"
    }

    /**
     * 这个模型能不能用在**这一格**上。返回 null = 放行，返回一句话 = 拦截理由。
     *
     * 判死的顺序是「这一格要什么 → 一定是 → 一定不是」：
     * 先按用途对上号，再让明确是语音的过（`gpt-4o-audio-preview` 不能被 `audio` 之外的字误伤），
     * 最后看黑名单和这家协议的白名单。
     *
     * @param proto 走哪套协议
     * @param role  "" = 朗读；"design" = 造嗓音；"clone" = 复刻嗓音
     */
    fun check(model: String, proto: String, role: String = ""): String? {
        val n = model.trim().lowercase()
        if (n.isBlank()) return null
        val name = model.trim()

        // 造嗓音 / 复刻嗓音这两格，名字必须带自己那个字。别的先不问。
        if (role == "design" && !n.contains("design")) {
            return "「$name」不是音色设计模型，造不出新嗓音。"
        }
        if (role == "clone" && !n.contains("clone")) {
            return "「$name」不是音色克隆模型，复刻不了嗓音。"
        }
        if (role == "design" || role == "clone") return null

        /*
         * 小米这三个口各锁各的，反着填也要拦。
         *
         * 把 voicedesign 填进「朗读模型」，对面不会报错 —— 它会拿默认声音把书念出来，
         * 只是念的不是这副嗓子。用户会以为「模型选择没用」。这不是配置错，是选串了行。
         */
        if (proto == "mimo") {
            if (n.contains("design")) {
                return "「$name」是造嗓音的模型，念不了书。朗读要用 mimo-v2.5-tts。"
            }
            if (n.contains("clone")) {
                return "「$name」是复刻嗓音的模型，念不了书。朗读要用 mimo-v2.5-tts。"
            }
        }

        if (IS_SPEECH.any { n.contains(it) }) return null
        val no = NOT_SPEECH.any { n.contains(it) } ||
            mustContain(proto)?.let { !n.contains(it) } == true
        if (!no) return null
        return "「$name」不是语音模型，不能用来合成音频。"
    }

    /**
     * @param t     抛出来的东西
     * @param model 这次真正发给对面的模型名，用来指名道姓
     */
    fun say(t: Throwable?, model: String): String {
        if (t == null) return "未捕获到失败原因。"

        val m = (t.message ?: "").trim()
        val low = m.lowercase()
        val name = model.trim().ifBlank { "当前模型" }

        fun has(vararg k: String) = k.any { low.contains(it) }

        return when {
            // ── 本机拦下的，请求还没发出去 ──
            has("还没填接口地址") -> "接口地址为空。"
            has("还没填钥匙", "还没填 api key") -> "API Key 为空。"
            has("还没填模型") -> "模型标识为空。"
            has("这段是空的") -> "待合成文本为空。"

            // ── 请求没到对面 ──
            has("unknownhost", "unable to resolve", "no address associated") ->
                "域名解析失败。请核对接口地址。"
            has("timeout", "timed out", "超时") -> "请求超时，服务端未在限期内响应。"
            has("connect", "refused", "不可达", "failed to connect") ->
                "连接被拒绝。请检查网络与接口地址。"

            // ── 对面回绝了。这三条与模型无关，不点模型名 ──
            has("401", "unauthorized", "invalid api key", "login fail") ->
                "鉴权失败（401）。API Key 无效或已过期。"
            has("429", "rate limit", "too many requests", "限流") ->
                "请求被限流（429）。请降低频率后重试。"

            /*
             * 本机拦下的。放在最前面 —— 它最容易被下面的 400 抢走，
             * 而下面的 400 说的是「太长、有怪符号」，方向完全错。
             *
             * 理由原样透出：这句话是 check() 拼好的，已经指名道姓、
             * 也已经说清是哪一种填错。这里再压一遍就只剩「不是语音模型」，
             * 而用户要的是「它是造嗓音的模型，念不了书」。
             */
            has("不能合成") -> {
                val line = m.substringAfter('：', "").trim()
                if (line.isNotEmpty()) line else "当前选中的模型不能合成音频。"
            }
            has("不是语音模型") -> {
                // 老存档、老异常里可能还带这一句
                val n2 = m.substringAfter('：', "").trim().ifBlank { name }
                "「$n2」不是语音模型，不能用来合成音频。"
            }

            /*
             * ── 选错了模型 ──
             *
             * 三个入口落同一句话：对面 200 回了话但没回音频的、
             * 对面 400 且原文提到 model/speech/audio 的、以及名字一眼就不对的。
             * 用户不需要知道是哪一种，他只需要知道这个名字不对、下一步换什么。
             */
            notSpeech(name) -> "「$name」不是语音模型，不能用来合成音频。"
            has("没有音频") -> "「$name」不是语音模型，不能用来合成音频。"
            has("400", "invalid", "bad request", "not support", "unsupported", "不支持", "参数错误") &&
                has("speech", "audio", "tts", "model") ->
                "「$name」不是语音模型，不能用来合成音频。"

            // ── 名字对、但这台机器或这把钥匙还不够 ──
            has("403", "forbidden") -> "权限不足（403）。当前 Key 未开通「$name」的调用权限。"
            has("404", "not found", "does not exist") -> "模型不存在（404）。服务端没有「$name」。"
            has("克隆模型要一段音频") ->
                "「$name」需要音频样本作为音色来源，当前配置没有样本。"

            // ── 参数被退回来，跟模型选择无关 ──
            has("400", "invalid", "bad request") ->
                "请求参数被拒绝（400）。文本长度或字符集可能超出限制。"

            // ── 回来了，但不是声音 ──
            has("回了一段话", "不是声音", "html") -> "响应不是音频。接口地址可能指向了网页或文档。"
            has("0 字节", "空声音", "empty") -> "响应音频为空。请更换音色后重试。"

            m.isNotEmpty() -> "服务端返回错误：${m.take(70)}"
            else -> "未收到任何响应内容。"
        }
    }
}
