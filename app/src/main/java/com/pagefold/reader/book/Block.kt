package com.pagefold.reader.book

/**
 * 一口气：一次请求合成、一次播放的一段。
 *
 * 一句一个请求时，五六个字也要单独起调和收音，句与句之间平均五秒断一次。
 * 这里把同一副嗓子的连续几句粘起来，一次发出去。
 *
 * @param from   首行在行表里的下标
 * @param to     末行的下标加一
 * @param voice  整块共用一副嗓子
 * @param dialog 块内对白的字多于旁白
 */
data class Block(
    val from: Int,
    val to: Int,
    val text: String,
    val voice: String,
    val dialog: Boolean,
)

/** 首块上限。点播放到出声这一段只等得了这么久。 */
const val LEAD_CHARS = 160

/** 之后每块的上限。六百字约念两分钟，合成二十秒上下。 */
const val BLOCK_CHARS = 600

/** 句末标点。粘块时前一行已经收尾就不再补逗号。 */
private const val STOPS = "。！？…；：，、,.;:!?—"

/** 「说道：」「笑道，」这类引子。 */
private val LEAD_IN = Regex("(道|说|问|答|喊|嚷|笑|骂|喝|应|念|讲|嘀咕)(着)?[：:，,]?$")

/**
 * 把行序列粘成块。调用方 [com.pagefold.reader.play.BookPlayer]。
 *
 * 引号前的短引导语并进后面那一块，用后面那句话的嗓子：
 * 先用旁白腔说「他冷笑道」、再换一副嗓子接话，听着像两个人在念。
 */
fun blocksOf(lines: List<Line>, startAt: Int = 0): List<Block> {
    if (lines.isEmpty()) return emptyList()
    /* 从中间一句起念时，第一块就从那一句开始。否则会退到这一块的头一句，
     * 也就是上一行。 */
    val head = startAt.coerceIn(0, lines.size - 1)
    if (head > 0) return blocksFrom(lines, head)

    val voice = Array(lines.size) { lines[it].voice }
    for (i in 0 until lines.size - 1) {
        val l = lines[i]
        if (l.isDialog || l.text.length > 12) continue
        if (!LEAD_IN.containsMatchIn(l.text.trim())) continue
        voice[i] = lines[i + 1].voice
    }

    val out = ArrayList<Block>(lines.size / 6 + 4)
    var from = 0
    var chars = 0
    val sb = StringBuilder(BLOCK_CHARS + LEAD_CHARS)

    fun flush(to: Int) {
        if (to <= from) return
        var d = 0
        var n = 0
        for (k in from until to) {
            if (lines[k].isDialog) d += lines[k].text.length else n += lines[k].text.length
        }
        out.add(Block(from, to, sb.toString(), voice[from], d > n))
        sb.setLength(0)
        chars = 0
        from = to
    }

    for (i in lines.indices) {
        val l = lines[i]
        val cap = if (out.isEmpty() && from == 0) LEAD_CHARS else BLOCK_CHARS
        if (chars > 0 && (voice[i] != voice[from] || chars + l.text.length > cap)) flush(i)
        if (chars > 0 && sb.isNotEmpty() && sb[sb.length - 1] !in STOPS) sb.append('，')
        sb.append(l.text)
        chars += l.text.length
        if (chars >= cap) flush(i + 1)
    }
    flush(lines.size)
    return out
}

/**
 * 只从 [head] 那一行往后粘块。第一块按首块的上限算，与整章从头粘时一样。
 *
 * 与「先粘整章再切掉前面」不同：块边界会跟着变，但念到的字一个不少、也不多。
 */
private fun blocksFrom(lines: List<Line>, head: Int): List<Block> {
    val voice = Array(lines.size) { lines[it].voice }
    for (i in head until lines.size - 1) {
        val l = lines[i]
        if (l.isDialog || l.text.length > 12) continue
        if (!LEAD_IN.containsMatchIn(l.text.trim())) continue
        voice[i] = lines[i + 1].voice
    }

    val out = ArrayList<Block>((lines.size - head) / 6 + 4)
    var from = head
    var chars = 0
    val sb = StringBuilder(BLOCK_CHARS + LEAD_CHARS)

    fun flush(to: Int) {
        if (to <= from) return
        var d = 0
        var n = 0
        for (k in from until to) {
            if (lines[k].isDialog) d += lines[k].text.length else n += lines[k].text.length
        }
        out.add(Block(from, to, sb.toString(), voice[from], d > n))
        sb.setLength(0)
        chars = 0
        from = to
    }

    for (i in head until lines.size) {
        val l = lines[i]
        val cap = if (from == head) LEAD_CHARS else BLOCK_CHARS
        if (chars > 0 && (voice[i] != voice[from] || chars + l.text.length > cap)) flush(i)
        if (chars > 0 && sb.isNotEmpty() && sb[sb.length - 1] !in STOPS) sb.append('，')
        sb.append(l.text)
        chars += l.text.length
        if (chars >= cap) flush(i + 1)
    }
    flush(lines.size)
    return out
}

/** 这一行落在第几块。 */
fun blockAt(blocks: List<Block>, line: Int): Int {
    if (blocks.isEmpty()) return 0
    var lo = 0
    var hi = blocks.size - 1
    while (lo < hi) {
        val m = (lo + hi + 1) / 2
        if (blocks[m].from <= line) lo = m else hi = m - 1
    }
    return lo
}
