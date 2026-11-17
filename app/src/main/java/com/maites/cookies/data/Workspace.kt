package com.maites.cookies.data

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/** An account we were invited into. [role] = "view" | "edit". */
data class Joined(val owner: String, val ownerName: String, val ownerEmail: String, val role: String)

/**
 * Whose data this phone shows: its own (default) or a shared account it joined.
 * Each has its own Room file, photo folders and sync progress, so the phone's own data waits aside untouched.
 * Fixed for the life of the process — switching restarts the app.
 */
object Workspace {
    private const val FILE = "space"
    var joined: Joined? = null; private set

    /** Live role (the owner may change it); "edit" when on our own data. */
    val role = MutableStateFlow("edit")
    val canEdit get() = role.value == "edit"

    fun init(ctx: Context) {
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        joined = p.getString("owner", null)?.takeIf { it.isNotBlank() }?.let {
            Joined(it, p.getString("name", "").orEmpty(), p.getString("email", "").orEmpty(), p.getString("role", "view").orEmpty())
        }
        role.value = joined?.role ?: "edit"
    }

    val dbName get() = joined?.let { "space_${it.owner}.db" } ?: "cookies.db"
    /** Root for photo folders. */
    fun files(ctx: Context): File = joined?.let { File(ctx.filesDir, "space_${it.owner}") } ?: ctx.filesDir
    /** Per-space DataStore key. */
    fun key(k: String) = joined?.let { "s_${it.owner}_$k" } ?: k

    fun setRole(ctx: Context, r: String) {
        val j = joined ?: return
        if (r == j.role) return
        joined = j.copy(role = r); role.value = r
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("role", r).apply()
    }

    /** Join [to] (or go back to our own data with null) and restart. Leaving deletes the shared copy on this phone. */
    fun switch(activity: Activity, to: Joined?) {
        val old = joined
        activity.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().apply {
            if (to == null) clear() else putString("owner", to.owner).putString("name", to.ownerName).putString("email", to.ownerEmail).putString("role", to.role)
        }.commit()
        if (old != null && old.owner != to?.owner) {
            activity.deleteDatabase("space_${old.owner}.db")
            File(activity.filesDir, "space_${old.owner}").deleteRecursively()
        }
        activity.startActivity(Intent(activity, Restart::class.java).putExtra("pid", Process.myPid()))
        activity.finishAffinity()
        Runtime.getRuntime().exit(0)
    }
}

/** Runs in its own process: waits for the app to die, then opens it fresh. */
class Restart : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Process.killProcess(intent.getIntExtra("pid", -1))
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
        finish()
        Runtime.getRuntime().exit(0)
    }
}
