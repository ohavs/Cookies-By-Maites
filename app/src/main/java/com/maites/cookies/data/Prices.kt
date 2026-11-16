package com.maites.cookies.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.maites.cookies.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Supermarket chains whose price files we read. Key = Firestore id. */
enum class Chain(val key: String, val label: String, val emoji: String) {
    Shufersal("shufersal", "שופרסל", "🛒"),
    RamiLevy("ramilevy", "רמי לוי", "🏷️"),
    Victory("victory", "ויקטורי", "🏆"),
    Yochananof("yochananof", "יוחננוף", "🧺"),
    OsherAd("osherad", "אושר עד", "📦"),
    Carrefour("carrefour", "קרפור", "🛍️");

    companion object { fun of(key: String) = entries.firstOrNull { it.key == key } }
}

@Entity(tableName = "chain_stores", primaryKeys = ["chain", "storeId"])
data class ChainStore(val chain: String, val storeId: String, val name: String, val city: String, val address: String)

@Entity(tableName = "chain_prices", primaryKeys = ["barcode", "chain", "storeId"])
data class ChainPrice(val barcode: String, val chain: String, val storeId: String, val agorot: Long)

data class StorePrice(val chain: String, val storeId: String, val name: String, val city: String, val address: String, val agorot: Long)

@Dao
interface PriceDao {
    @Query("SELECT DISTINCT city FROM chain_stores WHERE city != '' ORDER BY city")
    fun cities(): Flow<List<String>>

    @Query(
        """SELECT p.chain, p.storeId, s.name, s.city, s.address, p.agorot FROM chain_prices p
           JOIN chain_stores s ON s.chain = p.chain AND s.storeId = p.storeId
           WHERE p.barcode = :barcode AND s.city = :city ORDER BY p.agorot, s.name""",
    )
    fun inCity(barcode: String, city: String): Flow<List<StorePrice>>

    @Query(
        """SELECT p.barcode AS barcode, MIN(p.agorot) AS agorot FROM chain_prices p
           JOIN chain_stores s ON s.chain = p.chain AND s.storeId = p.storeId
           WHERE s.city = :city GROUP BY p.barcode""",
    )
    fun cheapestInCity(city: String): Flow<List<BarcodePrice>>

    @Query("SELECT COUNT(*) FROM chain_prices WHERE barcode = :barcode")
    fun countAnywhere(barcode: String): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putStores(s: List<ChainStore>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putPrices(p: List<ChainPrice>)
    @Query("DELETE FROM chain_stores WHERE chain = :chain") suspend fun clearStores(chain: String)
    @Query("DELETE FROM chain_prices WHERE barcode IN (:barcodes) AND chain NOT IN (:keep)") suspend fun clearPrices(barcodes: List<String>, keep: List<String>)
    @Query("DELETE FROM chain_prices WHERE chain = :chain") suspend fun clearChainPrices(chain: String)

    @Transaction
    suspend fun replaceStores(chain: String, s: List<ChainStore>) { clearStores(chain); putStores(s) }

    @Transaction
    suspend fun replacePrices(barcodes: List<String>, keep: List<String>, p: List<ChainPrice>) { clearPrices(barcodes, keep); putPrices(p) }

    /** Phone-fetched chain: replace its stores and all its prices at once. */
    @Transaction
    suspend fun replaceLocal(chain: String, s: List<ChainStore>, p: List<ChainPrice>) {
        replaceStores(chain, s); clearChainPrices(chain); putPrices(p)
    }
}

data class BarcodePrice(val barcode: String, val agorot: Long)

/** Firestore doc id for a barcode (leading zeros dropped — same as the Action). */
fun priceKey(barcode: String?): String? = barcode?.trim()?.trimStart('0')?.takeIf { it.length >= 6 && it.all(Char::isDigit) }

/**
 * Pulls the daily chain prices (written by the GitHub Action) into Room.
 * Read via Firestore REST with the public API key; the app always shows Room.
 */
class PriceSync(private val db: AppDb, private val prefs: Prefs, private val local: LocalChains) {
    private val lock = Mutex()
    private val project = BuildConfig.FIREBASE_PROJECT
    private val key = BuildConfig.FIREBASE_API_KEY
    private val base get() = "https://firestore.googleapis.com/v1/projects/$project/databases/(default)/documents"

    val configured get() = project.isNotBlank() && key.isNotBlank()

    /** Returns true when something was refreshed. Rate-limited unless [force]. */
    suspend fun sync(force: Boolean = false): Boolean = lock.withLock {
        val remote = if (configured && (force || System.currentTimeMillis() - prefs.lastPriceSync() >= 3 * 3600_000L)) syncRemote() else false
        syncLocal(force) || remote
    }

