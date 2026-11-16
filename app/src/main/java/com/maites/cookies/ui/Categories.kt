package com.maites.cookies.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.maites.cookies.CookiesApp
import com.maites.cookies.data.CategoryEntity
import com.maites.cookies.data.Cats
import com.maites.cookies.data.KIND_PRODUCTS
import com.maites.cookies.data.KIND_RECIPES
import com.maites.cookies.design.C
import com.maites.cookies.design.CCard
import com.maites.cookies.design.CChip
import com.maites.cookies.design.CSheet
import com.maites.cookies.design.CTextField
import com.maites.cookies.design.ColorDot
import com.maites.cookies.design.ConfirmSheet
import com.maites.cookies.design.GhostButton
import com.maites.cookies.design.SectionLabel
import com.maites.cookies.design.Segmented
import com.maites.cookies.design.Space
import com.maites.cookies.design.StickerButton
import com.maites.cookies.design.TextStyles
import com.maites.cookies.design.pressable
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun tintOf(i: Int): Color = with(C.colors) { listOf(tButter, tPeach, tMint, tSky, tLav, tRose)[Math.floorMod(i, 6)] }

private val EMOJIS = listOf(
    "🌾", "🥚", "🍫", "🫙", "🧈", "🥛", "🍯", "🥜", "🌰", "🍓", "🍋", "🥥", "🧂", "🍬", "☕", "🌿",
    "🍪", "🎂", "🥐", "🍮", "🥣", "🍰", "🧁", "🍩", "🥧", "🍞", "🎁", "📦", "🎉", "⭐",
)

/** Category chips for an editor, with "+ חדשה" at the end. */
@Composable
fun CategoryChips(app: CookiesApp, kind: String, selected: String, onSelect: (String) -> Unit) {
    var adding by remember { mutableStateOf(false) }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        Cats.list(kind).forEach { k -> CChip(k.label, selected == k.gid, { onSelect(k.gid) }, k.emoji) }
        CChip("חדשה", false, { adding = true }, "＋")
    }
    CategorySheet(app, adding, kind, null, { adding = false }) { gid -> adding = false; onSelect(gid) }
}

/** Add ([editing] null) or edit one category. */
@Composable
fun CategorySheet(app: CookiesApp, open: Boolean, kind: String, editing: CategoryEntity?, onDismiss: () -> Unit, onSaved: (String) -> Unit = {}) {
    val c = C.colors
    val scope = rememberCoroutineScope()
    var label by remember(open, editing) { mutableStateOf(editing?.label.orEmpty()) }
    var emoji by remember(open, editing) { mutableStateOf(editing?.emoji ?: if (kind == KIND_PRODUCTS) "🫙" else "🍪") }
    var tint by remember(open, editing) { mutableStateOf(editing?.tint ?: Cats.list(kind).size) }
    var confirmDelete by remember { mutableStateOf(false) }
    var moveCount by remember { mutableStateOf(0) }
    val others = Cats.list(kind).filter { it.gid != editing?.gid }

    CSheet(open && !confirmDelete, onDismiss) {
        Text(if (editing == null) "קטגוריה חדשה" else "עריכת קטגוריה", style = TextStyles.title, color = c.ink)
        Spacer(Modifier.height(Space.l))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(54.dp).clip(RoundedCornerShape(16.dp)).background(tintOf(tint)), contentAlignment = Alignment.Center) {
                Text(emoji, style = TextStyles.title)
            }
            Spacer(Modifier.width(Space.m))
            CTextField(label, { label = it.take(24) }, "שם", Modifier.weight(1f), placeholder = if (kind == KIND_PRODUCTS) "למשל: פירות יבשים" else "למשל: חגים")
        }
        Spacer(Modifier.height(Space.l))
        SectionLabel("אייקון")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            EMOJIS.forEach { e ->
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(if (e == emoji) c.accent else c.surface2).pressable({ emoji = e }),
                    contentAlignment = Alignment.Center,
                ) { Text(e, style = TextStyles.heading) }
            }
        }
        Spacer(Modifier.height(Space.l))
        SectionLabel("צבע")
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            (0 until 6).forEach { i -> ColorDot(tintOf(i), Math.floorMod(tint, 6) == i, { tint = i }) }
        }
        Spacer(Modifier.height(Space.xl))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
            if (editing != null && others.isNotEmpty()) GhostButton("מחיקה", {
                scope.launch {
                    val dao = app.db.categories()
                    moveCount = if (kind == KIND_PRODUCTS) dao.productCount(editing.gid) else dao.recipeCount(editing.gid)
                    confirmDelete = true
                }
            }, Modifier.weight(1f))
            StickerButton(if (editing == null) "הוספה" else "שמירה", {
                val dao = app.db.categories()
                val name = label.trim()
                val saved = editing?.copy(label = name, emoji = emoji, tint = Math.floorMod(tint, 6))
                    ?: CategoryEntity(UUID.randomUUID().toString(), kind, name, emoji, Math.floorMod(tint, 6), (Cats.list(kind).maxOfOrNull { it.position } ?: -1) + 1)
                Cats.all = Cats.all.filter { it.gid != saved.gid } + saved // show it right away
                app.applicationScope.launch { dao.save(saved) }
                onSaved(saved.gid)
            }, Modifier.weight(1f), enabled = label.isNotBlank())
        }
    }

    val to = others.firstOrNull()
    val what = if (kind == KIND_PRODUCTS) "מוצרים" else "מתכונים"
    ConfirmSheet(
        confirmDelete && editing != null && to != null,
        "למחוק את ${editing?.label}?",
        if (moveCount == 0) "אין בה $what, אז שום דבר לא זז." else "$moveCount $what יעברו ל${to?.label}.",
        "מחיקה",
        { confirmDelete = false },
    ) {
        val e = editing ?: return@ConfirmSheet
        val target = to ?: return@ConfirmSheet
        confirmDelete = false
        Cats.all = Cats.all.filter { it.gid != e.gid }
        app.applicationScope.launch { app.db.categories().delete(e, target.gid) }
        onDismiss()
    }
}

