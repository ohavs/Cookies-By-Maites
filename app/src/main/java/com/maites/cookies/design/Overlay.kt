package com.maites.cookies.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * One layer above the whole app. Sheets and toasts render here instead of where they are declared,
 * so a sheet declared inside a scrolling list or a page is never clipped or covered by the tab bar.
 * Later entries draw on top; [top] entries (toasts) always draw above sheets.
 */
class OverlayLayer {
    internal class Entry(val top: Boolean, val content: @Composable () -> Unit)
    internal val entries = mutableStateListOf<Entry>()
}

val LocalOverlay = staticCompositionLocalOf<OverlayLayer?> { null }

@Composable
fun OverlayHost(content: @Composable () -> Unit) {
    val layer = remember { OverlayLayer() }
    CompositionLocalProvider(LocalOverlay provides layer) {
        Box(Modifier.fillMaxSize()) {
            content()
            for (e in layer.entries.filter { !it.top } + layer.entries.filter { it.top }) key(e) { e.content() }
        }
    }
}

/** Renders [content] full screen in the app's overlay layer for as long as this call stays in composition. */
@Composable
fun Overlay(top: Boolean = false, content: @Composable () -> Unit) {
    val layer = LocalOverlay.current
    if (layer == null) { Box(Modifier.fillMaxSize()) { content() }; return }
    val latest = rememberUpdatedState(content)
    val entry = remember { OverlayLayer.Entry(top) { latest.value() } }
    DisposableEffect(layer) {
        layer.entries.add(entry)
        onDispose { layer.entries.remove(entry) }
    }
}
