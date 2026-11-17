package com.maites.cookies.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import kotlin.math.ceil

/**
 * One thing on the shopping list: a recipe ([recipeId], [qty] = batch multiplier)
 * or a product ([productId], [qty] = packs). Synced as one cloud document (`users/{uid}/shop/list`).
 * No foreign keys — recipes/products are rewritten with REPLACE, which would cascade.
 */
@Entity(tableName = "shop_entries")
data class ShopEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recipeId: Long? = null,
    val productId: Long? = null,
    val qty: Double,
    val addedAt: Long = System.currentTimeMillis(),
)

/** A ticked list line, by [ShopLine.key]. */
@Entity(tableName = "shop_checks")
data class ShopCheck(@PrimaryKey val key: String)

data class ShopPriceRow(val barcode: String, val chain: String, val storeId: String, val name: String, val agorot: Long)

@Dao
interface ShopDao {
    @Query("SELECT * FROM shop_entries ORDER BY addedAt") fun entries(): Flow<List<ShopEntry>>
    @Query("SELECT `key` FROM shop_checks") fun checks(): Flow<List<String>>

    @Query("SELECT * FROM ingredients WHERE recipeId IN (SELECT recipeId FROM shop_entries WHERE recipeId IS NOT NULL) ORDER BY recipeId, position")
    fun ingredients(): Flow<List<IngredientEntity>>

    @Query(
        """SELECT p.barcode, p.chain, p.storeId, s.name, p.agorot FROM chain_prices p
           JOIN chain_stores s ON s.chain = p.chain AND s.storeId = p.storeId
           WHERE s.city = :city AND p.barcode IN (:codes)""",
    )
    fun pricesInCity(city: String, codes: List<String>): Flow<List<ShopPriceRow>>

    @Query("SELECT * FROM shop_entries WHERE recipeId = :id LIMIT 1") suspend fun byRecipe(id: Long): ShopEntry?
    @Query("SELECT * FROM shop_entries WHERE productId = :id LIMIT 1") suspend fun byProduct(id: Long): ShopEntry?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(e: ShopEntry): Long
    @Query("UPDATE shop_entries SET qty = :qty WHERE id = :id") suspend fun setQty(id: Long, qty: Double)
    @Query("DELETE FROM shop_entries WHERE id = :id") suspend fun remove(id: Long)
    @Query("DELETE FROM shop_entries") suspend fun clearEntries()
    @Query("DELETE FROM shop_checks") suspend fun clearChecks()
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun check(c: ShopCheck)
    @Query("DELETE FROM shop_checks WHERE `key` = :key") suspend fun uncheck(key: String)

    /** Drops entries whose recipe/product was deleted. */
    @Query(
        """DELETE FROM shop_entries WHERE (recipeId IS NOT NULL AND recipeId NOT IN (SELECT id FROM recipes))
           OR (productId IS NOT NULL AND productId NOT IN (SELECT id FROM products))""",
    )
    suspend fun prune()

    /** Adds another [mult] batches of the recipe (summed when already listed). */
    @Transaction
    suspend fun addRecipe(id: Long, mult: Double) {
        val e = byRecipe(id)
        if (e == null) put(ShopEntry(recipeId = id, qty = mult)) else setQty(e.id, e.qty + mult)
    }

    @Transaction
    suspend fun addProduct(id: Long, packs: Double = 1.0) {
        val e = byProduct(id)
        if (e == null) put(ShopEntry(productId = id, qty = packs)) else setQty(e.id, e.qty + packs)
    }

    @Transaction
    suspend fun clear() { clearEntries(); clearChecks() }

