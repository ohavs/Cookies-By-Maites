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
import com.maites.cookies.data.ProductCategory
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
    var cat by remember { mutableStateOf(ProductCategory.Baking) }
    var unit by remember { mutableStateOf(BaseUnit.Gram) }
    var pack by remember { mutableStateOf<Double?>(null) }
    var price by remember { mutableStateOf<Long?>(null) }
    var image by remember { mutableStateOf<String?>(null) }
    var looking by remember { mutableStateOf(false) }
    var pad by remember { mutableStateOf(Pad.None) }

    LaunchedEffect(id) {
        if (id != null) dao.byId(id).first()?.let { p ->
            original = p; name = p.name; brand = p.brand; code = p.barcode ?: ""; cat = p.category
            unit = p.unit; pack = p.packAmount; price = p.priceAgorot; image = p.imageUrl
        }
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

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.m), verticalAlignment = Alignment.CenterVertically) {
                RoundButton("✕", onClose)
                Spacer(Modifier.size(Space.m))
                Text(if (id == null) "מוצר חדש" else "עריכת מוצר", style = TextStyles.title, color = c.ink)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Space.gutter)) {
                if (id == null && code.isBlank()) {
                    CCard(Modifier.fillMaxWidth(), onClick = scan) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(c.tSky), contentAlignment = Alignment.Center) { Text("📷", style = TextStyles.title) }
                            Spacer(Modifier.size(Space.m))
                            Column {
                                Text("סריקת ברקוד", style = TextStyles.heading, color = c.ink)
                                Text("השם, המותג והגודל יתמלאו לבד", style = TextStyles.label, color = c.inkDim)
                            }
                        }
                    }
                    Spacer(Modifier.height(Space.l))
                }
                if (looking) { Text("מחפשים את המוצר…", style = TextStyles.label, color = c.accentInk); Spacer(Modifier.height(Space.s)) }

                CTextField(name, { name = it }, "שם המוצר", placeholder = "למשל: חמאה")
                Spacer(Modifier.height(Space.l))
                CTextField(brand, { brand = it }, "מותג", placeholder = "לא חובה")
                Spacer(Modifier.height(Space.l))
                CTextField(code, { v -> code = v.filter { it.isDigit() } }, "ברקוד", placeholder = "לא חובה", ltr = true) {
                    Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(c.surface2).pressable(scan), contentAlignment = Alignment.Center) {
                        Text("📷", style = TextStyles.label)
                    }
                }

                Spacer(Modifier.height(Space.l))
                SectionLabel("קטגוריה")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    ProductCategory.entries.forEach { k -> CChip(k.label, cat == k, { cat = k }, k.emoji) }
                }

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
                        if (clean != null && clean != original?.barcode) app.applicationScope.launch { app.priceSync.watchNow(clean) }
                        toast(if (id == null) "${saved.name} נוסף" else "נשמר")
                        onSaved(if (id == null) newId else id)
                    }
                }, Modifier.fillMaxWidth(), enabled = valid)
            }
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
