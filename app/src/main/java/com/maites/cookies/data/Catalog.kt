package com.maites.cookies.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/** One packaged item seen in the chains' price files. [unit]: "G" / "M" / "U" / "". [stores]: how many stores sell it. */
@Entity(tableName = "catalog")
data class CatalogItem(
    @PrimaryKey val code: String,
    val name: String,
    val brand: String,
    val amount: Double,
    val unit: String,
    val stores: Int,
    /** Typical shelf price per chain: "shufersal:790,ramilevy:690". */
    @androidx.room.ColumnInfo(defaultValue = "") val prices: String = "",
) {
    /** (chain, agorot), cheapest first. */
    val chainPrices: List<Pair<Chain, Long>> get() = prices.split(',').mapNotNull { e ->
        val (k, v) = e.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
        val ch = Chain.of(k) ?: return@mapNotNull null
        v.toLongOrNull()?.takeIf { it > 0 }?.let { ch to it }
    }.sortedBy { it.second }
    val cheapest get() = chainPrices.firstOrNull()

    val baseUnit get() = when (unit) { "G" -> BaseUnit.Gram; "M" -> BaseUnit.Milliliter; "U" -> BaseUnit.Unit; else -> null }
}

@Dao
interface CatalogDao {
    @Query("SELECT * FROM catalog WHERE name LIKE '%' || :word || '%' OR brand LIKE '%' || :word || '%' OR code = :word ORDER BY stores DESC LIMIT 300")
    suspend fun byWord(word: String): List<CatalogItem>

    @Query("SELECT COUNT(*) FROM catalog") suspend fun count(): Int
    @Query("SELECT EXISTS(SELECT 1 FROM catalog WHERE prices != '')") suspend fun hasPrices(): Boolean
    @Query("DELETE FROM catalog") suspend fun clear()
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(items: List<CatalogItem>)

    @Transaction
    suspend fun replace(chunks: Sequence<List<CatalogItem>>) { clear(); chunks.forEach { put(it) } }

    /** All words must match name / brand; the longest word goes to SQL. */
    suspend fun search(q: String): List<CatalogItem> {
        val words = q.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val lead = words.maxBy { it.length }
        return byWord(lead).filter { i -> words.all { w -> i.name.contains(w) || i.brand.contains(w) || i.code == w } }.take(40)
    }
}

/** Downloads the daily catalog (published by the prices Action) once a week. */
class CatalogSync(private val db: AppDb, private val prefs: Prefs) {
    suspend fun sync(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val maxAge = if (db.catalog().hasPrices()) 7 * 24 * 3600_000L else 6 * 3600_000L
        if (!force && System.currentTimeMillis() - prefs.lastCatalog() < maxAge && db.catalog().count() > 0) return@withContext false
        runCatching {
            val c = (URL(URL_CATALOG).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000; readTimeout = 60000; instanceFollowRedirects = true
            }
            try {
                if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
                GZIPInputStream(c.inputStream, 64 * 1024).bufferedReader().useLines { lines ->
                    db.catalog().replace(lines.mapNotNull(::parse).chunked(1000))
                }
            } finally { c.disconnect() }
            prefs.setLastCatalog(System.currentTimeMillis())
            true
        }.getOrDefault(false)
    }

    private fun parse(line: String): CatalogItem? {
        val f = line.split('\t')
        if (f.size < 6) return null
        return CatalogItem(f[0], f[1], f[2], f[3].toDoubleOrNull() ?: 0.0, f[4], f[5].toIntOrNull() ?: 0, f.getOrNull(6).orEmpty())
    }

    companion object {
        const val URL_CATALOG = "https://github.com/ohavs/cookies-by-maites/releases/download/catalog/catalog.tsv.gz"
    }
}
