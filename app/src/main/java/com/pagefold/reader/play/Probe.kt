package com.pagefold.reader.play

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * 系统「文字转语音」设置页要问引擎两件事。少一件，那一页就残一半。
 *
 * 1. CHECK_TTS_DATA —— 设置页启动时扫一遍「这台引擎有没有中文语音数据」。
 *    没回应，语言那一栏就选不动。
 *
 * 2. GET_SAMPLE_TEXT —— 点「播放」之前，设置页先来要一句样本文本。
 *    拿不到就是 ActivityNotFoundException，mSampleText 留空，
 *    于是「播放」键被 setEnabled(false) —— 正是那个灰键。
 *
 * 系统里装着的 TTS 引擎都要声明这两个，少一个，那一页就残一半。
 * 返回值是 framework 的老常量，都是 1，这里按名字写清楚。
 */

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
