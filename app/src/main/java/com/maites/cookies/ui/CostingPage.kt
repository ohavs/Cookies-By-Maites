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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maites.cookies.CookiesApp
import com.maites.cookies.data.Costing
import com.maites.cookies.data.ExtraCost
import com.maites.cookies.data.ExtraKind
import com.maites.cookies.data.RecipeEntity
import com.maites.cookies.design.C
import com.maites.cookies.design.CCard
import com.maites.cookies.design.CChip
import com.maites.cookies.design.CSheet
import com.maites.cookies.design.CTextField
import com.maites.cookies.design.GhostButton
import com.maites.cookies.design.KeypadSheet
import com.maites.cookies.design.SectionLabel
import com.maites.cookies.design.Segmented
import com.maites.cookies.design.Space
import com.maites.cookies.design.StickerButton
import com.maites.cookies.design.TextStyles
import com.maites.cookies.design.pressable
import kotlinx.coroutines.launch
import java.util.Locale

private val PRESETS = listOf("📦" to "אריזה", "🔥" to "גז / חשמל", "👩‍🍳" to "עבודה", "🎀" to "קישוט")

private fun unitWord(r: RecipeEntity) = if (r.yieldCount > 0) "ליחידה" else "לתבנית"

fun formatPercent(v: Double) = String.format(Locale.US, "%.0f%%", v)

