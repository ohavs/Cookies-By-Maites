package com.maites.cookies.data

import android.app.Activity
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.maites.cookies.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Shown to the user as-is (Hebrew). */
class CloudError(message: String) : Exception(message)

internal class Res(val code: Int, val body: String) {
    val ok get() = code in 200..299
    fun json() = JSONObject(body)
}

internal fun http(method: String, url: String, body: ByteArray? = null, type: String = "application/json", auth: String? = null): Res {
    val c = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10000; readTimeout = 30000
        requestMethod = method
        auth?.let { setRequestProperty("Authorization", it) }
        if (body != null) { doOutput = true; setRequestProperty("Content-Type", type) }
    }
    return try {
        if (body != null) c.outputStream.use { it.write(body) }
        val code = c.responseCode
        Res(code, (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty())
    } catch (e: java.io.IOException) {
        throw CloudError("אין חיבור לאינטרנט")
    } finally { c.disconnect() }
}

/**
 * Google sign-in → Firebase Auth over REST (no Firebase SDK).
 * The refresh token lives in DataStore; ID tokens are cached in memory.
 */
class CloudAuth(private val prefs: Prefs) {
    private val key = BuildConfig.FIREBASE_API_KEY
    private val lock = Mutex()
    private var idToken: String? = null
    private var expires = 0L

    val configured get() = key.isNotBlank() && BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank() && BuildConfig.STORAGE_BUCKET.isNotBlank()

    /** Returns null when the user closed the account picker. */
    suspend fun signIn(activity: Activity): Account? {
        if (!configured) throw CloudError("ההתחברות עוד לא הוגדרה בגרסה הזו")
        val cm = CredentialManager.create(activity)
        val req = GetCredentialRequest.Builder().addCredentialOption(GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).build()).build()
        val cred = try {
            cm.getCredential(activity, req).credential
        } catch (e: GetCredentialCancellationException) {
            return null
        } catch (e: NoCredentialException) {
            throw CloudError("לא נמצא חשבון גוגל במכשיר")
        } catch (e: Exception) {
            throw CloudError("ההתחברות לגוגל נכשלה (${e.javaClass.simpleName})")
        }
        if (cred !is CustomCredential || cred.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) throw CloudError("ההתחברות לגוגל נכשלה")
        val google = GoogleIdTokenCredential.createFrom(cred.data)
        val body = JSONObject()
            .put("postBody", "id_token=${google.idToken}&providerId=google.com")
            .put("requestUri", "http://localhost").put("returnSecureToken", true).put("returnIdpCredential", true)
        val r = withContext(Dispatchers.IO) { http("POST", "https://identitytoolkit.googleapis.com/v1/accounts:signInWithIdp?key=$key", body.toString().toByteArray()) }
        if (!r.ok) throw CloudError("השרת סירב להתחברות (${r.code} ${errorCode(r)})")
        val j = r.json()
        val acc = Account(
            uid = j.getString("localId"), email = j.optString("email"),
            name = j.optString("displayName").ifBlank { google.displayName.orEmpty() },
            photo = j.optString("photoUrl").ifBlank { google.profilePictureUri?.toString().orEmpty() },
            refreshToken = j.getString("refreshToken"),
        )
        lock.withLock { idToken = j.getString("idToken"); expires = System.currentTimeMillis() + j.optLong("expiresIn", 3600) * 1000 - 120_000 }
        prefs.setAccount(acc)
        return acc
    }

    suspend fun signOut(ctx: Context) {
        lock.withLock { idToken = null; expires = 0 }
        prefs.setAccount(null)
        runCatching { CredentialManager.create(ctx).clearCredentialState(ClearCredentialStateRequest()) }
    }

    /** A valid ID token, refreshed when needed. Null when signed out. */
    suspend fun token(): String? = lock.withLock {
        val acc = prefs.account.first() ?: return@withLock null
        idToken?.takeIf { System.currentTimeMillis() < expires }?.let { return@withLock it }
        val form = "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(acc.refreshToken, "UTF-8")
        val r = withContext(Dispatchers.IO) { http("POST", "https://securetoken.googleapis.com/v1/token?key=$key", form.toByteArray(), "application/x-www-form-urlencoded") }
        if (r.code == 400) throw CloudError("צריך להתחבר מחדש")
        if (!r.ok) throw CloudError("השרת לא זמין (${r.code})")
        val j = r.json()
        idToken = j.getString("id_token")
        expires = System.currentTimeMillis() + j.optString("expires_in", "3600").toLong() * 1000 - 120_000
        j.optString("refresh_token").takeIf { it.isNotBlank() && it != acc.refreshToken }?.let { prefs.setRefreshToken(it) }
        idToken
    }

    private fun errorCode(r: Res) = runCatching { r.json().getJSONObject("error").getString("message") }.getOrDefault("")
}