/** Settings → categories: products / recipes, tap to edit, + to add, arrows to reorder. */
@Composable
fun CategoriesPage(app: CookiesApp, onBack: () -> Unit) {
    val c = C.colors
    var kind by rememberSaveable { mutableStateOf(KIND_PRODUCTS) }
    var editing by remember { mutableStateOf<CategoryEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    var counts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    val list = Cats.list(kind)
    LaunchedEffect(kind, list) {
        val dao = app.db.categories()
        counts = list.associate { it.gid to if (kind == KIND_PRODUCTS) dao.productCount(it.gid) else dao.recipeCount(it.gid) }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.m), verticalAlignment = Alignment.CenterVertically) {
                RoundButton("→", onBack)
                Spacer(Modifier.width(Space.m))
                Text("קטגוריות", style = TextStyles.title, color = c.ink)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Space.gutter)) {
                Segmented(listOf(KIND_PRODUCTS, KIND_RECIPES), kind, { if (it == KIND_PRODUCTS) "מוצרים" else "מתכונים" }, { kind = it }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(Space.l))
                CCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = Space.l, vertical = Space.xs)) {
                    list.forEachIndexed { i, k ->
                        Row(Modifier.fillMaxWidth().pressable({ editing = k }, scale = 0.98f).padding(vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(tintOf(k.tint)), contentAlignment = Alignment.Center) {
                                Text(k.emoji, style = TextStyles.heading)
                            }
                            Spacer(Modifier.width(Space.m))
                            Column(Modifier.weight(1f)) {
                                Text(k.label, style = TextStyles.heading, color = c.ink)
                                val n = counts[k.gid] ?: 0
                                Text(if (n == 0) "ריקה" else "$n ${if (kind == KIND_PRODUCTS) "מוצרים" else "מתכונים"}", style = TextStyles.label, color = c.inkDim)
                            }
                            if (i > 0) Box(
                                Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(c.surface2)
                                    .pressable({ app.applicationScope.launch { app.db.categories().swap(k, list[i - 1]) } }),
                                contentAlignment = Alignment.Center,
                            ) { Text("↑", style = TextStyles.heading, color = c.inkDim) }
                        }
                    }
                }
                Spacer(Modifier.height(Space.m))
                StickerButton("קטגוריה חדשה", { adding = true }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(Space.s))
                Text("החץ מקדים קטגוריה בסדר. מחיקה מעבירה את מה שבתוכה לקטגוריה הראשונה.", style = TextStyles.caption, color = c.inkFaint)
                Spacer(Modifier.height(Space.xl))
            }
        }
        CategorySheet(app, editing != null, kind, editing, { editing = null }) { editing = null }
        CategorySheet(app, adding, kind, null, { adding = false }) { adding = false }
    }
}
