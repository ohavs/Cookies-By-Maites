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
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.maites.cookies.design.C
import com.maites.cookies.design.Springs
import kotlinx.coroutines.launch

/**
 * A page that may refuse to close (unsaved changes) registers a guard here: it returns true when it
 * handled the close itself (e.g. asked "leave without saving?").
 */
val LocalLeaveGuard = androidx.compose.runtime.staticCompositionLocalOf<((() -> Boolean)?) -> Unit> { {} }

/** Asks [onAsk] instead of closing while [dirty] — for back, edge swipe and the page's own ✕. */
@Composable
fun LeaveGuard(dirty: Boolean, onAsk: () -> Unit) {
    val set = LocalLeaveGuard.current
    val d by androidx.compose.runtime.rememberUpdatedState(dirty)
    val ask by androidx.compose.runtime.rememberUpdatedState(onAsk)
    androidx.compose.runtime.DisposableEffect(Unit) {
        set { if (d) { ask(); true } else false }
        onDispose { set(null) }
    }
}

private class Entry(val page: Any) {
    /** 0 = on screen, 1 = fully off towards the end side. */
    val x = Animatable(1f)
    var leaving = false
}

/**
 * Stack of pushed pages over the tabs. Pages slide in from the end side on the glide spring; the
 * page underneath drifts back a little (parallax). Dragging from the start edge pulls the top page
 * back under the finger (interruptible); release past 30% or a fling pops it, with the fling's speed.
 */
@Composable
fun PageHost(stack: List<Any>, onPop: () -> Unit, content: @Composable (Any) -> Unit) {
    val entries = remember { mutableStateListOf<Entry>() }
    val scope = rememberCoroutineScope()
    val haptics = C.haptics
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val sign = if (rtl) -1f else 1f
    val flingV = remember { FloatArray(1) } // speed of the swipe that popped, handed to the exit
    val guards = remember { mutableMapOf<Any, () -> Boolean>() }
    /** Pops unless the top page's guard takes over. Returns whether it popped. */
    val tryPop: () -> Boolean = {
        val top = stack.lastOrNull()
        if (top != null && guards[top]?.invoke() == true) false else { onPop(); true }
    }

    LaunchedEffect(stack.toList()) {
        stack.forEach { p ->
            if (entries.none { it.page === p && !it.leaving }) {
                val e = Entry(p); entries.add(e)
                scope.launch { e.x.animateTo(0f, Springs.soft(0.0005f)) }
            }
        }
        entries.filter { e -> !e.leaving && stack.none { it === e.page } }.forEach { e ->
            e.leaving = true
            val v = flingV[0]; flingV[0] = 0f
            scope.launch { e.x.animateTo(1f, Springs.soft(0.0005f), initialVelocity = v); entries.remove(e) }
        }
    }

    BackHandler(stack.isNotEmpty()) { tryPop() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = with(LocalDensity.current) { maxWidth.toPx() }
        entries.forEachIndexed { i, e ->
            key(e) {
                val above = entries.getOrNull(i + 1)
                // Scrim over what's underneath.
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = (1f - e.x.value).coerceIn(0f, 1f) }.background(Color.Black.copy(alpha = 0.18f)))
                Box(
                    Modifier.fillMaxSize().graphicsLayer {
                        val under = if (above != null) (1f - above.x.value).coerceIn(0f, 1f) * -0.22f else 0f
                        translationX = (e.x.value + under) * w * sign
                    }.background(C.colors.bg),
                ) {
                    androidx.compose.runtime.CompositionLocalProvider(
                        LocalLeaveGuard provides { g -> if (g == null) { guards.remove(e.page) } else { guards[e.page] = g } },
                    ) { content(e.page) }
                    if (!e.leaving && e === entries.lastOrNull { !it.leaving }) {
                        Box(
                            Modifier.align(Alignment.CenterStart).fillMaxHeight().width(28.dp).pointerInput(e) {
                                val tracker = VelocityTracker()
                                var total = 0f // finger travel in screen space (the strip moves with the page)
                                fun release() {
                                    val v = tracker.calculateVelocity().x * sign / w
                                    tracker.resetTracking()
                                    if (v > 1.2f || (e.x.value > 0.3f && v > -0.5f)) {
                                        haptics.light(); flingV[0] = v
                                        if (!tryPop()) { flingV[0] = 0f; scope.launch { e.x.animateTo(0f, Springs.soft(0.0005f)) } }
                                    } else scope.launch { e.x.animateTo(0f, Springs.soft(0.0005f), initialVelocity = v) }
                                }
                                detectHorizontalDragGestures(
                                    onDragStart = { tracker.resetTracking(); total = 0f },
                                    onDragEnd = { release() },
                                    onDragCancel = { release() },
                                ) { change, dx ->
                                    change.consume()
                                    total += dx
                                    tracker.addPosition(change.uptimeMillis, androidx.compose.ui.geometry.Offset(total, 0f))
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
