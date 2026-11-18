package com.pagefold.reader.play

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** 系统「文字转语音」设置页要问引擎两件事。 */

private const val OK_TTS = 1

/** 点「播放」时念的这一句。要短：设置页会整句念完才收工。 */
private const val SAMPLE = "Quolune 试声，一二三四。"

/** 系统扫「有没有中文语音数据」。 */
class CheckVoiceData : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val out = Intent()
        // 格式是 语言-国家，ISO 639-2 / ISO 3166-1 三字母。多报几个不吃亏。
        out.putStringArrayListExtra(
            "availableVoices",
            arrayListOf("zho-CHN", "zho-HKG", "zho-TWN")
        )
        out.putStringArrayListExtra("unavailableVoices", arrayListOf<String>())
        setResult(OK_TTS, out)
        finish()
    }
}

/** 点「播放」之前，设置页来要一句样本文本。 */
class GetSampleText : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val out = Intent()
        out.putExtra("sampleText", SAMPLE)
        setResult(OK_TTS, out)
        finish()
    }
}
