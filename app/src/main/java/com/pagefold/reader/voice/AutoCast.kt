package com.pagefold.reader.voice

import com.pagefold.reader.field.EngineCfg
import com.pagefold.reader.field.VoiceSource

/** 没人挑过嗓子的时候，替人挑一副。 */
object AutoCast {

    const val MIMO_NARRATOR = "冰糖"
    const val MIMO_DIALOG = "苏打"

    /** 按开着的嗓源给一副默认。给不出就是空串。 */
    fun pick(engines: List<EngineCfg>, dialog: Boolean): String {
        val on = engines.filter { it.on }.map { it.src }.toSet()
        return when {
            VoiceSource.MIMO in on -> if (dialog) MIMO_DIALOG else MIMO_NARRATOR
            else -> ""
        }
    }

    /**
     * 真正该用的那一副。
     * @param pool 现在真能用的嗓子（[Catalog.ours] 那一份）
     * @param want 存下来的名字，可能是空的，也可能是早就不存在的
     * @return 空串 = 没有可用的，界面该写「未设置」
     */
    fun resolve(pool: List<Voice>, engines: List<EngineCfg>, want: String, dialog: Boolean): String {
        // 名字、参数、id 三个都比：旧存档里存的是 `alex`，现在显示「沉稳男声 alex」。
        if (want.isNotBlank()) {
            pool.firstOrNull { it.name == want || it.param == want || it.id == want }
                ?.let { return it.name }
        }
        val auto = pick(engines, dialog)
        if (auto.isNotBlank()) {
            pool.firstOrNull { it.name == auto || it.param == auto }?.let { return it.name }
        }
        return ""
    }
}
