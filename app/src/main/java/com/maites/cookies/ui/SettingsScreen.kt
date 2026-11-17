package com.maites.cookies.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.maites.cookies.BuildConfig
import com.maites.cookies.CookiesApp
import com.maites.cookies.data.SyncState
import com.maites.cookies.data.UiPrefs
import com.maites.cookies.design.Accent
import com.maites.cookies.design.C
import com.maites.cookies.design.CCard
import com.maites.cookies.design.ColorDot
import com.maites.cookies.design.GhostButton
import com.maites.cookies.design.SectionLabel
import com.maites.cookies.design.Segmented
import com.maites.cookies.design.SettingRow
import com.maites.cookies.design.Space
import com.maites.cookies.design.Springs
import com.maites.cookies.design.StickerButton
import com.maites.cookies.design.TextStyles
import com.maites.cookies.design.ThemeMode
import com.maites.cookies.update.UpdateState
import com.maites.cookies.update.Updater
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(app: CookiesApp, prefs: UiPrefs, toast: (String) -> Unit, onPickCity: () -> Unit, onCategories: () -> Unit, onCookbook: () -> Unit, onImport: () -> Unit) {
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 120.dp)) {
        ScreenTitle("הגדרות")
        Column(Modifier.padding(horizontal = Space.gutter), verticalArrangement = Arrangement.spacedBy(Space.xl)) {
            Column {
                SectionLabel("מראה")
                CCard {
                    Text("ערכת צבעים", style = TextStyles.heading)
                    Spacer(Modifier.height(Space.m))
                    Segmented(ThemeMode.entries, prefs.mode, { it.label }, { m -> scope.launch { app.prefs.setMode(m) } }, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(Space.l))
                    Text("צבע מוביל", style = TextStyles.heading)
                    Spacer(Modifier.height(Space.m))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                        Accent.entries.forEach { a ->
                            ColorDot(a.base, a == prefs.accent, { scope.launch { app.prefs.setAccent(a) } })
                        }
                    }
                    Spacer(Modifier.height(Space.s))
                    Text(prefs.accent.label, style = TextStyles.label, color = C.colors.inkDim)
                }
            }
            Column {
                SectionLabel("ארגון")
                CCard(padding = androidx.compose.foundation.layout.PaddingValues(horizontal = Space.l, vertical = Space.xs)) {
                    SettingRow("קטגוריות", "הוספה, שינוי שם, אייקון וצבע", onCategories) {
                        Text("🗂️", style = TextStyles.heading)
                    }
                }
            }
            Column {
                SectionLabel("ייבוא וייצוא")
                CCard(padding = androidx.compose.foundation.layout.PaddingValues(horizontal = Space.l, vertical = Space.xs)) {
                    SettingRow("ספר מתכונים", "PDF לשיתוף או להדפסה", onCookbook) { Text("📖", style = TextStyles.heading) }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(C.colors.divider))
                    SettingRow("אקסל", "ייבוא מוצרים ומתכונים, או קובץ לדוגמה", onImport) { Text("📊", style = TextStyles.heading) }
                }
            }
            Column {
                SectionLabel("גיבוי וסנכרון")
                AccountCard(app, toast)
                Spacer(Modifier.height(Space.m))
                ShareCard(app, toast)
            }
            Column {
                SectionLabel("מחירים")
                val city by app.prefs.city.collectAsStateWithLifecycle("")
                CCard(padding = androidx.compose.foundation.layout.PaddingValues(horizontal = Space.l, vertical = Space.xs)) {
                    SettingRow("עיר", city.ifBlank { "עוד לא נבחרה" }, onPickCity) {
                        Text("📍", style = TextStyles.heading)
                    }
                }
                if (city.isNotBlank()) {
                    Spacer(Modifier.height(Space.m))
                    ChainsCard(app, city)
                }
            }
            Column {
                SectionLabel("עדכונים")
                UpdateCard(toast)
            }
        }
    }
}

