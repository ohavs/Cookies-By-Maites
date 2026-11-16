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
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

enum class RecipeCategory(val label: String, val emoji: String) {
    Cookies("עוגיות", "🍪"), Cakes("עוגות", "🎂"), Pastry("מאפים", "🥐"), Desserts("קינוחים", "🍮"), Other("אחר", "🥣"),
}

/** [photos]: local file paths, "\n"-separated; the first is the main photo. [yieldCount]: units the recipe makes. */
@Entity(tableName = "recipes")
data class RecipeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val category: RecipeCategory = RecipeCategory.Cookies,
    val yieldCount: Int = 0,
    val yieldLabel: String = "",
    val notes: String = "",
    val photos: String = "",
    val favorite: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val photoList get() = photos.split('\n').filter { it.isNotBlank() }
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

data class RecipeRow(val id: Long, val name: String, val category: RecipeCategory, val yieldCount: Int, val yieldLabel: String, val photos: String, val favorite: Boolean, val count: Int)

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
    @Query("UPDATE recipes SET favorite = :fav WHERE id = :id") suspend fun setFavorite(id: Long, fav: Boolean)
    @Query("SELECT COUNT(*) FROM ingredients WHERE productId = :productId") suspend fun usesOfProduct(productId: Long): Int
    @Query("UPDATE ingredients SET productId = NULL WHERE productId = :productId") suspend fun unlinkProduct(productId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(r: RecipeEntity): Long
    @Insert suspend fun insertIngredients(i: List<IngredientEntity>)
    @Query("DELETE FROM ingredients WHERE recipeId = :id") suspend fun clearIngredients(id: Long)
    @Query("DELETE FROM recipes WHERE id = :id") suspend fun delete(id: Long)

    /** Saves the recipe and replaces its ingredient list. Returns the recipe id. */
    @Transaction
    suspend fun save(r: RecipeEntity, items: List<IngredientEntity>): Long {
        val id = if (r.id == 0L) upsert(r) else { upsert(r); r.id }
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
            val f = File(dir(ctx), UUID.randomUUID().toString() + ".webp")
            @Suppress("DEPRECATION")
            val fmt = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
            f.outputStream().use { bmp.compress(fmt, 82, it) }
            f.absolutePath
        }.getOrNull()
    }

    /** Deletes photo files no recipe references any more. */
    fun prune(ctx: Context, keep: Set<String>) {
        dir(ctx).listFiles()?.forEach { if (it.absolutePath !in keep) it.delete() }
    }
}

/** What one ingredient line costs, given its product and the product's price. Null when unknown. */
fun ingredientCost(i: IngredientEntity, p: ProductEntity?, packPrice: Long?): Long? {
    if (p == null || packPrice == null || p.packAmount <= 0 || p.unit != i.unit) return null
    return Math.round(packPrice * i.amount / p.packAmount)
}
