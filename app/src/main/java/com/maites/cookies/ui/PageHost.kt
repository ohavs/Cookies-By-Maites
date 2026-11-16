package com.maites.cookies.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.maites.cookies.design.C
import com.maites.cookies.design.Springs
import kotlinx.coroutines.launch

private class Entry(val page: Any) {
    /** 0 = on screen, 1 = fully off towards the end side. */
    val x = Animatable(1f)
    var leaving = false
}

/**
 * Stack of pushed pages over the tabs. Pages slide in from the end side on the soft spring;
 * dragging from the start edge pulls the top page back (interruptible), release past 25% pops it.
 */
@Composable
fun PageHost(stack: List<Any>, onPop: () -> Unit, content: @Composable (Any) -> Unit) {
    val entries = remember { mutableStateListOf<Entry>() }
    val scope = rememberCoroutineScope()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val sign = if (rtl) -1f else 1f

    LaunchedEffect(stack.toList()) {
        stack.forEach { p ->
            if (entries.none { it.page === p && !it.leaving }) {
                val e = Entry(p); entries.add(e)
                scope.launch { e.x.animateTo(0f, Springs.soft()) }
            }
        }
        entries.filter { e -> !e.leaving && stack.none { it === e.page } }.forEach { e ->
            e.leaving = true
            scope.launch { e.x.animateTo(1f, Springs.soft()); entries.remove(e) }
        }
    }

    BackHandler(stack.isNotEmpty(), onPop)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = with(LocalDensity.current) { maxWidth.toPx() }
        entries.forEach { e ->
            key(e) {
                // Scrim over what's underneath.
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = (1f - e.x.value).coerceIn(0f, 1f) }.background(Color.Black.copy(alpha = 0.18f)))
                Box(Modifier.fillMaxSize().graphicsLayer { translationX = e.x.value * w * sign }.background(C.colors.bg)) {
                    content(e.page)
                    if (!e.leaving && e === entries.lastOrNull { !it.leaving }) {
                        Box(
                            Modifier.align(Alignment.CenterStart).fillMaxHeight().width(22.dp).pointerInput(e) {
                                detectHorizontalDragGestures(
                                    onDragEnd = {
                                        if (e.x.value > 0.25f) onPop() else scope.launch { e.x.animateTo(0f, Springs.soft()) }
                                    },
                                    onDragCancel = { scope.launch { e.x.animateTo(0f, Springs.soft()) } },
                                ) { change, dx ->
                                    change.consume()
                                    scope.launch { e.x.snapTo((e.x.value + dx * sign / w).coerceIn(0f, 1f)) }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
