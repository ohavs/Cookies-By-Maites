package com.maites.cookies.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maites.cookies.CookiesApp
import com.maites.cookies.data.Chain
import com.maites.cookies.data.RecipeRow
import com.maites.cookies.data.ShopCheck
import com.maites.cookies.data.ShopEntry
import com.maites.cookies.data.ShopLine
import com.maites.cookies.data.Shopping
import com.maites.cookies.data.priceKey
import com.maites.cookies.design.C
import com.maites.cookies.design.CCard
import com.maites.cookies.design.CSheet
import com.maites.cookies.design.EmptyState
import com.maites.cookies.design.GhostButton
import com.maites.cookies.design.Pill
import com.maites.cookies.design.SearchBox
import com.maites.cookies.design.SectionLabel
import com.maites.cookies.design.Segmented
import com.maites.cookies.design.Space
import com.maites.cookies.design.Springs
import com.maites.cookies.design.flip
import androidx.compose.ui.graphics.graphicsLayer
import com.maites.cookies.design.StickerButton
import com.maites.cookies.design.TextStyles
import com.maites.cookies.design.pressable
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun ShoppingScreen(app: CookiesApp, onAdd: () -> Unit, onClear: () -> Unit, onPickCity: () -> Unit, onRecipe: (Long) -> Unit, onProduct: (Long) -> Unit, toast: (String) -> Unit) {
    val c = C.colors
    val shop = app.db.shop()
    LaunchedEffect(Unit) { shop.prune() }
    val entries by shop.entries().collectAsStateWithLifecycle(null)
    val ingredients by shop.ingredients().collectAsStateWithLifecycle(emptyList())
    val checks by shop.checks().collectAsStateWithLifecycle(emptyList())
    val products by app.db.products().all().collectAsStateWithLifecycle(emptyList())
    val recipes by app.db.recipes().rows().collectAsStateWithLifecycle(emptyList())
    val byPacks by app.prefs.shopPacks.collectAsStateWithLifecycle(true)
    val city by app.prefs.city.collectAsStateWithLifecycle("")
    val prices = rememberPackPrices(app)
    val scope = rememberCoroutineScope()

    val productMap = remember(products) { products.associateBy { it.id } }
    val recipeMap = remember(recipes) { recipes.associateBy { it.id } }
    val all = entries ?: return
    val list = all.filter { (it.recipeId != null && it.recipeId in recipeMap) || (it.productId != null && it.productId in productMap) }
    val lines = remember(list, ingredients, productMap, recipeMap) { Shopping.lines(list, ingredients, productMap, recipeMap.keys) }
    val codes = remember(lines) { lines.mapNotNull { priceKey(it.product?.barcode) }.distinct() }
    val rows by remember(city, codes) { if (city.isBlank() || codes.isEmpty()) flowOf(emptyList()) else shop.pricesInCity(city, codes) }.collectAsStateWithLifecycle(emptyList())
    val offers = remember(lines, rows, byPacks) { Shopping.rank(lines, rows, byPacks) }
    val costs = lines.map { it.cost(it.product?.id?.let(prices::get), byPacks) }
    val total = costs.sumOf { it ?: 0L }
    val checked = checks.toSet()

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("קניות", if (lines.isEmpty()) null else "${lines.size} פריטים · ${formatShekel(total)} משוער")
        if (list.isEmpty()) {
            Box(Modifier.weight(1f).padding(bottom = 96.dp), contentAlignment = Alignment.Center) {
                EmptyState("🛒", "הרשימה ריקה", "בוחרים מתכונים, והרשימה והסופר הזול לסל נבנים לבד.", c.tMint) {
                    StickerButton("הוספה לרשימה", onAdd)
                }
            }
            return
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, bottom = 120.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("מה קונים", Modifier.weight(1f))
                    Box(Modifier.padding(bottom = Space.s).clip(RoundedCornerShape(99.dp)).pressable(onAdd)) { Pill("+ הוספה", c.accentSoft, c.accentInk) }
                }
                CCard(Modifier.fillMaxWidth().animateContentSize(), padding = PaddingValues(horizontal = Space.l, vertical = Space.xs)) {
                    list.forEachIndexed { idx, e ->
                        if (idx > 0) Divider()
                        EntryRow(app, e, recipeMap, productMap, onRecipe, onProduct) { qty ->
                            scope.launch { if (qty <= 0) shop.remove(e.id) else shop.setQty(e.id, qty) }
                            if (qty <= 0) toast("הוסר מהרשימה")
                        }
                    }
                }
                Spacer(Modifier.height(Space.xl))
                SectionLabel("רשימה")
                Segmented(listOf(true, false), byPacks, { if (it) "לפי אריזות" else "כמות מדויקת" }, { v -> scope.launch { app.prefs.setShopPacks(v) } }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(Space.m))
                val order = lines.indices.sortedBy { lines[it].key in checked }
                val placed = remember { mutableMapOf<Any, Float>() }
                CCard(Modifier.fillMaxWidth().animateContentSize(Springs.soft()), padding = PaddingValues(horizontal = Space.l, vertical = Space.xs)) {
                    order.forEachIndexed { n, i ->
                        val l = lines[i]
                        androidx.compose.runtime.key(l.key) {
                            Column(Modifier.flip(l.key, placed)) {
                                if (n > 0) Divider()
                                val on = l.key in checked
                                LineRow(l, costs[i], byPacks, on) { scope.launch { if (on) shop.uncheck(l.key) else shop.check(ShopCheck(l.key)) } }
                            }
                        }
                    }
                    Divider()
                    Row(Modifier.fillMaxWidth().padding(vertical = Space.m), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("סה״כ משוער", style = TextStyles.heading, color = c.ink)
                            val missing = costs.count { it == null }
                            Text(if (missing > 0) "בלי $missing פריטים שאין להם מחיר" else "לפי המחיר של כל מוצר", style = TextStyles.label, color = if (missing > 0) c.danger else c.inkDim)
                        }
                        Text(formatShekel(total), style = TextStyles.title, color = c.ink)
                    }
                }
                Spacer(Modifier.height(Space.xl))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("איפה הכי זול", Modifier.weight(1f))
                    Box(Modifier.padding(bottom = Space.s).clip(RoundedCornerShape(99.dp)).pressable(onPickCity)) { Pill("📍 ${city.ifBlank { "בחירת עיר" }}") }
                }
                Basket(city, codes.size, lines.size, offers, onPickCity)
                Spacer(Modifier.height(Space.xl))
                GhostButton("ניקוי הרשימה", onClear, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun Divider() = Box(Modifier.fillMaxWidth().height(1.dp).background(C.colors.divider))

private fun multText(m: Double) = when (m) {
    0.5 -> "חצי מתכון"; 1.0 -> "מתכון אחד"; 2.0 -> "כפול"; 3.0 -> "פי 3"
    else -> "×" + String.format(Locale.US, "%.1f", m).trimEnd('0').trimEnd('.')
}

private fun packsText(n: Int) = if (n == 1) "אריזה אחת" else "$n אריזות"

@Composable
private fun EntryRow(
    app: CookiesApp, e: ShopEntry, recipes: Map<Long, RecipeRow>, products: Map<Long, com.maites.cookies.data.ProductEntity>,
    onRecipe: (Long) -> Unit, onProduct: (Long) -> Unit, onQty: (Double) -> Unit,
) {
    val c = C.colors
    val r = e.recipeId?.let(recipes::get)
    val p = e.productId?.let(products::get)
    Row(Modifier.fillMaxWidth().padding(vertical = Space.m), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).pressable({ r?.let { onRecipe(it.id) } ?: p?.let { onProduct(it.id) } }), verticalAlignment = Alignment.CenterVertically) {
            if (r != null) RecipePhoto(r.photos.split('\n').firstOrNull { it.isNotBlank() }, r.category, Modifier.size(44.dp), corner = 14.dp, iconSize = 30.dp)
            else if (p != null) ProductImage(p, Modifier.size(44.dp), corner = 14.dp, iconSize = 30.dp)
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                Text(r?.name ?: p?.name.orEmpty(), style = TextStyles.heading, color = c.ink, maxLines = 1)
                val sub = when {
                    r != null && r.yieldCount > 0 -> "${Math.round(r.yieldCount * e.qty)} ${r.yieldLabel.ifBlank { "יחידות" }} · ${multText(e.qty)}"
                    r != null -> multText(e.qty)
                    p != null -> packsText(e.qty.toInt()) + " · " + formatPack(p.packAmount, p.unit)
                    else -> ""
                }
                Text(sub, style = TextStyles.label, color = c.inkDim, maxLines = 1)
            }
        }
        // Recipes step by half a batch, products by one pack; below the minimum removes the entry.
        val step = if (r != null) 0.5 else 1.0
        RoundButton("−", { onQty(if (e.qty - step < step - 1e-9) 0.0 else e.qty - step) }, Modifier.size(38.dp))
        Spacer(Modifier.width(Space.s))
        RoundButton("+", { if (e.qty < 99) onQty(e.qty + step) }, Modifier.size(38.dp))
    }
}

