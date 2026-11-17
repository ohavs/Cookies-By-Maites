package com.maites.cookies.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maites.cookies.CookiesApp
import com.maites.cookies.data.BaseUnit
import com.maites.cookies.data.Density
import com.maites.cookies.data.Measure
import com.maites.cookies.data.baseFor
import com.maites.cookies.data.inProduct
import com.maites.cookies.data.m
import com.maites.cookies.data.IngredientEntity
import com.maites.cookies.data.ProductEntity
import com.maites.cookies.data.Cats
import com.maites.cookies.data.KIND_RECIPES
import com.maites.cookies.design.ConfirmSheet
import com.maites.cookies.data.RecipeEntity
import com.maites.cookies.data.RecipePhotos
import com.maites.cookies.data.ingredientCost
import com.maites.cookies.design.C
import com.maites.cookies.design.CCard
import com.maites.cookies.design.CChip
import com.maites.cookies.design.CSheet
import com.maites.cookies.design.CTextArea
import com.maites.cookies.design.CTextField
import com.maites.cookies.design.GhostButton
import com.maites.cookies.design.KeypadSheet
import com.maites.cookies.design.SearchBox
import com.maites.cookies.design.SectionLabel
import com.maites.cookies.design.Segmented
import com.maites.cookies.design.Space
import com.maites.cookies.design.StickerButton
import com.maites.cookies.design.TextStyles
import com.maites.cookies.design.pressable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val ICONS = listOf("🌾", "🧈", "🥚", "🥛", "🍫", "🍬", "🍯", "🧂", "🥜", "🌰", "🥥", "🍋", "🍓", "🫙", "🍪", "🥣")
private const val NEW = -1

