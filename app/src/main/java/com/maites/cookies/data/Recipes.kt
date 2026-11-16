package com.maites.cookies.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** [photos]: local file paths, "\n"-separated; the first is the main photo. [yieldCount]: units the recipe makes. */
@Entity(tableName = "recipes", indices = [Index("gid", unique = true)])
data class RecipeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val category: String = "Cookies",
    val yieldCount: Int = 0,
    val yieldLabel: String = "",
    val notes: String = "",
    val photos: String = "",
    val favorite: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
    /** JSON list of [ExtraCost]. */
    val extras: String = "",
    /** Sale price per unit (or per batch when [yieldCount] = 0), agorot. 0 = not set. */
    val salePrice: Long = 0,
    /** Target margin in percent (of the sale price). 0 = not set. */
    val targetMargin: Int = 0,
    val gid: String = UUID.randomUUID().toString(),
    val dirty: Boolean = true,
) {
    val photoList get() = photos.split('\n').filter { it.isNotBlank() }
    val extraList get() = ExtraCost.decode(extras)
}

/** How an extra cost is counted: per batch (₪), per unit (₪) or percent of the ingredients. */
enum class ExtraKind(val label: String) { Batch("₪ לתבנית"), PerUnit("₪ ליחידה"), Percent("% מהמרכיבים") }

/** [value]: agorot, or percent × 100 for [ExtraKind.Percent]. */
data class ExtraCost(val name: String, val kind: ExtraKind, val value: Long) {
    companion object {
        fun encode(list: List<ExtraCost>): String = org.json.JSONArray(list.map {
            org.json.JSONObject().put("n", it.name).put("k", it.kind.name).put("v", it.value)
        }).toString()

        fun decode(s: String): List<ExtraCost> = runCatching {
            val a = org.json.JSONArray(s)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                ExtraCost(o.getString("n"), runCatching { ExtraKind.valueOf(o.getString("k")) }.getOrDefault(ExtraKind.Batch), o.getLong("v"))
            }
        }.getOrDefault(emptyList())
    }
}

/**
 * Full costing of one batch at multiplier [mult]. [ingredients] is already scaled.
 * [units] = units the scaled batch makes (null when the recipe has no yield → per-unit = per-batch).
 */
data class Costing(val ingredients: Long, val extras: List<Pair<ExtraCost, Long>>, val units: Double?) {
    val extrasTotal get() = extras.sumOf { it.second }
    val total get() = ingredients + extrasTotal
    val perUnit: Long get() = units?.takeIf { it > 0 }?.let { Math.round(total / it) } ?: total

    fun profit(sale: Long) = sale - perUnit
    /** Profit as percent of the sale price. */
    fun margin(sale: Long) = if (sale > 0) profit(sale) * 100.0 / sale else 0.0
    /** Price that leaves [percent] of it as profit. */
    fun priceFor(percent: Int): Long? = if (percent in 1..99) Math.round(perUnit * 100.0 / (100 - percent)) else null

    companion object {
        fun of(ingredients: Long, extras: List<ExtraCost>, yieldCount: Int, mult: Double): Costing {
            val units = if (yieldCount > 0) yieldCount * mult else null
            return Costing(ingredients, extras.map { e ->
                e to when (e.kind) {
                    ExtraKind.Batch -> Math.round(e.value * mult)
                    ExtraKind.PerUnit -> Math.round(e.value * (units ?: 1.0))
                    ExtraKind.Percent -> Math.round(ingredients * e.value / 10000.0)
                }
            }, units)
        }
    }
}