/** Extras, sale price and margin for one recipe. Changes save right away. */
@Composable
fun CostingPage(app: CookiesApp, id: Long, onBack: () -> Unit, toast: (String) -> Unit) {
    val c = C.colors
    val dao = app.db.recipes()
    val recipe by remember(id) { dao.byId(id) }.collectAsStateWithLifecycle(null)
    val items by remember(id) { dao.ingredients(id) }.collectAsStateWithLifecycle(emptyList())
    val ingredients = rememberIngredientsCost(app, items)
    var byMargin by remember { mutableStateOf<Boolean?>(null) }
    var extraEdit by remember { mutableStateOf<Int?>(null) }
    var salePad by remember { mutableStateOf(false) }
    var marginPad by remember { mutableStateOf(false) }
    var removeExtra by remember { mutableStateOf<Int?>(null) }
    var pdfBusy by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val r = recipe ?: return
    val extras = r.extraList
    val cost = Costing.of(ingredients.sum, extras, r.yieldCount, 1.0)
    val mode = byMargin ?: (r.salePrice == 0L && r.targetMargin > 0)
    val per = unitWord(r)

    fun save(e: List<ExtraCost> = extras, sale: Long = r.salePrice, margin: Int = r.targetMargin) =
        app.applicationScope.launch { dao.setCosting(r.id, ExtraCost.encode(e), sale, margin) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.m), verticalAlignment = Alignment.CenterVertically) {
                RoundButton("→", onBack)
                Spacer(Modifier.width(Space.m))
                Column(Modifier.weight(1f)) {
                    Text("תמחיר", style = TextStyles.title, color = c.ink)
                    Text(r.name, style = TextStyles.label, color = c.inkDim)
                }
                RoundButton(if (pdfBusy) "⏳" else "📄", {
                    if (!pdfBusy) {
                        pdfBusy = true
                        scope.launch {
                            runCatching { PdfExport.costing(app, r.id) }.onSuccess { PdfExport.share(ctx, it) }.onFailure { toast("לא הצלחנו ליצור את הקובץ") }
                            pdfBusy = false
                        }
                    }
                })
            }
            Column(Modifier.padding(horizontal = Space.gutter)) {
                // Summary
                CCard(Modifier.fillMaxWidth()) {
                    Text("עלות $per", style = TextStyles.label, color = c.inkDim)
                    Text(formatShekel(cost.perUnit), style = TextStyles.display, color = c.ink)
                    Spacer(Modifier.height(Space.m))
                    Line("מרכיבים", formatShekel(cost.ingredients))
                    cost.extras.forEach { (e, v) -> Line(e.name, formatShekel(v)) }
                    Box(Modifier.fillMaxWidth().padding(vertical = Space.xs).height(1.dp).background(c.divider))
                    Line("סה״כ לתבנית", formatShekel(cost.total), strong = true)
                    yieldText(r.yieldCount, r.yieldLabel)?.let { Line("יוצא", it) }
                    if (ingredients.missing > 0) Text(
                        "בלי ${ingredients.missing} מרכיבים שאין להם מחיר", style = TextStyles.label, color = c.danger, modifier = Modifier.padding(top = Space.s),
                    )
                }

                Spacer(Modifier.height(Space.xl))
                SectionLabel("עלויות נוספות")
                CCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = Space.l, vertical = Space.xs)) {
                    cost.extras.forEachIndexed { i, (e, v) ->
                        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
                        Row(Modifier.fillMaxWidth().pressable({ if (editable(toast)) extraEdit = i }).padding(vertical = Space.m), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(e.name, style = TextStyles.heading, color = c.ink)
                                Text(extraValue(e), style = TextStyles.label, color = c.inkDim)
                            }
                            Text(formatShekel(v), style = TextStyles.heading, color = c.ink)
                        }
                    }
                    if (extras.isNotEmpty()) Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
                    Row(Modifier.fillMaxWidth().pressable({ if (editable(toast)) extraEdit = -1 }).padding(vertical = Space.m), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(c.accent), contentAlignment = Alignment.Center) {
                            Text("+", style = TextStyles.title, color = c.accentFg)
                        }
                        Spacer(Modifier.width(Space.m))
                        Column {
                            Text("הוספת עלות", style = TextStyles.heading, color = c.accentInk)
                            if (extras.isEmpty()) Text("אריזה, גז/חשמל, עבודה…", style = TextStyles.label, color = c.inkDim)
                        }
                    }
                }

                Spacer(Modifier.height(Space.xl))
                SectionLabel("מחיר מכירה")
                Segmented(listOf(false, true), mode, { if (it) "לפי מרווח רצוי" else "לפי מחיר" }, { byMargin = it }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(Space.m))
                if (!mode) {
                    ValueBox(if (r.salePrice > 0) "${formatShekel(r.salePrice)} $per" else "כמה זה עולה $per?", r.salePrice == 0L) { if (editable(toast)) salePad = true }
                    if (r.salePrice > 0) {
                        Spacer(Modifier.height(Space.m))
                        val profit = cost.profit(r.salePrice)
                        val good = profit >= 0
                        CCard(Modifier.fillMaxWidth()) {
                            Text(if (good) "רווח $per" else "הפסד $per", style = TextStyles.label, color = c.inkDim)
                            Text(formatShekel(kotlin.math.abs(profit)), style = TextStyles.display, color = if (good) c.good else c.danger)
                            Spacer(Modifier.height(Space.s))
                            Line("מרווח", formatPercent(cost.margin(r.salePrice)))
                            cost.units?.let { u -> Line("רווח לתבנית", formatShekel(Math.round(profit * u))) }
                        }
                        Spacer(Modifier.height(Space.s))
                        GhostButton("בלי מחיר מכירה", { save(sale = 0) }, Modifier.fillMaxWidth())
                    }
                } else {
                    ValueBox(if (r.targetMargin > 0) "מרווח ${r.targetMargin}%" else "כמה אחוז רווח?", r.targetMargin == 0) { if (editable(toast)) marginPad = true }
                    cost.priceFor(r.targetMargin)?.let { price ->
                        Spacer(Modifier.height(Space.m))
                        CCard(Modifier.fillMaxWidth()) {
                            Text("מחיר מומלץ $per", style = TextStyles.label, color = c.inkDim)
                            Text(formatShekel(price), style = TextStyles.display, color = c.accentInk)
                            Spacer(Modifier.height(Space.s))
                            Line("רווח $per", formatShekel(price - cost.perUnit))
                        }
                        Spacer(Modifier.height(Space.s))
                        StickerButton("זה המחיר שלי", {
                            byMargin = false; save(sale = price); toast("נקבע מחיר ${formatShekel(price)}")
                        }, Modifier.fillMaxWidth())
                    }
                }
                Spacer(Modifier.height(Space.xl))
            }
        }

        ExtraSheet(extraEdit?.let { if (it < 0) null else extras.getOrNull(it) }, extraEdit != null, { extraEdit = null }) { result ->
            val i = extraEdit ?: return@ExtraSheet
            extraEdit = null
            if (result == null && i >= 0) { removeExtra = i; return@ExtraSheet }
            val list = extras.toMutableList()
            when {
                result == null -> if (i >= 0) list.removeAt(i)
                i < 0 -> list += result
                else -> list[i] = result
            }
            save(e = list)
        }

        com.maites.cookies.design.ConfirmSheet(
            removeExtra != null, "להסיר את ${removeExtra?.let { extras.getOrNull(it)?.name }.orEmpty()}?", "העלות הנוספת תוסר מהתמחיר.", "הסרה", { removeExtra = null },
        ) {
            val i = removeExtra; removeExtra = null
            if (i != null && i in extras.indices) save(e = extras.toMutableList().also { it.removeAt(i) })
        }

        KeypadSheet(
            open = salePad, title = "מחיר מכירה", lead = "כמה גובים $per", initial = r.salePrice.takeIf { it > 0 },
            onDismiss = { salePad = false },
        ) { v -> salePad = false; save(sale = v) }

        KeypadSheet(
            open = marginPad, title = "מרווח רצוי", lead = "איזה חלק מהמחיר נשאר רווח (1–99)", initial = r.targetMargin.takeIf { it > 0 }?.toLong(),
            prefix = "", suffix = "%", decimals = 0, onDismiss = { marginPad = false },
        ) { v ->
            if (v in 1..99) { marginPad = false; save(margin = v.toInt()) } else toast("בין 1 ל-99 אחוז")
        }
    }
}

