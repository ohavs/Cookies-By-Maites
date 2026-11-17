package com.maites.cookies.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maites.cookies.CookiesApp
import com.maites.cookies.data.Cats
import com.maites.cookies.data.ExcelIO
import com.maites.cookies.data.KIND_RECIPES
import com.maites.cookies.design.C
import com.maites.cookies.design.CCard
import com.maites.cookies.design.CChip
import com.maites.cookies.design.CSheet
import com.maites.cookies.design.CheckDot
import com.maites.cookies.design.GhostButton
import com.maites.cookies.design.Segmented
import com.maites.cookies.design.Space
import com.maites.cookies.design.StickerButton
import com.maites.cookies.design.TextStyles
import com.maites.cookies.design.pressable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val PRICE_OPTS = listOf(false, true)
private fun priceLabel(on: Boolean) = if (on) "עם מחירים ותמחיר" else "בלי מחירים"

/** Builds a file off the main thread, then opens the share sheet. */
private suspend fun exportAndShare(ctx: Context, toast: (String) -> Unit, mime: String = "application/pdf", build: suspend () -> File) {
    runCatching { build() }
        .onSuccess { PdfExport.share(ctx, it, mime) }
        .onFailure { toast("לא הצלחנו ליצור את הקובץ") }
}

/** Recipe page → PDF of this recipe at the current quantities. */
@Composable
fun RecipePdfSheet(app: CookiesApp, open: Boolean, id: Long, mult: Double, onDismiss: () -> Unit, toast: (String) -> Unit) {
    val c = C.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var prices by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    CSheet(open, onDismiss) {
        Text("שיתוף כ-PDF", style = TextStyles.title, color = c.ink)
        Text(if (kotlin.math.abs(mult - 1.0) > 1e-9) "בכמויות שבחרת עכשיו." else "המתכון עם התמונה, המרכיבים וההערות.", style = TextStyles.body, color = c.inkDim)
        Spacer(Modifier.height(Space.l))
        Segmented(PRICE_OPTS, prices, ::priceLabel, { prices = it }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(Space.xl))
        StickerButton(if (busy) "מכין…" else "יצירת PDF", {
            if (busy) return@StickerButton
            busy = true
            scope.launch {
                exportAndShare(ctx, toast) { PdfExport.recipe(app, id, mult, prices) }
                busy = false; onDismiss()
            }
        }, Modifier.fillMaxWidth(), enabled = !busy)
    }
}

/** Settings → cookbook: pick recipes (by category or one by one), with or without prices. */
@Composable
fun CookbookSheet(app: CookiesApp, open: Boolean, onDismiss: () -> Unit, toast: (String) -> Unit) {
    val c = C.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val rows by app.db.recipes().rows().collectAsStateWithLifecycle(emptyList())
    val picked = remember(open) { mutableStateListOf<Long>() }
    var seeded by remember(open) { mutableStateOf(false) }
    if (open && !seeded && rows.isNotEmpty()) { picked.addAll(rows.map { it.id }); seeded = true }
    var prices by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val sorted = remember(rows) { rows.sortedBy { it.name } }

    CSheet(open, onDismiss) {
        Text("ספר מתכונים", style = TextStyles.title, color = c.ink)
        Text("PDF עם שער, תוכן עניינים ועמוד לכל מתכון.", style = TextStyles.body, color = c.inkDim)
        if (rows.isEmpty()) {
            Spacer(Modifier.height(Space.l))
            CCard(Modifier.fillMaxWidth()) { Text("עוד אין מתכונים", style = TextStyles.body, color = c.inkDim) }
            return@CSheet
        }
        Spacer(Modifier.height(Space.l))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            val all = picked.size == rows.size
            CChip("הכל", all, { picked.clear(); if (!all) picked.addAll(rows.map { it.id }) })
            Cats.list(KIND_RECIPES).filter { cat -> rows.any { Cats.recipe(it.category).gid == cat.gid } }.forEach { cat ->
                val ids = rows.filter { Cats.recipe(it.category).gid == cat.gid }.map { it.id }
                val on = ids.all(picked::contains)
                CChip(cat.label, on, { if (on) picked.removeAll(ids) else ids.filterNot(picked::contains).let(picked::addAll) }, cat.emoji)
            }
        }
        Spacer(Modifier.height(Space.s))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
            items(sorted, key = { it.id }) { r ->
                val on = r.id in picked
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).pressable({ if (on) picked.remove(r.id) else picked.add(r.id) }, scale = 0.97f)
                        .padding(horizontal = Space.s, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CheckDot(on)
                    Spacer(Modifier.width(Space.m))
                    RecipePhoto(r.photos.split('\n').firstOrNull { it.isNotBlank() }, r.category, Modifier.size(40.dp), corner = 14.dp, iconSize = 28.dp)
                    Spacer(Modifier.width(Space.m))
                    Column(Modifier.weight(1f)) {
                        Text(r.name, style = TextStyles.heading, color = c.ink, maxLines = 1)
                        Text(Cats.recipe(r.category).label, style = TextStyles.label, color = c.inkDim, maxLines = 1)
                    }
                }
            }
        }
        Spacer(Modifier.height(Space.m))
        Segmented(PRICE_OPTS, prices, ::priceLabel, { prices = it }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(Space.l))
        StickerButton(
            when { busy -> "מכין…"; picked.isEmpty() -> "בוחרים מתכונים"; else -> "יצירת PDF · ${picked.size} מתכונים" },
            {
                if (busy || picked.isEmpty()) return@StickerButton
                busy = true
                scope.launch {
                    exportAndShare(ctx, toast) { PdfExport.cookbook(app, picked.toList(), prices) }
                    busy = false; onDismiss()
                }
            },
            Modifier.fillMaxWidth(), enabled = !busy && picked.isNotEmpty(),
        )
    }
}

