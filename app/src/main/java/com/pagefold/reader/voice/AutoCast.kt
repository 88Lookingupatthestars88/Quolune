package com.pagefold.reader.voice

import com.pagefold.reader.field.EngineCfg
import com.pagefold.reader.field.VoiceSource

/**
 * 没人挑过嗓子的时候，替人挑一副。
 *
 * 规矩只有三条，按顺序：
 *   一、MiMo 开着 → 旁白「冰糖」、对白「苏打」；
 *   二、没 MiMo 但 Edge 开着 → 旁白「云健」、对白「晓晓」；
 *   三、别的嗓源 → 一副都不填，界面写「未设置」。
 *
 * 两个都在的时候按第一条 —— MiMo 那两副是照中文听书调过的，Edge 只在退而求其次时用。
 *
 * 为什么要有这一层：设置里存的那两个名字是固定的「白桦 / 苏打」，
 * 一旦 MiMo 没开，这两个名字在音色库里根本不存在。界面照抄它们，
 * 就成了「写着白桦，点开找不到白桦，念出来又是别人的声音」。
 */
object AutoCast {

    const val MIMO_NARRATOR = "冰糖"
    const val MIMO_DIALOG = "苏打"
    const val EDGE_NARRATOR = "云健"
    const val EDGE_DIALOG = "晓晓"

    /** 按开着的嗓源给一副默认。给不出就是空串。 */
    fun pick(engines: List<EngineCfg>, dialog: Boolean): String {
        val on = engines.filter { it.on }.map { it.src }.toSet()
        return when {
            VoiceSource.MIMO in on -> if (dialog) MIMO_DIALOG else MIMO_NARRATOR
            VoiceSource.EDGE in on -> if (dialog) EDGE_DIALOG else EDGE_NARRATOR
            else -> ""
        }
    }

    /**
     * 真正该用的那一副。
     *
     * @param pool 现在真能用的嗓子（[Catalog.ours] 那一份）
     * @param want 存下来的名字，可能是空的，也可能是早就不存在的
     * @return 空串 = 没有可用的，界面该写「未设置」
     */
    fun resolve(pool: List<Voice>, engines: List<EngineCfg>, want: String, dialog: Boolean): String {
        /*
         * 三个都比：名字、参数、id。旧存档里硅基那几副存的是 `alex`，
         * 现在显示的是「沉稳男声 alex」，只比名字会认不出它，界面就写「未设置」，
         * 而念出来其实还是它 —— 写着空，实际有声，最说不清的一种。
         */
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