sealed interface SyncState {
    data object Idle : SyncState
    data object Running : SyncState
    data class Failed(val message: String) : SyncState
}

/**
 * Room stays the source of truth; the cloud is a per-user mirror at `users/{uid}/{products|recipes}/{gid}`.
 * Pull: documents whose server time `syncAt` is past our watermark (local unsent edits win).
 * Push: dirty rows + tombstones, then recipe photos and product images to Storage `users/{uid}/photos/{file}`.
 * Photos we uploaded that nothing references any more are deleted from Storage after a successful push.
 */
class CloudSync(private val ctx: Context, private val db: AppDb, private val prefs: Prefs, private val auth: CloudAuth, private val scope: CoroutineScope) {
    private val lock = Mutex()
    private val project = BuildConfig.FIREBASE_PROJECT
    private val bucket = BuildConfig.STORAGE_BUCKET
    private val root = "projects/$project/databases/(default)/documents"
    private val api = "https://firestore.googleapis.com/v1/$root"

    val state = MutableStateFlow<SyncState>(SyncState.Idle)

    /** True while the app is on screen: a shared account is polled for the others' changes. */
    @Volatile var foreground = false

    /** Push local edits a moment after they happen. */
    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            combine(prefs.account, db.sync().pending()) { a, n -> a != null && n > 0 }
                .debounce(2500).filter { it }.collect { sync() }
        }
        scope.launch {
            combine(prefs.account, db.shop().entries(), db.shop().checks()) { a, _, _ -> a != null }
                .debounce(2500).filter { it }.collect { if (shopLocal() != prefs.str(Workspace.key("shop_synced"))) sync() }
        }
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(45_000)
                if (foreground && prefs.account.first() != null) sync()
            }
        }
    }

    fun syncSoon() { scope.launch { sync() } }

    /** Called right after sign-in: merge everything on this phone with the account. */
    suspend fun afterSignIn() {
        prefs.resetCloud()
        db.sync().dirtyProducts(); db.sync().dirtyRecipes(); db.categories().dirtyAll()
        sync()
    }

    suspend fun sync() = lock.withLock {
        if (!auth.configured) return@withLock
        val me = prefs.account.first() ?: return@withLock
        val uid = Workspace.joined?.owner ?: me.uid
        state.value = SyncState.Running
        state.value = runCatching {
            withContext(Dispatchers.IO) {
                val t = auth.token() ?: return@withContext
                Workspace.joined?.let { checkInvite(it, me, t) }
                val edit = Workspace.canEdit
                if (!edit) db.sync().forgetLocalEdits()
                pullCategories(uid, t)
                pullProducts(uid, t)
                pullRecipes(uid, t)
                if (edit) ProductPhotos.localizeAll(ctx, db.products())
                fetchMissingPhotos(uid, t)
                if (edit) push(uid, t)
                syncShop(uid, t, edit)
                if (edit) collectPhotos(uid, t)
            }
            prefs.setLastCloudSync(System.currentTimeMillis())
            SyncState.Idle
        }.getOrElse { SyncState.Failed((it as? CloudError)?.message ?: "הסנכרון נכשל (${it.javaClass.simpleName})") }
    }

    /** The owner may have changed our role or removed us. */
    private fun checkInvite(j: Joined, me: Account, t: String) {
        val r = http("GET", "$api/invites/" + enc(me.email.lowercase()), auth = "Bearer $t")
        if (r.code == 404 || r.code == 403) throw CloudError("הגישה לחשבון של ${j.ownerName.ifBlank { j.ownerEmail }} הוסרה")
        if (!r.ok) throw CloudError("קריאה מהענן נכשלה (${r.code})")
        val f = r.json().optJSONObject("fields") ?: JSONObject()
        if (f.str("owner") != j.owner) throw CloudError("הגישה לחשבון של ${j.ownerName.ifBlank { j.ownerEmail }} הוסרה")
        Workspace.setRole(ctx, f.str("role").ifBlank { "view" })
    }

    // ---------- shopping list (one document, last writer wins) ----------

    /** The local list as canonical JSON, by cloud ids. */
    private suspend fun shopLocal(): String {
        val e = JSONArray()
        db.shop().entriesOnce().forEach { x ->
            val r = x.recipeId?.let { db.recipes().gidOf(it) }
            val p = x.productId?.let { db.products().once(it)?.gid }
            if (r == null && p == null) return@forEach
            e.put(JSONObject().put("r", r.orEmpty()).put("p", p.orEmpty()).put("q", x.qty).put("at", x.addedAt))
        }
        val c = db.shop().checksOnce().mapNotNull { k ->
            if (k.startsWith("p")) k.drop(1).toLongOrNull()?.let { db.products().once(it)?.gid }?.let { "p$it" } else k
        }.sorted()
        return JSONObject().put("e", e).put("c", JSONArray(c)).toString()
    }

    private suspend fun applyShop(remote: String) {
        val j = JSONObject(remote)
        val ea = j.optJSONArray("e") ?: JSONArray()
        val entries = (0 until ea.length()).mapNotNull { i ->
            val o = ea.getJSONObject(i)
            val r = o.optString("r").takeIf { it.isNotBlank() }?.let { db.recipes().byGid(it)?.id }
            val p = o.optString("p").takeIf { it.isNotBlank() }?.let { db.products().byGid(it)?.id }
            if (r == null && p == null) null else ShopEntry(recipeId = r, productId = p, qty = o.optDouble("q", 1.0), addedAt = o.optLong("at"))
        }
        val ca = j.optJSONArray("c") ?: JSONArray()
        val checks = (0 until ca.length()).mapNotNull { i ->
            val k = ca.getString(i)
            if (k.startsWith("p")) db.products().byGid(k.drop(1))?.id?.let { "p$it" } else k
        }
        db.shop().replace(entries, checks)
    }

    private suspend fun syncShop(uid: String, t: String, edit: Boolean) {
        val r = http("GET", "$api/users/$uid/shop/list", auth = "Bearer $t")
        if (!r.ok && r.code != 404) throw CloudError("קריאה מהענן נכשלה (${r.code})")
        val remote = if (r.ok) r.json().optJSONObject("fields")?.str("data")?.ifBlank { null } else null
        val kSynced = Workspace.key("shop_synced"); val kSeen = Workspace.key("shop_remote")
        val local = shopLocal()
        val localChanged = local != prefs.str(kSynced)
        if (remote != null && (if (edit) remote != prefs.str(kSeen) && (!localChanged || prefs.str(kSeen).isEmpty()) else remote != prefs.str(kSeen) || localChanged)) {
            applyShop(remote)
            prefs.setStr(kSynced, shopLocal()); prefs.setStr(kSeen, remote)
        } else if (edit && localChanged) {
            commit(t, listOf(doc(uid, "shop", "list", fields("data" to local, "updatedAt" to System.currentTimeMillis()))))
            prefs.setStr(kSynced, local); prefs.setStr(kSeen, local)
        }
    }

    // ---------- pull ----------

    /** Every document in [kind] changed since the watermark, oldest first. */
    private suspend fun changed(uid: String, kind: String, t: String, each: suspend (gid: String, f: JSONObject) -> Unit) {
        while (true) {
            val wm = prefs.watermark(kind)
            val q = JSONObject().put("structuredQuery", JSONObject()
                .put("from", JSONArray().put(JSONObject().put("collectionId", kind)))
                .put("where", JSONObject().put("fieldFilter", JSONObject()
                    .put("field", JSONObject().put("fieldPath", "syncAt")).put("op", "GREATER_THAN")
                    .put("value", JSONObject().put("timestampValue", wm))))
                .put("orderBy", JSONArray().put(JSONObject().put("field", JSONObject().put("fieldPath", "syncAt")).put("direction", "ASCENDING")))
                .put("limit", PAGE))
            val r = http("POST", "$api/users/$uid:runQuery", q.toString().toByteArray(), auth = "Bearer $t")
            if (!r.ok) throw CloudError("קריאה מהענן נכשלה (${r.code})")
            val arr = JSONArray(r.body)
            var n = 0
            for (i in 0 until arr.length()) {
                val doc = arr.getJSONObject(i).optJSONObject("document") ?: continue
                val f = doc.optJSONObject("fields") ?: JSONObject()
                each(doc.getString("name").substringAfterLast('/'), f)
                f.optJSONObject("syncAt")?.optString("timestampValue")?.takeIf { it.isNotBlank() }?.let { prefs.setWatermark(kind, it) }
                n++
            }
            if (n < PAGE) return
        }
    }

    private suspend fun pullCategories(uid: String, t: String) {
        val dao = db.categories()
        changed(uid, "categories", t) { gid, f ->
            val local = dao.byGid(gid)
            if (f.bool("deleted")) { if (local != null) dao.deleteRow(gid); return@changed }
            if (local != null && local.dirty) return@changed
            dao.put(CategoryEntity(
                gid = gid, kind = f.str("kind"), label = f.str("label"), emoji = f.str("emoji"),
                tint = f.long("tint").toInt(), position = f.long("position").toInt(), updatedAt = f.long("updatedAt"), dirty = false,
            ))
        }
    }

    private suspend fun pullProducts(uid: String, t: String) {
        val dao = db.products()
        changed(uid, "products", t) { gid, f ->
            val local = dao.byGid(gid) ?: f.str("barcode").takeIf { it.isNotBlank() }?.let { dao.byBarcode(it) }
            if (f.bool("deleted")) {
                if (local != null && local.gid == gid) { db.recipes().clearProduct(local.id); dao.deleteRow(local.id) }
                return@changed
            }
            val merged = local != null && local.gid != gid // same barcode added on two phones → one product
            if (local != null && local.dirty) {
                if (merged) { dao.put(local.copy(gid = gid)); db.sync().tomb(Tombstone(local.gid, "products")) }
                return@changed
            }
            val remote = ProductEntity(
                id = local?.id ?: 0, gid = gid, dirty = false,
                name = f.str("name"), barcode = f.str("barcode").ifBlank { null }, packAmount = f.num("packAmount"),
                unit = runCatching { BaseUnit.valueOf(f.str("unit")) }.getOrDefault(BaseUnit.Gram),
                priceAgorot = f.optJSONObject("price")?.optString("integerValue")?.toLongOrNull(),
                favorite = f.bool("favorite"), updatedAt = f.long("updatedAt"), brand = f.str("brand"),
                category = f.str("category").ifBlank { "Pantry" },
                gramsPerCup = f.num("gpc").takeIf { it > 0 },
                imageUrl = f.str("image").takeIf { it.isNotBlank() }?.let { ProductPhotos.file(ctx, it).absolutePath } ?: f.str("imageUrl").ifBlank { null },
            )
            if (local != null && local.copy(dirty = false) == remote) return@changed
            dao.put(remote)
            if (local?.imageUrl != remote.imageUrl && ProductPhotos.isLocal(local?.imageUrl)) File(local!!.imageUrl!!).delete()
            if (merged) db.sync().tomb(Tombstone(local!!.gid, "products"))
        }
    }

    private suspend fun pullRecipes(uid: String, t: String) {
        val dao = db.recipes()
        var removed = false
        changed(uid, "recipes", t) { gid, f ->
            val local = dao.byGid(gid)
            if (f.bool("deleted")) {
                if (local != null) { dao.deleteRow(local.id); removed = true }
                return@changed
            }
            if (local != null && (local.dirty || local.updatedAt == f.long("updatedAt"))) return@changed
            val photos = f.arr("photos").map { RecipePhotos.file(ctx, it.optString("stringValue")).absolutePath }
            val r = RecipeEntity(
                id = local?.id ?: 0, gid = gid, dirty = false,
                name = f.str("name"), category = f.str("category").ifBlank { "Other" },
                yieldCount = f.long("yieldCount").toInt(), yieldLabel = f.str("yieldLabel"), notes = f.str("notes"),
                photos = photos.joinToString("\n"), favorite = f.bool("favorite"), updatedAt = f.long("updatedAt"),
                extras = f.str("extras"), salePrice = f.long("salePrice"), targetMargin = f.long("targetMargin").toInt(),
            )
            val items = f.arr("ingredients").map { v ->
                val m = v.optJSONObject("mapValue")?.optJSONObject("fields") ?: JSONObject()
                IngredientEntity(
                    recipeId = 0, position = 0,
                    productId = m.str("p").takeIf { it.isNotBlank() }?.let { db.products().byGid(it)?.id },
                    name = m.str("n"), amount = m.num("a"),
                    unit = runCatching { BaseUnit.valueOf(m.str("u")) }.getOrDefault(BaseUnit.Gram), icon = m.str("i"), measure = m.str("m"),
                )
            }
            dao.write(r, items)
            if (local != null && local.photos != r.photos) removed = true
        }
        if (removed) RecipePhotos.prune(ctx, dao.all().flatMap { it.photoList }.toSet())
    }

    private suspend fun fetchMissingPhotos(uid: String, t: String) {
        (db.recipes().all().flatMap { it.photoList } + db.products().localImages()).map(::File).filter { !it.exists() }.forEach { f ->
            val c = (URL(objectUrl(uid, f.name) + "?alt=media").openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000; readTimeout = 30000; setRequestProperty("Authorization", "Firebase $t")
            }
            try {
                if (c.responseCode == 200) {
                    val tmp = File(f.parentFile, f.name + ".part")
                    c.inputStream.use { i -> tmp.outputStream().use { i.copyTo(it) } }
                    tmp.renameTo(f)
                    prefs.addUploaded(f.name)
                }
            } catch (_: java.io.IOException) {
            } finally { c.disconnect() }
        }
    }

    // ---------- push ----------

    private suspend fun push(uid: String, t: String) {
        val tombs = db.sync().tombstones()
        commit(t, tombs.map { doc(uid, it.kind, it.gid, JSONObject().put("deleted", JSONObject().put("booleanValue", true))) })
        tombs.forEach { db.sync().untomb(it.gid) }

        val cats = db.categories().dirty()
        commit(t, cats.map { k ->
            doc(uid, "categories", k.gid, fields(
                "kind" to k.kind, "label" to k.label, "emoji" to k.emoji, "tint" to k.tint.toLong(),
                "position" to k.position.toLong(), "updatedAt" to k.updatedAt,
            ))
        })
        cats.forEach { db.categories().markClean(it.gid, it.updatedAt) }

        val products = db.products().dirty()
        commit(t, products.map { p ->
            doc(uid, "products", p.gid, fields(
                "name" to p.name, "barcode" to p.barcode, "packAmount" to p.packAmount, "unit" to p.unit.name,
                "price" to p.priceAgorot, "favorite" to p.favorite, "updatedAt" to p.updatedAt, "brand" to p.brand,
                "category" to p.category, "gpc" to p.gramsPerCup,
                "image" to p.imageUrl?.takeIf(ProductPhotos::isLocal)?.let { File(it).name },
                "imageUrl" to p.imageUrl?.takeIf { !ProductPhotos.isLocal(it) },
            ))
        })
        products.forEach { db.products().markClean(it.id, it.updatedAt) }

        val recipes = db.recipes().dirty()
        val uploaded = prefs.uploaded()
        (recipes.flatMap { it.photoList } + products.mapNotNull { it.imageUrl?.takeIf(ProductPhotos::isLocal) }).map(::File).filter { it.exists() && it.name !in uploaded }.forEach { f ->
            val r = http("POST", "https://firebasestorage.googleapis.com/v0/b/$bucket/o?uploadType=media&name=" + enc(path(uid, f.name)), f.readBytes(), "image/webp", "Firebase $t")
            if (!r.ok) throw CloudError("העלאת תמונה נכשלה (${r.code})")
            prefs.addUploaded(f.name)
        }
        commit(t, recipes.map { r ->
            val items = db.recipes().ingredientsOnce(r.id).map { i ->
                fields("p" to i.productId?.let { db.products().once(it)?.gid }, "n" to i.name, "a" to i.amount, "u" to i.unit.name, "i" to i.icon, "m" to i.measure)
            }
            doc(uid, "recipes", r.gid, fields(
                "name" to r.name, "category" to r.category, "yieldCount" to r.yieldCount.toLong(), "yieldLabel" to r.yieldLabel,
                "notes" to r.notes, "favorite" to r.favorite, "updatedAt" to r.updatedAt, "extras" to r.extras,
                "salePrice" to r.salePrice, "targetMargin" to r.targetMargin.toLong(),
                "photos" to r.photoList.map { File(it).name },
            ).put("ingredients", JSONObject().put("arrayValue", JSONObject().put("values", JSONArray(items.map { JSONObject().put("mapValue", JSONObject().put("fields", it)) })))))
        })
        recipes.forEach { db.recipes().markClean(it.id, it.updatedAt) }
    }

    /** Deletes from Storage the photos we uploaded that no recipe or product uses any more. */
    private suspend fun collectPhotos(uid: String, t: String) {
        val used = (db.recipes().all().flatMap { it.photoList } + db.products().localImages()).map { File(it).name }.toSet()
        prefs.uploaded().filter { it !in used }.forEach { name ->
            val r = http("DELETE", objectUrl(uid, name), auth = "Firebase $t")
            if (r.ok || r.code == 404) prefs.removeUploaded(name)
        }
    }

    private fun doc(uid: String, kind: String, gid: String, fields: JSONObject) = JSONObject()
        .put("update", JSONObject().put("name", "$root/users/$uid/$kind/$gid").put("fields", fields))
        .put("updateTransforms", JSONArray().put(JSONObject().put("fieldPath", "syncAt").put("setToServerValue", "REQUEST_TIME")))

    private fun commit(t: String, writes: List<JSONObject>) = writes.chunked(200).forEach { chunk ->
        val r = http("POST", "$api:commit", JSONObject().put("writes", JSONArray(chunk)).toString().toByteArray(), auth = "Bearer $t")
        if (!r.ok) throw CloudError("שמירה בענן נכשלה (${r.code})")
    }

    private fun path(uid: String, name: String) = "users/$uid/photos/$name"
    private fun objectUrl(uid: String, name: String) = "https://firebasestorage.googleapis.com/v0/b/$bucket/o/" + enc(path(uid, name))
    internal fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private companion object { const val PAGE = 300 }
}

