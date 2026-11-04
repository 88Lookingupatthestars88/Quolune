package com.pagefold.reader.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pagefold.reader.App
import com.pagefold.reader.book.BookMeta
import com.pagefold.reader.book.CastBook
import com.pagefold.reader.book.CastRole
import com.pagefold.reader.book.CastRule
import com.pagefold.reader.book.Rule
import com.pagefold.reader.book.RuleCodec
import com.pagefold.reader.kit.Btn
import com.pagefold.reader.kit.BtnKind
import com.pagefold.reader.kit.IconBtn
import com.pagefold.reader.kit.LocalSkin
import com.pagefold.reader.kit.PickChip
import com.pagefold.reader.kit.T
import com.pagefold.reader.voice.Catalog
import com.pagefold.reader.voice.Voice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 派角。一本书一张表。
 *
 * 一张派角表：旁白一副、对白一副、若干角色，外加一张匹配规则表。
 * 名字和嗓子分开存 —— 一个人可以有好几种叫法，几条规则指向同一个人。
 */
@Composable
fun CastScreen(
    book: BookMeta?,
    app: App,
    onChanged: () -> Unit = {},
    onBack: () -> Unit,
) {
    val s = LocalSkin.current
    val scope = rememberCoroutineScope()
    val groups = remember(book?.id, app.box.custom().size) {
        Catalog.groups(app.field.now.engines, app.box.custom())
    }

    var cast by remember(book?.id) { mutableStateOf(loadCast(app, book)) }
    var msg by remember { mutableStateOf<String?>(null) }
    var personEdit by remember { mutableStateOf<Int?>(null) }   // 0 旁白, 1 对白
    var roleEdit by remember { mutableStateOf<Int?>(null) }
    var ruleEdit by remember { mutableStateOf<Int?>(null) }
    var rawOpen by remember { mutableStateOf(false) }
    var raw by remember { mutableStateOf("") }

    fun save(next: CastBook, note: String? = null) {
        cast = next
        val json = RuleCodec.toJson(next)
        if (book == null) app.field.edit { it.copy(castJson = json) }
        else app.shelf.setCast(book.id, json)
        onChanged()
        if (note != null) msg = note
    }

    fun say(t: String) { msg = t }

    val importPick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { app.contentResolver.openInputStream(uri)?.use { it.reader().readText() } }.getOrNull()
            }
            if (text.isNullOrBlank()) { say("这个文件读不出来"); return@launch }
            val head0 = text.trimStart()
            val loaded = runCatching {
                if (head0.startsWith("{")) RuleCodec.fromJson(text)
                else {
                    // 一行一条的文本：说白/白口那两行改的是旁白和对白，其余各行是角色 + 正则
                    RuleCodec.toBook(RuleCodec.fromText(text), cast.narrator, cast.dialog)
                }
            }.getOrNull()
            if (loaded == null || (loaded.rules.isEmpty() && loaded.roles.isEmpty())) {
                say("里头没读出东西")
                return@launch
            }
            // 嗓子是本机攒的：表里没写的，留旧的。
            val mine = cast.roles.filter { it.voice.isNotBlank() }.associate { it.name to it.voice }
            save(
                loaded.copy(
                    roles = loaded.roles.map { it.copy(voice = it.voice.ifBlank { mine[it.name].orEmpty() }) },
                    narrator = loaded.narrator.ifBlank { cast.narrator },
                    dialog = loaded.dialog.ifBlank { cast.dialog },
                ),
                "进来了 ${loaded.roles.size} 个角色 · ${loaded.rules.size} 条规则",
            )
        }
    }

    fun handOver(body: String, name: String) {
        scope.launch {
            val f = withContext(Dispatchers.IO) {
                val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "cast")
                if (!dir.exists()) dir.mkdirs()
                runCatching { File(dir, name).also { it.writeText(body) } }.getOrNull()
            }
            if (f == null) {
                app.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("派角", body))
                say("没处写，已经复制到剪贴板")
            } else {
                say("存到 ${f.name}")
                runCatching {
                    app.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "application/octet-stream"
                                putExtra(Intent.EXTRA_STREAM, Uri.fromFile(f))
                            }, "发到"
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(s.ground)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconBtn(Icons.AutoMirrored.Filled.ArrowBack, "返回", onBack, size = 34.dp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    T("派角", size = 17, weight = FontWeight.Medium)
                    T(book?.title ?: "所有书共用这一张", size = 10, color = s.inkFaint, maxLines = 1)
                }
            }

            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 44.dp)
            ) {
                // ── 一、谁在念 ──────────────────────────
                item { Head("旁白和对白", "旁白念正文，对白念引号里的话。") }
                item {
                    PersonRow("旁白", cast.narrator, cast.narratorStyle) { personEdit = 0 }
                }
                item {
                    PersonRow("对白", cast.dialog, cast.dialogStyle) { personEdit = 1 }
                }

                // ── 二、角色 ────────────────────────────
                item { Head("角色", if (cast.roles.isEmpty()) "还没有。加一个名字，比如书里的主角。" else "") }
                itemsIndexed(cast.roles, key = { _, r -> "r_" + r.name }) { i, r ->
                    Line2(r.name, r.voice.ifBlank { "跟对白同一副" }, dim = r.voice.isBlank()) {
                        roleEdit = i
                    }
                }
                item {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Btn("加角色", { roleEdit = -1 }, BtnKind.GHOST, Modifier.weight(1f))
                        Btn("加规则", { ruleEdit = -1 }, BtnKind.GHOST, Modifier.weight(1f))
                    }
                }

                // ── 三、正则规则 ─────────────────────────
                item {
                    Head("正则规则", "从上往下试，第一条对上的算。只看引号外面那半句。")
                }
                if (cast.rules.isEmpty()) {
                    item {
                        Text(
                            "还没有规则。点「导入」把表拿进来，或者点「加规则」。",
                            fontSize = 11.sp, color = s.inkFaint,
                            modifier = Modifier.padding(vertical = 10.dp)
                        )
                    }
                }
                itemsIndexed(cast.rules) { i, r ->
                    val bad = r.bad()
                    // 上一条跟这条一模一样 —— 多半是导进来的表本身就重复。
                    // 规则是从上往下第一条算，重复的那些永远轮不到，用户不知道。
                    val same = i > 0 && cast.rules[i - 1].pattern == r.pattern
                    val who = cast.roles.firstOrNull { it.name == r.character }
                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() }
                                ) { ruleEdit = i }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 左边一道细线：这一格是「一条」，不是「一段字」。
                            Box(
                                Modifier.width(2.dp).height(30.dp)
                                    .background(if (bad) s.bad else s.flame.copy(alpha = 0.55f))
                            )
                            Spacer(Modifier.width(11.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    T(r.character.ifBlank { "没派给谁" }, size = 14, color = if (bad) s.bad else s.ink)
                                    Spacer(Modifier.width(6.dp))
                                    T(
                                        if (r.afterDialog) "只看引号后" else "前后都看",
                                        size = 9, color = s.inkFaint,
                                    )
                                    if (bad) {
                                        Spacer(Modifier.width(6.dp))
                                        T("写坏了", size = 9, color = s.bad)
                                    }
                                }
                                Spacer(Modifier.height(3.dp))
                                T(
                                    if (who == null) "这个名字还没有嗓音，先去上面加角色"
                                    else "对上就用「" + (who.voice.ifBlank { "对白那副" }) + "」",
                                    size = 10,
                                    color = if (who == null) s.warn else s.inkFaint,
                                    maxLines = 1,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    if (bad) r.pattern.ifBlank { "空" }
                                    else summarize(r.pattern),
                                    fontSize = 9.sp, lineHeight = 14.sp,
                                    color = if (bad) s.bad else s.inkFaint.copy(alpha = 0.75f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (same) {
                                    Spacer(Modifier.height(2.dp))
                                    T("跟上面那条一样，永远轮不到它", size = 9, color = s.warn)
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            T("改", size = 11, color = s.inkFaint)
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(s.hairSoft))
                    }
                }

                // ── 四、导入和导出 ──────────────────────
                item {
                    Spacer(Modifier.height(26.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Btn("导入", { importPick.launch(arrayOf("*/*")) }, BtnKind.PRIMARY, Modifier.weight(1f))
                        Btn("导出", {
                            handOver(
                                RuleCodec.toJson(cast),
                                ((book?.title ?: "派角").take(20).ifBlank { "cast" }) + ".cast"
                            )
                        }, BtnKind.GHOST, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "能读两种：这里导出的角色表，和一行一条的文本。\n" +
                            "文本这么写：\n" +
                            "　　林瑾｜云健｜(林瑾.{0,8})(说道|沉声道)\n" +
                            "　　旁白｜白桦｜\n" +
                            "竖线只切前两刀，正则里的竖线不会被切坏。",
                        fontSize = 10.sp, color = s.inkFaint, lineHeight = 17.sp,
                    )
                }
            }
        }

        // 挑嗓子
        // 旁白 / 对白：嗓子和调门在一张纸上，不再分两层
        personEdit?.let { i ->
            PersonSheet(
                what = if (i == 0) "旁白" else "对白",
                voice = if (i == 0) cast.narrator else cast.dialog,
                style = if (i == 0) cast.narratorStyle else cast.dialogStyle,
                groups = groups,
                onClose = { personEdit = null },
                onOk = { v, st ->
                    val nxt = if (i == 0) cast.copy(narrator = v, narratorStyle = st)
                    else cast.copy(dialog = v, dialogStyle = st)
                    save(nxt, null)
                    personEdit = null
                },
            )
        }

        // 加 / 改角色
        roleEdit?.let { i ->
            val isNew = i < 0
            val r = if (isNew) null else cast.roles.getOrNull(i)
            RoleSheet(
                role = r,
                groups = groups,
                onClose = { roleEdit = null },
                onOk = { name0, voice ->
                    val name = name0.trim()
                    if (name.isEmpty()) say("总得有个名字")
                    else {
                        val fresh = CastRole(name, voice.trim())
                        val next = if (isNew || r == null) cast.with(fresh)
                        else cast.without(r.name).with(fresh)
                        save(next, if (isNew) "加了「$name」" else "改好了")
                        roleEdit = null
                    }
                },
            )
        }

        // 加 / 改一条规则
        ruleEdit?.let { i ->
            val isNew = i < 0
            val r = if (isNew) null else cast.rules.getOrNull(i)
            RuleSheet(
                rule = r,
                names = cast.roles.map { it.name },
                onClose = { ruleEdit = null },
                onDelete = if (isNew) null else ({
                    save(cast.copy(rules = cast.rules.filterIndexed { j, _ -> j != i }), "删掉一条")
                    ruleEdit = null
                }),
                onOk = { ch0, pat, after ->
                    val who2 = ch0.trim()
                    if (who2.isEmpty() || pat.isBlank()) say("名字和正则都得有")
                    else {
                        val list = cast.rules.toMutableList()
                        val fresh = CastRule(who2, pat.trim(), after, true)
                        if (isNew) list.add(fresh) else list[i] = fresh
                        var roles = cast.roles
                        if (roles.none { it.name == who2 }) roles = roles + CastRole(who2)
                        save(cast.copy(rules = list, roles = roles), if (isNew) "加了一条" else "改好了")
                        ruleEdit = null
                    }
                },
            )
        }

        if (rawOpen) {
            RawSheet(
                text = raw,
                onClose = { rawOpen = false },
                onOk = {
                    val rules = RuleCodec.fromText(raw)
                    if (rules.isEmpty()) say("一条都没读出来")
                    else {
                        save(RuleCodec.toBook(rules, cast.narrator, cast.dialog))
                        rawOpen = false
                        say("换成 ${rules.size} 条")
                    }
                },
            )
        }

        msg?.let {
            LaunchedEffect(it) { kotlinx.coroutines.delay(2600); msg = null }
            Box(Modifier.padding(bottom = 34.dp).align(Alignment.BottomCenter)) {
                Box(
                    Modifier.background(s.ink.copy(alpha = 0.92f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 9.dp)
                ) { T(it, size = 11, color = s.ground) }
            }
        }
    }
}

