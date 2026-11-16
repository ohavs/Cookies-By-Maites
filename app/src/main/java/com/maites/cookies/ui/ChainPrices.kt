package com.maites.cookies.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maites.cookies.CookiesApp
import com.maites.cookies.data.Chain
import com.maites.cookies.data.ProductEntity
import com.maites.cookies.data.StorePrice
import com.maites.cookies.data.priceKey
import com.maites.cookies.design.C
import com.maites.cookies.design.CCard
import com.maites.cookies.design.CSheet
import com.maites.cookies.design.Pill
import com.maites.cookies.design.SearchBox
import com.maites.cookies.design.SectionLabel
import com.maites.cookies.design.Space
import com.maites.cookies.design.StickerButton
import com.maites.cookies.design.TextStyles
import com.maites.cookies.design.pressable
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@Composable
fun chainTint(chain: String): Color = with(C.colors) {
    when (Chain.of(chain)) {
        Chain.Shufersal -> tMint; Chain.RamiLevy -> tSky; Chain.Victory -> tButter
        Chain.Yochananof -> tPeach; Chain.OsherAd -> tLav; Chain.Carrefour -> tRose; null -> surface2
    }
}

/** "מחירים ברשתות" block on the product page: stores in the chosen city, cheapest first. */
@Composable
fun ChainPricesSection(app: CookiesApp, prod: ProductEntity, onPickCity: () -> Unit) {
    val c = C.colors
    val code = priceKey(prod.barcode)
    val city by app.prefs.city.collectAsStateWithLifecycle("")
    val rows by remember(code, city) {
        if (code == null || city.isBlank()) flowOf(emptyList()) else app.db.prices().inCity(code, city)
    }.collectAsStateWithLifecycle(emptyList())
    val anywhere by remember(code) { if (code == null) flowOf(0) else app.db.prices().countAnywhere(code) }.collectAsStateWithLifecycle(0)
    var expanded by remember(code, city) { mutableStateOf(false) }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SectionLabel("מחירים ברשתות", Modifier.weight(1f))
        if (code != null) Box(Modifier.padding(bottom = Space.s).clip(RoundedCornerShape(99.dp)).pressable(onPickCity)) {
            Pill("📍 ${city.ifBlank { "בחירת עיר" }}")
        }
    }
    CCard(Modifier.animateContentSize(), padding = PaddingValues(horizontal = Space.l, vertical = Space.xs)) {
        when {
            code == null -> Note("🏷️", "אין ברקוד למוצר", "עם ברקוד נמשוך מחירים מהרשתות פעם ביום. אפשר להוסיף אותו בעריכה.")
            city.isBlank() -> {
                Note("📍", "באיזו עיר קונים?", "נציג את הסניפים בעיר מהזול ליקר.")
                StickerButton("בחירת עיר", onPickCity, Modifier.fillMaxWidth().padding(bottom = Space.m))
            }
            rows.isEmpty() && anywhere == 0 -> Note("🌙", "המחירים בדרך", "המשיכה מהרשתות רצה פעם ביום, מחר בבוקר המחירים יופיעו כאן.")
            rows.isEmpty() -> Note("🔍", "אין מחיר ב$city", "המוצר לא נמצא בסניפים בעיר הזו. אפשר לבחור עיר אחרת.")
            else -> {
                val shown = if (expanded) rows else rows.take(5)
                shown.forEachIndexed { i, r ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
                    PriceRow(r, prod, cheapest = i == 0)
                }
                if (rows.size > 5) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
                    Row(Modifier.fillMaxWidth().pressable({ expanded = !expanded }).padding(vertical = Space.m)) {
                        Text(if (expanded) "פחות סניפים" else "עוד ${rows.size - 5} סניפים", style = TextStyles.heading, color = c.accentInk)
                    }
                }
            }
        }
    }
}

@Composable
private fun PriceRow(r: StorePrice, prod: ProductEntity, cheapest: Boolean) {
    val c = C.colors
    val chain = Chain.of(r.chain)
    Row(Modifier.fillMaxWidth().padding(vertical = Space.m), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(chainTint(r.chain)), contentAlignment = Alignment.Center) {
            Text(chain?.emoji ?: "🏬", style = TextStyles.heading)
        }
        Spacer(Modifier.size(Space.m))
        Column(Modifier.weight(1f)) {
            Text(chain?.label ?: r.chain, style = TextStyles.heading, color = c.ink)
            Text(r.name.ifBlank { r.address }, style = TextStyles.label, color = c.inkDim, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(formatShekel(r.agorot), style = TextStyles.title, color = if (cheapest) c.good else c.ink)
            if (cheapest) Text("הכי זול", style = TextStyles.caption, color = c.good)
            else formatUnitPrice(r.agorot, prod.packAmount, prod.unit)?.let { Text(it, style = TextStyles.caption, color = c.inkDim) }
        }
    }
}

@Composable
private fun Note(emoji: String, title: String, line: String) {
    val c = C.colors
    Row(Modifier.fillMaxWidth().padding(vertical = Space.l), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(c.surface2), contentAlignment = Alignment.Center) { Text(emoji, style = TextStyles.heading) }
        Spacer(Modifier.size(Space.m))
        Column(Modifier.weight(1f)) {
            Text(title, style = TextStyles.heading, color = c.ink)
            Text(line, style = TextStyles.label, color = c.inkDim)
        }
    }
}

/** Bottom sheet: searchable list of cities that have chain stores. */
@Composable
fun CityPickerSheet(app: CookiesApp, open: Boolean, onDismiss: () -> Unit, toast: (String) -> Unit) {
    val c = C.colors
    val scope = rememberCoroutineScope()
    val cities by remember { app.db.prices().cities() }.collectAsStateWithLifecycle(emptyList())
    val current by app.prefs.city.collectAsStateWithLifecycle("")
    var q by remember { mutableStateOf("") }

    CSheet(open, onDismiss) {
        Text("באיזו עיר?", style = TextStyles.title, color = c.ink)
        Text("המחירים יוצגו לפי הסניפים בעיר הזו.", style = TextStyles.body, color = c.inkDim)
        Spacer(Modifier.height(Space.l))
        if (cities.isEmpty()) {
            Note("🌙", "רשימת הסניפים עוד לא ירדה", "צריך חיבור לאינטרנט פעם אחת. נסי שוב עוד רגע.")
            return@CSheet
        }
        SearchBox(q, { q = it }, "חיפוש עיר")
        Spacer(Modifier.height(Space.m))
        val list = cities.filter { q.isBlank() || it.contains(q.trim()) }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(list, key = { it }) { city ->
                val sel = city == current
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (sel) c.accentSoft else Color.Transparent)
                        .pressable({ app.applicationScope.launch { app.prefs.setCity(city); app.priceSync.sync(force = true) }; onDismiss(); toast("המחירים לפי $city") })
                        .padding(horizontal = Space.l, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(city, style = TextStyles.heading, color = c.ink, modifier = Modifier.weight(1f))
                    if (sel) Text("✓", style = TextStyles.heading, color = c.accentInk)
                }
            }
            if (list.isEmpty()) item { Text("לא נמצאה עיר בשם הזה", style = TextStyles.body, color = c.inkDim, modifier = Modifier.padding(Space.l)) }
        }
    }
}
