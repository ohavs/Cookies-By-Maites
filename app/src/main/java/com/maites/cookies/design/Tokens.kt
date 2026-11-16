package com.maites.cookies.design

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Colour tokens — mirror of web/proto CSS variables. */
@Immutable
data class CColors(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val surface3: Color,
    val ink: Color,
    val inkDim: Color,
    val inkFaint: Color,
    val divider: Color,
    val stroke: Color,
    val accent: Color,
    val accentFg: Color,
    val accentInk: Color,
    val accentSoft: Color,
    val good: Color,
    val goodSoft: Color,
    val danger: Color,
    val dangerSoft: Color,
    val tButter: Color,
    val tPeach: Color,
    val tMint: Color,
    val tSky: Color,
    val tLav: Color,
    val tRose: Color,
    val dough: Color,
    val chip: Color,
    val isDark: Boolean,
)

enum class Accent(val label: String, val base: Color, val inkLight: Color, val inkDark: Color) {
    Caramel("קרמל", Color(0xFFF08A24), Color(0xFFB5560A), Color(0xFFFFAE66)),
    Pistachio("פיסטוק", Color(0xFF8DBF4A), Color(0xFF4E7A17), Color(0xFFB5E07A)),
    Raspberry("פטל", Color(0xFFE5486F), Color(0xFFA81E44), Color(0xFFFF8AA6)),
    Blueberry("אוכמניות", Color(0xFF5B6CF0), Color(0xFF3341B8), Color(0xFF9CA6FF)),
}

fun lightColors(a: Accent) = CColors(
    bg = Color(0xFFF6F5F1), surface = Color.White, surface2 = Color(0xFFEFEEE9), surface3 = Color(0xFFE3E1DA),
    ink = Color(0xFF17140F), inkDim = Color(0xFF625D55), inkFaint = Color(0xFFA19B92),
    divider = Color(0x1217140F), stroke = Color(0xFF17140F),
    accent = a.base, accentFg = Color(0xFF17140F), accentInk = a.inkLight, accentSoft = a.base.copy(alpha = 0.16f).compositeOn(Color.White),
    good = Color(0xFF1F9D57), goodSoft = Color(0xFFE2F3E9), danger = Color(0xFFD92D20), dangerSoft = Color(0xFFFDECEC),
    tButter = Color(0xFFFFF3C9), tPeach = Color(0xFFFFE6D6), tMint = Color(0xFFE0F2E7),
    tSky = Color(0xFFE3EEFF), tLav = Color(0xFFECE9FF), tRose = Color(0xFFFDE3EA),
    dough = Color(0xFFE7B36C), chip = Color(0xFF3D2414), isDark = false,
)

fun darkColors(a: Accent) = CColors(
    bg = Color(0xFF0F0E0C), surface = Color(0xFF1B1A17), surface2 = Color(0xFF26241F), surface3 = Color(0xFF322F29),
    ink = Color(0xFFF5F3EE), inkDim = Color(0xFFA8A298), inkFaint = Color(0xFF6E685F),
    divider = Color(0x12FFFFFF), stroke = Color(0xFF0A0908),
    accent = a.base, accentFg = Color(0xFF17140F), accentInk = a.inkDark, accentSoft = a.base.copy(alpha = 0.16f),
    good = Color(0xFF3CC27A), goodSoft = Color(0x263CC27A), danger = Color(0xFFFF6B5E), dangerSoft = Color(0x24FF6B5E),
    tButter = Color(0x24F2BE28), tPeach = Color(0x24FF8C50), tMint = Color(0x213CC27A),
    tSky = Color(0x245C9BFF), tLav = Color(0x26826EFF), tRose = Color(0x24F05A82),
    dough = Color(0xFFC9984F), chip = Color(0xFF24130A), isDark = true,
)

private fun Color.compositeOn(under: Color): Color {
    val a = alpha
    return Color(red * a + under.red * (1 - a), green * a + under.green * (1 - a), blue * a + under.blue * (1 - a), 1f)
}

val LocalColors = staticCompositionLocalOf { lightColors(Accent.Caramel) }

object Radius {
    val card = 24.dp
    val sheet = 28.dp
    val button = 18.dp
    val chip = 999.dp
}

object Space {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val gutter = 20.dp
}

/** The only two springs in the app: soft for pages/sheets, bouncy for presses/pops. */
object Springs {
    fun <T> soft() = spring<T>(dampingRatio = 0.9f, stiffness = 380f)
    fun <T> pop() = spring<T>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
}