@Composable
private fun LineRow(l: ShopLine, cost: Long?, byPacks: Boolean, checked: Boolean, onToggle: () -> Unit) {
    val c = C.colors
    val haptics = C.haptics
    Row(Modifier.fillMaxWidth().pressable({ haptics.toggle(!checked); onToggle() }, scale = 0.97f, haptic = false).padding(vertical = Space.m), verticalAlignment = Alignment.CenterVertically) {
        com.maites.cookies.design.CheckDot(checked)
        Spacer(Modifier.width(Space.m))
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(l.product?.let { categoryTint(it.category) } ?: c.surface2), contentAlignment = Alignment.Center) {
            Text(l.icon, style = TextStyles.heading)
        }
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            Text(
                l.name, style = TextStyles.heading.copy(textDecoration = if (checked) TextDecoration.LineThrough else null),
                color = if (checked) c.inkFaint else c.ink, maxLines = 1,
            )
            val packs = l.packs
            val sub = when {
                l.product == null -> formatPack(l.need, l.unit) + " · לא מקושר למוצר"
                byPacks && packs != null -> packsText(packs) + " · צריך " + formatPack(l.need, l.unit)
                else -> formatPack(l.need, l.unit)
            }
            Text(sub, style = TextStyles.label, color = c.inkDim, maxLines = 1)
        }
        Text(cost?.let { formatShekel(it) } ?: "—", style = TextStyles.heading, color = if (cost != null && !checked) c.ink else c.inkFaint)
    }
}