/**
 * 把一条正则翻成人话。
 *
 * 一行里塞四五十个名字的正则，铺在列表上谁也看不出它在干什么 ——
 * 只能看出「很长」。给个长度和关键词，比原样摊开有用。
 */
private fun summarize(pat: String): String {
    val p = pat.trim()
    if (p.isEmpty()) return "空"
    val names = Regex("[\u4e00-\u9fa5]{2,3}").findAll(p).map { it.value }.distinct().count()
    return when {
        names >= 6 -> "按名字表认人，表里 $names 个名字"
        p.length <= 26 -> p
        else -> p.take(24) + "…"
    }
}

// ── 小件 ──────────────────────────────────────────

@Composable
private fun Head(title: String, note: String) {
    val s = LocalSkin.current
    Spacer(Modifier.height(22.dp))
    T(title, size = 12, color = s.inkDim)
    if (note.isNotBlank()) {
        Spacer(Modifier.height(3.dp))
        T(note, size = 10, color = s.inkFaint)
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun Line2(label: String, value: String, dim: Boolean = false, onClick: () -> Unit) {
    val s = LocalSkin.current
    Row(
        Modifier.fillMaxWidth()
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClick() }
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        T(label, size = 14, modifier = Modifier.weight(1f))
        T(value, size = 12, color = if (dim) s.inkFaint else s.flame)
        Spacer(Modifier.width(6.dp))
        T("›", size = 14, color = s.inkFaint)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(s.ink.copy(alpha = 0.06f)))
}

@Composable
private fun VoicePick(
    title: String,
    groups: List<Pair<String, List<Voice>>>,
    current: String,
    onPick: (Voice) -> Unit,
    onClose: () -> Unit,
) {
    val s = LocalSkin.current
    Sheet(title, onClose) {
        if (groups.isEmpty()) {
            T("一副都没开。去定音里开一路声源，这里才有得挑。", size = 12, color = s.inkFaint)
            Spacer(Modifier.height(10.dp))
            return@Sheet
        }
        groups.forEach { (g, list) ->
            T(g, size = 10, color = s.inkFaint)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                list.forEach { v ->
                    PickChip(v.name, v.name == current, { onPick(v) }, Modifier.padding(end = 6.dp))
                }
            }
            Spacer(Modifier.height(14.dp))
        }
    }
}

