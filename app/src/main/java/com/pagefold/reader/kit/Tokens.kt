package com.pagefold.reader.kit

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 材质种类。决定面板怎么画：GLASS 半透明叠加 + 亮边FROST 实色 + 噪点颗粒HUD   切角 + 描边 + 扫描线PAPER 。 */
enum class Mat { GLASS, FROST, HUD, PAPER }

/** 一整套视觉皮肤。所有界面只读这里，任何地方不写死颜色。 */
data class Skin(
    val key: String,
    val label: String,
    val tagline: String,
    val dark: Boolean,
    val mat: Mat,

    val ground: Color,
    val spotA: Color,
    val spotB: Color,
    val spotC: Color,

    val panel: Color,
    val panelLift: Color,
    val panelSink: Color,
    val hair: Color,
    val hairSoft: Color,

    val ink: Color,
    val inkDim: Color,
    val inkFaint: Color,

    val flame: Color,
    val onFlame: Color,
    val flameGhost: Color,

    val ok: Color,
    val warn: Color,
    val bad: Color,

    val rBig: Dp,
    val rCard: Dp,
    val rChip: Dp,

    val titleFont: FontFamily,
    val numFont: FontFamily,

    val grain: Float,
    val scan: Boolean,
    val cut: Boolean,
) {
    val accentSoft: Color get() = flame.copy(alpha = if (dark) 0.16f else 0.12f)
    /** 浮层底下的那层「压暗」。不用黑。 */
    val scrim: Color get() = ground.copy(alpha = if (dark) 0.80f else 0.74f)

    /** 不透明底。凡是要遮住下层的构件（弹层、底栏、对话框、输入法面板）都必须用它。 */
    val surface: Color
        get() = when (mat) {
            Mat.GLASS -> if (dark) Color(0xFF12151C) else Color(0xFFF7F8FA)
            Mat.FROST -> panel
            Mat.HUD -> if (dark) Color(0xFF070C0E) else Color(0xFFF4F6F7)
            Mat.PAPER -> panel
        }

    /** 不透明底上的抬升层：卡片、输入框、选中项。 */
    val surfaceHi: Color
        get() = when (mat) {
            Mat.GLASS -> if (dark) Color(0xFF1A1F29) else Color(0xFFFFFFFF)
            Mat.FROST -> panelLift
            Mat.HUD -> if (dark) Color(0xFF0D1417) else Color(0xFFFFFFFF)
            Mat.PAPER -> panelLift
        }
}

private val SANS = FontFamily.SansSerif
private val SERIF = FontFamily.Serif
private val MONO = FontFamily.Monospace

// ────────────────────────────────────────────────────────────────
// 一、玻璃
// ────────────────────────────────────────────────────────────────
val GlassNight = Skin(
    key = "glass_night", label = "玻璃 · 夜航", tagline = "深空蓝的透光面板",
    dark = true, mat = Mat.GLASS,
    ground = Color(0xFF070A12),
    spotA = Color(0xFF3A54C8), spotB = Color(0xFF0E6E70), spotC = Color(0xFF6B2E8E),
    panel = Color(0xFFFFFFFF).copy(alpha = 0.055f),
    panelLift = Color(0xFFFFFFFF).copy(alpha = 0.095f),
    panelSink = Color(0xFF000000).copy(alpha = 0.25f),
    hair = Color(0xFFFFFFFF).copy(alpha = 0.13f),
    hairSoft = Color(0xFFFFFFFF).copy(alpha = 0.06f),
    ink = Color(0xFFF0F3FA), inkDim = Color(0xFF97A1B8), inkFaint = Color(0xFF5A6377),
    flame = Color(0xFF6FC6F0), onFlame = Color(0xFF04121C),
    flameGhost = Color(0xFF6FC6F0).copy(alpha = 0.14f),
    ok = Color(0xFF5BD6A0), warn = Color(0xFFF0C25E), bad = Color(0xFFF0705E),
    rBig = 30.dp, rCard = 20.dp, rChip = 12.dp,
    titleFont = SANS, numFont = SANS, grain = 0.020f, scan = false, cut = false,
)