/** One line of a recipe. Linked to a product ([productId]) or free text ([name]). [amount] in base units of [unit]. */
@Entity(
    tableName = "ingredients",
    foreignKeys = [ForeignKey(RecipeEntity::class, ["id"], ["recipeId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("recipeId"), Index("productId")],
)
data class IngredientEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recipeId: Long,
    val position: Int,
    val productId: Long? = null,
    val name: String,
    val amount: Double,
    val unit: BaseUnit,
    val icon: String,
)

data class RecipeRow(val id: Long, val name: String, val category: String, val yieldCount: Int, val yieldLabel: String, val photos: String, val favorite: Boolean, val count: Int)

@Dao
interface RecipeDao {
    @Query(
        """SELECT r.id, r.name, r.category, r.yieldCount, r.yieldLabel, r.photos, r.favorite,
           (SELECT COUNT(*) FROM ingredients i WHERE i.recipeId = r.id) AS count
           FROM recipes r ORDER BY r.favorite DESC, r.updatedAt DESC""",
    )
    fun rows(): Flow<List<RecipeRow>>

    @Query("SELECT * FROM recipes WHERE id = :id") fun byId(id: Long): Flow<RecipeEntity?>
    @Query("SELECT * FROM ingredients WHERE recipeId = :id ORDER BY position") fun ingredients(id: Long): Flow<List<IngredientEntity>>
    @Query("SELECT * FROM ingredients WHERE recipeId = :id ORDER BY position") suspend fun ingredientsOnce(id: Long): List<IngredientEntity>
    @Query("UPDATE recipes SET favorite = :fav, dirty = 1, updatedAt = :at WHERE id = :id") suspend fun setFavorite(id: Long, fav: Boolean, at: Long = System.currentTimeMillis())
    @Query("SELECT COUNT(*) FROM ingredients WHERE productId = :productId") suspend fun usesOfProduct(productId: Long): Int
    @Query("UPDATE recipes SET dirty = 1, updatedAt = :at WHERE id IN (SELECT recipeId FROM ingredients WHERE productId = :productId)")
    suspend fun touchUsers(productId: Long, at: Long = System.currentTimeMillis())
    @Query("UPDATE ingredients SET productId = NULL WHERE productId = :productId") suspend fun clearProduct(productId: Long)

    /** Product deleted: its recipe lines keep their names but lose the link. */
    @Transaction suspend fun unlinkProduct(productId: Long) { touchUsers(productId); clearProduct(productId) }

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(r: RecipeEntity): Long
    @Insert suspend fun insertIngredients(i: List<IngredientEntity>)
    @Query("DELETE FROM ingredients WHERE recipeId = :id") suspend fun clearIngredients(id: Long)
    @Query("DELETE FROM recipes WHERE id = :id") suspend fun deleteRow(id: Long)
    @Query("SELECT gid FROM recipes WHERE id = :id") suspend fun gidOf(id: Long): String?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun tomb(t: Tombstone)
    @Query("UPDATE recipes SET extras = :extras, salePrice = :sale, targetMargin = :margin, dirty = 1, updatedAt = :at WHERE id = :id")
    suspend fun setCosting(id: Long, extras: String, sale: Long, margin: Int, at: Long = System.currentTimeMillis())

    @Query("SELECT * FROM recipes WHERE gid = :gid") suspend fun byGid(gid: String): RecipeEntity?
    @Query("SELECT * FROM recipes WHERE dirty = 1") suspend fun dirty(): List<RecipeEntity>
    @Query("SELECT * FROM recipes") suspend fun all(): List<RecipeEntity>
    @Query("UPDATE recipes SET dirty = 0 WHERE id = :id AND updatedAt = :at") suspend fun markClean(id: Long, at: Long)

    @Transaction
    suspend fun delete(id: Long) { gidOf(id)?.let { tomb(Tombstone(it, "recipes")) }; deleteRow(id) }

    /** Saves the recipe and replaces its ingredient list. Returns the recipe id. */
    @Transaction
    suspend fun save(r: RecipeEntity, items: List<IngredientEntity>): Long = write(r.copy(dirty = true), items)

    /** Like [save] without marking the recipe for upload (cloud pulls). */
    @Transaction
    suspend fun write(r: RecipeEntity, items: List<IngredientEntity>): Long {
        val id = if (r.id == 0L) put(r) else { put(r); r.id }
        clearIngredients(id)
        insertIngredients(items.mapIndexed { i, it -> it.copy(id = 0, recipeId = id, position = i) })
        return id
    }
}

/** Recipe photos live in app storage, downscaled to ≤1600px webp. */
object RecipePhotos {
    private fun dir(ctx: Context) = File(ctx.filesDir, "recipes").apply { mkdirs() }

    suspend fun import(ctx: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val bmp: Bitmap = if (Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
                    val s = info.size; val k = maxOf(s.width, s.height) / 1600f
                    if (k > 1f) d.setTargetSize((s.width / k).toInt(), (s.height / k).toInt())
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                val b = ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) }
                val k = maxOf(b.width, b.height) / 1600f
                if (k > 1f) Bitmap.createScaledBitmap(b, (b.width / k).toInt(), (b.height / k).toInt(), true) else b
            }
            save(dir(ctx), bmp)
        }.getOrNull()
    }

    internal fun save(dir: File, bmp: Bitmap): String {
        val f = File(dir, UUID.randomUUID().toString() + ".webp")
        @Suppress("DEPRECATION")
        val fmt = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        f.outputStream().use { bmp.compress(fmt, 82, it) }
        return f.absolutePath
    }

    fun file(ctx: Context, name: String) = File(dir(ctx), name)

    /** Deletes photo files no recipe references any more. */
    fun prune(ctx: Context, keep: Set<String>) {
        dir(ctx).listFiles()?.forEach { if (it.absolutePath !in keep) it.delete() }
    }
}

/**
 * Product pack images live on the phone as webp (`filesDir/products`), like recipe photos,
 * so they sync to Storage instead of pointing at Open Food Facts.
 */
object ProductPhotos {
    private fun dir(ctx: Context) = File(ctx.filesDir, "products").apply { mkdirs() }

    fun file(ctx: Context, name: String) = File(dir(ctx), name)
    fun isLocal(path: String?) = path != null && path.startsWith("/")

    /** Downloads [url] and stores it as webp ≤800px. Null on failure. */
    suspend fun download(ctx: Context, url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val c = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply { connectTimeout = 10000; readTimeout = 20000 }
            val bytes = try { c.inputStream.use { it.readBytes() } } finally { c.disconnect() }
            val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!
            val k = maxOf(b.width, b.height) / 800f
            RecipePhotos.save(dir(ctx), if (k > 1f) Bitmap.createScaledBitmap(b, (b.width / k).toInt(), (b.height / k).toInt(), true) else b)
        }.getOrNull()
    }

    private val lock = kotlinx.coroutines.sync.Mutex()

    /** Moves every product still pointing at a web image onto the phone (marked for sync), then drops unused files. */
    suspend fun localizeAll(ctx: Context, dao: ProductDao) = lock.withLock {
        dao.remoteImages().forEach { p ->
            val url = p.imageUrl ?: return@forEach
            download(ctx, url)?.let { if (dao.setImage(p.id, url, it) == 0) File(it).delete() }
        }
        val keep = dao.localImages().toSet()
        dir(ctx).listFiles()?.forEach { if (it.absolutePath !in keep && !it.name.endsWith(".part")) it.delete() }
    }
}

/** What one ingredient line costs, given its product and the product's price. Null when unknown. */
fun ingredientCost(i: IngredientEntity, p: ProductEntity?, packPrice: Long?): Long? {
    if (p == null || packPrice == null || p.packAmount <= 0 || p.unit != i.unit) return null
    return Math.round(packPrice * i.amount / p.packAmount)
}