/** Chains ranked by the whole basket in the chosen city. */
@Composable
private fun Basket(city: String, coded: Int, lines: Int, offers: List<Shopping.Offer>, onPickCity: () -> Unit) {
    val c = C.colors
    when {
        city.isBlank() -> CCard(Modifier.fillMaxWidth()) {
            Text("באיזו עיר קונים?", style = TextStyles.heading, color = c.ink)
            Text("נשווה את הסל בכל הרשתות שיש להן סניף בעיר.", style = TextStyles.label, color = c.inkDim)
            Spacer(Modifier.height(Space.m))
            StickerButton("בחירת עיר", onPickCity, Modifier.fillMaxWidth())
        }
        coded == 0 -> CCard(Modifier.fillMaxWidth()) {
            Text("אין עם מה להשוות", style = TextStyles.heading, color = c.ink)
            Text("אף מוצר ברשימה לא מחובר למחירי הרשתות. מחברים בדף המוצר ← \"חיפוש במחירון\".", style = TextStyles.label, color = c.inkDim)
        }
        offers.isEmpty() -> CCard(Modifier.fillMaxWidth()) {
            Text("אין מחירים ב$city", style = TextStyles.heading, color = c.ink)
            Text("לרשתות בעיר עוד אין מחירים למוצרים האלה. המחירים מתעדכנים פעם ביום.", style = TextStyles.label, color = c.inkDim)
        }
        else -> Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            offers.forEachIndexed { i, o ->
                val ch = Chain.of(o.chain)
                CCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(chainTint(o.chain)), contentAlignment = Alignment.Center) {
                            Text(ch?.emoji ?: "🛒", style = TextStyles.heading)
                        }
                        Spacer(Modifier.width(Space.m))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                                Text(ch?.label ?: o.chain, style = TextStyles.heading, color = c.ink)
                                if (i == 0) Pill("הכי זול", c.goodSoft, c.good)
                            }
                            Text(o.store, style = TextStyles.label, color = c.inkDim, maxLines = 1)
                            Text(
                                if (o.missing == 0) "כל ${o.of} המוצרים" else "חסרים ${o.missing} מתוך ${o.of}",
                                style = TextStyles.label, color = if (o.missing == 0) c.good else c.danger,
                            )
                        }
                        Text(formatShekel(o.total), style = TextStyles.title, color = c.ink)
                    }
                }
            }
            if (coded < lines) Text(
                "${lines - coded} פריטים בלי חיבור לרשתות לא נספרים בהשוואה.",
                style = TextStyles.label, color = c.inkDim, modifier = Modifier.padding(top = Space.xs, start = Space.xs),
            )
        }
    }
}

