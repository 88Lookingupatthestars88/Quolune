package com.pagefold.reader.ui

import android.content.Context

/**
 * 三份条款。正文在 `assets/legal` 下，与仓库里的 markdown 是同一份，改一处要两处一起换。
 * 表格在窄屏上读不了，读入时转成「条目 + 缩进字段」。
 */
object Legal {

    private var at: Context? = null

    /** Application 起来时接上，读 assets 要用。 */
    fun attach(c: Context) {
        at = c.applicationContext
        ALL = listOf(load("terms.md"), load("privacy.md"), load("voice.md"))
    }

    /** 三份，按入口列出的顺序。 */
    lateinit var ALL: List<Doc>
        private set

    val TERMS: Doc get() = ALL[0]
    val PRIVACY: Doc get() = ALL[1]
    val VOICE: Doc get() = ALL[2]

    /** 一份条款。sections 是「标题 to 正文」，正文保留原样的换行。 */
    data class Doc(
        val name: String,
        val title: String,
        val version: String,
        val effective: String,
        val updated: String,
        val sections: List<Pair<String, String>>,
    )

    private fun load(file: String): Doc {
        val raw = runCatching {
            at?.assets?.open("legal/$file")?.bufferedReader()?.use { it.readText() }
        }.getOrNull().orEmpty()

        var title = file.substringAfterLast('/').substringBeforeLast('.')
        var version = ""
        var effective = ""
        var updated = ""
        val secs = ArrayList<Pair<String, String>>()
        /* 第一个 ## 之前那段是前言，head 记空串，界面上不画节标题。 */
        var head: String? = ""
        val body = StringBuilder()

        fun flush() {
            val h = head ?: return
            val b = tidy(body.toString())
            if (b.isNotEmpty()) secs += h to b
            body.setLength(0)
        }

        raw.lineSequence().forEach { line ->
            when {
                line.startsWith("# ") -> title = line.removePrefix("# ").trim()
                line.startsWith("> 版本") -> {
                    line.removePrefix(">").split('\u3000').forEach { seg ->
                        val s = seg.trim()
                        when {
                            s.startsWith("版本") -> version = s.removePrefix("版本").trim()
                            s.startsWith("生效日期") -> effective = s.substringAfter('：').trim()
                            s.startsWith("更新日期") -> updated = s.substringAfter('：').trim()
                        }
                    }
                }
                line.startsWith("> ") -> Unit          // 抬头里的说明句，不上屏
                line.startsWith("## ") -> {
                    flush()
                    head = line.removePrefix("## ").trim()
                }
                else -> body.append(line).append('\n')
            }
        }
        flush()

        return Doc(
            name = title.removePrefix("Quolune "),
            title = title,
            version = version,
            effective = effective,
            updated = updated,
            sections = secs,
        )
    }

    /** 去掉行内代码的反引号；把 markdown 表格换成逐条列出的样子。 */
    private fun tidy(text: String): String {
        val out = StringBuilder()
        val lines = text.replace("`", "").lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val isHead = line.trimStart().startsWith("|")
            val isRule = i + 1 < lines.size &&
                lines[i + 1].trim().replace("|", "").replace("-", "").replace(":", "").isBlank() &&
                lines[i + 1].contains('|')
            if (isHead && isRule) {
                val cols = split(line)
                i += 2
                while (i < lines.size && lines[i].trimStart().startsWith("|")) {
                    val cells = split(lines[i])
                    out.append(cells.firstOrNull().orEmpty()).append('\n')
                    cols.drop(1).forEachIndexed { k, c ->
                        out.append('\u3000').append(c).append('：')
                            .append(cells.getOrNull(k + 1).orEmpty()).append('\n')
                    }
                    out.append('\n')
                    i++
                }
            } else {
                out.append(line).append('\n')
                i++
            }
        }
        return out.toString().trim()
    }

    private fun split(row: String): List<String> =
        row.trim().trim('|').split('|').map { it.trim() }
}