    @Query("SELECT * FROM shop_entries ORDER BY addedAt, id") suspend fun entriesOnce(): List<ShopEntry>
    @Query("SELECT `key` FROM shop_checks") suspend fun checksOnce(): List<String>
    @Insert suspend fun putAll(e: List<ShopEntry>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun checkAll(c: List<ShopCheck>)

    /** Replaces the whole list (from the cloud). */
    @Transaction
    suspend fun replace(entries: List<ShopEntry>, checks: List<String>) {
        clearEntries(); clearChecks(); putAll(entries); checkAll(checks.map(::ShopCheck))
    }
}

/**
 * One merged line of the list. [need] in base units of [unit].
 * Linked lines ([product] != null) share the product's unit; others merge by name + unit.
 */
data class ShopLine(val key: String, val product: ProductEntity?, val name: String, val icon: String, val unit: BaseUnit, val need: Double) {
    /** Whole packs to buy (≥1). Null when not linked to a product with a pack size. */
    val packs: Int? get() = product?.takeIf { it.packAmount > 0 }?.let { maxOf(1, ceil(need / it.packAmount - 1e-6).toInt()) }

    /** Cost at [packPrice]: whole packs, or the exact share of a pack. */
    fun cost(packPrice: Long?, byPacks: Boolean): Long? {
        val p = product ?: return null
        if (packPrice == null || p.packAmount <= 0) return null
        return if (byPacks) packPrice * (packs ?: 1) else Math.round(packPrice * need / p.packAmount)
    }
}

object Shopping {
    fun lines(
        entries: List<ShopEntry>,
        ingredients: List<IngredientEntity>,
        products: Map<Long, ProductEntity>,
        recipes: Set<Long>,
    ): List<ShopLine> {
        val out = LinkedHashMap<String, ShopLine>()
        fun add(key: String, mk: () -> ShopLine, amount: Double) {
            val cur = out[key] ?: mk().copy(need = 0.0)
            out[key] = cur.copy(need = cur.need + amount)
        }
        val byRecipe = ingredients.groupBy { it.recipeId }
        for (e in entries) {
            if (e.recipeId != null && e.recipeId in recipes) {
                for (i in byRecipe[e.recipeId].orEmpty()) {
                    val p = i.productId?.let(products::get)
                    val amount = i.amount * e.qty
                    if (amount <= 0) continue
                    if (p != null && p.unit == i.unit) add("p${p.id}", { ShopLine("p${p.id}", p, p.name, i.icon, p.unit, 0.0) }, amount)
                    else {
                        val name = p?.name ?: i.name.trim()
                        add("n$name|${i.unit}", { ShopLine("n$name|${i.unit}", null, name, i.icon, i.unit, 0.0) }, amount)
                    }
                }
            }
            val p = e.productId?.let(products::get) ?: continue
            add("p${p.id}", { ShopLine("p${p.id}", p, p.name, Cats.product(p.category).emoji, p.unit, 0.0) }, e.qty * p.packAmount)
        }
        return out.values.toList()
    }

    /** Basket total in one chain: its best store in the city (most items, then cheapest). */
    data class Offer(val chain: String, val store: String, val total: Long, val have: Int, val of: Int) {
        val missing get() = of - have
    }

    /** Chains ranked for [lines] (only lines with a barcode count): fewest missing, then cheapest. */
    fun rank(lines: List<ShopLine>, rows: List<ShopPriceRow>, byPacks: Boolean): List<Offer> {
        val coded = lines.mapNotNull { l -> priceKey(l.product?.barcode)?.let { it to l } }
        if (coded.isEmpty()) return emptyList()
        val stores = rows.groupBy { it.chain to it.storeId }
        return stores.map { (k, rs) ->
            val price = rs.associate { it.barcode to it.agorot }
            var total = 0L; var have = 0
            for ((code, l) in coded) l.cost(price[code], byPacks)?.let { total += it; have++ }
            Offer(k.first, rs.first().name, total, have, coded.size)
        }.groupBy { it.chain }
            .map { (_, offers) -> offers.sortedWith(compareBy({ -it.have }, { it.total })).first() }
            .sortedWith(compareBy({ it.missing }, { it.total }))
    }
}