val GlassDawn = GlassNight.copy(
    key = "glass_dawn", label = "玻璃 · 晨雾", tagline = "奶白通透的柔光",
    dark = false, mat = Mat.GLASS,
    ground = Color(0xFFE9EEF8),
    spotA = Color(0xFFBDD5FF), spotB = Color(0xFFB9EAE4), spotC = Color(0xFFE2D6FF),
    panel = Color(0xFFFFFFFF).copy(alpha = 0.60f),
    panelLift = Color(0xFFFFFFFF).copy(alpha = 0.82f),
    panelSink = Color(0xFF5B6B8C).copy(alpha = 0.10f),
    hair = Color(0xFFFFFFFF).copy(alpha = 0.90f),
    hairSoft = Color(0xFF2C3A50).copy(alpha = 0.08f),
    ink = Color(0xFF101B29), inkDim = Color(0xFF5A6880), inkFaint = Color(0xFF98A3B5),
    flame = Color(0xFF1C6FD0), onFlame = Color(0xFFFFFFFF),
    flameGhost = Color(0xFF1C6FD0).copy(alpha = 0.12f),
    ok = Color(0xFF1E8E62), warn = Color(0xFF9A6B12), bad = Color(0xFFC04434),
    grain = 0.014f,
)

// ────────────────────────────────────────────────────────────────
// 二、磨砂
// ────────────────────────────────────────────────────────────────
val FrostSlate = Skin(
    key = "frost_slate", label = "磨砂 · 石墨", tagline = "哑光岩面，颗粒手感",
    dark = true, mat = Mat.FROST,
    ground = Color(0xFF16171A),
    spotA = Color(0xFF2A2C31), spotB = Color(0xFF1F2126), spotC = Color(0xFF2E2A26),
    panel = Color(0xFF212327),
    panelLift = Color(0xFF2A2D32),
    panelSink = Color(0xFF141518),
    hair = Color(0xFF32363C), hairSoft = Color(0xFF262A2F),
    ink = Color(0xFFEBEBE9), inkDim = Color(0xFF9C9D9B), inkFaint = Color(0xFF6A6C6B),
    flame = Color(0xFFC9A96E), onFlame = Color(0xFF1A1408),
    flameGhost = Color(0xFFC9A96E).copy(alpha = 0.15f),
    ok = Color(0xFF6FBE8C), warn = Color(0xFFD9A85C), bad = Color(0xFFD9705F),
    rBig = 18.dp, rCard = 14.dp, rChip = 10.dp,
    titleFont = SANS, numFont = MONO, grain = 0.055f, scan = false, cut = false,
)

val FrostBone = FrostSlate.copy(
    key = "frost_bone", label = "磨砂 · 骨白", tagline = "砂岩色的纸质触感",
    dark = false, mat = Mat.FROST,
    ground = Color(0xFFE7E4DE),
    spotA = Color(0xFFF2EFE9), spotB = Color(0xFFDED9D0), spotC = Color(0xFFEFE9DD),
    panel = Color(0xFFF5F2EC),
    panelLift = Color(0xFFFCFAF5),
    panelSink = Color(0xFFDCD7CE),
    hair = Color(0xFFD3CDC2), hairSoft = Color(0xFFE2DDD4),
    ink = Color(0xFF1D1C1A), inkDim = Color(0xFF65625D), inkFaint = Color(0xFF9B978F),
    flame = Color(0xFF8A6A34), onFlame = Color(0xFFFFFBF2),
    flameGhost = Color(0xFF8A6A34).copy(alpha = 0.12f),
    ok = Color(0xFF3F7A55), warn = Color(0xFF8A6A22), bad = Color(0xFFA34732),
    grain = 0.048f,
)

// ────────────────────────────────────────────────────────────────
// 三、科幻
// ────────────────────────────────────────────────────────────────
val HudVoid = Skin(
    key = "hud_void", label = "科幻 · 深空", tagline = "切角舱体，扫描线",
    dark = true, mat = Mat.HUD,
    ground = Color(0xFF03070A),
    spotA = Color(0xFF08383A), spotB = Color(0xFF0A2436), spotC = Color(0xFF06403A),
    panel = Color(0xFF08131A),
    panelLift = Color(0xFF0D1E27),
    panelSink = Color(0xFF040C11),
    hair = Color(0xFF1B4448), hairSoft = Color(0xFF122E33),
    ink = Color(0xFFCBEFF0), inkDim = Color(0xFF6E9BA0), inkFaint = Color(0xFF42666C),
    flame = Color(0xFF2FE3C8), onFlame = Color(0xFF00120F),
    flameGhost = Color(0xFF2FE3C8).copy(alpha = 0.13f),
    ok = Color(0xFF2FE3C8), warn = Color(0xFFF0B23E), bad = Color(0xFFFF5C61),
    rBig = 4.dp, rCard = 3.dp, rChip = 2.dp,
    titleFont = MONO, numFont = MONO, grain = 0.012f, scan = true, cut = true,
)

