package com.pagefold.reader.play

/**
 * 把底层抛出来的东西翻成一句人话。
 *
 * 每条说清两件事：出了什么、现在能做什么。
 * 笼统一句「检查引擎与网络」等于没说：可能只是没填钥匙，也可能这一段太长被拒了。
 */
object Fail {

    fun read(t: Throwable?): String {
        if (t == null) return "这一段没能取出声来。往后翻一页再回来试试。"

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
            /*
             * 选错了模型。放在最前面 —— 它最容易被下面的 400 抢走，
             * 而下面的 400 说的是「太长、有怪符号」，方向完全错。
             */
            has("不是语音模型") ->
                "当前选中的模型不是语音模型，无法合成音频。请在定音中换成语音模型。"

            // 原话已说清第几位是什么，别再套成「没填钥匙」。
            has("钥匙第", "钥匙太长") -> m.take(80)

            // 先把「连不上」这一类挑出来，不然会被下面的 401 之类抢走
            has("unknownhost", "unable to resolve host", "no address associated") ->
                "网没接上：查不出这个地址。看看手机是不是断网了。"

            has("timeout", "timed out", "超时", "sockettimeout") ->
                "等太久了，对面没回。过一会儿再来，或者换一副嗓音。"

            has("connect", "网络不可达", "connection refused", "failed to connect") ->
                "连不上对面。换个网再试，或者先听已经存稿的那几段。"

            has("401", "unauthorized", "invalid api key", "api key", "没填") ->
                "这一副还没填钥匙，或者钥匙不对。去定音里补一下。"

            has("403", "forbidden", "permission") ->
                "这个音色你没开权限。换一副，或者去开一下。"

            has("429", "rate limit", "too many requests", "限流") ->
                "取得太急了，对面在限流。歇一会儿，或者把「隔多久再开口」调大。"

            has("400", "invalid", "bad request", "too long", "长度", "超过") ->
                "这一段它不收：可能太长，或者有它不认的符号。把「一气念多少字」调小再试。"

            has("5xx", "500", "502", "503", "504", "server error") ->
                "对面自己出错了。等一会儿再来。"

            has("没有音频", "空音频", "empty", "0 字节", "no audio") ->
                "它回了空声音。换一副嗓音，或者把这一段切短。"

            has("还没配", "未配置", "not configured", "引擎") && has("没", "空") ->
                "还没挑嗓音。去定音里挑一副再开讲。"

            has("拒绝合成", "没起来", "tts", "语音包") ->
                "手机上的语音包没起来。去系统设置里看一下，或者换一副嗓音。"

            m.isNotEmpty() -> "这一段卡住了：${m.take(60)}。往后翻一页再回来试试。"

            else -> "这一段没能取出声来。往后翻一页再回来试试。"
        }
    }
}