/** Settings → Excel: import a file in our format, or get one (your data, or an example) to fill in. */
@Composable
fun ImportSheet(app: CookiesApp, open: Boolean, onDismiss: () -> Unit, toast: (String) -> Unit) {
    val c = C.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var result by remember(open) { mutableStateOf<ExcelIO.Result?>(null) }
    var error by remember(open) { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true; error = null
        scope.launch {
            runCatching { ctx.contentResolver.openInputStream(uri)!!.use { ExcelIO.read(app, it) } }
                .onSuccess { result = it }
                .onFailure { error = it.message?.takeIf { m -> m.any { ch -> ch in 'א'..'ת' } } ?: "הקובץ לא נקרא — צריך קובץ ‎.xlsx‎ בפורמט של הדוגמה" }
            busy = false
        }
    }

    CSheet(open, onDismiss) {
        val r = result
        if (r != null) {
            Text(if (r.total > 0) "הייבוא הסתיים" else "לא נמצא מה לייבא", style = TextStyles.title, color = c.ink)
            Spacer(Modifier.height(Space.l))
            CCard(Modifier.fillMaxWidth()) {
                ResultLine("🧈", "מוצרים", r.productsAdded, r.productsUpdated)
                ResultLine("🍪", "מתכונים", r.recipesAdded, r.recipesUpdated)
                if (r.ingredients > 0) Text("${r.ingredients} שורות מרכיבים", style = TextStyles.label, color = c.inkDim, modifier = Modifier.padding(top = Space.xs))
            }
            if (r.skipped.isNotEmpty()) {
                Spacer(Modifier.height(Space.m))
                CCard(Modifier.fillMaxWidth()) {
                    Text("שורות שדילגנו עליהן", style = TextStyles.heading, color = c.danger)
                    r.skipped.take(6).forEach { Text(it, style = TextStyles.label, color = c.inkDim) }
                    if (r.skipped.size > 6) Text("ועוד ${r.skipped.size - 6}", style = TextStyles.label, color = c.inkDim)
                }
            }
            Spacer(Modifier.height(Space.xl))
            StickerButton("סיום", onDismiss, Modifier.fillMaxWidth())
            return@CSheet
        }

        Text("ייבוא מאקסל", style = TextStyles.title, color = c.ink)
        Text("קובץ עם שלושה גיליונות: מוצרים, מתכונים, מרכיבים. מה שכבר קיים (לפי שם או ברקוד) מתעדכן, השאר נוסף.", style = TextStyles.body, color = c.inkDim)
        Spacer(Modifier.height(Space.l))
        CCard(Modifier.fillMaxWidth()) {
            FormatLine("מוצרים", "שם · מותג · ברקוד · גודל אריזה · יחידה · מחיר · קטגוריה")
            FormatLine("מתכונים", "שם · קטגוריה · כמות יחידות · שם היחידה · מחיר מכירה · הערות")
            FormatLine("מרכיבים", "מתכון · מרכיב · כמות · יחידה · ברקוד")
        }
        error?.let {
            Spacer(Modifier.height(Space.m))
            Text(it, style = TextStyles.label, color = c.danger)
        }
        Spacer(Modifier.height(Space.xl))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
            GhostButton("קובץ לדוגמה", {
                if (busy) return@GhostButton
                scope.launch {
                    exportAndShare(ctx, toast, XLSX) {
                        withContext(Dispatchers.IO) {
                            val dir = File(ctx.cacheDir, "exports").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                            File(dir, "Cookies by Maites.xlsx").also { f -> f.outputStream().use { ExcelIO.write(app, it) } }
                        }
                    }
                }
            }, Modifier.weight(1f))
            StickerButton(if (busy) "מייבא…" else "בחירת קובץ", {
                if (!busy) pick.launch(arrayOf(XLSX, "application/vnd.ms-excel", "application/octet-stream"))
            }, Modifier.weight(1f), enabled = !busy)
        }
        Spacer(Modifier.height(Space.s))
        Text("הקובץ לדוגמה כולל את המוצרים והמתכונים שכבר יש באפליקציה — אפשר לערוך אותו ולייבא חזרה.", style = TextStyles.label, color = c.inkFaint)
    }
}

private const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

@Composable
private fun FormatLine(sheet: String, cols: String) {
    val c = C.colors
    Column(Modifier.padding(vertical = Space.xs)) {
        Text(sheet, style = TextStyles.heading, color = c.ink)
        Text(cols, style = TextStyles.label, color = c.inkDim)
    }
}

@Composable
private fun ResultLine(emoji: String, what: String, added: Int, updated: Int) {
    val c = C.colors
    Row(Modifier.fillMaxWidth().padding(vertical = Space.xs), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(c.surface2), contentAlignment = Alignment.Center) { Text(emoji, style = TextStyles.heading) }
        Spacer(Modifier.width(Space.m))
        Text(what, style = TextStyles.heading, color = c.ink, modifier = Modifier.weight(1f))
        Text("$added חדשים · $updated עודכנו", style = TextStyles.label, color = c.inkDim)
    }
}
