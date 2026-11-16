package com.maites.cookies.ui

import com.maites.cookies.data.priceKey
import androidx.compose.runtime.remember
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import com.maites.cookies.CookiesApp
import com.maites.cookies.data.Cats
import com.maites.cookies.data.KIND_PRODUCTS
import com.maites.cookies.data.ProductEntity
import com.maites.cookies.design.C
import com.maites.cookies.design.CCard
import com.maites.cookies.design.CChip
import com.maites.cookies.design.EmptyState
import com.maites.cookies.design.SearchBox
import com.maites.cookies.design.Space
import com.maites.cookies.design.StickerButton
import com.maites.cookies.design.TextStyles

@Composable
fun categoryTint(key: String): Color = tintOf(Cats.product(key).tint)

/** Packaging photo on white; category icon on its tint when there's no photo. */
@Composable
fun ProductImage(p: ProductEntity, modifier: Modifier = Modifier, corner: Dp = 18.dp, iconSize: Dp = 44.dp) {
    val c = C.colors
    val shape = RoundedCornerShape(corner)
    val fallback: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize().background(categoryTint(p.category)), contentAlignment = Alignment.Center) {
            Text(Cats.product(p.category).emoji, style = TextStyles.display.copy(fontSize = with(androidx.compose.ui.platform.LocalDensity.current) { (iconSize * 0.6f).toSp() }))
        }
    }
    Box(modifier.clip(shape).background(Color.White).border(1.dp, c.divider, shape), contentAlignment = Alignment.Center) {
        if (p.imageUrl == null) fallback()
        else SubcomposeAsyncImage(
            model = p.imageUrl, contentDescription = null, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(corner / 2),
            loading = { Box(Modifier.fillMaxSize().background(Color.White)) },
            error = { fallback() },
        )
    }
}

private const val FAV = "fav"
private const val ALL = "all"

@Composable
fun ProductsScreen(app: CookiesApp, onAdd: () -> Unit, onOpen: (Long) -> Unit) {
    val c = C.colors
    val products by app.db.products().all().collectAsStateWithLifecycle(null)
    val city by app.prefs.city.collectAsStateWithLifecycle("")
    val cheapest by remember(city) { app.db.prices().cheapestInCity(city) }.collectAsStateWithLifecycle(emptyList())
    val cheapMap = remember(cheapest) { cheapest.associate { it.barcode to it.agorot } }
    var q by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(ALL) }
    val list = products ?: return

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("מוצרים", if (list.isEmpty()) null else "${list.size} מוצרים ברשימה")
        if (list.isEmpty()) {
            Box(Modifier.weight(1f).padding(bottom = 96.dp), contentAlignment = Alignment.Center) {
                EmptyState("🧈", "עוד אין מוצרים", "חמאה, קמח, שוקולד — מוסיפים פעם אחת, והמחיר מתעדכן לבד.", c.tButter) {
                    StickerButton("מוצר חדש", onAdd)
                }
            }
            return
        }
        SearchBox(q, { q = it }, "חיפוש מוצר, מותג או ברקוד", Modifier.padding(horizontal = Space.gutter))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Space.gutter, vertical = Space.m),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            CChip("הכל", filter == ALL, { filter = ALL })
            CChip("מועדפים", filter == FAV, { filter = FAV }, "❤️")
            Cats.list(KIND_PRODUCTS).forEach { cat -> CChip(cat.label, filter == cat.gid, { filter = cat.gid }, cat.emoji) }
        }

        val needle = q.trim().lowercase()
        val shown = list.filter { p ->
            (needle.isEmpty() || p.name.lowercase().contains(needle) || p.brand.lowercase().contains(needle) || (p.barcode?.contains(needle) == true)) &&
                (filter == ALL || (filter == FAV && p.favorite) || Cats.product(p.category).gid == filter)
        }
        if (shown.isEmpty()) {
            Box(Modifier.weight(1f).padding(bottom = 96.dp), contentAlignment = Alignment.Center) {
                if (filter == FAV && needle.isEmpty()) EmptyState("❤️", "אין עדיין מועדפים", "לוחצים על הלב בדף המוצר כדי לשמור אותו כאן.", c.tRose)
                else EmptyState("🔎", "לא נמצא מוצר", "נסו שם אחר, או סרקו את הברקוד מכפתור ה־+.", c.tSky)
            }
            return
        }
        val groups = Cats.list(KIND_PRODUCTS).mapNotNull { cat -> shown.filter { Cats.product(it.category).gid == cat.gid }.takeIf { it.isNotEmpty() }?.let { cat to it } }
        LazyVerticalGrid(
            GridCells.Fixed(2),
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, bottom = 120.dp),
            horizontalArrangement = Arrangement.spacedBy(Space.m),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            groups.forEach { (cat, items) ->
                item(key = "h-${cat.name}", span = { GridItemSpan(2) }) {
                    Row(Modifier.padding(top = Space.s, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.Bottom) {
                        Text(cat.label, style = TextStyles.title, color = c.ink, modifier = Modifier.weight(1f))
                        Text("${items.size} מוצרים", style = TextStyles.label, color = c.inkDim)
                    }
                }
                items(items, key = { it.id }) { p -> Box(Modifier.animateItem(placementSpec = com.maites.cookies.design.Springs.soft(androidx.compose.ui.unit.IntOffset(1, 1)))) { ProductTile(p, priceKey(p.barcode)?.let(cheapMap::get)) { onOpen(p.id) } } }
            }
        }
    }
}

@Composable
private fun ProductTile(p: ProductEntity, chainPrice: Long?, onClick: () -> Unit) {
    val c = C.colors
    CCard(onClick = onClick, padding = PaddingValues(Space.m)) {
        Box {
            ProductImage(p, Modifier.fillMaxWidth().aspectRatio(1.15f))
            if (p.favorite) Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(30.dp)
                    .shadow(4.dp, RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp)).background(c.surface),
                contentAlignment = Alignment.Center,
            ) { Text("❤️", style = TextStyles.label) }
        }
        Spacer(Modifier.height(Space.s))
        Text(p.name, style = TextStyles.heading, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(formatPack(p.packAmount, p.unit) + if (p.brand.isNotBlank()) " · ${p.brand}" else "", style = TextStyles.label, color = c.inkDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(Space.xs))
        if (p.priceAgorot != null) Text(formatShekel(p.priceAgorot), style = TextStyles.heading, color = c.ink)
        else if (chainPrice != null) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("מ־", style = TextStyles.label, color = c.inkDim)
            Text(formatShekel(chainPrice), style = TextStyles.heading, color = c.good)
        }
        else Text("אין מחיר עדיין", style = TextStyles.label, color = c.inkFaint)
    }
}
