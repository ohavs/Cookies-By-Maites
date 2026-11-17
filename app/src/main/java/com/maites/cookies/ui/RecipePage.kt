package com.maites.cookies.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maites.cookies.CookiesApp
import com.maites.cookies.data.IngredientEntity
import com.maites.cookies.data.ProductEntity
import com.maites.cookies.data.RecipePhotos
import com.maites.cookies.data.Costing
import com.maites.cookies.data.RecipeEntity
import com.maites.cookies.design.C
import com.maites.cookies.design.CCard
import com.maites.cookies.design.CSheet
import com.maites.cookies.design.GhostButton
import com.maites.cookies.design.Pill
import com.maites.cookies.design.SectionLabel
import com.maites.cookies.design.Space
import com.maites.cookies.design.Springs
import com.maites.cookies.design.StickerButton
import com.maites.cookies.design.TextStyles
import com.maites.cookies.design.pressable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun RecipePage(app: CookiesApp, id: Long, onBack: () -> Unit, onEdit: () -> Unit, onProduct: (Long) -> Unit, onCosting: () -> Unit, toast: (String) -> Unit) {
    val c = C.colors
    val dao = app.db.recipes()
    val ctx = LocalContext.current
    val recipe by remember(id) { dao.byId(id) }.collectAsStateWithLifecycle(null)
    val items by remember(id) { dao.ingredients(id) }.collectAsStateWithLifecycle(emptyList())
    val products by app.db.products().all().collectAsStateWithLifecycle(emptyList())
    val byId = remember(products) { products.associateBy { it.id } }
    var mult by remember(id) { mutableStateOf(1.0) }
    val cost = rememberIngredientsCost(app, items, mult)
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    var pdf by remember { mutableStateOf(false) }
    val r = recipe ?: return

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.m), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                RoundButton("→", onBack)
                Spacer(Modifier.weight(1f))
                RoundButton(if (r.favorite) "❤️" else "🤍", {
                    scope.launch { dao.setFavorite(r.id, !r.favorite) }
                    toast(if (r.favorite) "הוסר מהמועדפים" else "נשמר במועדפים")
                })
                RoundButton("📄", { pdf = true })
                if (com.maites.cookies.data.Workspace.canEdit) RoundButton("✏️", onEdit)
            }
            Gallery(r.photoList, r.category)

            Column(Modifier.padding(Space.gutter)) {
                Text(r.name, style = TextStyles.display, color = c.ink)
                Spacer(Modifier.height(Space.m))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Pill(com.maites.cookies.data.Cats.recipe(r.category).let { "${it.emoji} ${it.label}" }, recipeTint(r.category))
                    yieldText(r.yieldCount, r.yieldLabel)?.let { Pill(it) }
                }

                if (items.isNotEmpty()) {
                    Spacer(Modifier.height(Space.xl))
                    SectionLabel("כמויות")
                    Scaler(r.yieldCount, r.yieldLabel, mult) { mult = it }
                }

                Spacer(Modifier.height(Space.xl))
                SectionLabel("מרכיבים")
                if (items.isEmpty()) {
                    CCard(Modifier.fillMaxWidth()) { Text("עוד אין מרכיבים — מוסיפים בעריכה ✏️", style = TextStyles.body, color = c.inkDim) }
                } else {
                    CCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = Space.l, vertical = Space.xs)) {
                        items.forEachIndexed { idx, i ->
                            if (idx > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
                            IngredientLine(i.copy(amount = i.amount * mult), i.productId?.let(byId::get), cost.lines[idx], onClick = i.productId?.takeIf(byId::containsKey)?.let { pid -> { onProduct(pid) } })
                        }
                    }
                    Spacer(Modifier.height(Space.xl))
                    SectionLabel("תמחיר")
                    CostSummary(r, Costing.of(cost.sum, r.extraList, r.yieldCount, mult), cost.missing, onCosting)
                    Spacer(Modifier.height(Space.m))
                    if (com.maites.cookies.data.Workspace.canEdit) StickerButton("🛒 לרשימת הקניות", {
                        app.applicationScope.launch { app.db.shop().addRecipe(r.id, mult) }
                        toast("${r.name} נוסף לקניות")
                    }, Modifier.fillMaxWidth())
                }

                if (r.notes.isNotBlank()) {
                    Spacer(Modifier.height(Space.xl))
                    SectionLabel("הערות")
                    CCard(Modifier.fillMaxWidth()) { Text(r.notes, style = TextStyles.body, color = c.ink) }
                }

                Spacer(Modifier.height(Space.xl))
                if (com.maites.cookies.data.Workspace.canEdit) GhostButton("מחיקת המתכון", { confirmDelete = true }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(Space.xl))
            }
        }

        RecipePdfSheet(app, pdf, r.id, mult, { pdf = false }, toast)

        CSheet(confirmDelete, { confirmDelete = false }) {
            Text("למחוק את ${r.name}?", style = TextStyles.title, color = c.ink)
            Text("המתכון, המרכיבים והתמונות שלו יימחקו.", style = TextStyles.body, color = c.inkDim)
            Spacer(Modifier.height(Space.xl))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
                GhostButton("ביטול", { confirmDelete = false }, Modifier.weight(1f))
                StickerButton("מחיקה", {
                    confirmDelete = false
                    onBack()
                    app.applicationScope.launch {
                        dao.delete(r.id)
                        val keep = dao.rows().first().flatMap { it.photos.split('\n') }.toSet()
                        RecipePhotos.prune(ctx, keep)
                    }
                    toast("${r.name} נמחק")
                }, Modifier.weight(1f))
            }
        }
    }
}

