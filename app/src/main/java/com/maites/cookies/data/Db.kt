package com.maites.cookies.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** Base units: grams / millilitres / units. Money: agorot. */
enum class BaseUnit { Gram, Milliliter, Unit }

@Entity(tableName = "products", indices = [Index("barcode"), Index("gid", unique = true)])
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
    /** Key of a [CategoryEntity] (kind products). */
    val category: String = "Pantry",
    val imageUrl: String? = null,
    /** Cloud id (stable across devices). */
    val gid: String = java.util.UUID.randomUUID().toString(),
    /** Changed locally since the last cloud push. */
    val dirty: Boolean = true,
)

/** A deleted row the cloud still has to hear about. [kind] = cloud collection. */
@Entity(tableName = "tombstones")
data class Tombstone(@PrimaryKey val gid: String, val kind: String)

@Dao
interface SyncDao {
    @Query("SELECT (SELECT COUNT(*) FROM products WHERE dirty = 1) + (SELECT COUNT(*) FROM recipes WHERE dirty = 1) + (SELECT COUNT(*) FROM categories WHERE dirty = 1) + (SELECT COUNT(*) FROM tombstones)")
    fun pending(): Flow<Int>
    @Query("SELECT * FROM tombstones") suspend fun tombstones(): List<Tombstone>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun tomb(t: Tombstone)
    @Query("DELETE FROM tombstones WHERE gid = :gid") suspend fun untomb(gid: String)
    @Query("DELETE FROM tombstones") suspend fun clearTombstones()
    /** Viewers of a shared account: the cloud always wins. */
    @Transaction
    suspend fun forgetLocalEdits() { cleanProducts(); cleanRecipes(); cleanCategories(); clearTombstones() }
    @Query("UPDATE products SET dirty = 0") suspend fun cleanProducts()
    @Query("UPDATE recipes SET dirty = 0") suspend fun cleanRecipes()
    @Query("UPDATE categories SET dirty = 0") suspend fun cleanCategories()
    @Query("UPDATE products SET dirty = 1") suspend fun dirtyProducts()
    @Query("UPDATE recipes SET dirty = 1") suspend fun dirtyRecipes()
}

@Dao
interface ProductDao {
    @Query("SELECT * FROM products ORDER BY favorite DESC, name")
    fun all(): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products WHERE id = :id")
    fun byId(id: Long): Flow<ProductEntity?>

    @Query("SELECT * FROM products WHERE barcode = :code LIMIT 1")
    suspend fun byBarcode(code: String): ProductEntity?

    /** Raw write (cloud pulls). UI code uses [upsert]. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(p: ProductEntity): Long

    @Transaction
    suspend fun upsert(p: ProductEntity): Long = put(p.copy(dirty = true))

    @Query("UPDATE products SET favorite = :fav, dirty = 1, updatedAt = :at WHERE id = :id")
    suspend fun setFavorite(id: Long, fav: Boolean, at: Long = System.currentTimeMillis())

    @Query("UPDATE products SET priceAgorot = :agorot, dirty = 1, updatedAt = :at WHERE id = :id")
    suspend fun setPrice(id: Long, agorot: Long?, at: Long = System.currentTimeMillis())

    @Query("SELECT * FROM products WHERE gid = :gid") suspend fun byGid(gid: String): ProductEntity?
    @Query("SELECT * FROM products WHERE id = :id") suspend fun once(id: Long): ProductEntity?
    @Query("SELECT * FROM products WHERE dirty = 1") suspend fun dirty(): List<ProductEntity>
    @Query("SELECT * FROM products WHERE imageUrl LIKE 'http%'") suspend fun remoteImages(): List<ProductEntity>
    @Query("UPDATE products SET imageUrl = :path, dirty = 1 WHERE id = :id AND imageUrl = :old") suspend fun setImage(id: Long, old: String, path: String): Int
    @Query("SELECT imageUrl FROM products WHERE imageUrl LIKE '/%'") suspend fun localImages(): List<String>
    @Query("UPDATE products SET dirty = 0 WHERE id = :id AND updatedAt = :at") suspend fun markClean(id: Long, at: Long)
    @Query("DELETE FROM products WHERE id = :id") suspend fun deleteRow(id: Long)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun tomb(t: Tombstone)

    @Query("SELECT barcode FROM products WHERE barcode IS NOT NULL")
    suspend fun barcodes(): List<String>

    @Transaction
    suspend fun delete(p: ProductEntity) { deleteRow(p.id); tomb(Tombstone(p.gid, "products")) }
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

private val M6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        for (t in listOf("products", "recipes")) {
            db.execSQL("ALTER TABLE $t ADD COLUMN gid TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE $t ADD COLUMN dirty INTEGER NOT NULL DEFAULT 1")
            db.execSQL("UPDATE $t SET gid = lower(hex(randomblob(16)))")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_${t}_gid ON $t(gid)")
        }
        db.execSQL("CREATE TABLE IF NOT EXISTS tombstones (gid TEXT NOT NULL, kind TEXT NOT NULL, PRIMARY KEY(gid))")
    }
}

private val M7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS shop_entries (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, recipeId INTEGER, productId INTEGER, qty REAL NOT NULL, addedAt INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS shop_checks (`key` TEXT NOT NULL, PRIMARY KEY(`key`))")
    }
}

private val M8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) = Cats.seed(db)
}

/** Catalog gets per-chain prices; emptied so the new file is downloaded on next use. */
private val M9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE catalog ADD COLUMN prices TEXT NOT NULL DEFAULT ''")
        db.execSQL("DELETE FROM catalog")
    }
}

@Database(entities = [CategoryEntity::class, ProductEntity::class, ChainStore::class, ChainPrice::class, RecipeEntity::class, IngredientEntity::class, CatalogItem::class, Tombstone::class, ShopEntry::class, ShopCheck::class], version = 10, exportSchema = false)
@androidx.room.TypeConverters(Converters::class)
abstract class AppDb : RoomDatabase() {
    abstract fun products(): ProductDao
    abstract fun prices(): PriceDao
    abstract fun recipes(): RecipeDao
    abstract fun catalog(): CatalogDao
    abstract fun sync(): SyncDao
    abstract fun shop(): ShopDao
    abstract fun categories(): CategoryDao

    companion object {
        fun create(ctx: Context) = Room.databaseBuilder(ctx, AppDb::class.java, Workspace.dbName).addMigrations(M1_2, M2_3, M3_4, M4_5, M5_6, M6_7, M7_8, M8_9, M9_10)
            .addCallback(object : Callback() { override fun onCreate(db: SupportSQLiteDatabase) = Cats.seed(db) })
            .build()
    }
}

class Converters {
    @androidx.room.TypeConverter fun fromUnit(u: BaseUnit) = u.name
    @androidx.room.TypeConverter fun toUnit(s: String) = BaseUnit.valueOf(s)
}
