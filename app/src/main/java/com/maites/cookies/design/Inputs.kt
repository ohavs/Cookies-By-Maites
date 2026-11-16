package com.maites.cookies.design

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Velocity
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** Single-line text field on surface2, rounded, with a label above. */
@Composable
fun CTextField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    ltr: Boolean = false,
    focus: androidx.compose.ui.focus.FocusRequester? = null,
    onFocus: ((Boolean) -> Unit)? = null,
    keyboardType: androidx.compose.ui.text.input.KeyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = C.colors
    Column(modifier) {
        SectionLabel(label)
        Row(
            Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(16.dp)).background(c.surface).padding(horizontal = Space.l),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) Text(placeholder, style = TextStyles.body, color = c.inkFaint)
                BasicTextField(
                    value, onChange, singleLine = true,
                    textStyle = TextStyles.body.copy(color = c.ink, textDirection = if (ltr) TextDirection.Ltr else TextDirection.Content),
                    cursorBrush = SolidColor(c.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, keyboardType = keyboardType),
                    modifier = Modifier.fillMaxWidth()
                        .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
                        .then(if (onFocus != null) Modifier.onFocusChanged { onFocus(it.isFocused) } else Modifier),
                )
            }
            if (trailing != null) trailing()
        }
    }
}

/** Multi-line text field (notes), same look as [CTextField]. */
@Composable
fun CTextArea(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, placeholder: String = "") {
    val c = C.colors
    Column(modifier) {
        SectionLabel(label)
        Box(Modifier.fillMaxWidth().heightIn(min = 120.dp).clip(RoundedCornerShape(16.dp)).background(c.surface).padding(Space.l)) {
            if (value.isEmpty()) Text(placeholder, style = TextStyles.body, color = c.inkFaint)
            BasicTextField(
                value, onChange,
                textStyle = TextStyles.body.copy(color = c.ink, textDirection = TextDirection.Content),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
fun SearchBox(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val c = C.colors
    Row(
        modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(18.dp)).background(c.surface2).padding(horizontal = Space.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("🔍", style = TextStyles.body, modifier = Modifier.graphicsLayer { alpha = 0.6f })
        Spacer(Modifier.size(Space.s))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, style = TextStyles.body, color = c.inkFaint)
            BasicTextField(
                value, onChange, singleLine = true,
                textStyle = TextStyles.body.copy(color = c.ink), cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Box(Modifier.size(26.dp).clip(CircleShape).background(c.surface3).pressable({ onChange("") }), contentAlignment = Alignment.Center) {
                Text("✕", style = TextStyles.caption, color = c.inkDim)
            }
        }
    }
}

/** Filter chip: ink fill when selected. */
@Composable
fun CChip(text: String, selected: Boolean, onClick: () -> Unit, emoji: String? = null) {
    val c = C.colors
    val bg by animateColorAsState(if (selected) c.ink else c.surface, Springs.soft(), label = "chip")
    val fg by animateColorAsState(if (selected) c.bg else c.ink, Springs.soft(), label = "chipfg")
    Row(
        Modifier.height(38.dp).clip(RoundedCornerShape(Radius.chip)).background(bg).pressable(onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (emoji != null) { Text(emoji, style = TextStyles.label); Spacer(Modifier.size(6.dp)) }
        Text(text, style = TextStyles.label, color = fg)
    }
}

/** Small rounded pill for metadata. */
@Composable
fun Pill(text: String, tint: Color = C.colors.surface2, fg: Color = C.colors.ink, ltr: Boolean = false) {
    Box(Modifier.clip(RoundedCornerShape(Radius.chip)).background(tint).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(text, style = TextStyles.label.copy(textDirection = if (ltr) TextDirection.Ltr else TextDirection.Content), color = fg)
    }
}

/**
 * Bottom sheet with scrim and grabber, on the glide spring. Drag it down anywhere (or fling) to
 * dismiss; a list inside scrolls first and hands the leftover pull to the sheet.
 */
@Composable
fun CSheet(open: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = C.colors
    val haptics = C.haptics
    val scope = rememberCoroutineScope()
    val dismiss by rememberUpdatedState(onDismiss)
    val p = remember { Animatable(0f) }
    var h by remember { mutableFloatStateOf(1f) }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(open) {
        if (open) { shown = true; haptics.light() }
        p.animateTo(if (open) 1f else 0f, Springs.soft(0.001f))
        if (!open) shown = false
    }
    BackHandler(open, onDismiss)
    if (!open && !shown) return

    fun dragBy(dy: Float) {
        scope.launch {
            var v = p.value - dy / h
            if (v > 1f) v = 1f + (v - 1f) * 0.15f
            p.snapTo(v)
        }
    }
    fun settle(vel: Float) {
        if (vel > 1400f || p.value < 0.6f) { haptics.light(); dismiss() }
        else scope.launch { p.animateTo(1f, Springs.soft(0.001f), initialVelocity = -vel / h) }
    }
    val nested = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                if (available.y < 0f && p.value < 1f && source == NestedScrollSource.UserInput) { dragBy(available.y); Offset(0f, available.y) }
                else Offset.Zero

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                if (available.y > 0f && source == NestedScrollSource.UserInput) { dragBy(available.y); Offset(0f, available.y) }
                else Offset.Zero

            override suspend fun onPreFling(available: Velocity): Velocity =
                if (p.value < 1f) { settle(available.y); available } else Velocity.Zero
        }
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = p.value.coerceIn(0f, 1f) }
                .background(Color.Black.copy(alpha = 0.38f))
                .pointerInput(Unit) { detectTapGestures { if (open) dismiss() } },
        )
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .onSizeChanged { h = it.height.toFloat().coerceAtLeast(1f) }
                .graphicsLayer { translationY = (1f - p.value) * (h + 30.dp.toPx()) }
                .nestedScroll(nested)
                .draggable(rememberDraggableState { dragBy(it) }, Orientation.Vertical, onDragStopped = { settle(it) })
                .clip(RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet))
                .background(c.bg)
                .clickable(remember { MutableInteractionSource() }, null) {}
                .navigationBarsPadding().imePadding().padding(Space.gutter),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(40.dp, 5.dp).clip(CircleShape).background(c.surface3))
            Spacer(Modifier.height(Space.l))
            content()
        }
    }
}