/** New / edit recipe. [id] null = new. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun RecipeEditor(app: CookiesApp, id: Long?, onClose: () -> Unit, onSaved: (Long) -> Unit, toast: (String) -> Unit) {
    val c = C.colors
    val dao = app.db.recipes()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val products by app.db.products().all().collectAsStateWithLifecycle(emptyList())
    val byId = remember(products) { products.associateBy { it.id } }
    val prices = rememberPackPrices(app)

    var loaded by remember { mutableStateOf(id == null) }
    var original by remember { mutableStateOf<RecipeEntity?>(null) }
    var name by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf(Cats.firstKey(KIND_RECIPES)) }
    var yieldCount by remember { mutableStateOf(0) }
    var yieldLabel by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    val photos = remember { mutableStateListOf<String>() }
    val items = remember { mutableStateListOf<IngredientEntity>() }

    var yieldPad by remember { mutableStateOf(false) }
    var chooserFor by remember { mutableStateOf<Int?>(null) }
    var amountFor by remember { mutableStateOf<Int?>(null) }
    var editIdx by remember { mutableStateOf<Int?>(null) }
    var cupFor by remember { mutableStateOf<ProductEntity?>(null) }
    var importing by remember { mutableStateOf(0) }
    var removePhoto by remember { mutableStateOf<String?>(null) }
    var removeItem by remember { mutableStateOf<Int?>(null) }
    var askLeave by remember { mutableStateOf(false) }
    var initial by remember { mutableStateOf<List<Any?>?>(null) }
    fun snapshot(): List<Any?> = listOf(name.trim(), cat, yieldCount, yieldLabel.trim(), notes.trim(), photos.toList(), items.toList())

    LaunchedEffect(id) {
        if (id != null) dao.byId(id).first()?.let { r ->
            original = r; name = r.name; cat = r.category; yieldCount = r.yieldCount; yieldLabel = r.yieldLabel; notes = r.notes
            photos.addAll(r.photoList); items.addAll(dao.ingredients(id).first())
        }
        initial = snapshot()
        loaded = true
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(8)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        importing += uris.size
        scope.launch {
            uris.forEach { u -> RecipePhotos.import(ctx, u)?.let(photos::add) ?: toast("לא הצלחנו לטעון תמונה"); importing-- }
        }
    }

    if (!loaded) return
    val valid = name.isNotBlank()
    val dirty = initial != null && initial != snapshot()
    LeaveGuard(dirty) { askLeave = true }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.m), verticalAlignment = Alignment.CenterVertically) {
                RoundButton("✕", { if (dirty) askLeave = true else onClose() })
                Spacer(Modifier.size(Space.m))
                Text(if (id == null) "מתכון חדש" else "עריכת מתכון", style = TextStyles.title, color = c.ink)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Space.gutter)) {
                SectionLabel("תמונות")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    photos.forEachIndexed { i, path ->
                        PhotoThumb(path, cat, main = i == 0,
                            onMain = { if (i > 0) { photos.removeAt(i); photos.add(0, path); toast("נבחרה תמונה ראשית") } },
                            onRemove = { removePhoto = path })
                    }
                    repeat(importing) { Box(Modifier.size(96.dp).clip(RoundedCornerShape(18.dp)).background(c.surface2)) }
                    Box(
                        Modifier.size(96.dp).clip(RoundedCornerShape(18.dp)).background(c.surface).border(2.dp, c.divider, RoundedCornerShape(18.dp))
                            .pressable({ pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("📷", style = TextStyles.title)
                            Text("הוספה", style = TextStyles.caption, color = c.inkDim)
                        }
                    }
                }
                if (photos.size > 1) Text("נגיעה בתמונה הופכת אותה לראשית", style = TextStyles.caption, color = c.inkFaint, modifier = Modifier.padding(top = Space.xs))

                Spacer(Modifier.height(Space.l))
                CTextField(name, { name = it }, "שם המתכון", placeholder = "למשל: עוגיות שוקולד צ׳יפס")
                Spacer(Modifier.height(Space.l))
                SectionLabel("קטגוריה")
                CategoryChips(app, KIND_RECIPES, cat) { cat = it }

                Spacer(Modifier.height(Space.l))
                SectionLabel("כמה יוצא")
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.Bottom) {
                    Box(Modifier.weight(1f)) { ValueBox(if (yieldCount > 0) "$yieldCount" else "כמות", yieldCount == 0) { yieldPad = true } }
                    CTextField(yieldLabel, { yieldLabel = it }, "", Modifier.weight(1.4f), placeholder = "עוגיות")
                }

                Spacer(Modifier.height(Space.l))
                SectionLabel("מרכיבים")
                CCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = Space.l, vertical = Space.xs)) {
                    items.forEachIndexed { idx, i ->
                        if (idx > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
                        val p = i.productId?.let(byId::get)
                        IngredientLine(i, p, ingredientCost(i, p, i.productId?.let(prices::get)), onClick = { editIdx = idx })
                    }
                    if (items.isNotEmpty()) Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
                    Row(Modifier.fillMaxWidth().pressable({ chooserFor = NEW }).padding(vertical = Space.m), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(c.accent), contentAlignment = Alignment.Center) {
                            Text("+", style = TextStyles.title, color = c.accentFg)
                        }
                        Spacer(Modifier.width(Space.m))
                        Text("הוספת מרכיב", style = TextStyles.heading, color = c.accentInk)
                    }
                }

                Spacer(Modifier.height(Space.l))
                CTextArea(notes, { notes = it }, "הערות", placeholder = "טמפרטורה, זמן אפייה, טיפים…")
                Spacer(Modifier.height(Space.xl))
            }
            Box(Modifier.padding(horizontal = Space.gutter, vertical = Space.m)) {
                StickerButton(if (id == null) "שמירת המתכון" else "שמירה", {
                    scope.launch {
                        val base = original ?: RecipeEntity(name = "")
                        val r = base.copy(
                            name = name.trim(), category = cat, yieldCount = yieldCount, yieldLabel = yieldLabel.trim(),
                            notes = notes.trim(), photos = photos.joinToString("\n"), updatedAt = System.currentTimeMillis(),
                        )
                        val newId = dao.save(r, items.filter { it.amount > 0 })
                        app.applicationScope.launch { RecipePhotos.prune(ctx, dao.rows().first().flatMap { it.photos.split('\n') }.toSet()) }
                        toast(if (id == null) "${r.name} נשמר" else "נשמר")
                        onSaved(newId)
                    }
                }, Modifier.fillMaxWidth(), enabled = valid)
            }
        }

        KeypadSheet(
            open = yieldPad, title = "כמה יוצא", lead = "מספר היחידות שהמתכון מוציא",
            initial = yieldCount.takeIf { it > 0 }?.toLong(), prefix = "", suffix = yieldLabel.ifBlank { "יחידות" }, decimals = 0,
            onDismiss = { yieldPad = false },
        ) { v -> yieldPad = false; yieldCount = v.toInt() }

        ProductChooser(
            open = chooserFor != null, products = products, onDismiss = { chooserFor = null },
            onPick = { p, free ->
                val target = chooserFor ?: NEW
                chooserFor = null
                val line = IngredientEntity(
                    recipeId = 0, position = 0, productId = p?.id, name = p?.name ?: free,
                    amount = 0.0, unit = p?.unit ?: BaseUnit.Gram, icon = p?.let { com.maites.cookies.data.Cats.product(it.category).emoji } ?: "🥣",
                )
                if (target == NEW) { items.add(line); amountFor = items.lastIndex }
                else {
                    val old = items[target]
                    items[target] = line.copy(amount = old.amount, measure = old.m.name, unit = old.m.baseFor(p))
                    if (items[target].amount == 0.0) amountFor = target
                }
            },
        )

        ConfirmSheet(removePhoto != null, "להסיר את התמונה?", "היא תימחק מהמתכון כשתשמרי.", "הסרה", { removePhoto = null }) {
            removePhoto?.let { photos.remove(it) }; removePhoto = null
        }
        val removing = removeItem?.let { items.getOrNull(it) }
        ConfirmSheet(removing != null, "להסיר את ${removing?.let { i -> byId[i.productId]?.name ?: i.name }.orEmpty()}?", "המרכיב יוסר מהמתכון.", "הסרה", { removeItem = null }) {
            removeItem?.let { if (it in items.indices) items.removeAt(it) }; removeItem = null
        }
        ConfirmSheet(askLeave, "לצאת בלי לשמור?", "השינויים במתכון לא יישמרו.", "יציאה", { askLeave = false }, cancel = "להמשיך לערוך") {
            askLeave = false; initial = null; onClose()
        }

        val amountLine = amountFor?.let { items.getOrNull(it) }
        KeypadSheet(
            open = amountLine != null, title = "כמות", lead = amountLine?.let { byId[it.productId]?.name ?: it.name }.orEmpty(),
            initial = amountLine?.amount?.takeIf { it > 0 }?.let { Math.round(it * 100) }, prefix = "", suffix = amountLine?.m?.many.orEmpty(),
            onDismiss = {
                amountFor?.let { i -> if (items.getOrNull(i)?.amount == 0.0) items.removeAt(i) }
                amountFor = null
            },
        ) { v ->
            val i = amountFor
            amountFor = null
            if (i != null && v > 0) items[i] = items[i].copy(amount = v / 100.0) else if (i != null && items[i].amount == 0.0) items.removeAt(i)
        }

        val cupP = cupFor
        KeypadSheet(
            open = cupP != null, title = "כמה גרם בכוס?", lead = cupP?.let { "כוס ${it.name} (240 מ״ל) · נשמר במוצר" }.orEmpty(),
            initial = cupP?.let { Math.round(Density.cup(it)) }, prefix = "", suffix = "גרם", decimals = 0,
            onDismiss = { cupFor = null },
        ) { v ->
            val prod = cupFor
            cupFor = null
            if (prod != null && v > 0) scope.launch { app.db.products().put(prod.copy(gramsPerCup = v.toDouble(), dirty = true, updatedAt = System.currentTimeMillis())) }
        }

        val editing = editIdx?.let { items.getOrNull(it) }
        CSheet(editing != null, { editIdx = null }) {
            if (editing == null) return@CSheet
            val idx = editIdx ?: return@CSheet
            val p = editing.productId?.let(byId::get)
            Text(p?.name ?: editing.name, style = TextStyles.title, color = c.ink)
            Text(if (p != null) "מקושר למוצר · המחיר נמשך ממנו" else "לא מקושר למוצר — אין מחיר", style = TextStyles.body, color = c.inkDim)
            Spacer(Modifier.height(Space.l))
            SectionLabel("יחידה")
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Measure.entries.forEach { m ->
                    CChip(m.one, m == editing.m, {
                        val amount = if (m.isPinch) 1.0 else if (editing.m.isPinch) 0.0 else editing.amount
                        items[idx] = editing.copy(measure = m.name, unit = m.baseFor(p), amount = amount)
                        if (amount == 0.0) { editIdx = null; amountFor = idx }
                    })
                }
            }
            Spacer(Modifier.height(Space.m))
            if (!editing.m.isPinch) ValueBox(formatMeasure(editing.amount, editing.m), false) { editIdx = null; amountFor = idx }
            if (p != null) {
                val m = editing.m
                val crosses = (m.unit == BaseUnit.Milliliter && p.unit == BaseUnit.Gram) || (m.unit == BaseUnit.Gram && p.unit == BaseUnit.Milliliter)
                when {
                    crosses -> {
                        Spacer(Modifier.height(Space.s))
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).pressable({ cupFor = p }).padding(Space.m),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("כוס ${p.name} = ${Math.round(Density.cup(p))} גרם", style = TextStyles.label, color = c.ink)
                                Text(if (Density.isGuess(p)) "הערכה · לחצי לדיוק" else "נשמר במוצר · לכל המתכונים", style = TextStyles.label, color = c.inkDim)
                            }
                            Text("שינוי", style = TextStyles.label, color = c.accentInk)
                        }
                    }
                    editing.inProduct(p) == null -> {
                        Spacer(Modifier.height(Space.s))
                        Text("אין המרה מ${m.one} ל${unitLabel(p.unit)} — המרכיב יופיע בלי מחיר", style = TextStyles.label, color = c.inkDim)
                    }
                }
            }
            Spacer(Modifier.height(Space.l))
            SectionLabel("אייקון")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                ICONS.forEach { e ->
                    val on = e == editing.icon
                    Box(
                        Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(if (on) c.accent else c.surface)
                            .pressable({ items[idx] = editing.copy(icon = e) }),
                        contentAlignment = Alignment.Center,
                    ) { Text(e, style = TextStyles.heading) }
                }
            }
            Spacer(Modifier.height(Space.xl))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                GhostButton("הסרה", { editIdx = null; removeItem = idx }, Modifier.weight(1f))
                GhostButton(if (p != null) "החלפת מוצר" else "קישור למוצר", { editIdx = null; chooserFor = idx }, Modifier.weight(1f))
            }
        }
    }
}

/** Search the product list; or use the typed text as a free ingredient. */
@Composable
private fun ProductChooser(open: Boolean, products: List<ProductEntity>, onDismiss: () -> Unit, onPick: (ProductEntity?, String) -> Unit) {
    val c = C.colors
    var q by remember(open) { mutableStateOf("") }
    CSheet(open, onDismiss) {
        Text("איזה מרכיב?", style = TextStyles.title, color = c.ink)
        Spacer(Modifier.height(Space.m))
        SearchBox(q, { q = it }, "חיפוש מוצר")
        Spacer(Modifier.height(Space.s))
        val needle = q.trim().lowercase()
        val shown = products.filter { needle.isEmpty() || it.name.lowercase().contains(needle) || it.brand.lowercase().contains(needle) }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
            items(shown, key = { it.id }) { p ->
                Row(Modifier.fillMaxWidth().pressable({ onPick(p, "") }).padding(vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
                    ProductImage(p, Modifier.size(44.dp), corner = 14.dp, iconSize = 30.dp)
                    Spacer(Modifier.width(Space.m))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = TextStyles.heading, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(formatPack(p.packAmount, p.unit) + if (p.brand.isNotBlank()) " · ${p.brand}" else "", style = TextStyles.label, color = c.inkDim, maxLines = 1)
                    }
                }
            }
            if (needle.isNotEmpty()) item {
                Row(Modifier.fillMaxWidth().pressable({ onPick(null, q.trim()) }).padding(vertical = Space.m), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(c.surface2), contentAlignment = Alignment.Center) { Text("✍️", style = TextStyles.heading) }
                    Spacer(Modifier.width(Space.m))
                    Column {
                        Text("„${q.trim()}“", style = TextStyles.heading, color = c.ink)
                        Text("מרכיב בלי מוצר (בלי מחיר)", style = TextStyles.label, color = c.inkDim)
                    }
                }
            }
            if (shown.isEmpty() && needle.isEmpty()) item {
                Text("עוד אין מוצרים. מקלידים שם כדי להוסיף מרכיב חופשי.", style = TextStyles.body, color = c.inkDim, modifier = Modifier.padding(vertical = Space.m))
            }
        }
    }
}

