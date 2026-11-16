package com.maites.cookies.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

enum class ThemeMode(val label: String) { System("לפי המכשיר"), Light("בהיר"), Dark("כהה") }

@Composable
fun CookiesTheme(mode: ThemeMode, accent: Accent, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val c = if (dark) darkColors(accent) else lightColors(accent)
    CompositionLocalProvider(
        LocalColors provides c,
        LocalLayoutDirection provides LayoutDirection.Rtl,
        LocalContentColor provides c.ink,
        LocalTextStyle provides TextStyles.body,
        LocalTextSelectionColors provides TextSelectionColors(c.accent, c.accentSoft),
        content = content,
    )
}

object C {
    val colors: CColors @Composable get() = LocalColors.current
}