/**
 * 一个角色：名字 + 嗓子。
 *
 * 嗓子不是再弹一层窗，而是把音色库里**所有**嗓子铺在下面，点哪个是哪个。
 * 名字只跟这本书有关，所以这里不出现任何别人书里的名字。
 */
@Composable
private fun RoleSheet(
    role: CastRole?,
    groups: List<Pair<String, List<Voice>>>,
    onClose: () -> Unit,
    onOk: (String, String) -> Unit,
) {
    val s = LocalSkin.current
    var name by remember { mutableStateOf(role?.name.orEmpty()) }
    var voice by remember { mutableStateOf(role?.voice.orEmpty()) }

    Sheet(if (role == null) "加一个角色" else "改「${role.name}」", onClose) {
        Lab("名字", "跟书里写的一模一样")
        Field(name, "比如 林瑾", 0) { name = it }

        Spacer(Modifier.height(14.dp))
        Lab("嗓音", "认出来是他，就用这一副")
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            PickChip("跟对白那一副", voice.isBlank(), { voice = "" }, Modifier.padding(end = 6.dp))
            groups.forEach { (_, list) ->
                list.forEach { v ->
                    PickChip(v.name, voice == v.name, { voice = v.name }, Modifier.padding(end = 6.dp))
                }
            }
        }
        if (groups.isEmpty()) {
            Spacer(Modifier.height(6.dp))
            T("音色库是空的。先去定音里开一路声源。", size = 10, color = s.inkFaint)
        }

        Spacer(Modifier.height(18.dp))
        Btn("存下", { onOk(name, voice) }, BtnKind.PRIMARY, Modifier.fillMaxWidth(), enabled = name.isNotBlank())
    }
}

