package com.maites.cookies.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.maites.cookies.CookiesApp
import com.maites.cookies.data.BaseUnit
import com.maites.cookies.data.OpenFoodFacts
import com.maites.cookies.data.CatalogItem
import com.maites.cookies.data.Cats
import com.maites.cookies.data.KIND_PRODUCTS
import com.maites.cookies.design.ConfirmSheet
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
import com.maites.cookies.data.ProductEntity
import com.maites.cookies.design.C
import com.maites.cookies.design.CCard
import com.maites.cookies.design.CChip
import com.maites.cookies.design.CTextField
import com.maites.cookies.design.KeypadSheet
import com.maites.cookies.design.SectionLabel
import com.maites.cookies.design.Segmented
import com.maites.cookies.design.Space
import com.maites.cookies.design.StickerButton
import com.maites.cookies.design.TextStyles
import com.maites.cookies.design.pressable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private enum class Pad { None, Pack, Price }

/**
 * New / edit product. [id] null = new. [barcode] = start from a scanned code (fills from Open Food Facts).
 * [onSaved] gets the product id; [onExisting] when the scanned barcode is already in the list.
 */
@Composable
fun ProductEditor(
    app: CookiesApp,
    id: Long?,
    barcode: String?,
    onClose: () -> Unit,
    onSaved: (Long) -> Unit,
    onExisting: (Long) -> Unit,
    toast: (String) -> Unit,
) {
    val c = C.colors
    val dao = app.db.products()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var loaded by remember { mutableStateOf(id == null) }
    var original by remember { mutableStateOf<ProductEntity?>(null) }
    var name by remember { mutableStateOf("") }
    var brand by remember { mutableStateOf("") }
    var code by remember { mutableStateOf(barcode ?: "") }
    var cat by remember { mutableStateOf(Cats.firstKey(KIND_PRODUCTS)) }
    var unit by remember { mutableStateOf(BaseUnit.Gram) }
    var pack by remember { mutableStateOf<Double?>(null) }
    var price by remember { mutableStateOf<Long?>(null) }
    var image by remember { mutableStateOf<String?>(null) }
    var looking by remember { mutableStateOf(false) }
    var pad by remember { mutableStateOf(Pad.None) }
    var catalogOpen by remember { mutableStateOf(false) }
    var nameFocused by remember { mutableStateOf(false) }
    var typingCode by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf<List<CatalogItem>>(emptyList()) }
    var askLeave by remember { mutableStateOf(false) }
    var initial by remember { mutableStateOf<List<Any?>?>(null) }
    val nameFocus = remember { FocusRequester() }
    val codeFocus = remember { FocusRequester() }
    fun snapshot(): List<Any?> = listOf(name.trim(), brand.trim(), code, cat, unit, pack, price, image)

    LaunchedEffect(id) {
        if (id != null) dao.byId(id).first()?.let { p ->
            original = p; name = p.name; brand = p.brand; code = p.barcode ?: ""; cat = p.category
            unit = p.unit; pack = p.packAmount; price = p.priceAgorot; image = p.imageUrl
        }
        initial = snapshot()
        loaded = true
    }

    suspend fun fill(barcodeValue: String) {
        val existing = dao.byBarcode(barcodeValue)
        if (existing != null && existing.id != id) { toast("המוצר כבר ברשימה"); onExisting(existing.id); return }
        code = barcodeValue
        looking = true
        val off = OpenFoodFacts.lookup(barcodeValue)
        looking = false
        if (off == null) { toast("לא מצאנו פרטים — ממלאים ידנית"); return }
        if (name.isBlank()) name = off.name
        if (brand.isBlank()) brand = off.brand
        if (off.packAmount != null && off.unit != null && pack == null) { pack = off.packAmount; unit = off.unit }
        if (off.imageUrl != null) image = off.imageUrl
        toast("נמצא: ${off.name.ifBlank { barcodeValue }}")
    }

    LaunchedEffect(barcode) { if (barcode != null && id == null) fill(barcode) }

    // Live suggestions from the chains' catalog while typing the name.
    LaunchedEffect(name, code, nameFocused) {
        val q = name.trim()
        if (code.isNotBlank() || !nameFocused || q.length < 2) { suggestions = emptyList(); return@LaunchedEffect }
        delay(220)
        if (app.db.catalog().count() == 0) { app.applicationScope.launch { app.catalog.sync(force = true) }; return@LaunchedEffect }
        suggestions = app.db.catalog().search(q).take(4)
    }
    LaunchedEffect(Unit) { if (id == null && barcode == null) { delay(350); nameFocus.requestFocus() } }

    fun pick(item: CatalogItem) {
        scope.launch {
            val other = dao.byBarcode(item.code)
            if (other != null && other.id != id) { toast("המוצר כבר ברשימה"); onExisting(other.id); return@launch }
            code = item.code
            name = item.name
            if (brand.isBlank()) brand = item.brand
            val u = item.baseUnit
            if (pack == null && u != null && item.amount > 0) { pack = item.amount; unit = u }
            toast("חובר: ${item.name}")
            if (image == null) OpenFoodFacts.lookup(item.code)?.imageUrl?.let { if (image == null && code == item.code) image = it }
        }
    }

    val scan: () -> Unit = {
        scope.launch {
            when (val r = scanBarcode(ctx)) {
                is ScanResult.Code -> fill(r.value)
                is ScanResult.Failed -> toast("הסריקה לא זמינה במכשיר")
                ScanResult.Cancelled -> Unit
            }
        }
    }

    val valid = name.isNotBlank() && (pack ?: 0.0) > 0
    if (!loaded) return
    val dirty = initial != null && initial != snapshot()
    LeaveGuard(dirty) { askLeave = true }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.m), verticalAlignment = Alignment.CenterVertically) {
                RoundButton("✕", { if (dirty) askLeave = true else onClose() })
                Spacer(Modifier.size(Space.m))
                Text(if (id == null) "מוצר חדש" else "עריכת מוצר", style = TextStyles.title, color = c.ink)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Space.gutter)) {
                if (looking) { Text("מחפשים את המוצר…", style = TextStyles.label, color = c.accentInk); Spacer(Modifier.height(Space.s)) }

                CTextField(name, { name = it }, "שם המוצר", placeholder = "למשל: חמאה", focus = nameFocus, onFocus = { nameFocused = it })
                if (suggestions.isNotEmpty()) {
                    Spacer(Modifier.height(Space.s))
                    CCard(Modifier.fillMaxWidth(), padding = androidx.compose.foundation.layout.PaddingValues(horizontal = Space.m, vertical = Space.xs)) {
                        suggestions.forEach { i -> SuggestionRow(i) { pick(i) } }
                        Row(
                            Modifier.fillMaxWidth().pressable({ catalogOpen = true }, scale = 0.98f).padding(vertical = Space.m),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("כל התוצאות במחירון", style = TextStyles.label, color = c.accentInk, modifier = Modifier.weight(1f))
                            Text("‹", style = TextStyles.heading, color = c.accentInk)
                        }
                    }
                }
                Spacer(Modifier.height(Space.l))
                CTextField(brand, { brand = it }, "מותג", placeholder = "לא חובה")
                Spacer(Modifier.height(Space.l))
                if (code.isBlank() && !typingCode) {
                    SectionLabel("ברקוד")
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        CodeTile("📷", "סריקה", c.tSky, Modifier.weight(1f), scan)
                        CodeTile("⌨️", "הקלדה", c.surface, Modifier.weight(1f)) { typingCode = true }
                    }
                } else {
                    CTextField(code, { v -> code = v.filter { it.isDigit() } }, "ברקוד", placeholder = "ספרות בלבד", ltr = true, focus = codeFocus, keyboardType = KeyboardType.Number)
                    LaunchedEffect(typingCode) { if (typingCode && code.isBlank()) { delay(50); codeFocus.requestFocus() } }
                    Row(Modifier.padding(top = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.l)) {
                        Text("סריקה מחדש", style = TextStyles.label, color = c.accentInk, modifier = Modifier.pressable(scan).padding(vertical = Space.xs))
                        Text("ניקוי", style = TextStyles.label, color = c.inkDim, modifier = Modifier.pressable({ code = ""; typingCode = false }).padding(vertical = Space.xs))
                    }
                }

                Spacer(Modifier.height(Space.l))
                SectionLabel("קטגוריה")
                CategoryChips(app, KIND_PRODUCTS, cat) { cat = it }

                Spacer(Modifier.height(Space.l))
                SectionLabel("גודל אריזה")
                Segmented(BaseUnit.entries, unit, ::unitLabel, { unit = it }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(Space.s))
                ValueRow(pack?.let { formatPack(it, unit) } ?: "הקלדת גודל", pack == null) { pad = Pad.Pack }

                Spacer(Modifier.height(Space.l))
                SectionLabel("מחיר לאריזה")
                ValueRow(price?.let { formatShekel(it) } ?: "לא חובה — אפשר גם אחר כך", price == null) { pad = Pad.Price }
                Spacer(Modifier.height(Space.xl))
            }
            Box(Modifier.padding(horizontal = Space.gutter, vertical = Space.m)) {
                StickerButton(if (id == null) "הוספה לרשימה" else "שמירה", {
                    scope.launch {
                        val clean = code.trim().ifBlank { null }
                        if (clean != null) dao.byBarcode(clean)?.let { if (it.id != id) { toast("הברקוד כבר שייך ל${it.name}"); return@launch } }
                        val base = original ?: ProductEntity(name = "", packAmount = 0.0, unit = unit)
                        val saved = base.copy(
                            name = name.trim(), brand = brand.trim(), barcode = clean, category = cat, unit = unit,
                            packAmount = pack ?: 0.0, priceAgorot = price, imageUrl = image, updatedAt = System.currentTimeMillis(),
                        )
                        val newId = dao.upsert(saved)
                        if (image?.startsWith("http") == true) app.applicationScope.launch { com.maites.cookies.data.ProductPhotos.localizeAll(app, dao) }
                        if (clean != null && clean != original?.barcode) app.applicationScope.launch { app.priceSync.watchNow(clean); app.priceSync.sync(force = true) }
                        toast(if (id == null) "${saved.name} נוסף" else "נשמר")
                        onSaved(if (id == null) newId else id)
                    }
                }, Modifier.fillMaxWidth(), enabled = valid)
            }
        }

        CatalogSheet(app, catalogOpen, listOf(brand, name).filter { it.isNotBlank() }.joinToString(" ").ifBlank { name }, { catalogOpen = false }) { item ->
            catalogOpen = false
            pick(item)
        }

        ConfirmSheet(askLeave, "לצאת בלי לשמור?", "השינויים במוצר לא יישמרו.", "יציאה", { askLeave = false }, cancel = "להמשיך לערוך") {
            askLeave = false; initial = null; onClose()
        }

        KeypadSheet(
            open = pad == Pad.Pack, title = "גודל אריזה", lead = "ב${unitLabel(unit)}",
            initial = pack?.toLong(), prefix = "", suffix = unitLabel(unit), decimals = 0,
            onDismiss = { pad = Pad.None },
        ) { v -> pad = Pad.None; pack = v.toDouble() }
        KeypadSheet(
            open = pad == Pad.Price, title = "מחיר לאריזה", lead = name.ifBlank { "המחיר שלי" },
            initial = price, onDismiss = { pad = Pad.None },
        ) { v -> pad = Pad.None; price = v }
    }
}

