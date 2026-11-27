package com.pagefold.reader.play

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import com.pagefold.reader.App
import com.pagefold.reader.Main
import com.pagefold.reader.R

/**
 * 长按图标的第一条：接着上次听的那本念。另外三条写在 res/xml/shortcuts.xml。
 *
 * 位置每四秒落一次盘，这边跟着刷会一直重建快捷方式，所以按「书 + 章」和六十秒两重节流。
 * 图标是 `sc_resume`：一副头戴，怀里一枚播放三角，跟应用图标是同一件东西。
 */
object Shortcuts {

    private const val ID = "resume"

    private var lastKey = ""
    private var lastAt = 0L

    fun refresh(ctx: Context, force: Boolean = false) {
        val app = ctx.applicationContext as? App ?: return
        val b = app.shelf.all().maxByOrNull { it.openedAt } ?: return

        val at = if (b.chapterNo > 0) "第 ${b.chapterNo} 章" else "第 ${b.chapterIndex + 1} 节"
        val key = b.id + "#" + b.chapterIndex
        val now = System.currentTimeMillis()
        if (!force && key == lastKey && now - lastAt < 60_000L) return

        val sm = ctx.getSystemService(ShortcutManager::class.java) ?: return
        if (sm.maxShortcutCountPerActivity <= 0) return

        val info = ShortcutInfo.Builder(ctx, ID)
            .setShortLabel("继续听")
            .setLongLabel("继续听 · ${b.title} · $at")
            .setIcon(Icon.createWithResource(ctx, R.drawable.sc_resume))
            .setIntent(
                Intent(ctx, Main::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .putExtra(Main.EXTRA_BOOK, b.id)
            )
            .build()

        if (runCatching { sm.setDynamicShortcuts(listOf(info)) }.isSuccess) {
            lastKey = key
            lastAt = now
        }
    }
}
