package com.maites.cookies.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maites.cookies.CookiesApp
import com.maites.cookies.data.CatalogItem
import com.maites.cookies.design.C
import com.maites.cookies.design.CSheet
import com.maites.cookies.design.SearchBox
import com.maites.cookies.design.Space
import com.maites.cookies.design.TextStyles
import com.maites.cookies.design.pressable
import kotlinx.coroutines.delay

/** Search the chains' product list by name; picking an item gives the product its barcode (and so its prices). */
@Composable
fun CatalogSheet(app: CookiesApp, open: Boolean, initial: String, onDismiss: () -> Unit, onPick: (CatalogItem) -> Unit) {
    val c = C.colors
    var q by remember(open) { mutableStateOf(initial) }
    var results by remember(open) { mutableStateOf<List<CatalogItem>?>(null) }
    var empty by remember { mutableStateOf(false) }
    LaunchedEffect(open) {
        if (open && app.db.catalog().count() == 0) { empty = true; app.catalog.sync(force = true); empty = app.db.catalog().count() == 0 }
    }
    LaunchedEffect(open, q, empty) {
        if (!open) return@LaunchedEffect
        delay(200)
        results = if (q.trim().length < 2) null else app.db.catalog().search(q)
    }
    CSheet(open, onDismiss) {
        Text("חיפוש במחירון", style = TextStyles.title, color = c.ink)
        Text("כל המוצרים שנמכרים ברשתות. בחירה מחברת את המוצר למחירים.", style = TextStyles.body, color = c.inkDim)
        Spacer(Modifier.height(Space.m))
        SearchBox(q, { q = it }, "למשל: חמאה תנובה")
        Spacer(Modifier.height(Space.s))
        val list = results
        when {
            empty -> Text("טוענים את המחירון… (פעם אחת, צריך אינטרנט)", style = TextStyles.body, color = c.inkDim, modifier = Modifier.padding(vertical = Space.m))
            list == null -> Text("מקלידים לפחות 2 אותיות", style = TextStyles.body, color = c.inkFaint, modifier = Modifier.padding(vertical = Space.m))
            list.isEmpty() -> Text("לא נמצא. נסו מילה אחרת או רק את המותג.", style = TextStyles.body, color = c.inkDim, modifier = Modifier.padding(vertical = Space.m))
            else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                items(list, key = { it.code }) { i ->
                    Row(Modifier.fillMaxWidth().pressable({ onPick(i) }).padding(vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(c.surface2), contentAlignment = Alignment.Center) {
                            Text("🏷️", style = TextStyles.heading)
                        }
                        Spacer(Modifier.width(Space.m))
                        Column(Modifier.weight(1f)) {
                            Text(i.name, style = TextStyles.heading, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val size = i.baseUnit?.takeIf { i.amount > 0 }?.let { formatPack(i.amount, it) }
                            Text(listOfNotNull(i.brand.ifBlank { null }, size, "${i.stores} סניפים").joinToString(" · "), style = TextStyles.label, color = c.inkDim, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
