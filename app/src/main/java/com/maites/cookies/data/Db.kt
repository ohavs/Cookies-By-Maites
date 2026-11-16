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

@Database(entities = [ProductEntity::class, ChainStore::class, ChainPrice::class], version = 3, exportSchema = false)
@androidx.room.TypeConverters(Converters::class)
abstract class AppDb : RoomDatabase() {
    abstract fun products(): ProductDao
    abstract fun prices(): PriceDao

    companion object {
        fun create(ctx: Context) = Room.databaseBuilder(ctx, AppDb::class.java, "cookies.db").addMigrations(M1_2, M2_3).build()
    }
}

class Converters {
    @androidx.room.TypeConverter fun fromUnit(u: BaseUnit) = u.name
    @androidx.room.TypeConverter fun toUnit(s: String) = BaseUnit.valueOf(s)
    @androidx.room.TypeConverter fun fromCat(c: ProductCategory) = c.name
    @androidx.room.TypeConverter fun toCat(s: String) = runCatching { ProductCategory.valueOf(s) }.getOrDefault(ProductCategory.Pantry)
}