// ---------- Firestore value encoding ----------

internal fun value(v: Any?): JSONObject = when (v) {
    null -> JSONObject().put("nullValue", JSONObject.NULL)
    is String -> JSONObject().put("stringValue", v)
    is Boolean -> JSONObject().put("booleanValue", v)
    is Long -> JSONObject().put("integerValue", v.toString())
    is Int -> JSONObject().put("integerValue", v.toString())
    is Double -> JSONObject().put("doubleValue", v)
    is List<*> -> JSONObject().put("arrayValue", JSONObject().put("values", JSONArray(v.map { value(it) })))
    else -> error("unsupported ${v.javaClass}")
}

internal fun fields(vararg kv: Pair<String, Any?>) = JSONObject().apply { kv.forEach { (k, v) -> put(k, value(v)) } }

internal fun JSONObject.str(k: String) = optJSONObject(k)?.optString("stringValue").orEmpty()
internal fun JSONObject.bool(k: String) = optJSONObject(k)?.optBoolean("booleanValue") ?: false
private fun JSONObject.long(k: String) = optJSONObject(k)?.optString("integerValue")?.toLongOrNull() ?: 0L
private fun JSONObject.num(k: String): Double = optJSONObject(k)?.let { o ->
    if (o.has("doubleValue")) o.optDouble("doubleValue") else o.optString("integerValue").toDoubleOrNull()
} ?: 0.0
private fun JSONObject.arr(k: String): List<JSONObject> =
    optJSONObject(k)?.optJSONObject("arrayValue")?.optJSONArray("values")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