    private val localKeys = LocalChains.LOCAL.map { it.key }

    private suspend fun syncRemote(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val codes = db.products().barcodes().mapNotNull(::priceKey).distinct()
            codes.forEach(::watch)
            (Chain.entries - LocalChains.LOCAL).forEach { ch -> fetchStores(ch)?.let { db.prices().replaceStores(ch.key, it) } }
            codes.chunked(100).forEach { chunk -> db.prices().replacePrices(chunk, localKeys, fetchPrices(chunk)) }
            prefs.setLastPriceSync(System.currentTimeMillis())
            true
        }.getOrDefault(false)
    }

    /** Victory / Carrefour: read on the phone (their sites only answer Israeli IPs). Daily, or when the city changes. */
    private suspend fun syncLocal(force: Boolean): Boolean = withContext(Dispatchers.IO) {
        val city = prefs.city.first()
        val st = prefs.localState()
        if (!force && st.city == city && System.currentTimeMillis() - st.at < 20 * 3600_000L) return@withContext false
        val codes = db.products().barcodes().mapNotNull(::priceKey).toSet()
        val canon = db.prices().cities().first().associateBy { LocalChains.norm(it) }
        val rows = ArrayList<String>()
        for (ch in LocalChains.LOCAL) {
            val r = runCatching { local.fetch(ch, codes, city, canon) }
            r.getOrNull()?.let { db.prices().replaceLocal(ch.key, it.stores, it.prices) }
            rows += r.fold({ "${ch.key}:ok:${it.stores.size}:${it.prices.size}" }, { e -> "${ch.key}:err:${e.javaClass.simpleName} ${e.message.orEmpty().replace(':', ' ').replace(';', ' ').take(80)}" })
        }
        val status = rows.joinToString(";")
        prefs.setLocalState(LocalState(System.currentTimeMillis(), city, status))
        true
    }

    /** Ask the daily Action to track this barcode. Fire-and-forget. */
    suspend fun watchNow(barcode: String?) = withContext(Dispatchers.IO) { priceKey(barcode)?.let { runCatching { watch(it) } } }

    private fun watch(code: String) {
        val body = JSONObject().put("fields", JSONObject().put("at", JSONObject().put("integerValue", System.currentTimeMillis().toString())))
        http("PATCH", "$base/watch/$code?key=$key", body.toString())
    }

    private fun fetchStores(ch: Chain): List<ChainStore>? {
        val doc = http("GET", "$base/stores/${ch.key}?key=$key") ?: return null
        val s = JSONObject(doc).optJSONObject("fields")?.mapField("s") ?: return null
        return s.keys().asSequence().map { id ->
            val f = s.getJSONObject(id).optJSONObject("mapValue")?.optJSONObject("fields") ?: JSONObject()
            ChainStore(ch.key, id, f.str("n"), f.str("c"), f.str("a"))
        }.toList()
    }

    private fun fetchPrices(codes: List<String>): List<ChainPrice> {
        val req = JSONObject().put("documents", JSONArray(codes.map { "projects/$project/databases/(default)/documents/prices/$it" }))
        val res = http("POST", "https://firestore.googleapis.com/v1/projects/$project/databases/(default)/documents:batchGet?key=$key", req.toString()) ?: return emptyList()
        val arr = JSONArray(res)
        val out = ArrayList<ChainPrice>()
        for (i in 0 until arr.length()) {
            val found = arr.getJSONObject(i).optJSONObject("found") ?: continue
            val code = found.getString("name").substringAfterLast('/')
            val p = found.optJSONObject("fields")?.mapField("p") ?: continue
            p.keys().forEach { k ->
                val chain = k.substringBefore('_'); val store = k.substringAfter('_')
                val v = p.getJSONObject(k).optString("integerValue").toLongOrNull() ?: return@forEach
                out += ChainPrice(code, chain, store, v)
            }
        }
        return out
    }

    private fun JSONObject.mapField(name: String) = optJSONObject(name)?.optJSONObject("mapValue")?.optJSONObject("fields")
    private fun JSONObject.str(name: String) = optJSONObject(name)?.optString("stringValue").orEmpty()

    private fun http(method: String, url: String, body: String? = null): String? {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 15000
            if (method == "PATCH") { requestMethod = "POST"; setRequestProperty("X-HTTP-Method-Override", "PATCH") } else requestMethod = method
            if (body != null) { doOutput = true; setRequestProperty("Content-Type", "application/json") }
        }
        return try {
            if (body != null) c.outputStream.use { it.write(body.toByteArray()) }
            if (c.responseCode in 200..299) c.inputStream.bufferedReader().readText() else null
        } finally { c.disconnect() }
    }
}
