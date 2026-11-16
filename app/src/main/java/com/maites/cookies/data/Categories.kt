package com.maites.cookies.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * A user-editable category. [gid] is also the key products / recipes store in `category`
 * (the built-in ones keep their old enum names). [kind]: "products" / "recipes". [tint]: index into the six tints.
 */
@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val gid: String,
    val kind: String,
    val label: String,
    val emoji: String,
    val tint: Int,
    val position: Int,
    val updatedAt: Long = System.currentTimeMillis(),
    val dirty: Boolean = true,
)

const val KIND_PRODUCTS = "products"
const val KIND_RECIPES = "recipes"

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY position, label") fun all(): Flow<List<CategoryEntity>>
    @Query("SELECT * FROM categories WHERE kind = :kind ORDER BY position, label") suspend fun ofKind(kind: String): List<CategoryEntity>
    @Query("SELECT * FROM categories WHERE gid = :gid") suspend fun byGid(gid: String): CategoryEntity?
    @Query("SELECT * FROM categories WHERE dirty = 1") suspend fun dirty(): List<CategoryEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(c: CategoryEntity)
    @Query("UPDATE categories SET dirty = 0 WHERE gid = :gid AND updatedAt = :at") suspend fun markClean(gid: String, at: Long)
    @Query("UPDATE categories SET dirty = 1") suspend fun dirtyAll()
    @Query("DELETE FROM categories WHERE gid = :gid") suspend fun deleteRow(gid: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun tomb(t: Tombstone)
    @Query("SELECT COUNT(*) FROM products WHERE category = :gid") suspend fun productCount(gid: String): Int
    @Query("SELECT COUNT(*) FROM recipes WHERE category = :gid") suspend fun recipeCount(gid: String): Int
    @Query("UPDATE products SET category = :to, dirty = 1, updatedAt = :at WHERE category = :from") suspend fun moveProducts(from: String, to: String, at: Long = System.currentTimeMillis())
    @Query("UPDATE recipes SET category = :to, dirty = 1, updatedAt = :at WHERE category = :from") suspend fun moveRecipes(from: String, to: String, at: Long = System.currentTimeMillis())

    @Transaction
    suspend fun save(c: CategoryEntity) = put(c.copy(dirty = true, updatedAt = System.currentTimeMillis()))

    /** Deletes [c]; whatever was in it moves to [to]. */
    @Transaction
    suspend fun delete(c: CategoryEntity, to: String) {
        if (c.kind == KIND_PRODUCTS) moveProducts(c.gid, to) else moveRecipes(c.gid, to)
        deleteRow(c.gid)
        tomb(Tombstone(c.gid, "categories"))
    }

    /** Swaps the order of two categories. */
    @Transaction
    suspend fun swap(a: CategoryEntity, b: CategoryEntity) {
        val now = System.currentTimeMillis()
        put(a.copy(position = b.position, dirty = true, updatedAt = now))
        put(b.copy(position = a.position, dirty = true, updatedAt = now))
    }
}

/** In-memory mirror of the categories table, readable from composables (snapshot state). */
object Cats {
    var all by mutableStateOf(DEFAULTS)
        internal set

    fun list(kind: String) = all.filter { it.kind == kind }.sortedBy { it.position }

    /** The category for [key], or the first of its kind when it's gone (deleted on another phone). */
    fun of(key: String, kind: String): CategoryEntity =
        all.firstOrNull { it.gid == key } ?: list(kind).firstOrNull() ?: CategoryEntity(key, kind, "", if (kind == KIND_PRODUCTS) "🫙" else "🥣", 2, 0)

    fun product(key: String) = of(key, KIND_PRODUCTS)
    fun recipe(key: String) = of(key, KIND_RECIPES)
    fun firstKey(kind: String) = list(kind).firstOrNull()?.gid ?: if (kind == KIND_PRODUCTS) "Pantry" else "Other"

    val DEFAULTS: List<CategoryEntity> get() = listOf(
        CategoryEntity("Baking", KIND_PRODUCTS, "אפייה", "🌾", 0, 0, 0, false),
        CategoryEntity("Dairy", KIND_PRODUCTS, "חלב וביצים", "🥚", 3, 1, 0, false),
        CategoryEntity("Chocolate", KIND_PRODUCTS, "שוקולד", "🍫", 1, 2, 0, false),
        CategoryEntity("Pantry", KIND_PRODUCTS, "מזווה", "🫙", 2, 3, 0, false),
        CategoryEntity("Cookies", KIND_RECIPES, "עוגיות", "🍪", 1, 0, 0, false),
        CategoryEntity("Cakes", KIND_RECIPES, "עוגות", "🎂", 5, 1, 0, false),
        CategoryEntity("Pastry", KIND_RECIPES, "מאפים", "🥐", 0, 2, 0, false),
        CategoryEntity("Desserts", KIND_RECIPES, "קינוחים", "🍮", 4, 3, 0, false),
        CategoryEntity("Other", KIND_RECIPES, "אחר", "🥣", 2, 4, 0, false),
    )

    internal fun seed(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS categories (gid TEXT NOT NULL, kind TEXT NOT NULL, label TEXT NOT NULL, emoji TEXT NOT NULL, tint INTEGER NOT NULL, position INTEGER NOT NULL, updatedAt INTEGER NOT NULL, dirty INTEGER NOT NULL, PRIMARY KEY(gid))")
        DEFAULTS.forEach { c ->
            db.execSQL(
                "INSERT OR IGNORE INTO categories (gid, kind, label, emoji, tint, position, updatedAt, dirty) VALUES (?, ?, ?, ?, ?, ?, 0, 0)",
                arrayOf<Any>(c.gid, c.kind, c.label, c.emoji, c.tint, c.position),
            )
        }
    }
}