@Composable
private fun RuleSheet(
    rule: CastRule?,
    names: List<String>,
    onClose: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onOk: (String, String, Boolean) -> Unit,
) {
    val s = LocalSkin.current
    var who by remember { mutableStateOf(rule?.character.orEmpty()) }
    var pat by remember { mutableStateOf(rule?.pattern.orEmpty()) }
    var after by remember { mutableStateOf(rule?.afterDialog ?: false) }
    var probe by remember { mutableStateOf("") }
    val err = if (pat.isBlank()) null
    else runCatching { Regex(pat) }.exceptionOrNull()?.let { "这条不通：" + it.message?.take(40) }
    val hit = remember(probe, pat) {
        if (probe.isBlank() || pat.isBlank() || err != null) null
        else runCatching { Regex(pat).containsMatchIn(probe) }.getOrDefault(false)
    }

    Sheet(if (rule == null) "加一条规则" else "改这条规则", onClose) {
        Lab("派给谁", "对上这条，就用这个角色的嗓音")
        if (names.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                names.forEach { n -> PickChip(n, who == n, { who = n }, Modifier.padding(end = 6.dp)) }
            }
            Spacer(Modifier.height(8.dp))
        }
        Field(who, "角色名", 0) { who = it }

        Spacer(Modifier.height(12.dp))
        Lab("正则", "在引号外面那半句里找这个人")
        Field(pat, "(林瑾.{0,8})(说道|沉声道)", 80) { pat = it }
        if (err != null) { Spacer(Modifier.height(4.dp)); T(err, size = 10, color = s.bad) }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                T("看引号后面那半句", size = 12)
                T("不勾就前后都看", size = 10, color = s.inkFaint)
            }
            Switch(
                checked = after,
                onCheckedChange = { after = it },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = s.ground, checkedTrackColor = s.flame,
                    uncheckedThumbColor = s.inkFaint, uncheckedTrackColor = s.panelSink,
                ),
            )
        }

        Spacer(Modifier.height(12.dp))
        Lab("试一句", "粘一句原文进来")
        Field(probe, "林瑾沉声道：“公子，您可曾见过？”", 64) { probe = it }
        if (hit != null) {
            Spacer(Modifier.height(5.dp))
            T(if (hit) "认出来了" else "没认出来", size = 10, color = if (hit) s.ok else s.bad)
        }

        Spacer(Modifier.height(18.dp))
        Btn(
            "存下",
            { onOk(who, pat, after) },
            BtnKind.PRIMARY, Modifier.fillMaxWidth(),
            enabled = who.isNotBlank() && pat.isNotBlank() && err == null,
        )
        if (onDelete != null) {
            Spacer(Modifier.height(8.dp))
            Btn("删掉这条", onDelete, BtnKind.DANGER, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun RawSheet(text: String, onClose: () -> Unit, onOk: () -> Unit) {
    val s = LocalSkin.current
    var v by remember { mutableStateOf(text) }
    Sheet("整张表", onClose) {
        T("一行一条：谁｜嗓音｜正则。以 # 开头的行不算。", size = 10, color = s.inkFaint)
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier.fillMaxWidth().height(280.dp)
                .clip(RoundedCornerShape(9.dp)).background(s.panelSink)
                .padding(12.dp)
        ) {
            BasicTextField(
                value = v,
                onValueChange = { v = it },
                textStyle = TextStyle(fontSize = 11.sp, color = s.ink, fontFamily = s.numFont),
                cursorBrush = SolidColor(s.flame),
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.height(14.dp))
        Btn("按这张表来", onOk, BtnKind.PRIMARY, Modifier.fillMaxWidth())
    }
}

@Composable
private fun Sheet(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    val s = LocalSkin.current
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(s.ground.copy(alpha = 0.72f)))
        Column(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                .background(s.ground, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .pointerInput(Unit) { detectTapGestures { } }
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 16.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                T(title, size = 15, weight = FontWeight.Medium, modifier = Modifier.weight(1f))
                IconBtn(Icons.AutoMirrored.Filled.ArrowBack, "收起", onClose, size = 30.dp)
            }
            Spacer(Modifier.height(8.dp))
            content()
            Spacer(Modifier.height(18.dp))
        }
    }
}

