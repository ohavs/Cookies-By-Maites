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

private class Res(val code: Int, val body: String) {
    val ok get() = code in 200..299
    fun json() = JSONObject(body)
}

private fun http(method: String, url: String, body: ByteArray? = null, type: String = "application/json", auth: String? = null): Res {
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

    /** Push local edits a moment after they happen. */
    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            combine(prefs.account, db.sync().pending()) { a, n -> a != null && n > 0 }
                .debounce(2500).filter { it }.collect { sync() }
        }
    }

    fun syncSoon() { scope.launch { sync() } }

    /** Called right after sign-in: merge everything on this phone with the account. */
    suspend fun afterSignIn() {
        prefs.resetCloud()
        db.sync().dirtyProducts(); db.sync().dirtyRecipes()
        sync()
    }

    suspend fun sync() = lock.withLock {
        if (!auth.configured) return@withLock
        val uid = prefs.account.first()?.uid ?: return@withLock
        state.value = SyncState.Running
        state.value = runCatching {
            withContext(Dispatchers.IO) {
                val t = auth.token() ?: return@withContext
                pullProducts(uid, t)
                pullRecipes(uid, t)
                ProductPhotos.localizeAll(ctx, db.products())
                fetchMissingPhotos(uid, t)
                push(uid, t)
                collectPhotos(uid, t)
            }
            prefs.setLastCloudSync(System.currentTimeMillis())
            SyncState.Idle
        }.getOrElse { SyncState.Failed((it as? CloudError)?.message ?: "הסנכרון נכשל (${it.javaClass.simpleName})") }
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
                category = runCatching { ProductCategory.valueOf(f.str("category")) }.getOrDefault(ProductCategory.Pantry),
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
                name = f.str("name"), category = runCatching { RecipeCategory.valueOf(f.str("category")) }.getOrDefault(RecipeCategory.Other),
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
                    unit = runCatching { BaseUnit.valueOf(m.str("u")) }.getOrDefault(BaseUnit.Gram), icon = m.str("i"),
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

        val products = db.products().dirty()
        commit(t, products.map { p ->
            doc(uid, "products", p.gid, fields(
                "name" to p.name, "barcode" to p.barcode, "packAmount" to p.packAmount, "unit" to p.unit.name,
                "price" to p.priceAgorot, "favorite" to p.favorite, "updatedAt" to p.updatedAt, "brand" to p.brand,
                "category" to p.category.name,
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
                fields("p" to i.productId?.let { db.products().once(it)?.gid }, "n" to i.name, "a" to i.amount, "u" to i.unit.name, "i" to i.icon)
            }
            doc(uid, "recipes", r.gid, fields(
                "name" to r.name, "category" to r.category.name, "yieldCount" to r.yieldCount.toLong(), "yieldLabel" to r.yieldLabel,
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
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private companion object { const val PAGE = 300 }
}

// ---------- Firestore value encoding ----------

private fun value(v: Any?): JSONObject = when (v) {
    null -> JSONObject().put("nullValue", JSONObject.NULL)
    is String -> JSONObject().put("stringValue", v)
    is Boolean -> JSONObject().put("booleanValue", v)
    is Long -> JSONObject().put("integerValue", v.toString())
    is Int -> JSONObject().put("integerValue", v.toString())
    is Double -> JSONObject().put("doubleValue", v)
    is List<*> -> JSONObject().put("arrayValue", JSONObject().put("values", JSONArray(v.map { value(it) })))
    else -> error("unsupported ${v.javaClass}")
}

private fun fields(vararg kv: Pair<String, Any?>) = JSONObject().apply { kv.forEach { (k, v) -> put(k, value(v)) } }

private fun JSONObject.str(k: String) = optJSONObject(k)?.optString("stringValue").orEmpty()
private fun JSONObject.bool(k: String) = optJSONObject(k)?.optBoolean("booleanValue") ?: false
private fun JSONObject.long(k: String) = optJSONObject(k)?.optString("integerValue")?.toLongOrNull() ?: 0L
private fun JSONObject.num(k: String): Double = optJSONObject(k)?.let { o ->
    if (o.has("doubleValue")) o.optDouble("doubleValue") else o.optString("integerValue").toDoubleOrNull()
} ?: 0.0
private fun JSONObject.arr(k: String): List<JSONObject> =
    optJSONObject(k)?.optJSONObject("arrayValue")?.optJSONArray("values")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
