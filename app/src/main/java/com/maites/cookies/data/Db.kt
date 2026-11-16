package com.maites.cookies.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** Base units: grams / millilitres / units. Money: agorot. */
enum class BaseUnit { Gram, Milliliter, Unit }

@Entity(tableName = "products")
data class ProductEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val barcode: String? = null,
    val packAmount: Double,
    val unit: BaseUnit,
    val priceAgorot: Long? = null,
    val favorite: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Dao
interface ProductDao {
    @Query("SELECT * FROM products ORDER BY favorite DESC, name")
    fun all(): Flow<List<ProductEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(p: ProductEntity): Long
}

@Database(entities = [ProductEntity::class], version = 1, exportSchema = false)
@androidx.room.TypeConverters(Converters::class)
abstract class AppDb : RoomDatabase() {
    abstract fun products(): ProductDao

    companion object {
        fun create(ctx: Context) = Room.databaseBuilder(ctx, AppDb::class.java, "cookies.db").build()
    }
}

class Converters {
    @androidx.room.TypeConverter fun fromUnit(u: BaseUnit) = u.name
    @androidx.room.TypeConverter fun toUnit(s: String) = BaseUnit.valueOf(s)
}