/** 旁白 / 对白的一行：名字 + 现在用的嗓子 + 调门。 */
@Composable
private fun PersonRow(label: String, voice: String, style: String, onClick: () -> Unit) {
    val s = LocalSkin.current
    Row(
        Modifier.fillMaxWidth()
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClick() }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            T(label, size = 14)
            Spacer(Modifier.height(3.dp))
            T(
                if (style.isBlank()) "调门跟这一句的性质走" else "调门 $style",
                size = 10, color = s.inkFaint, maxLines = 1,
            )
        }
        T(voice.ifBlank { "没挑" }, size = 12, color = if (voice.isBlank()) s.inkFaint else s.flame)
        Spacer(Modifier.width(6.dp))
        T("›", size = 14, color = s.inkFaint)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(s.ink.copy(alpha = 0.06f)))
}

/** 旁白 / 对白共用的那张纸：嗓子从整个音色库里挑，调门可写可选。 */
@Composable
private fun PersonSheet(
    what: String,
    voice: String,
    style: String,
    groups: List<Pair<String, List<Voice>>>,
    onClose: () -> Unit,
    onOk: (String, String) -> Unit,
) {
    val s = LocalSkin.current
    var v by remember { mutableStateOf(voice) }
    var st by remember { mutableStateOf(style) }

    Sheet("改「$what」", onClose) {
        Lab("嗓音", "音色库里所有的，都在这里")
        if (groups.isEmpty()) {
            T("一副都没开。去定音里开一路声源，这里才有得挑。", size = 11, color = s.inkFaint)
            Spacer(Modifier.height(8.dp))
        } else {
            groups.forEach { (g, list) ->
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    list.forEach { vo ->
                        PickChip(vo.name, v == vo.name, { v = vo.name }, Modifier.padding(end = 6.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(Modifier.height(10.dp))
        Lab("调门", "点一个，或者自己写一句。不写就按旁白/对白分开算")
        var write by remember { mutableStateOf(st.isNotBlank() && st !in STYLE_WORDS) }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            STYLE_WORDS.forEach { w ->
                PickChip(w, st == w, { st = if (st == w) "" else w; write = false }, Modifier.padding(end = 6.dp))
            }
            PickChip("自己写", write, {
                write = true
                if (st in STYLE_WORDS) st = ""
            }, Modifier.padding(end = 6.dp))
        }
        if (write) {
            Spacer(Modifier.height(8.dp))
            Field(st, "比如 低沉、急促、带笑", 0) { st = it.take(40) }
        }

        Spacer(Modifier.height(18.dp))
        Btn("存下", { onOk(v, st.trim()) }, BtnKind.PRIMARY, Modifier.fillMaxWidth())
    }
}

/** 调门的常用写法。填什么都行，这只是省得每次手打。 */
private val STYLE_WORDS = listOf(
    "平静", "温和", "严肃", "激昂", "低沉", "讲述",
    "慵懒", "轻快", "清冷", "急促", "耳语", "带笑",
)

@Composable
private fun Lab(title: String, note: String) {
    val s = LocalSkin.current
    T(title, size = 11, color = s.inkDim)
    if (note.isNotBlank()) {
        Spacer(Modifier.height(2.dp))
        T(note, size = 10, color = s.inkFaint)
    }
    Spacer(Modifier.height(7.dp))
}

@Composable
private fun Field(value: String, hint: String, height: Int = 0, onChange: (String) -> Unit) {
    val s = LocalSkin.current
    val m = if (height > 0) Modifier.fillMaxWidth().height(height.dp) else Modifier.fillMaxWidth()
    Box(
        m.clip(RoundedCornerShape(9.dp)).background(s.panelSink).padding(horizontal = 12.dp, vertical = 11.dp)
    ) {
        if (value.isEmpty()) T(hint, size = 12, color = s.inkFaint)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = TextStyle(fontSize = 13.sp, color = s.ink),
            cursorBrush = SolidColor(s.flame),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ── 存取 ──────────────────────────────────────────

private fun loadCast(app: App, book: BookMeta?): CastBook {
    val own = book?.castJson.orEmpty()
    if (own.isNotBlank()) return RuleCodec.fromJson(own)
    val g = app.field.now.castJson
    if (g.isNotBlank()) return RuleCodec.fromJson(g)
    return CastBook(narrator = app.field.now.narratorVoice, dialog = app.field.now.defaultVoice)
}