@Composable
private fun PhotoThumb(path: String, cat: String, main: Boolean, onMain: () -> Unit, onRemove: () -> Unit) {
    val c = C.colors
    Box(Modifier.size(96.dp)) {
        RecipePhoto(path, cat, Modifier.fillMaxSize().pressable(onMain), corner = 18.dp)
        if (main) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp).clip(CircleShape).background(c.surface).padding(horizontal = 8.dp, vertical = 2.dp)) {
            Text("ראשית", style = TextStyles.caption, color = c.ink)
        }
        Box(
            Modifier.align(Alignment.TopEnd).padding(4.dp).size(26.dp).clip(CircleShape).background(c.surface).pressable(onRemove),
            contentAlignment = Alignment.Center,
        ) { Text("✕", style = TextStyles.caption, color = c.inkDim) }
    }
}

@Composable
fun ValueBox(text: String, placeholder: Boolean, onClick: () -> Unit) {
    val c = C.colors
    Row(
        Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(16.dp)).background(c.surface).pressable(onClick).padding(horizontal = Space.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = if (placeholder) TextStyles.body else TextStyles.heading, color = if (placeholder) c.inkFaint else c.ink, modifier = Modifier.weight(1f))
        Text("‹", style = TextStyles.title, color = c.inkFaint)
    }
}
