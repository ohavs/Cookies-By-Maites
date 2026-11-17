package com.maites.cookies.ui

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maites.cookies.CookiesApp
import com.maites.cookies.data.Invite
import com.maites.cookies.data.Joined
import com.maites.cookies.data.Member
import com.maites.cookies.data.Workspace
import com.maites.cookies.design.C
import com.maites.cookies.design.CCard
import com.maites.cookies.design.CSheet
import com.maites.cookies.design.CTextField
import com.maites.cookies.design.ConfirmSheet
import com.maites.cookies.design.GhostButton
import com.maites.cookies.design.Pill
import com.maites.cookies.design.Segmented
import com.maites.cookies.design.SettingRow
import com.maites.cookies.design.Space
import com.maites.cookies.design.StickerButton
import com.maites.cookies.design.TextStyles
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** False (with a toast) for viewers of a shared account. */
fun editable(toast: (String) -> Unit): Boolean =
    Workspace.canEdit.also { if (!it) toast("צפייה בלבד — אין הרשאת עריכה") }

private val roles = listOf("view", "edit")
private fun roleLabel(r: String) = if (r == "edit") "עריכה" else "צפייה"

/**
 * Sharing the account by Gmail. The owner invites and sets view/edit; the invitee joins and then sees
 * exactly the owner's recipes, products, categories and shopping list (their own data waits aside).
 */
@Composable
fun ShareCard(app: CookiesApp, toast: (String) -> Unit) {
    val account by app.prefs.account.collectAsStateWithLifecycle(null)
    val acc = account ?: return
    val joined = Workspace.joined
    if (joined != null) JoinedCard(app, joined, acc.email, toast) else OwnerCard(app, toast)
}

/** We're inside someone else's account. */
@Composable
private fun JoinedCard(app: CookiesApp, j: Joined, myEmail: String, toast: (String) -> Unit) {
    val c = C.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val role by Workspace.role.collectAsStateWithLifecycle()
    var leave by remember { mutableStateOf(false) }
    val who = j.ownerName.ifBlank { j.ownerEmail }
    CCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("👥", style = TextStyles.title)
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                Text("החשבון של $who", style = TextStyles.heading, color = c.ink)
                Text(if (role == "edit") "אפשר לראות ולשנות הכל" else "צפייה בלבד — אי אפשר לשנות", style = TextStyles.label, color = c.inkDim)
            }
            Pill(roleLabel(role))
        }
        Spacer(Modifier.height(Space.l))
        GhostButton("חזרה לחשבון שלי", { leave = true }, Modifier.fillMaxWidth())
    }
    ConfirmSheet(
        leave, "לחזור לחשבון שלך?",
        "הנתונים שלך יחזרו בדיוק כמו שהשארת אותם. אפשר להצטרף ל$who שוב מכאן, כל עוד ההזמנה בתוקף.",
        "חזרה", { leave = false },
    ) {
        leave = false
        scope.launch {
            app.cloud.sync() // let the last edits reach the shared account
            (ctx as? Activity)?.let { Workspace.switch(it, null) }
        }
    }
}

/** Our own account: a pending invitation for us, and the people we shared with. */
@Composable
private fun OwnerCard(app: CookiesApp, toast: (String) -> Unit) {
    val c = C.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var members by remember { mutableStateOf<List<Member>?>(null) }
    var invite by remember { mutableStateOf<Invite?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var inviting by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Member?>(null) }
    var joining by remember { mutableStateOf(false) }

    LaunchedEffect(reload) {
        runCatching { app.sharing.members() to app.sharing.mine() }
            .onSuccess { (m, i) -> members = m; invite = i; error = null }
            .onFailure { error = it.message ?: "אין חיבור לענן" }
    }

    invite?.let { inv ->
        val who = inv.ownerName.ifBlank { inv.ownerEmail }
        CCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("💌", style = TextStyles.title)
                Spacer(Modifier.width(Space.m))
                Column(Modifier.weight(1f)) {
                    Text("$who מזמינה אותך", style = TextStyles.heading, color = c.ink)
                    Text("לחשבון המשותף · ${roleLabel(inv.role)}", style = TextStyles.label, color = c.inkDim)
                }
            }
            Spacer(Modifier.height(Space.l))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                GhostButton("לא תודה", {
                    scope.launch {
                        val me = app.prefs.account.first()?.email?.lowercase() ?: return@launch
                        runCatching { app.sharing.remove(me) }.onSuccess { invite = null }.onFailure { toast(it.message ?: "נכשל") }
                    }
                }, Modifier.weight(1f))
                StickerButton("הצטרפות", { joining = true }, Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(Space.m))
        ConfirmSheet(
            joining, "להצטרף לחשבון של $who?",
            "תראי בדיוק מה ש$who רואה — מתכונים, מוצרים, קטגוריות ורשימת קניות" +
                (if (inv.role == "edit") ", וכל שינוי שלך יופיע אצלה." else ". צפייה בלבד.") +
                " הנתונים שלך נשמרים בצד, ואפשר לחזור אליהם מתי שרוצים.",
            "הצטרפות", { joining = false },
        ) {
            joining = false
            scope.launch {
                runCatching { app.sharing.markJoined() }
                    .onSuccess { (ctx as? Activity)?.let { Workspace.switch(it, Joined(inv.owner, inv.ownerName, inv.ownerEmail, inv.role)) } }
                    .onFailure { toast(it.message ?: "ההצטרפות נכשלה") }
            }
        }
    }

    CCard(padding = PaddingValues(horizontal = Space.l, vertical = Space.s)) {
        Text("שיתוף החשבון", style = TextStyles.heading, color = c.ink, modifier = Modifier.padding(top = Space.s))
        Text("מי שמוזמן רואה בדיוק את מה שאת רואה", style = TextStyles.label, color = c.inkDim)
        Spacer(Modifier.height(Space.s))
        when {
            error != null && members == null -> {
                Text(error!!, style = TextStyles.label, color = c.danger)
                Spacer(Modifier.height(Space.s))
                GhostButton("לנסות שוב", { reload++ }, Modifier.fillMaxWidth())
            }
            members == null -> Text("טוענת…", style = TextStyles.label, color = c.inkFaint)
            else -> members!!.forEach { m ->
                SettingRow(m.name.ifBlank { m.email }, if (m.joined) m.email else "ממתינ/ה להצטרפות · ${m.email}", { editing = m }) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(c.surface2), contentAlignment = Alignment.Center) {
                        Text(m.name.ifBlank { m.email }.take(1).uppercase(), style = TextStyles.body, color = c.ink)
                    }
                    Spacer(Modifier.width(Space.s))
                    Pill(roleLabel(m.role))
                }
            }
        }
        Spacer(Modifier.height(Space.s))
        StickerButton("הזמנת משתמש", { inviting = true }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(Space.s))
    }

    InviteSheet(app, inviting, { inviting = false }, onDone = { reload++ })
    MemberSheet(app, editing, { editing = null }, toast, onChanged = { reload++ })
}