@Composable
private fun UpdateCard(toast: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = C.colors
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    var pendingInstall by remember { mutableStateOf<java.io.File?>(null) }

    LifecycleResumeEffect(pendingInstall) {
        val f = pendingInstall
        if (f != null && Updater.canInstall(ctx)) { pendingInstall = null; Updater.install(ctx, f) }
        onPauseOrDispose { }
    }

    fun check() = scope.launch {
        state = UpdateState.Checking
        state = runCatching { Updater.latest() }.fold(
            onSuccess = { r -> if (r != null && Updater.isNewer(r)) UpdateState.Available(r) else UpdateState.UpToDate },
            onFailure = { UpdateState.Error("אין חיבור ל-GitHub. נסי שוב.") },
        )
    }

    fun install(f: java.io.File) {
        if (Updater.canInstall(ctx)) Updater.install(ctx, f)
        else { pendingInstall = f; toast("צריך לאשר התקנה מהאפליקציה — פעם אחת"); ctx.startActivity(Updater.installPermissionIntent(ctx)) }
    }

    CCard {
        SettingRow("גרסה ${BuildConfig.VERSION_NAME}", "Cookies by Maites")
        Spacer(Modifier.height(Space.s))
        AnimatedContent(state, transitionSpec = { fadeIn(Springs.soft()) togetherWith fadeOut(Springs.soft()) }, label = "upd") { s ->
            Column {
                when (s) {
                    UpdateState.Idle -> StickerButton("בדוק עדכונים", { check() })
                    UpdateState.Checking -> Text("בודקת…", style = TextStyles.body, color = c.inkDim)
                    UpdateState.UpToDate -> {
                        Text("יש לך את הגרסה האחרונה ✓", style = TextStyles.body, color = c.good)
                        Spacer(Modifier.height(Space.m)); GhostButton("בדוק שוב", { check() })
                    }
                    is UpdateState.Available -> {
                        Text("גרסה ${s.release.versionName} זמינה", style = TextStyles.heading, color = c.accentInk)
                        if (s.release.notes.isNotBlank()) Text(s.release.notes, style = TextStyles.label, color = c.inkDim, maxLines = 4)
                        Spacer(Modifier.height(Space.m))
                        StickerButton("הורד והתקן", {
                            scope.launch {
                                state = UpdateState.Downloading(0f)
                                runCatching { Updater.download(ctx, s.release) { p -> state = UpdateState.Downloading(p) } }
                                    .onSuccess { f -> state = UpdateState.Ready(f); install(f) }
                                    .onFailure { state = UpdateState.Error("ההורדה נכשלה. נסי שוב.") }
                            }
                        })
                    }
                    is UpdateState.Downloading -> {
                        Text("מורידה… ${(s.progress * 100).toInt()}%", style = TextStyles.body, color = c.inkDim)
                        Spacer(Modifier.height(Space.s))
                        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(99.dp)).background(c.surface2)) {
                            Box(Modifier.fillMaxWidth(s.progress.coerceIn(0f, 1f)).height(8.dp).clip(RoundedCornerShape(99.dp)).background(c.accent))
                        }
                    }
                    is UpdateState.Ready -> StickerButton("התקן עכשיו", { install(s.file) })
                    is UpdateState.Error -> {
                        Text(s.message, style = TextStyles.body, color = c.danger)
                        Spacer(Modifier.height(Space.m)); GhostButton("נסה שוב", { check() })
                    }
                }
            }
        }
    }
}

/** Every chain: how many stores in the city, and whether its prices could be read. */
@Composable
private fun ChainsCard(app: CookiesApp, city: String) {
    val c = C.colors
    val counts by androidx.compose.runtime.remember(city) { app.db.prices().storesInCity(city) }.collectAsStateWithLifecycle(emptyList())
    val local by app.prefs.localState.collectAsStateWithLifecycle(com.maites.cookies.data.LocalState(0, "", ""))
    val failed = local.status.split(';').mapNotNull { row -> row.split(':').takeIf { it.getOrNull(1) == "err" }?.let { it[0] to it.getOrNull(2).orEmpty() } }.toMap()
    val n = counts.associate { it.chain to it.n }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    CCard(padding = androidx.compose.foundation.layout.PaddingValues(horizontal = Space.l, vertical = Space.s)) {
        Text("רשתות ב$city", style = TextStyles.heading, color = c.ink, modifier = Modifier.padding(top = Space.s))
        Text("המחירים מתעדכנים פעם ביום", style = TextStyles.label, color = c.inkDim)
        Spacer(Modifier.height(Space.s))
        com.maites.cookies.data.Chain.entries.forEach { ch ->
            val err = failed[ch.key]
            val stores = n[ch.key] ?: 0
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(ch.emoji, style = TextStyles.body)
                Spacer(Modifier.width(Space.s))
                Text(ch.label, style = TextStyles.body, color = c.ink, modifier = Modifier.weight(1f))
                Text(
                    when {
                        err != null -> "לא הצלחנו לקרוא"
                        stores == 0 -> "אין סניף בעיר"
                        stores == 1 -> "סניף אחד"
                        else -> "$stores סניפים"
                    },
                    style = TextStyles.label, color = when { err != null -> c.danger; stores == 0 -> c.inkFaint; else -> c.good },
                )
            }
        }
        if (failed.isNotEmpty()) {
            Text(failed.values.first().take(60), style = TextStyles.caption, color = c.inkFaint)
            Spacer(Modifier.height(Space.s))
            GhostButton(if (busy) "מנסה…" else "לנסות שוב", {
                if (!busy) scope.launch { busy = true; app.priceSync.sync(force = true); busy = false }
            }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(Space.s))
        }
    }
}

