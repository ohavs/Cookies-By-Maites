package com.maites.cookies.design

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Scale-on-press with the pop spring. Returns modifier + interaction source to share with clickable. */
@Composable
fun Modifier.pressable(onClick: () -> Unit, enabled: Boolean = true): Modifier {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, Springs.pop(), label = "press")
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick)
}

@Composable
fun CCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(Space.l),
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = C.colors
    val shape = RoundedCornerShape(Radius.card)
    Column(
        modifier
            .shadow(if (c.isDark) 0.dp else 10.dp, shape, ambientColor = Color(0x1417140F), spotColor = Color(0x1417140F))
            .clip(shape)
            .background(c.surface)
            .then(if (onClick != null) Modifier.pressable(onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

/** Primary "sticker" button: accent fill, ink outline, hard offset shadow that collapses on press. */
@Composable
fun StickerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = C.colors
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val drop by animateDpAsState(if (pressed) 0.dp else 4.dp, Springs.pop(), label = "drop")
    val shape = RoundedCornerShape(Radius.button)
    Box(
        modifier
            .padding(bottom = 4.dp, end = 4.dp)
            .drawBehind {
                val d = drop.toPx()
                drawRoundRect(c.stroke, topLeft = Offset(d, d), size = size, cornerRadius = CornerRadius(Radius.button.toPx()))
            }
            .offset(x = 4.dp - drop, y = 4.dp - drop)
            .clip(shape)
            .background(if (enabled) c.accent else c.surface3)
            .border(2.dp, c.stroke, shape)
            .clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = TextStyles.heading, color = if (enabled) c.accentFg else c.inkFaint)
    }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = C.colors
    Box(
        modifier
            .clip(RoundedCornerShape(Radius.button))
            .background(c.surface2)
            .pressable(onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = TextStyles.label, color = c.ink) }
}

@Composable
fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val c = C.colors
    Row(
        modifier.clip(RoundedCornerShape(16.dp)).background(c.surface2).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { o ->
            val on = o == selected
            val bg by androidx.compose.animation.animateColorAsState(if (on) c.surface else Color.Transparent, Springs.soft(), label = "seg")
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(bg)
                    .pressable({ onSelect(o) }).padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label(o), style = TextStyles.label, color = if (on) c.ink else c.inkDim) }
        }
    }
}

@Composable
fun ColorDot(color: Color, selected: Boolean, onClick: () -> Unit, size: Dp = 40.dp) {
    val c = C.colors
    val ring by animateDpAsState(if (selected) 3.dp else 0.dp, Springs.pop(), label = "ring")
    Box(
        Modifier.size(size).clip(CircleShape).border(ring, c.ink, CircleShape).padding(5.dp)
            .clip(CircleShape).background(color).border(2.dp, c.stroke, CircleShape).pressable(onClick),
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = TextStyles.label, color = C.colors.inkDim, modifier = modifier.padding(start = 4.dp, bottom = Space.s))
}

/** Every empty screen gets a tinted illustration tile, a title and one line. */
@Composable
fun EmptyState(emoji: String, title: String, line: String, tint: Color, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val c = C.colors
    Column(modifier.fillMaxWidth().padding(Space.xl), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(104.dp).clip(RoundedCornerShape(32.dp)).background(tint).border(2.dp, c.stroke.copy(alpha = if (c.isDark) 0f else 0.9f), RoundedCornerShape(32.dp)),
            contentAlignment = Alignment.Center,
        ) { Text(emoji, style = TextStyles.display) }
        Spacer(Modifier.height(Space.l))
        Text(title, style = TextStyles.title, color = c.ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Space.xs))
        Text(line, style = TextStyles.body, color = c.inkDim, textAlign = TextAlign.Center)
        if (action != null) { Spacer(Modifier.height(Space.xl)); action() }
    }
}

@Composable
fun Toast(message: String?, modifier: Modifier = Modifier) {
    val c = C.colors
    AnimatedVisibility(
        visible = message != null,
        enter = slideInVertically(Springs.pop()) { it } + fadeIn(),
        exit = slideOutVertically(Springs.soft()) { it } + fadeOut(),
        modifier = modifier,
    ) {
        Box(
            Modifier.clip(RoundedCornerShape(18.dp)).background(c.ink).padding(horizontal = 18.dp, vertical = 14.dp),
        ) { Text(message ?: "", style = TextStyles.label, color = c.bg) }
    }
}

@Composable
fun SettingRow(title: String, subtitle: String? = null, onClick: (() -> Unit)? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    val c = C.colors
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.pressable(onClick) else Modifier).padding(vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = TextStyles.heading, color = c.ink)
            if (subtitle != null) Text(subtitle, style = TextStyles.label, color = c.inkDim)
        }
        trailing()
    }
}