@Composable
private fun ValueRow(text: String, placeholder: Boolean, onClick: () -> Unit) {
    val c = C.colors
    Row(
        Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(16.dp)).background(c.surface).pressable(onClick).padding(horizontal = Space.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = if (placeholder) TextStyles.body else TextStyles.heading, color = if (placeholder) c.inkFaint else c.ink, modifier = Modifier.weight(1f))
        Text("‹", style = TextStyles.title, color = c.inkFaint)
    }
}

@Composable
private fun CodeTile(emoji: String, label: String, tint: androidx.compose.ui.graphics.Color, modifier: Modifier, onClick: () -> Unit) {
    val c = C.colors
    Row(
        modifier.height(54.dp).clip(RoundedCornerShape(16.dp)).background(tint).pressable(onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(emoji, style = TextStyles.heading)
        Spacer(Modifier.size(Space.s))
        Text(label, style = TextStyles.heading, color = c.ink)
    }
}

@Composable
private fun SuggestionRow(i: CatalogItem, onClick: () -> Unit) {
    val c = C.colors
    Row(Modifier.fillMaxWidth().pressable(onClick, scale = 0.98f).padding(vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(c.surface2), contentAlignment = Alignment.Center) {
            Text("🏷️", style = TextStyles.label)
        }
        Spacer(Modifier.size(Space.m))
        Column(Modifier.weight(1f)) {
            Text(i.name, style = TextStyles.heading, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val size = i.baseUnit?.takeIf { i.amount > 0 }?.let { formatPack(i.amount, it) }
            Text(listOfNotNull(i.brand.ifBlank { null }, size).joinToString(" · ").ifBlank { "${i.stores} סניפים" }, style = TextStyles.label, color = c.inkDim, maxLines = 1)
        }
    }
}
