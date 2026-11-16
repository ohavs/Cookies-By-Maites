package com.maites.cookies.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maites.cookies.CookiesApp
import com.maites.cookies.design.C
import com.maites.cookies.design.CCard
import com.maites.cookies.design.CSheet
import com.maites.cookies.design.GhostButton
import com.maites.cookies.design.KeypadSheet
import com.maites.cookies.design.Pill
import com.maites.cookies.design.SectionLabel
import com.maites.cookies.design.Space
import com.maites.cookies.design.Springs
import com.maites.cookies.design.StickerButton
import com.maites.cookies.design.TextStyles
import com.maites.cookies.design.pressable
import kotlinx.coroutines.launch

/** Round icon button used in page top bars. */
@Composable
fun RoundButton(glyph: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = C.colors
    Box(
        modifier.size(44.dp).shadow(8.dp, RoundedCornerShape(15.dp), ambientColor = Color(0x2217140F), spotColor = Color(0x2217140F))
            .clip(RoundedCornerShape(15.dp)).background(c.surface).pressable(onClick),
        contentAlignment = Alignment.Center,
    ) { Text(glyph, style = TextStyles.heading, color = c.ink) }
}

@Composable
fun ProductPage(app: CookiesApp, id: Long, onBack: () -> Unit, onEdit: () -> Unit, toast: (String) -> Unit) {
    val c = C.colors
    val dao = app.db.products()
    val p by remember(id) { dao.byId(id) }.collectAsStateWithLifecycle(null)
    val scope = rememberCoroutineScope()
    var keypad by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val prod = p ?: return
    val favPop by animateFloatAsState(if (prod.favorite) 1f else 0f, Springs.pop(), label = "fav")

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.m), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                RoundButton("→", onBack)
                Spacer(Modifier.weight(1f))
                RoundButton(if (prod.favorite) "❤️" else "🤍", {
                    scope.launch { dao.setFavorite(prod.id, !prod.favorite) }
                    toast(if (prod.favorite) "הוסר מהמועדפים" else "נשמר במועדפים")
                }, Modifier.graphicsLayer { val s = 1f + 0.12f * favPop * (1f - favPop) * 4f; scaleX = s; scaleY = s })
                RoundButton("✏️", onEdit)
            }
            ProductImage(prod, Modifier.padding(horizontal = Space.gutter).fillMaxWidth().height(240.dp), corner = 28.dp, iconSize = 120.dp)

            Column(Modifier.padding(Space.gutter)) {
                Text(prod.name, style = TextStyles.display, color = c.ink)
                if (prod.brand.isNotBlank()) Text(prod.brand, style = TextStyles.body, color = c.inkDim)
                Spacer(Modifier.height(Space.m))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Pill(formatPack(prod.packAmount, prod.unit))
                    Pill("${prod.category.emoji} ${prod.category.label}", categoryTint(prod.category))
                    prod.barcode?.let { Pill(it, ltr = true) }
                }

                Spacer(Modifier.height(Space.xl))
                SectionLabel("המחיר שלי")
                CCard(padding = androidx.compose.foundation.layout.PaddingValues(horizontal = Space.l, vertical = Space.xs)) {
                    Row(Modifier.fillMaxWidth().pressable({ keypad = true }).padding(vertical = Space.m), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(c.tButter), contentAlignment = Alignment.Center) { Text("🏷️", style = TextStyles.heading) }
                        Spacer(Modifier.size(Space.m))
                        Column(Modifier.weight(1f)) {
                            Text(if (prod.priceAgorot != null) "מחיר ידני" else "הזנת מחיר ידני", style = TextStyles.heading, color = c.ink)
                            Text(
                                prod.priceAgorot?.let { formatUnitPrice(it, prod.packAmount, prod.unit) } ?: "לאריזה של ${formatPack(prod.packAmount, prod.unit)}",
                                style = TextStyles.label, color = c.inkDim,
                            )
                        }
                        Text(prod.priceAgorot?.let { formatShekel(it) } ?: "—", style = TextStyles.title, color = c.ink)
                    }
                    if (prod.priceAgorot != null) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
                        Row(Modifier.fillMaxWidth().pressable({
                            scope.launch { dao.setPrice(prod.id, null) }; toast("המחיר הידני נמחק")
                        }).padding(vertical = Space.m)) {
                            Text("מחיקת המחיר הידני", style = TextStyles.heading, color = c.danger)
                        }
                    }
                }

                Spacer(Modifier.height(Space.xl))
                GhostButton("מחיקת המוצר", { confirmDelete = true }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(Space.xl))
            }
        }

        KeypadSheet(
            open = keypad, title = "המחיר שלי", lead = "${prod.name} · ${formatPack(prod.packAmount, prod.unit)}",
            initial = prod.priceAgorot, onDismiss = { keypad = false },
        ) { v ->
            keypad = false
            scope.launch { dao.setPrice(prod.id, v) }
            toast("המחיר נשמר")
        }

        CSheet(confirmDelete, { confirmDelete = false }) {
            Text("למחוק את ${prod.name}?", style = TextStyles.title, color = c.ink)
            Text("המוצר והמחיר שלו יימחקו מהרשימה.", style = TextStyles.body, color = c.inkDim)
            Spacer(Modifier.height(Space.xl))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
                GhostButton("ביטול", { confirmDelete = false }, Modifier.weight(1f))
                StickerButton("מחיקה", {
                    confirmDelete = false
                    onBack()
                    scope.launch { dao.delete(prod) }
                    toast("${prod.name} נמחק")
                }, Modifier.weight(1f))
            }
        }
    }
}