/** Google account: optional; when signed in, recipes and products are mirrored to the cloud. */
@Composable
private fun AccountCard(app: CookiesApp, toast: (String) -> Unit) {
    val c = C.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val account by app.prefs.account.collectAsStateWithLifecycle(null)
    val sync by app.cloud.state.collectAsStateWithLifecycle()
    val last by app.prefs.lastCloudSync.collectAsStateWithLifecycle(0L)
    var busy by remember { mutableStateOf(false) }
    var confirmOut by remember { mutableStateOf(false) }

    CCard {
        AnimatedContent(account, transitionSpec = { fadeIn(Springs.soft()) togetherWith fadeOut(Springs.soft()) }, label = "acc") { acc ->
            Column {
                if (acc == null) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("☁️", style = TextStyles.title)
                        Spacer(Modifier.width(Space.m))
                        Column(Modifier.weight(1f)) {
                            Text("שמירה בענן", style = TextStyles.heading, color = c.ink)
                            Text("המתכונים, המוצרים והתמונות נשמרים בחשבון הגוגל ועוברים לכל טלפון שתתחברי ממנו", style = TextStyles.label, color = c.inkDim)
                        }
                    }
                    Spacer(Modifier.height(Space.l))
                    StickerButton(if (busy) "מתחברת…" else "התחברות עם Google", {
                        val act = ctx as? android.app.Activity ?: return@StickerButton
                        if (!busy) scope.launch {
                            busy = true
                            runCatching { app.auth.signIn(act) }
                                .onSuccess { a -> if (a != null) { toast("מחוברת כ${a.name.ifBlank { a.email }}"); app.applicationScope.launch { app.cloud.afterSignIn() } } }
                                .onFailure { toast(it.message ?: "ההתחברות נכשלה") }
                            busy = false
                        }
                    }, Modifier.fillMaxWidth())
                } else {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Box(Modifier.size(48.dp).clip(androidx.compose.foundation.shape.CircleShape).background(c.surface2), contentAlignment = androidx.compose.ui.Alignment.Center) {
                            Text(acc.name.ifBlank { acc.email }.take(1), style = TextStyles.heading, color = c.ink)
                            if (acc.photo.isNotBlank()) coil.compose.AsyncImage(acc.photo, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                        }
                        Spacer(Modifier.width(Space.m))
                        Column(Modifier.weight(1f)) {
                            Text(acc.name.ifBlank { "מחוברת" }, style = TextStyles.heading, color = c.ink)
                            Text(acc.email, style = TextStyles.label, color = c.inkDim)
                        }
                    }
                    Spacer(Modifier.height(Space.m))
                    val (line, tint) = when (val s = sync) {
                        SyncState.Running -> "מסנכרנת…" to c.inkDim
                        is SyncState.Failed -> s.message to c.danger
                        SyncState.Idle -> (if (last == 0L) "עוד לא סונכרן" else "מסונכרן ✓ · ${ago(last)}") to (if (last == 0L) c.inkDim else c.good)
                    }
                    Text(line, style = TextStyles.label, color = tint)
                    Spacer(Modifier.height(Space.m))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                        GhostButton("לסנכרן עכשיו", { app.cloud.syncSoon() }, Modifier.weight(1f))
                        GhostButton("התנתקות", { confirmOut = true }, Modifier.weight(1f))
                    }
                }
            }
        }
    }

    com.maites.cookies.design.CSheet(confirmOut, { confirmOut = false }) {
        Text("להתנתק מהחשבון?", style = TextStyles.title, color = c.ink)
        Text(if (com.maites.cookies.data.Workspace.joined != null) "תחזרי לנתונים שלך בטלפון. החשבון המשותף נשאר בענן." else "הכל נשאר בטלפון ובענן. שינויים מעכשיו לא יגובו עד שתתחברי שוב.", style = TextStyles.body, color = c.inkDim)
        Spacer(Modifier.height(Space.xl))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            GhostButton("ביטול", { confirmOut = false }, Modifier.weight(1f))
            StickerButton("התנתקות", {
                confirmOut = false
                scope.launch {
                    app.auth.signOut(ctx); toast("התנתקת")
                    if (com.maites.cookies.data.Workspace.joined != null) (ctx as? android.app.Activity)?.let { com.maites.cookies.data.Workspace.switch(it, null) }
                }
            }, Modifier.weight(1f))
        }
    }
}

private fun ago(t: Long): String {
    val m = (System.currentTimeMillis() - t) / 60_000
    return when {
        m < 1 -> "עכשיו"
        m < 60 -> "לפני $m דק׳"
        m < 24 * 60 -> "לפני ${m / 60} שע׳"
        else -> "לפני ${m / (24 * 60)} ימים"
    }
}