/** Swipeable photo gallery with dots; category art when there are no photos. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Gallery(photos: List<String>, cat: String) {
    val c = C.colors
    val mod = Modifier.padding(horizontal = Space.gutter).fillMaxWidth().height(280.dp)
    if (photos.isEmpty()) { RecipePhoto(null, cat, mod, corner = 28.dp, iconSize = 120.dp); return }
    val pager = rememberPagerState { photos.size }
    Box(mod) {
        HorizontalPager(pager, Modifier.fillMaxSize(), pageSpacing = Space.s) { i ->
            RecipePhoto(photos[i], cat, Modifier.fillMaxSize(), corner = 28.dp)
        }
        if (photos.size > 1) Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = Space.m).clip(CircleShape).background(c.surface.copy(alpha = 0.85f)).padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            repeat(photos.size) { i ->
                val on = i == pager.currentPage
                val w by animateDpAsState(if (on) 18.dp else 6.dp, Springs.pop(), label = "dot")
                val col by animateColorAsState(if (on) c.ink else c.inkFaint, Springs.soft(), label = "dotc")
                Box(Modifier.size(w, 6.dp).clip(CircleShape).background(col))
            }
        }
    }
}

@Composable
fun IngredientLine(i: IngredientEntity, p: ProductEntity?, cost: Long?, onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    val c = C.colors
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.pressable(onClick) else Modifier).padding(vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(p?.let { categoryTint(it.category) } ?: c.surface2), contentAlignment = Alignment.Center) {
            Text(i.icon, style = TextStyles.heading)
        }
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            Text(p?.name ?: i.name, style = TextStyles.heading, color = c.ink)
            Text(formatPack(i.amount, i.unit) + if (p == null) " · לא מקושר למוצר" else "", style = TextStyles.label, color = c.inkDim)
        }
        if (trailing != null) trailing()
        else Text(cost?.let { formatShekel(it) } ?: "—", style = TextStyles.heading, color = if (cost != null) c.ink else c.inkFaint)
    }
}


private val STEPS = listOf(0.5 to "חצי", 1.0 to "רגיל", 2.0 to "כפול", 3.0 to "פי 3")

/** Multiplier chips, plus a units stepper when the recipe has a yield. */
@Composable
private fun Scaler(yieldCount: Int, yieldLabel: String, mult: Double, onMult: (Double) -> Unit) {
    val c = C.colors
    CCard(Modifier.fillMaxWidth()) {
        if (yieldCount > 0) {
            val units = Math.round(yieldCount * mult).toInt()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("$units ${yieldLabel.ifBlank { "יחידות" }}", style = TextStyles.title, color = c.ink)
                    Text("הכמויות והעלות מתעדכנות", style = TextStyles.label, color = c.inkDim)
                }
                RoundButton("−", { if (units > 1) onMult((units - 1).toDouble() / yieldCount) })
                Spacer(Modifier.width(Space.s))
                RoundButton("+", { if (units < 9999) onMult((units + 1).toDouble() / yieldCount) })
            }
            Spacer(Modifier.height(Space.m))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            STEPS.forEach { (m, label) -> com.maites.cookies.design.CChip(label, kotlin.math.abs(mult - m) < 1e-9, { onMult(m) }) }
        }
    }
}

@Composable
private fun CostSummary(r: RecipeEntity, cost: Costing, missing: Int, onOpen: () -> Unit) {
    val c = C.colors
    CCard(Modifier.fillMaxWidth(), onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (r.yieldCount > 0) "עלות ליחידה" else "עלות לתבנית", style = TextStyles.heading, color = c.ink)
                Text(
                    "מרכיבים ${formatShekel(cost.ingredients)}" + (if (cost.extras.isNotEmpty()) " · נוספות ${formatShekel(cost.extrasTotal)}" else "") +
                        (if (r.yieldCount > 0) " · סה״כ ${formatShekel(cost.total)}" else ""),
                    style = TextStyles.label, color = c.inkDim,
                )
            }
            Text(formatShekel(cost.perUnit), style = TextStyles.title, color = c.ink)
        }
        if (missing > 0) Text("בלי $missing מרכיבים שאין להם מחיר", style = TextStyles.label, color = c.danger, modifier = Modifier.padding(top = Space.xs))
        Spacer(Modifier.height(Space.m))
        if (r.salePrice > 0) {
            val p = cost.profit(r.salePrice)
            Text(
                "נמכר ב-${formatShekel(r.salePrice)} · ${if (p >= 0) "רווח" else "הפסד"} ${formatShekel(kotlin.math.abs(p))} (${formatPercent(cost.margin(r.salePrice))})",
                style = TextStyles.body, color = if (p >= 0) c.good else c.danger,
            )
        } else {
            Text("‹ עלויות נוספות ומחיר מכירה", style = TextStyles.label, color = c.accentInk)
        }
    }
}