/**
 * Our own numeric keypad sheet. [initial] and the result are in hundredths (agorot, or 1/100 of a unit)
 * when [decimals] = 2; with [decimals] = 0 values are whole numbers.
 */
@Composable
fun KeypadSheet(
    open: Boolean,
    title: String,
    lead: String,
    initial: Long?,
    prefix: String = "₪",
    suffix: String = "",
    decimals: Int = 2,
    onDismiss: () -> Unit,
    onDone: (Long) -> Unit,
) {
    val c = C.colors
    var text by remember(open, initial) { mutableStateOf(initial?.let { fromHundredths(it, decimals) } ?: "") }
    val shake = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = C.haptics
    CSheet(open, onDismiss) {
        Text(title, style = TextStyles.title, color = c.ink)
        Text(lead, style = TextStyles.body, color = c.inkDim)
        Row(
            Modifier.fillMaxWidth().padding(top = Space.l, bottom = Space.l).graphicsLayer { translationX = shake.value },
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom,
        ) {
            if (prefix.isNotEmpty()) Text(prefix, style = TextStyles.title.copy(fontSize = 30.sp), color = c.inkDim)
            Text(text.ifEmpty { "0" }, style = TextStyles.display.copy(fontSize = 56.sp, lineHeight = 60.sp, textDirection = TextDirection.Ltr), color = c.ink)
            if (suffix.isNotEmpty()) Text(" $suffix", style = TextStyles.title, color = c.inkDim)
        }
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", if (decimals > 0) "." else "", "0", "⌫")
        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            keys.chunked(3).forEach { row ->
                // Keypad keeps phone order (1 2 3 left→right) regardless of RTL.
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        row.forEach { k ->
                            Box(
                                Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(16.dp))
                                    .background(if (k.isEmpty()) Color.Transparent else c.surface)
                                    .pressable({ haptics.tick(); text = press(text, k, decimals) }, enabled = k.isNotEmpty(), haptic = false),
                                contentAlignment = Alignment.Center,
                            ) { Text(k, style = TextStyles.title, color = c.ink, textAlign = TextAlign.Center) }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(Space.l))
        StickerButton("שמירה", {
            val v = toHundredths(text, decimals)
            if (v == null || v <= 0) scope.launch {
                haptics.reject()
                for (x in listOf(10f, -10f, 6f, 0f)) shake.animateTo(x, Springs.pop())
            } else onDone(v)
        }, Modifier.fillMaxWidth())
    }
}

private fun press(t: String, k: String, decimals: Int): String = when {
    k == "⌫" -> t.dropLast(1)
    k == "." -> if (t.contains('.')) t else (t.ifEmpty { "0" }) + "."
    t.contains('.') && t.substringAfter('.').length >= decimals -> t
    t.replace(".", "").length >= 7 -> t
    t == "0" -> k
    else -> t + k
}

private fun toHundredths(t: String, decimals: Int): Long? {
    val d = t.toBigDecimalOrNull() ?: return null
    return if (decimals == 0) d.toLong() else d.movePointRight(2).toLong()
}

private fun fromHundredths(v: Long, decimals: Int): String =
    if (decimals == 0) v.toString() else java.math.BigDecimal(v).movePointLeft(2).stripTrailingZeros().toPlainString()

/** Yes / no question in a sheet. [danger] colours nothing new — the confirm is the sticker button. */
@Composable
fun ConfirmSheet(open: Boolean, title: String, line: String, confirm: String, onDismiss: () -> Unit, cancel: String = "ביטול", onConfirm: () -> Unit) {
    val c = C.colors
    CSheet(open, onDismiss) {
        Text(title, style = TextStyles.title, color = c.ink)
        Text(line, style = TextStyles.body, color = c.inkDim)
        Spacer(Modifier.height(Space.xl))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
            GhostButton(cancel, onDismiss, Modifier.weight(1f))
            StickerButton(confirm, onConfirm, Modifier.weight(1f))
        }
    }
}
