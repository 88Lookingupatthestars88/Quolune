package com.pagefold.reader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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
                    "版本 2.3.1",
                    fontSize = 11.sp,
                    color = skin.inkFaint,
                    letterSpacing = 1.2.sp,
                    fontFamily = skin.titleFont,
                )
            }
        }
    }
}

/** 正文一段：首行缩两格，跟书里的排法一致。 */
@Composable
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
