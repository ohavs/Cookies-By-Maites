package com.maites.cookies.update

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.maites.cookies.design.C
import com.maites.cookies.design.CSheet
import com.maites.cookies.design.GhostButton
import com.maites.cookies.design.Space
import com.maites.cookies.design.StickerButton
import com.maites.cookies.design.TextStyles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/** Check → download → install, shared by the settings card and the launch prompt. */
class UpdateFlow internal constructor(private val ctx: Context, private val scope: CoroutineScope, private val toast: (String) -> Unit) {
    var state by mutableStateOf<UpdateState>(UpdateState.Idle)
    internal var pendingInstall by mutableStateOf<File?>(null)

    fun check() = scope.launch {
        state = UpdateState.Checking
        state = runCatching { Updater.latest() }.fold(
            onSuccess = { r -> if (r != null && Updater.isNewer(r)) UpdateState.Available(r) else UpdateState.UpToDate },
            onFailure = { UpdateState.Error("אין חיבור ל-GitHub. נסי שוב.") },
        )
    }

    fun download(r: Release) = scope.launch {
        state = UpdateState.Downloading(0f)
        runCatching { Updater.download(ctx, r) { p -> state = UpdateState.Downloading(p) } }
            .onSuccess { f -> state = UpdateState.Ready(f); install(f) }
            .onFailure { state = UpdateState.Error("ההורדה נכשלה. נסי שוב.") }
    }

    fun install(f: File) {
        if (Updater.canInstall(ctx)) Updater.install(ctx, f)
        else { pendingInstall = f; toast("צריך לאשר התקנה מהאפליקציה — פעם אחת"); ctx.startActivity(Updater.installPermissionIntent(ctx)) }
    }
}

@Composable
fun rememberUpdateFlow(toast: (String) -> Unit): UpdateFlow {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val flow = remember { UpdateFlow(ctx, scope, toast) }
    LifecycleResumeEffect(flow.pendingInstall) {
        val f = flow.pendingInstall
        if (f != null && Updater.canInstall(ctx)) { flow.pendingInstall = null; Updater.install(ctx, f) }
        onPauseOrDispose { }
    }
    return flow
}

/** Shown once per launch when a newer release exists. */
@Composable
fun UpdateSheet(open: Boolean, flow: UpdateFlow, onDismiss: () -> Unit) {
    val c = C.colors
    CSheet(open, onDismiss) {
        when (val s = flow.state) {
            is UpdateState.Available -> {
                Text("גרסה חדשה 🎉", style = TextStyles.title, color = c.ink)
                Text("גרסה ${s.release.versionName} מחכה לך", style = TextStyles.body, color = c.inkDim)
                if (s.release.notes.isNotBlank()) {
                    Spacer(Modifier.height(Space.s))
                    Text(s.release.notes, style = TextStyles.label, color = c.inkDim, maxLines = 6)
                }
                Spacer(Modifier.height(Space.xl))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
                    GhostButton("לא עכשיו", onDismiss, Modifier.weight(1f))
                    StickerButton("עדכון", { flow.download(s.release) }, Modifier.weight(1f))
                }
            }
            is UpdateState.Downloading -> {
                Text("מורידה…", style = TextStyles.title, color = c.ink)
                Text("${(s.progress * 100).toInt()}%", style = TextStyles.body, color = c.inkDim)
                Spacer(Modifier.height(Space.m))
                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(99.dp)).background(c.surface2)) {
                    Box(Modifier.fillMaxWidth(s.progress.coerceIn(0f, 1f)).height(8.dp).clip(RoundedCornerShape(99.dp)).background(c.accent))
                }
                Spacer(Modifier.height(Space.l))
            }
            is UpdateState.Ready -> {
                Text("מוכן להתקנה", style = TextStyles.title, color = c.ink)
                Spacer(Modifier.height(Space.xl))
                StickerButton("התקן עכשיו", { flow.install(s.file) }, Modifier.fillMaxWidth())
            }
            is UpdateState.Error -> {
                Text("משהו השתבש", style = TextStyles.title, color = c.ink)
                Text(s.message, style = TextStyles.body, color = c.danger)
                Spacer(Modifier.height(Space.xl))
                GhostButton("סגירה", onDismiss, Modifier.fillMaxWidth())
            }
            else -> Spacer(Modifier.height(Space.xl))
        }
    }
}