private enum class AddKind { Recipes, Products }

/** Picker: add a recipe (one batch) or a product (one pack) to the list. */
@Composable
fun ShopAddSheet(app: CookiesApp, open: Boolean, onDismiss: () -> Unit, toast: (String) -> Unit) {
    val c = C.colors
    val recipes by app.db.recipes().rows().collectAsStateWithLifecycle(emptyList())
    val products by app.db.products().all().collectAsStateWithLifecycle(emptyList())
    var kind by remember { mutableStateOf(AddKind.Recipes) }
    var q by remember(open) { mutableStateOf("") }
    CSheet(open, onDismiss) {
        Text("מה מוסיפים לרשימה?", style = TextStyles.title, color = c.ink)
        Spacer(Modifier.height(Space.l))
        Segmented(AddKind.entries, kind, { if (it == AddKind.Recipes) "מתכונים" else "מוצרים" }, { kind = it }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(Space.m))
        SearchBox(q, { q = it }, if (kind == AddKind.Recipes) "חיפוש מתכון" else "חיפוש מוצר")
        Spacer(Modifier.height(Space.m))
        val t = q.trim()
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (kind == AddKind.Recipes) {
                val list = recipes.filter { t.isEmpty() || it.name.contains(t, true) }
                items(list, key = { "r${it.id}" }) { r ->
                    PickRow({ RecipePhoto(r.photos.split('\n').firstOrNull { it.isNotBlank() }, r.category, Modifier.size(40.dp), corner = 14.dp, iconSize = 28.dp) }, r.name, yieldText(r.yieldCount, r.yieldLabel) ?: "${r.count} מרכיבים") {
                        app.applicationScope.launch { app.db.shop().addRecipe(r.id, 1.0) }
                        onDismiss(); toast("${r.name} נוסף לקניות")
                    }
                }
                if (list.isEmpty()) item { Text(if (recipes.isEmpty()) "עוד אין מתכונים" else "לא נמצא מתכון", style = TextStyles.body, color = c.inkDim, modifier = Modifier.padding(Space.l)) }
            } else {
                val list = products.filter { t.isEmpty() || it.name.contains(t, true) || it.brand.contains(t, true) }
                items(list, key = { "p${it.id}" }) { p ->
                    PickRow({ ProductImage(p, Modifier.size(40.dp), corner = 14.dp, iconSize = 28.dp) }, p.name, formatPack(p.packAmount, p.unit)) {
                        app.applicationScope.launch { app.db.shop().addProduct(p.id) }
                        onDismiss(); toast("${p.name} נוסף לקניות")
                    }
                }
                if (list.isEmpty()) item { Text(if (products.isEmpty()) "עוד אין מוצרים" else "לא נמצא מוצר", style = TextStyles.body, color = c.inkDim, modifier = Modifier.padding(Space.l)) }
            }
        }
    }
}

@Composable
private fun PickRow(thumb: @Composable () -> Unit, title: String, sub: String, onClick: () -> Unit) {
    val c = C.colors
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).pressable(onClick).padding(horizontal = Space.s, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        thumb()
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            Text(title, style = TextStyles.heading, color = c.ink, maxLines = 1)
            Text(sub, style = TextStyles.label, color = c.inkDim, maxLines = 1)
        }
        Text("+", style = TextStyles.title, color = c.accentInk)
    }
}

@Composable
fun ShopClearSheet(app: CookiesApp, open: Boolean, onDismiss: () -> Unit, toast: (String) -> Unit) {
    val c = C.colors
    CSheet(open, onDismiss) {
        Text("לנקות את הרשימה?", style = TextStyles.title, color = c.ink)
        Text("המתכונים והמוצרים עצמם לא נמחקים — רק הרשימה.", style = TextStyles.body, color = c.inkDim)
        Spacer(Modifier.height(Space.xl))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
            GhostButton("ביטול", onDismiss, Modifier.weight(1f))
            StickerButton("ניקוי", {
                app.applicationScope.launch { app.db.shop().clear() }
                onDismiss(); toast("הרשימה נוקתה")
            }, Modifier.weight(1f))
        }
    }
}