@Composable
private fun InviteSheet(app: CookiesApp, open: Boolean, onDismiss: () -> Unit, onDone: () -> Unit) {
    val c = C.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by remember(open) { mutableStateOf("") }
    var role by remember(open) { mutableStateOf("edit") }
    var busy by remember(open) { mutableStateOf(false) }
    var error by remember(open) { mutableStateOf<String?>(null) }
    var sent by remember(open) { mutableStateOf<String?>(null) }

    CSheet(open, onDismiss) {
        val to = sent
        if (to == null) {
            Text("הזמנת משתמש", style = TextStyles.title, color = c.ink)
            Text("כתובת ה-Gmail שאיתה יתחברו לאפליקציה", style = TextStyles.body, color = c.inkDim)
            Spacer(Modifier.height(Space.l))
            CTextField(email, { email = it; error = null }, "Gmail", placeholder = "name@gmail.com", ltr = true, keyboardType = KeyboardType.Email)
            Spacer(Modifier.height(Space.l))
            Segmented(roles, role, { if (it == "edit") "עריכה" else "צפייה בלבד" }, { role = it }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(Space.s))
            Text(if (role == "edit") "רואים ומשנים הכל, כמו בחשבון שלך" else "רואים הכל, בלי לשנות", style = TextStyles.label, color = c.inkDim)
            error?.let { Spacer(Modifier.height(Space.s)); Text(it, style = TextStyles.label, color = c.danger) }
            Spacer(Modifier.height(Space.xl))
            StickerButton(if (busy) "שולחת…" else "הזמנה", {
                if (!busy) scope.launch {
                    busy = true
                    runCatching { app.sharing.invite(email, role) }
                        .onSuccess { sent = email.trim().lowercase(); onDone() }
                        .onFailure { error = it.message ?: "ההזמנה נכשלה" }
                    busy = false
                }
            }, Modifier.fillMaxWidth(), enabled = email.contains('@'))
        } else {
            Text("ההזמנה מוכנה ✓", style = TextStyles.title, color = c.ink)
            Text("$to יראה אותה בהגדרות → גיבוי וסנכרון, אחרי התחברות עם הכתובת הזו.", style = TextStyles.body, color = c.inkDim)
            Spacer(Modifier.height(Space.xl))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                GhostButton("סיום", onDismiss, Modifier.weight(1f))
                StickerButton("שליחת הודעה", {
                    val text = "הזמנתי אותך לחשבון שלי ב-Cookies by Maites 🍪\n" +
                        "1. מתקינים: https://github.com/ohavs/cookies-by-maites/releases/latest/download/cookies-by-maites.apk\n" +
                        "2. הגדרות → התחברות עם Google ($to)\n3. \"הצטרפות\""
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
                    onDismiss()
                }, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MemberSheet(app: CookiesApp, member: Member?, onDismiss: () -> Unit, toast: (String) -> Unit, onChanged: () -> Unit) {
    val c = C.colors
    val scope = rememberCoroutineScope()
    var last by remember { mutableStateOf<Member?>(null) }
    if (member != null) last = member
    val m = last ?: return
    var role by remember(m) { mutableStateOf(m.role) }
    var confirm by remember(m) { mutableStateOf(false) }

    CSheet(member != null, onDismiss) {
        Text(m.name.ifBlank { m.email }, style = TextStyles.title, color = c.ink)
        Text(m.email, style = TextStyles.body, color = c.inkDim)
        Spacer(Modifier.height(Space.l))
        Segmented(roles, role, { if (it == "edit") "עריכה" else "צפייה בלבד" }, { r ->
            val old = role
            role = r
            scope.launch {
                runCatching { app.sharing.setRole(m.email, r) }.onSuccess { onChanged() }.onFailure { role = old; toast(it.message ?: "נכשל") }
            }
        }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(Space.xl))
        GhostButton("הסרה מהחשבון", { confirm = true }, Modifier.fillMaxWidth())
    }
    ConfirmSheet(confirm, "להסיר את ${m.name.ifBlank { m.email }}?", "הגישה לחשבון תיחסם מיד. הנתונים שלך לא משתנים.", "הסרה", { confirm = false }) {
        confirm = false
        scope.launch {
            runCatching { app.sharing.remove(m.email) }.onSuccess { onDismiss(); onChanged(); toast("הוסר/ה") }.onFailure { toast(it.message ?: "נכשל") }
        }
    }
}
