package com.maites.cookies.data

import com.maites.cookies.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** Someone invited into an account. [joined] once they opened it. */
data class Member(val email: String, val role: String, val joined: Boolean, val name: String)

/** An invitation waiting for us. */
data class Invite(val owner: String, val ownerName: String, val ownerEmail: String, val role: String)

/**
 * Sharing an account by Gmail: `invites/{email}` = {owner, ownerName, ownerEmail, role, joined, name}.
 * Firestore/Storage rules let the invitee read (and with role "edit" write) everything under `users/{owner}`.
 */
class Sharing(private val auth: CloudAuth, private val prefs: Prefs) {
    private val root = "projects/${BuildConfig.FIREBASE_PROJECT}/databases/(default)/documents"
    private val api = "https://firestore.googleapis.com/v1/$root"

    private suspend fun <T> call(block: suspend (t: String, me: Account) -> T): T = withContext(Dispatchers.IO) {
        val me = prefs.account.first() ?: throw CloudError("צריך להתחבר קודם")
        val t = auth.token() ?: throw CloudError("צריך להתחבר קודם")
        block(t, me)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun name(email: String) = "$root/invites/$email"

    private fun commit(t: String, write: JSONObject): Res =
        http("POST", "$api:commit", JSONObject().put("writes", JSONArray().put(write)).toString().toByteArray(), auth = "Bearer $t")

    /** People this account was shared with. */
    suspend fun members(): List<Member> = call { t, me ->
        val q = JSONObject().put("structuredQuery", JSONObject()
            .put("from", JSONArray().put(JSONObject().put("collectionId", "invites")))
            .put("where", JSONObject().put("fieldFilter", JSONObject()
                .put("field", JSONObject().put("fieldPath", "owner")).put("op", "EQUAL")
                .put("value", JSONObject().put("stringValue", me.uid)))))
        val r = http("POST", "$api:runQuery", q.toString().toByteArray(), auth = "Bearer $t")
        if (!r.ok) throw CloudError("קריאה מהענן נכשלה (${r.code})")
        val arr = JSONArray(r.body)
        (0 until arr.length()).mapNotNull { arr.getJSONObject(it).optJSONObject("document") }.map { d ->
            val f = d.optJSONObject("fields") ?: JSONObject()
            Member(d.getString("name").substringAfterLast('/'), f.str("role"), f.bool("joined"), f.str("name"))
        }.sortedBy { it.email }
    }

    suspend fun invite(email: String, role: String) = call { t, me ->
        val e = email.trim().lowercase()
        if (!Regex("^[^@\\s/]+@[^@\\s/]+\\.[^@\\s/]+$").matches(e)) throw CloudError("כתובת המייל לא תקינה")
        if (e == me.email.lowercase()) throw CloudError("זו הכתובת שלך")
        val r = commit(t, JSONObject()
            .put("update", JSONObject().put("name", name(e)).put("fields", fields(
                "owner" to me.uid, "ownerName" to me.name, "ownerEmail" to me.email, "role" to role, "at" to System.currentTimeMillis(),
            )))
            .put("currentDocument", JSONObject().put("exists", false)))
        when {
            r.ok -> Unit
            r.code == 400 || r.code == 409 || r.code == 403 -> throw CloudError("הכתובת הזו כבר מוזמנת לחשבון")
            else -> throw CloudError("ההזמנה נכשלה (${r.code})")
        }
    }

    suspend fun setRole(email: String, role: String) = call { t, _ ->
        val r = commit(t, JSONObject()
            .put("update", JSONObject().put("name", name(email)).put("fields", fields("role" to role)))
            .put("updateMask", JSONObject().put("fieldPaths", JSONArray().put("role")))
            .put("currentDocument", JSONObject().put("exists", true)))
        if (!r.ok) throw CloudError("השינוי נכשל (${r.code})")
    }

    /** Owner removes [email], or the invitee declines/leaves (own email). */
    suspend fun remove(email: String) = call { t, _ ->
        val r = http("DELETE", "$api/invites/" + enc(email), auth = "Bearer $t")
        if (!r.ok && r.code != 404) throw CloudError("ההסרה נכשלה (${r.code})")
    }

    /** An invitation for our Gmail, if any. */
    suspend fun mine(): Invite? = call { t, me ->
        val r = http("GET", "$api/invites/" + enc(me.email.lowercase()), auth = "Bearer $t")
        if (r.code == 404 || r.code == 403) return@call null
        if (!r.ok) throw CloudError("קריאה מהענן נכשלה (${r.code})")
        val f = r.json().optJSONObject("fields") ?: JSONObject()
        Invite(f.str("owner"), f.str("ownerName"), f.str("ownerEmail"), f.str("role").ifBlank { "view" })
    }

    /** Tells the owner we're in. */
    suspend fun markJoined() = call { t, me ->
        val r = commit(t, JSONObject()
            .put("update", JSONObject().put("name", name(me.email.lowercase())).put("fields", fields("joined" to true, "name" to me.name)))
            .put("updateMask", JSONObject().put("fieldPaths", JSONArray().put("joined").put("name")))
            .put("currentDocument", JSONObject().put("exists", true)))
        if (!r.ok) throw CloudError("ההצטרפות נכשלה (${r.code})")
    }
}
