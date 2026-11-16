package com.maites.cookies.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maites.cookies.CookiesApp
import com.maites.cookies.design.C
import com.maites.cookies.design.CCard
import com.maites.cookies.design.EmptyState
import com.maites.cookies.design.Space
import com.maites.cookies.design.StickerButton
import com.maites.cookies.design.TextStyles
import java.util.Locale

@Composable
fun ScreenTitle(title: String, sub: String? = null) {
    Column(Modifier.padding(start = Space.gutter, end = Space.gutter, top = Space.l, bottom = Space.m)) {
        Text(title, style = TextStyles.display, color = C.colors.ink)
        if (sub != null) Text(sub, style = TextStyles.body, color = C.colors.inkDim)
    }
}

@Composable
fun RecipesScreen(onAdd: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("מתכונים")
        Box(Modifier.weight(1f).padding(bottom = 96.dp), contentAlignment = Alignment.Center) {
            EmptyState("🍪", "עוד אין מתכונים", "המתכון הראשון שלך יופיע כאן, עם תמחיר לכל עוגייה.", C.colors.tPeach) {
                StickerButton("מתכון חדש", onAdd)
            }
        }
    }
}

@Composable
fun ProductsScreen(app: CookiesApp, onAdd: () -> Unit) {
    val products by app.db.products().all().collectAsStateWithLifecycle(emptyList())
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("מוצרים")
        if (products.isEmpty()) {
            Box(Modifier.weight(1f).padding(bottom = 96.dp), contentAlignment = Alignment.Center) {
                EmptyState("🧈", "עוד אין מוצרים", "חמאה, קמח, שוקולד — מוסיפים פעם אחת, והמחיר מתעדכן לבד.", C.colors.tButter) {
                    StickerButton("מוצר חדש", onAdd)
                }
            }
        } else {
            LazyColumn(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Space.m),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = Space.gutter, end = Space.gutter, bottom = 120.dp),
            ) {
                items(products, key = { it.id }) { p ->
                    CCard {
                        Text(p.name, style = TextStyles.heading)
                        p.priceAgorot?.let { Text(formatShekel(it), style = TextStyles.label, color = C.colors.inkDim) }
                    }
                }
            }
        }
    }
}

@Composable
fun ShoppingScreen() {
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("קניות")
        Box(Modifier.weight(1f).padding(bottom = 96.dp), contentAlignment = Alignment.Center) {
            EmptyState("🛒", "הרשימה ריקה", "בוחרים מתכונים, והרשימה והסופר הזול לסל נבנים לבד.", C.colors.tMint)
        }
    }
}

/** Money is always agorot (Long); shown as ₪ with 2 decimals. */
fun formatShekel(agorot: Long): String = "₪" + String.format(Locale.US, "%,.2f", agorot / 100.0)
