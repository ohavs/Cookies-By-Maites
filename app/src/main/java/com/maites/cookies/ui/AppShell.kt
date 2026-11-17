package com.maites.cookies.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.maites.cookies.CookiesApp
import com.maites.cookies.data.UiPrefs
import com.maites.cookies.design.C
import com.maites.cookies.design.CCard
import com.maites.cookies.design.Radius
import com.maites.cookies.design.Space
import com.maites.cookies.design.Springs
import com.maites.cookies.design.TextStyles
import com.maites.cookies.design.Toast
import com.maites.cookies.design.pressable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed interface Page
class ProductPg(val id: Long) : Page
class EditPg(val id: Long? = null, val barcode: String? = null) : Page
class RecipePg(val id: Long) : Page
class RecipeEditPg(val id: Long? = null) : Page
class CostingPg(val id: Long) : Page
object CategoriesPg : Page

enum class Tab(val label: String, val emoji: String) {
    Recipes("מתכונים", "🍪"), Products("מוצרים", "🧈"), Shopping("קניות", "🛒"), Settings("הגדרות", "⚙️")
}

@Composable
fun AppShell(app: CookiesApp, prefs: UiPrefs) {
    val c = C.colors
    var tab by rememberSaveable { mutableStateOf(Tab.Recipes) }
    var addOpen by remember { mutableStateOf(false) }
    var cityOpen by remember { mutableStateOf(false) }
    var shopAdd by remember { mutableStateOf(false) }
    var shopClear by remember { mutableStateOf(false) }
    var cookbook by remember { mutableStateOf(false) }
    var excel by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { app.applicationScope.launch { app.catalog.sync() }; app.priceSync.sync() }
    var toast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(toast) { if (toast != null) { delay(2200); toast = null } }
    val showToast: (String) -> Unit = { toast = it }
    val stack = remember { mutableStateListOf<Page>() }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val push: (Page) -> Unit = { stack.add(it) }
    val pop: () -> Unit = { stack.removeLastOrNull() }
    val replaceTop: (Page) -> Unit = { if (stack.isEmpty()) stack.add(it) else stack[stack.lastIndex] = it }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        AnimatedContent(
            targetState = tab,
            transitionSpec = { fadeIn(Springs.soft()) togetherWith fadeOut(Springs.soft()) },
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            label = "tab",
        ) { t ->
            when (t) {
                Tab.Recipes -> RecipesScreen(app, onAdd = { if (editable(showToast)) push(RecipeEditPg()) }, onOpen = { push(RecipePg(it)) })
                Tab.Products -> ProductsScreen(app, onAdd = { if (editable(showToast)) push(EditPg()) }, onOpen = { push(ProductPg(it)) })
                Tab.Shopping -> ShoppingScreen(app, onAdd = { if (editable(showToast)) shopAdd = true }, onClear = { if (editable(showToast)) shopClear = true }, onPickCity = { cityOpen = true }, onRecipe = { push(RecipePg(it)) }, onProduct = { push(ProductPg(it)) }, toast = showToast)
                Tab.Settings -> SettingsScreen(app, prefs, showToast, onPickCity = { cityOpen = true }, onCategories = { if (editable(showToast)) push(CategoriesPg) }, onCookbook = { cookbook = true }, onImport = { if (editable(showToast)) excel = true })
            }
        }

        BottomBar(tab, onTab = { tab = it }, onAdd = { if (editable(showToast)) addOpen = true }, modifier = Modifier.align(Alignment.BottomCenter))

        PageHost(stack, pop) { pg ->
            when (pg) {
                is ProductPg -> ProductPage(app, pg.id, onBack = pop, onEdit = { push(EditPg(pg.id)) }, onPickCity = { cityOpen = true }, toast = showToast)
                is EditPg -> ProductEditor(
                    app, pg.id, pg.barcode, onClose = pop,
                    onSaved = { id -> if (pg.id == null) replaceTop(ProductPg(id)) else pop() },
                    onExisting = { id -> replaceTop(ProductPg(id)) },
                    toast = showToast,
                )
                is RecipePg -> RecipePage(app, pg.id, onBack = pop, onEdit = { push(RecipeEditPg(pg.id)) }, onProduct = { push(ProductPg(it)) }, onCosting = { push(CostingPg(pg.id)) }, toast = showToast)
                is CostingPg -> CostingPage(app, pg.id, onBack = pop, toast = showToast)
                is CategoriesPg -> CategoriesPage(app, onBack = pop)
                is RecipeEditPg -> RecipeEditor(
                    app, pg.id, onClose = pop,
                    onSaved = { id -> if (pg.id == null) replaceTop(RecipePg(id)) else pop() },
                    toast = showToast,
                )
            }
        }

        ShopAddSheet(app, shopAdd, { shopAdd = false }, showToast)
        ShopClearSheet(app, shopClear, { shopClear = false }, showToast)
        CityPickerSheet(app, cityOpen, { cityOpen = false }, showToast)
        CookbookSheet(app, cookbook, { cookbook = false }, showToast)
        ImportSheet(app, excel, { excel = false }, showToast)

        Toast(toast, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 104.dp))

        AddSheet(open = addOpen, onDismiss = { addOpen = false }) { what ->
            addOpen = false
            when (what) {
                AddWhat.Recipe -> { tab = Tab.Recipes; push(RecipeEditPg()) }
                AddWhat.Product -> { tab = Tab.Products; push(EditPg()) }
                AddWhat.Scan -> scope.launch {
                    when (val r = scanBarcode(ctx)) {
                        is ScanResult.Code -> {
                            tab = Tab.Products
                            val existing = app.db.products().byBarcode(r.value)
                            if (existing != null) { showToast("${existing.name} כבר ברשימה"); push(ProductPg(existing.id)) }
                            else push(EditPg(barcode = r.value))
                        }
                        is ScanResult.Failed -> showToast("הסריקה לא זמינה במכשיר")
                        ScanResult.Cancelled -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomBar(tab: Tab, onTab: (Tab) -> Unit, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    val c = C.colors
    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Space.l, vertical = Space.m)) {
        Row(
            Modifier.fillMaxWidth().height(68.dp)
                .shadow(18.dp, RoundedCornerShape(26.dp), ambientColor = Color(0x4717140F), spotColor = Color(0x4717140F))
                .clip(RoundedCornerShape(26.dp)).background(c.surface).padding(horizontal = Space.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            TabItem(Tab.Recipes, tab, onTab)
            TabItem(Tab.Products, tab, onTab)
            Spacer(Modifier.width(64.dp))
            TabItem(Tab.Shopping, tab, onTab)
            TabItem(Tab.Settings, tab, onTab)
        }
        // Raised "+" sticker: hard shadow down-right, button exactly centred over the gap.
        Box(Modifier.align(Alignment.TopCenter).offset(y = (-20).dp).size(64.dp)) {
            Box(Modifier.matchParentSize().absoluteOffset(x = 3.dp, y = 3.dp).clip(CircleShape).background(c.stroke))
            Box(
                Modifier.matchParentSize().clip(CircleShape).background(c.accent).border(2.5.dp, c.stroke, CircleShape).pressable(onAdd),
                contentAlignment = Alignment.Center,
            ) {
                // Drawn, not a "+" glyph — font glyphs sit off-centre.
                androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
                    val w = 3.5.dp.toPx()
                    val col = c.accentFg
                    drawLine(col, androidx.compose.ui.geometry.Offset(size.width / 2, 0f), androidx.compose.ui.geometry.Offset(size.width / 2, size.height), w, androidx.compose.ui.graphics.StrokeCap.Round)
                    drawLine(col, androidx.compose.ui.geometry.Offset(0f, size.height / 2), androidx.compose.ui.geometry.Offset(size.width, size.height / 2), w, androidx.compose.ui.graphics.StrokeCap.Round)
                }
            }
        }
    }
}

@Composable
private fun TabItem(t: Tab, current: Tab, onTab: (Tab) -> Unit) {
    val c = C.colors
    val on = t == current
    val color by animateColorAsState(if (on) c.ink else c.inkFaint, Springs.soft(), label = "tabc")
    val lift by animateFloatAsState(if (on) 1f else 0f, Springs.pop(), label = "lift")
    Column(
        Modifier.width(64.dp).pressable({ onTab(t) }).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(t.emoji, style = TextStyles.title, modifier = Modifier.graphicsLayer {
            val s = 0.9f + 0.15f * lift; scaleX = s; scaleY = s; alpha = 0.55f + 0.45f * lift
        })
        Text(t.label, style = TextStyles.caption, color = color)
    }
}

enum class AddWhat { Recipe, Product, Scan }

@Composable
private fun AddSheet(open: Boolean, onDismiss: () -> Unit, onPick: (AddWhat) -> Unit) {
    val c = C.colors
    com.maites.cookies.design.CSheet(open, onDismiss) {
        Text("מה מוסיפים?", style = TextStyles.title, color = c.ink)
        Spacer(Modifier.height(Space.l))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            AddOption("🍪", "מתכון", c.tPeach, Modifier.weight(1f)) { onPick(AddWhat.Recipe) }
            AddOption("🧈", "מוצר", c.tButter, Modifier.weight(1f)) { onPick(AddWhat.Product) }
            AddOption("📷", "סריקה", c.tSky, Modifier.weight(1f)) { onPick(AddWhat.Scan) }
        }
        Spacer(Modifier.height(Space.s))
    }
}

@Composable
private fun AddOption(emoji: String, label: String, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    val c = C.colors
    CCard(modifier, onClick = onClick) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(tint).border(2.dp, c.stroke, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
            Text(emoji, style = TextStyles.title)
        }
        Spacer(Modifier.height(Space.m))
        Text(label, style = TextStyles.heading, color = c.ink)
    }
}
