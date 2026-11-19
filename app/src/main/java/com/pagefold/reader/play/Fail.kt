package com.pagefold.reader.play

/** 把底层抛出来的东西翻成一句人话。 */
object Fail {

    fun read(t: Throwable?): String {
        if (t == null) return "这一段没有取到音频。翻到下一页再返回可重试。"

        val m = (t.message ?: "").trim()
        val all = buildString {
            if (m.isNotEmpty()) append(m)
            var c: Throwable? = t.cause
            var n = 0
            while (c != null && n < 3) {
                val cm = c.message
                if (!cm.isNullOrBlank() && !m.contains(cm)) append(" | ").append(cm)
                c = c.cause
                n++
            }
        }
        val low = all.lowercase()

        fun has(vararg k: String) = k.any { low.contains(it) }

        return when {
            /* 选错了模型。放在最前面 —— 它最容易被下面的 400 抢走，而下面的 400 说的是「太长、有怪符号」，方向完全错。 */
            has("不是语音模型") ->
                "当前模型不支持语音合成。请在定音中更换为语音模型。"

            // 原话已经点明第几位，直接透传。
            has("钥匙第", "钥匙太长") -> m.take(80)

            // 先把「连不上」这一类挑出来，不然会被下面的 401 之类抢走
            has("unknownhost", "unable to resolve host", "no address associated") ->
                "无法解析服务地址。请检查网络连接。"

            has("timeout", "timed out", "超时", "sockettimeout") ->
                "请求超时，服务商未在限定时间内返回。可稍后重试或更换音色。"

            has("connect", "网络不可达", "connection refused", "failed to connect") ->
                "无法连接到服务商。请更换网络，或播放已缓存的段落。"

            has("401", "unauthorized", "invalid api key", "api key", "没填") ->
                "未填写密钥或密钥无效。请在定音中补充。"

            has("403", "forbidden", "permission") ->
                "当前音色无权限。请更换音色或开通权限。"

            has("429", "rate limit", "too many requests", "限流") ->
                "服务商已限流（429）。请稍后重试，或调大「传输间隔」。"

            has("400", "invalid", "bad request", "too long", "长度", "超过") ->
                "服务商拒绝该请求：文本过长或含不支持的字符。请调小「一气念多少字」后重试。"

            has("5xx", "500", "502", "503", "504", "server error") ->
                "服务商内部错误。请稍后重试。"

            has("没有音频", "空音频", "empty", "0 字节", "no audio") ->
                "服务商返回空音频。请更换音色，或缩短该段文本。"

            has("还没配", "未配置", "not configured", "引擎") && has("没", "空") ->
                "尚未选择音色。请在定音中选择音色后再播放。"

            has("拒绝合成", "没起来", "tts", "语音包") ->
                "系统语音引擎未启动。请检查系统设置，或更换音色。"

            m.isNotEmpty() -> "这一段合成失败：${m.take(60)}。翻到下一页再返回可重试。"

            else -> "这一段没有取到音频。翻到下一页再返回可重试。"
        }
    }
}
