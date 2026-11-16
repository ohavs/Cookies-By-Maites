package com.maites.cookies.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.maites.cookies.CookiesApp
import com.maites.cookies.data.UiPrefs
import com.maites.cookies.design.C
import com.maites.cookies.design.CCard
import com.maites.cookies.design.Radius
import com.maites.cookies.design.Space
import com.maites.cookies.design.Springs
import com.maites.cookies.design.TextStyles
import com.maites.cookies.design.Toast
import com.maites.cookies.design.pressable
import kotlinx.coroutines.delay

enum class Tab(val label: String, val emoji: String) {
    Recipes("מתכונים", "🍪"), Products("מוצרים", "🧈"), Shopping("קניות", "🛒"), Settings("הגדרות", "⚙️")
}

@Composable
fun AppShell(app: CookiesApp, prefs: UiPrefs) {
    val c = C.colors
    var tab by rememberSaveable { mutableStateOf(Tab.Recipes) }
    var addOpen by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(toast) { if (toast != null) { delay(2200); toast = null } }
    val showToast: (String) -> Unit = { toast = it }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        AnimatedContent(
            targetState = tab,
            transitionSpec = { fadeIn(Springs.soft()) togetherWith fadeOut(Springs.soft()) },
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            label = "tab",
        ) { t ->
            when (t) {
                Tab.Recipes -> RecipesScreen { addOpen = true }
                Tab.Products -> ProductsScreen(app) { addOpen = true }
                Tab.Shopping -> ShoppingScreen()
                Tab.Settings -> SettingsScreen(app, prefs, showToast)
            }
        }

        BottomBar(tab, onTab = { tab = it }, onAdd = { addOpen = true }, modifier = Modifier.align(Alignment.BottomCenter))

        Toast(toast, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 104.dp))

        AddSheet(open = addOpen, onDismiss = { addOpen = false }) { what ->
            addOpen = false
            showToast("$what — נבנה בשלב הבא")
        }
    }
}

@Composable
private fun BottomBar(tab: Tab, onTab: (Tab) -> Unit, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    val c = C.colors
    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Space.l, vertical = Space.m)) {
        Row(
            Modifier.fillMaxWidth().height(68.dp)
                .shadow(18.dp, RoundedCornerShape(26.dp), ambientColor = Color(0x4717140F), spotColor = Color(0x4717140F))
                .clip(RoundedCornerShape(26.dp)).background(c.surface).padding(horizontal = Space.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            TabItem(Tab.Recipes, tab, onTab)
            TabItem(Tab.Products, tab, onTab)
            Spacer(Modifier.width(64.dp))
            TabItem(Tab.Shopping, tab, onTab)
            TabItem(Tab.Settings, tab, onTab)
        }
        // Raised "+" sticker
        Box(
            Modifier.align(Alignment.TopCenter).offset(y = (-18).dp).size(64.dp)
                .clip(CircleShape).background(c.stroke).padding(end = 0.dp),
        )
        Box(
            Modifier.align(Alignment.TopCenter).offset(x = (-3).dp, y = (-21).dp).size(62.dp)
                .clip(CircleShape).background(c.accent).border(2.5.dp, c.stroke, CircleShape).pressable(onAdd),
            contentAlignment = Alignment.Center,
        ) { Text("+", style = TextStyles.display, color = c.accentFg) }
    }
}

@Composable
private fun TabItem(t: Tab, current: Tab, onTab: (Tab) -> Unit) {
    val c = C.colors
    val on = t == current
    val color by animateColorAsState(if (on) c.ink else c.inkFaint, Springs.soft(), label = "tabc")
    val lift by animateFloatAsState(if (on) 1f else 0f, Springs.pop(), label = "lift")
    Column(
        Modifier.width(64.dp).pressable({ onTab(t) }).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(t.emoji, style = TextStyles.title, modifier = Modifier.graphicsLayer {
            val s = 0.9f + 0.15f * lift; scaleX = s; scaleY = s; alpha = 0.55f + 0.45f * lift
        })
        Text(t.label, style = TextStyles.caption, color = color)
    }
}

@Composable
private fun AddSheet(open: Boolean, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val c = C.colors
    BackHandler(open, onDismiss)
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(open, enter = fadeIn(Springs.soft()), exit = fadeOut(Springs.soft())) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.38f))
                .clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss))
        }
        AnimatedVisibility(
            open,
            enter = slideInVertically(Springs.soft()) { it },
            exit = slideOutVertically(Springs.soft()) { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet))
                    .background(c.bg).navigationBarsPadding().padding(Space.gutter),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).size(40.dp, 5.dp).clip(CircleShape).background(c.surface3))
                Spacer(Modifier.height(Space.l))
                Text("מה מוסיפים?", style = TextStyles.title, color = c.ink)
                Spacer(Modifier.height(Space.l))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    AddOption("🍪", "מתכון", c.tPeach, Modifier.weight(1f)) { onPick("מתכון חדש") }
                    AddOption("🧈", "מוצר", c.tButter, Modifier.weight(1f)) { onPick("מוצר חדש") }
                }
                Spacer(Modifier.height(Space.s))
            }
        }
    }
}

@Composable
private fun AddOption(emoji: String, label: String, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    val c = C.colors
    CCard(modifier, onClick = onClick) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(tint).border(2.dp, c.stroke, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
            Text(emoji, style = TextStyles.title)
        }
        Spacer(Modifier.height(Space.m))
        Text(label, style = TextStyles.heading, color = c.ink)
    }
}