val HudLab = HudVoid.copy(
    key = "hud_lab", label = "科幻 · 净室", tagline = "白得晃眼",
    dark = false, mat = Mat.HUD,
    ground = Color(0xFFE8F1F1),
    spotA = Color(0xFFD3EAE7), spotB = Color(0xFFD8E8F1), spotC = Color(0xFFE6F0E9),
    panel = Color(0xFFFBFFFF),
    panelLift = Color(0xFFFFFFFF),
    panelSink = Color(0xFFDCE9E9),
    hair = Color(0xFFB7D5D3), hairSoft = Color(0xFFD0E4E2),
    ink = Color(0xFF07201F), inkDim = Color(0xFF4A6C6B), inkFaint = Color(0xFF84A3A2),
    flame = Color(0xFF0B8C7C), onFlame = Color(0xFFFFFFFF),
    flameGhost = Color(0xFF0B8C7C).copy(alpha = 0.11f),
    ok = Color(0xFF0B8C7C), warn = Color(0xFF9A6A10), bad = Color(0xFFB23A32),
    grain = 0.010f,
)

// ────────────────────────────────────────────────────────────────
// 四、纸墨
// ────────────────────────────────────────────────────────────────
val PaperInk = Skin(
    key = "paper_ink", label = "纸墨 · 宣", tagline = "一张宣纸，半边砑光",
    dark = false, mat = Mat.PAPER,
    ground = Color(0xFFF2ECDF),
    spotA = Color(0xFFFAF5EA), spotB = Color(0xFFEDE4D2), spotC = Color(0xFFF6EFE2),
    panel = Color(0xFFFBF7ED),
    panelLift = Color(0xFFFFFCF4),
    panelSink = Color(0xFFE9E1D0),
    hair = Color(0xFFDDD2BC), hairSoft = Color(0xFFEDE5D5),
    ink = Color(0xFF221C15), inkDim = Color(0xFF6B6152), inkFaint = Color(0xFF9E9382),
    flame = Color(0xFFA63D28), onFlame = Color(0xFFFFF8F2),
    flameGhost = Color(0xFFA63D28).copy(alpha = 0.10f),
    ok = Color(0xFF3E6B47), warn = Color(0xFF8A6410), bad = Color(0xFF9E3222),
    rBig = 8.dp, rCard = 6.dp, rChip = 4.dp,
    titleFont = SERIF, numFont = SERIF, grain = 0.035f, scan = false, cut = false,
)

val PaperNight = PaperInk.copy(
    key = "paper_night", label = "纸墨 · 夜读", tagline = "油灯离纸三寸",
    dark = true, mat = Mat.PAPER,
    ground = Color(0xFF12100C),
    spotA = Color(0xFF251E15), spotB = Color(0xFF1A1611), spotC = Color(0xFF2A2018),
    panel = Color(0xFF1B1712),
    panelLift = Color(0xFF241F18),
    panelSink = Color(0xFF100D0A),
    hair = Color(0xFF342C22), hairSoft = Color(0xFF251F19),
    ink = Color(0xFFE7DCC7), inkDim = Color(0xFF9C8F79), inkFaint = Color(0xFF6A6053),
    flame = Color(0xFFD98554), onFlame = Color(0xFF1A0F06),
    flameGhost = Color(0xFFD98554).copy(alpha = 0.14f),
    ok = Color(0xFF7FB98A), warn = Color(0xFFD6A659), bad = Color(0xFFD0715B),
    grain = 0.045f,
)

/** 四套主题，每套含明暗两个变体。界面只认这 4 个 key。 */
enum class ThemeId(val key: String, val label: String, val tagline: String, val light: Skin, val dark: Skin) {
    GLASS("glass", "玻璃", "玻璃质感，体验不一样的感觉", GlassDawn, GlassNight),
    FROST("frost", "磨砂", "旧石阶淋过雨，指腹上留一层砂", FrostBone, FrostSlate),
    HUD("hud", "科幻", "未来的感觉现在体验", HudLab, HudVoid),
    PAPER("paper", "纸墨", "体验书籍的感觉", PaperInk, PaperNight);

    fun skin(dark: Boolean) = if (dark) this.dark else this.light

    companion object {
        fun of(key: String?): ThemeId = entries.firstOrNull { it.key == key } ?: GLASS
    }
}

val LocalSkin = staticCompositionLocalOf { GlassNight }

/** 尺寸刻度，全局统一。 */
object Sz {
    val gutter: Dp = 20.dp
    val cardPad: Dp = 18.dp
    val gap: Dp = 12.dp
    val gapL: Dp = 20.dp
    val rowMin: Dp = 52.dp
    val tap: Dp = 48.dp
    const val body: Int = 15
    const val bodyL: Int = 16
    const val label: Int = 13
    const val micro: Int = 11
    const val h1: Int = 30
    const val h2: Int = 22
    const val hero: Int = 38
}
