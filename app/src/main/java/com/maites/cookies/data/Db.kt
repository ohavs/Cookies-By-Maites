package com.maites.cookies.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** Base units: grams / millilitres / units. Money: agorot. */
enum class BaseUnit { Gram, Milliliter, Unit }

enum class ProductCategory(val label: String, val emoji: String) {
    Baking("אפייה", "🌾"), Dairy("חלב וביצים", "🥚"), Chocolate("שוקולד", "🍫"), Pantry("מזווה", "🫙"),
}

@Entity(tableName = "products", indices = [Index("barcode")])
data class ProductEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val barcode: String? = null,
    val packAmount: Double,
    val unit: BaseUnit,
    /** Manual price per pack (agorot). Overrides chain prices when set. */
    val priceAgorot: Long? = null,
    val favorite: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
    val brand: String = "",
    val category: ProductCategory = ProductCategory.Pantry,
    val imageUrl: String? = null,
)

@Dao
interface ProductDao {
    @Query("SELECT * FROM products ORDER BY favorite DESC, name")
    fun all(): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products WHERE id = :id")
    fun byId(id: Long): Flow<ProductEntity?>

    @Query("SELECT * FROM products WHERE barcode = :code LIMIT 1")
    suspend fun byBarcode(code: String): ProductEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(p: ProductEntity): Long

    @Query("UPDATE products SET favorite = :fav WHERE id = :id")
    suspend fun setFavorite(id: Long, fav: Boolean)

    @Query("UPDATE products SET priceAgorot = :agorot, updatedAt = :at WHERE id = :id")
    suspend fun setPrice(id: Long, agorot: Long?, at: Long = System.currentTimeMillis())

    @Query("SELECT barcode FROM products WHERE barcode IS NOT NULL")
    suspend fun barcodes(): List<String>

    @Delete
    suspend fun delete(p: ProductEntity)
}

private val M1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE products ADD COLUMN brand TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE products ADD COLUMN category TEXT NOT NULL DEFAULT 'Pantry'")
        db.execSQL("ALTER TABLE products ADD COLUMN imageUrl TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_products_barcode ON products(barcode)")
    }
}

private val M2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS chain_stores (chain TEXT NOT NULL, storeId TEXT NOT NULL, name TEXT NOT NULL, city TEXT NOT NULL, address TEXT NOT NULL, PRIMARY KEY(chain, storeId))")
        db.execSQL("CREATE TABLE IF NOT EXISTS chain_prices (barcode TEXT NOT NULL, chain TEXT NOT NULL, storeId TEXT NOT NULL, agorot INTEGER NOT NULL, PRIMARY KEY(barcode, chain, storeId))")
    }
}

private val M3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS recipes (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, category TEXT NOT NULL, yieldCount INTEGER NOT NULL, yieldLabel TEXT NOT NULL, notes TEXT NOT NULL, photos TEXT NOT NULL, favorite INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS ingredients (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, recipeId INTEGER NOT NULL, position INTEGER NOT NULL, productId INTEGER, name TEXT NOT NULL, amount REAL NOT NULL, unit TEXT NOT NULL, icon TEXT NOT NULL, FOREIGN KEY(recipeId) REFERENCES recipes(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_ingredients_recipeId ON ingredients(recipeId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_ingredients_productId ON ingredients(productId)")
    }
}

private val M4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE recipes ADD COLUMN extras TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE recipes ADD COLUMN salePrice INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE recipes ADD COLUMN targetMargin INTEGER NOT NULL DEFAULT 0")
    }
}

private val M5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS catalog (code TEXT NOT NULL, name TEXT NOT NULL, brand TEXT NOT NULL, amount REAL NOT NULL, unit TEXT NOT NULL, stores INTEGER NOT NULL, PRIMARY KEY(code))")
    }
}

@Database(entities = [ProductEntity::class, ChainStore::class, ChainPrice::class, RecipeEntity::class, IngredientEntity::class, CatalogItem::class], version = 6, exportSchema = false)
@androidx.room.TypeConverters(Converters::class)
abstract class AppDb : RoomDatabase() {
    abstract fun products(): ProductDao
    abstract fun prices(): PriceDao
    abstract fun recipes(): RecipeDao
    abstract fun catalog(): CatalogDao

    companion object {
        fun create(ctx: Context) = Room.databaseBuilder(ctx, AppDb::class.java, "cookies.db").addMigrations(M1_2, M2_3, M3_4, M4_5, M5_6).build()
    }
}

class Converters {
    @androidx.room.TypeConverter fun fromUnit(u: BaseUnit) = u.name
    @androidx.room.TypeConverter fun toUnit(s: String) = BaseUnit.valueOf(s)
    @androidx.room.TypeConverter fun fromCat(c: ProductCategory) = c.name
    @androidx.room.TypeConverter fun toCat(s: String) = runCatching { ProductCategory.valueOf(s) }.getOrDefault(ProductCategory.Pantry)
    @androidx.room.TypeConverter fun fromRCat(c: RecipeCategory) = c.name
    @androidx.room.TypeConverter fun toRCat(s: String) = runCatching { RecipeCategory.valueOf(s) }.getOrDefault(RecipeCategory.Other)
}
