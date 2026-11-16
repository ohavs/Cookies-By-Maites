package com.maites.cookies.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import com.maites.cookies.CookiesApp
import com.maites.cookies.data.RecipeCategory
import com.maites.cookies.data.RecipeRow
import com.maites.cookies.data.priceKey
import com.maites.cookies.design.C
import com.maites.cookies.design.CCard
import com.maites.cookies.design.CChip
import com.maites.cookies.design.EmptyState
import com.maites.cookies.design.SearchBox
import com.maites.cookies.design.Space
import com.maites.cookies.design.StickerButton
import com.maites.cookies.design.TextStyles
import java.io.File

@Composable
fun recipeTint(cat: RecipeCategory): Color = with(C.colors) {
    when (cat) {
        RecipeCategory.Cookies -> tPeach; RecipeCategory.Cakes -> tRose; RecipeCategory.Pastry -> tButter
        RecipeCategory.Desserts -> tLav; RecipeCategory.Other -> tMint
    }
}

/** A recipe photo (local file), or the category emoji on its tint. */
@Composable
fun RecipePhoto(path: String?, cat: RecipeCategory, modifier: Modifier = Modifier, corner: Dp = 18.dp, iconSize: Dp = 44.dp) {
    val fallback: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize().background(recipeTint(cat)), contentAlignment = Alignment.Center) {
            Text(cat.emoji, style = TextStyles.display.copy(fontSize = with(LocalDensity.current) { (iconSize * 0.6f).toSp() }))
        }
    }
    Box(modifier.clip(RoundedCornerShape(corner)), contentAlignment = Alignment.Center) {
        if (path == null) fallback()
        else SubcomposeAsyncImage(
            model = File(path), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            loading = { Box(Modifier.fillMaxSize().background(recipeTint(cat))) }, error = { fallback() },
        )
    }
}

/** Pack price per product id: the manual price, else the cheapest in the chosen city. */
@Composable
fun rememberPackPrices(app: CookiesApp): Map<Long, Long> {
    val products by app.db.products().all().collectAsStateWithLifecycle(emptyList())
    val city by app.prefs.city.collectAsStateWithLifecycle("")
    val cheapest by remember(city) { app.db.prices().cheapestInCity(city) }.collectAsStateWithLifecycle(emptyList())
    return remember(products, cheapest) {
        val cheap = cheapest.associate { it.barcode to it.agorot }
        products.mapNotNull { p -> (p.priceAgorot ?: priceKey(p.barcode)?.let(cheap::get))?.let { p.id to it } }.toMap()
    }
}

fun yieldText(count: Int, label: String) = if (count > 0) "$count ${label.ifBlank { "יחידות" }}" else null

private const val ALL = "all"
private const val FAV = "fav"

@Composable
fun RecipesScreen(app: CookiesApp, onAdd: () -> Unit, onOpen: (Long) -> Unit) {
    val c = C.colors
    val rows by app.db.recipes().rows().collectAsStateWithLifecycle(null)
    var q by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(ALL) }
    val list = rows ?: return

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("מתכונים", if (list.isEmpty()) null else "${list.size} מתכונים")
        if (list.isEmpty()) {
            Box(Modifier.weight(1f).padding(bottom = 96.dp), contentAlignment = Alignment.Center) {
                EmptyState("🍪", "עוד אין מתכונים", "המתכון הראשון שלך יופיע כאן, עם תמחיר לכל עוגייה.", c.tPeach) {
                    StickerButton("מתכון חדש", onAdd)
                }
            }
            return
        }
        SearchBox(q, { q = it }, "חיפוש מתכון", Modifier.padding(horizontal = Space.gutter))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Space.gutter, vertical = Space.m),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            CChip("הכל", filter == ALL, { filter = ALL })
            CChip("מועדפים", filter == FAV, { filter = FAV }, "❤️")
            RecipeCategory.entries.filter { cat -> list.any { it.category == cat } }
                .forEach { cat -> CChip(cat.label, filter == cat.name, { filter = cat.name }, cat.emoji) }
        }
        val needle = q.trim().lowercase()
        val shown = list.filter { r ->
            (needle.isEmpty() || r.name.lowercase().contains(needle)) &&
                (filter == ALL || (filter == FAV && r.favorite) || r.category.name == filter)
        }
        if (shown.isEmpty()) {
            Box(Modifier.weight(1f).padding(bottom = 96.dp), contentAlignment = Alignment.Center) {
                if (filter == FAV && needle.isEmpty()) EmptyState("❤️", "אין עדיין מועדפים", "לוחצים על הלב בדף המתכון כדי לשמור אותו כאן.", c.tRose)
                else EmptyState("🔎", "לא נמצא מתכון", "נסו מילה אחרת או קטגוריה אחרת.", c.tSky)
            }
            return
        }
        LazyVerticalGrid(
            GridCells.Fixed(2), Modifier.weight(1f),
            contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, bottom = 120.dp),
            horizontalArrangement = Arrangement.spacedBy(Space.m),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            items(shown, key = { it.id }) { r -> RecipeTile(r) { onOpen(r.id) } }
        }
    }
}

@Composable
private fun RecipeTile(r: RecipeRow, onClick: () -> Unit) {
    val c = C.colors
    CCard(onClick = onClick, padding = PaddingValues(Space.m)) {
        Box {
            RecipePhoto(r.photos.lineSequence().firstOrNull { it.isNotBlank() }, r.category, Modifier.fillMaxWidth().aspectRatio(1f))
            if (r.favorite) Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(30.dp)
                    .shadow(4.dp, RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp)).background(c.surface),
                contentAlignment = Alignment.Center,
            ) { Text("❤️", style = TextStyles.label) }
        }
        Spacer(Modifier.height(Space.s))
        Text(r.name, style = TextStyles.heading, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(yieldText(r.yieldCount, r.yieldLabel), "${r.count} מרכיבים").joinToString(" · "),
            style = TextStyles.label, color = c.inkDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}