private fun extraValue(e: ExtraCost) = when (e.kind) {
    ExtraKind.Batch -> "${formatShekel(e.value)} לתבנית"
    ExtraKind.PerUnit -> "${formatShekel(e.value)} ליחידה"
    ExtraKind.Percent -> String.format(Locale.US, "%s%% מהמרכיבים", java.math.BigDecimal(e.value).movePointLeft(2).stripTrailingZeros().toPlainString())
}

@Composable
private fun Line(label: String, value: String, strong: Boolean = false) {
    val c = C.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = if (strong) TextStyles.heading else TextStyles.body, color = if (strong) c.ink else c.inkDim, modifier = Modifier.weight(1f))
        Text(value, style = TextStyles.heading, color = c.ink)
    }
}

/** Add / edit one extra cost. [onDone] null = remove. */
@Composable
private fun ExtraSheet(initial: ExtraCost?, open: Boolean, onDismiss: () -> Unit, onDone: (ExtraCost?) -> Unit) {
    val c = C.colors
    var name by remember(open, initial) { mutableStateOf(initial?.name.orEmpty()) }
    var kind by remember(open, initial) { mutableStateOf(initial?.kind ?: ExtraKind.Batch) }
    var value by remember(open, initial) { mutableStateOf(initial?.value ?: 0L) }
    var pad by remember(open) { mutableStateOf(false) }
    CSheet(open, onDismiss) {
        Text(if (initial == null) "עלות נוספת" else initial.name, style = TextStyles.title, color = c.ink)
        Spacer(Modifier.height(Space.m))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            PRESETS.forEach { (e, n) -> CChip(n, name == n, { name = n }, e) }
        }
        Spacer(Modifier.height(Space.m))
        CTextField(name, { name = it }, "שם", placeholder = "למשל: קופסה ומדבקה")
        Spacer(Modifier.height(Space.m))
        Segmented(ExtraKind.entries, kind, { it.label }, { if (it != kind) { kind = it; value = 0 } }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(Space.s))
        val shown = if (value <= 0) null else when (kind) {
            ExtraKind.Percent -> java.math.BigDecimal(value).movePointLeft(2).stripTrailingZeros().toPlainString() + "%"
            else -> formatShekel(value)
        }
        ValueBox(shown ?: "סכום", shown == null) { pad = true }
        Spacer(Modifier.height(Space.xl))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
            if (initial != null) GhostButton("הסרה", { onDone(null) }, Modifier.weight(1f))
            StickerButton("שמירה", { onDone(ExtraCost(name.trim(), kind, value)) }, Modifier.weight(1f), enabled = name.isNotBlank() && value > 0)
        }
    }
    KeypadSheet(
        open = open && pad, title = name.ifBlank { "סכום" }, lead = kind.label, initial = value.takeIf { it > 0 },
        prefix = if (kind == ExtraKind.Percent) "" else "₪", suffix = if (kind == ExtraKind.Percent) "%" else "",
        onDismiss = { pad = false },
    ) { v -> pad = false; value = v }
}
