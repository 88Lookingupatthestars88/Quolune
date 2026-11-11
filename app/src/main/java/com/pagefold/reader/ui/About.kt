package com.pagefold.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pagefold.reader.App
import com.pagefold.reader.field.Settings
import com.pagefold.reader.kit.LocalSkin
import com.pagefold.reader.kit.Masthead
import com.pagefold.reader.kit.Ornament
import com.pagefold.reader.kit.Rule
import com.pagefold.reader.kit.Sz

/**
 * 关于。
 *
 * 一屏三件事：谁做的、它能干什么、想说话往哪儿说。
 * 不列致谢、不说接了几家服务 —— 用户不必知道这些。版次只在末尾留一行，不占版面。
 *
 * 「创作人」单独成一块：这是书的末尾会有的那半页（版权页），
 * 不是一张功能卡。下面的字从它底下接着排，首行缩进两格，跟正文一个排法。
 *
 * 也不写某一家引擎的名字：Quolune 是几张嗓子并用，谁的都能接。
 * 把其中一家写进「关于」，等于把入口收窄成一家。
 */
@Composable
fun AboutScreen(
    s: Settings,
    app: App,
    list: LazyListState,
) {
    val skin = LocalSkin.current

    /*
     * 版次从安装包自己身上读，不写死。
     *
     * 写死的那一行每改一次版本就得记着同步一次 —— 忘一次，「关于」里那一行就是错的。
     */
    val verName = remember(app) {
        runCatching {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName
        }.getOrNull().orEmpty().ifBlank { "—" }
    }

    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 40.dp),
    ) {
        item {
            Column(Modifier.fillMaxWidth().statusBarsPadding()) {
                Masthead(title = "关于 Quolune", word = "让文字拥有温度，让声音抵达内心。")
                Ornament(Modifier.padding(horizontal = Sz.gutter).padding(top = 2.dp))
            }
        }

        // 落款：名 + 出处。不放印 —— 两字朱印容易被认成别家的标。
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = Sz.gutter).padding(top = 24.dp)) {
                Text(
                    "创作人",
                    fontSize = 10.sp,
                    color = skin.inkFaint,
                    letterSpacing = 3.sp,
                )
                Spacer(Modifier.height(7.dp))
                Text(
                    "仰望星空才能无所畏惧",
                    fontSize = 19.sp,
                    lineHeight = 27.sp,
                    color = skin.ink,
                    fontWeight = FontWeight.Medium,
                    fontFamily = skin.titleFont,
                    letterSpacing = 0.4.sp,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    "B 站 UP 主",
                    fontSize = 11.sp,
                    color = skin.inkFaint,
                    letterSpacing = 0.8.sp,
                )
            }
        }

        item { Rule(Modifier.padding(horizontal = Sz.gutter).padding(top = 24.dp)) }

        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = Sz.gutter).padding(top = 22.dp)) {
                Body(
                    "这个应用可以让你把各家的朗读引擎接入进来，当作你手机的系统朗读引擎，" +
                        "去帮你朗读你想要朗读的东西。"
                )
                Spacer(Modifier.height(14.dp))
                Body(
                    "也可以在本应用的书房里导入你喜欢看的书，进行阅读、聆听。" +
                        "在书里点击弹出控制栏后，可以看到旁白、对白等等。" +
                        "还可以选择多角色朗读，让整本书彻底活过来。"
                )
                Spacer(Modifier.height(14.dp))
                Body("用得别扭，或者想添什么，到 B 站私信我。")
            }
        }

        // 版次。版权页末尾那一行，压在右下，字比正文小两级，不抢正文。
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Sz.gutter).padding(top = 26.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    "版本 " + verName,
                    fontSize = 11.sp,
                    color = skin.inkFaint,
                    letterSpacing = 1.2.sp,
                    fontFamily = skin.titleFont,
                )
            }
        }

        /*
         * 项目页。**摆在版次下面** —— 版权页的顺序就是「正文、版次、出处」，
         * 仓库放在正文中间等于把落款插进了话里。
         *
         * 一行一张卡，跟「声源」「接口」那些卡片是同一套画法：左边一枚印、中间名字 + 地址、
         * 整行可点。点一下交给浏览器，不在应用里套一个 WebView —— 那种「半截浏览器」
         * 没有地址栏、没有书签，用户想存下来或换个浏览器都得先退出去。
         */
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = Sz.gutter).padding(top = 16.dp)) {
                LinkCard(
                    mark = "仓库",
                    title = "GitHub 项目页",
                    url = REPO_URL,
                )
            }
        }
        item { Spacer(Modifier.height(10.dp)) }
    }
}

/** 项目页。仓库地址放在这里，别处要用就引用这一个。 */
private const val REPO_URL = "https://github.com/88Lookingupatthestars88/Quolune"

/**
 * 一张「点一下去外面」的卡。
 *
 * 交给系统浏览器打开，不在应用里套 WebView：那种「半截浏览器」没有地址栏、
 * 没有书签，想存下来或换个浏览器都得先退出去。系统里没装能开 http 的应用时，
 * 直接说一句，不留一个点了没反应的行。
 */
@Composable
private fun LinkCard(mark: String, title: String, url: String) {
    val s = LocalSkin.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var said by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(said) {
        if (said != null) {
            kotlinx.coroutines.delay(2600)
            said = null
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(s.rCard))
                .background(s.surface)
                .border(1.dp, s.hairSoft, RoundedCornerShape(s.rCard))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {
                    val ok = runCatching {
                        ctx.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(url)
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        true
                    }.getOrDefault(false)
                    if (!ok) said = "这台机器上没有能打开网页的应用"
                }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            /*
             * 左边那块印里写的是「仓库」两个字。
             *
             * 上一版只放一个「仓」，读起来像一枚闲章，认不出是干什么的 ——
             * 那儿本来就该写清楚这是哪个东西。
             */
            Box(
                Modifier.width(44.dp).height(34.dp).clip(RoundedCornerShape(9.dp))
                    .background(s.flameGhost),
                contentAlignment = Alignment.Center,
            ) {
                Text(mark, fontSize = 12.sp, color = s.flame, fontFamily = s.titleFont)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, color = s.ink, fontFamily = s.titleFont)
                Spacer(Modifier.height(3.dp))
                Text(
                    url, fontSize = 10.sp, color = s.inkFaint,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text("›", fontSize = 15.sp, color = s.inkFaint)
        }
        said?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, fontSize = 10.sp, color = s.bad)
        }
    }
}

/** 正文一段：首行缩两格，跟书里的排法一致。 */@Composable
private fun Body(text: String) {
    val s = LocalSkin.current
    Text(
        "　　" + text,
        fontSize = 15.sp,
        lineHeight = 28.sp,
        color = s.inkDim,
        fontFamily = s.titleFont,
    )
}
