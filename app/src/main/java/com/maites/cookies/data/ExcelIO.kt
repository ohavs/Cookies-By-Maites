package com.maites.cookies.data

import com.maites.cookies.CookiesApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

/**
 * Our Excel format — three sheets, first row = headers (matched by name, any order):
 *  מוצרים:  שם · מותג · ברקוד · גודל אריזה · יחידה · מחיר · קטגוריה
 *  מתכונים: שם · קטגוריה · כמות יחידות · שם היחידה · מחיר מכירה · הערות
 *  מרכיבים: מתכון · מרכיב · כמות · יחידה · ברקוד
 * Units: גרם / ק״ג / מ״ל / ליטר / יחידות. Money in ₪.
 * Import updates by barcode or name and adds the rest; a recipe listed in מרכיבים gets exactly those lines.
 */
object ExcelIO {
    private val P_HEAD = listOf("שם", "מותג", "ברקוד", "גודל אריזה", "יחידה", "מחיר (₪)", "קטגוריה")
    private val R_HEAD = listOf("שם", "קטגוריה", "כמות יחידות", "שם היחידה", "מחיר מכירה (₪)", "הערות")
    private val I_HEAD = listOf("מתכון", "מרכיב", "כמות", "יחידה", "ברקוד")

    data class Result(val productsAdded: Int, val productsUpdated: Int, val recipesAdded: Int, val recipesUpdated: Int, val ingredients: Int, val skipped: List<String>) {
        val total get() = productsAdded + productsUpdated + recipesAdded + recipesUpdated
    }

    // ---- export (the user's data, or examples when empty) ----

    suspend fun write(app: CookiesApp, out: OutputStream) = withContext(Dispatchers.IO) {
        val db = app.db
        val products = db.products().all().first()
        val recipes = db.recipes().all().sortedBy { it.name }
        val byId = products.associateBy { it.id }
        val pRows: List<List<Any?>>
        val rRows: List<List<Any?>>
        val iRows: List<List<Any?>>
        if (products.isEmpty() && recipes.isEmpty()) {
            pRows = listOf(
                listOf("קמח לבן", "סוגת", "7290000000001", 1, "ק״ג", 5.9, "אפייה"),
                listOf("חמאה", "תנובה", "", 200, "גרם", 8.5, "חלב וביצים"),
                listOf("ביצים L", "", "", 12, "יחידות", 14.9, "חלב וביצים"),
            )
            rRows = listOf(listOf("עוגיות חמאה", "עוגיות", 24, "עוגיות", 3.5, "אופים 12 דקות ב-180°"))
            iRows = listOf(
                listOf("עוגיות חמאה", "קמח לבן", 300, "גרם", ""),
                listOf("עוגיות חמאה", "חמאה", 200, "גרם", ""),
                listOf("עוגיות חמאה", "ביצים L", 1, "יחידות", ""),
                listOf("עוגיות חמאה", "תמצית וניל", 5, "מ״ל", ""),
            )
        } else {
            pRows = products.sortedBy { it.name }.map { p ->
                val (amt, u) = displayAmount(p.packAmount, p.unit)
                listOf(p.name, p.brand, p.barcode.orEmpty(), amt, u, p.priceAgorot?.let { it / 100.0 }, Cats.product(p.category).label)
            }
            rRows = recipes.map { r ->
                listOf(r.name, Cats.recipe(r.category).label, r.yieldCount.takeIf { it > 0 }, r.yieldLabel, r.salePrice.takeIf { it > 0 }?.let { it / 100.0 }, r.notes)
            }
            iRows = recipes.flatMap { r ->
                db.recipes().ingredientsOnce(r.id).map { i ->
                    val p = i.productId?.let(byId::get)
                    listOf(r.name, p?.name ?: i.name, clean(i.amount), i.m.one, p?.barcode.orEmpty())
                }
            }
        }
        Xlsx.write(out, listOf("מוצרים" to listOf(P_HEAD) + pRows, "מתכונים" to listOf(R_HEAD) + rRows, "מרכיבים" to listOf(I_HEAD) + iRows))
    }

    private fun clean(d: Double): Number = if (d == Math.floor(d)) d.toLong() else Math.round(d * 1000) / 1000.0
    private fun unitWord(u: BaseUnit) = when (u) { BaseUnit.Gram -> "גרם"; BaseUnit.Milliliter -> "מ״ל"; BaseUnit.Unit -> "יחידות" }
    private fun displayAmount(a: Double, u: BaseUnit): Pair<Number, String> = when {
        u == BaseUnit.Gram && a >= 1000 && a % 100 == 0.0 -> clean(a / 1000) to "ק״ג"
        u == BaseUnit.Milliliter && a >= 1000 && a % 100 == 0.0 -> clean(a / 1000) to "ליטר"
        else -> clean(a) to unitWord(u)
    }

    // ---- import ----

    private class Table(rows: List<List<String>>) {
        val head = rows.firstOrNull().orEmpty().map(::norm)
        val body = rows.drop(1).filter { r -> r.any { it.isNotBlank() } }
        fun col(vararg names: String): Int = names.map(::norm).firstNotNullOfOrNull { n -> head.indexOfFirst { it == n }.takeIf { it >= 0 } }
            ?: names.map(::norm).firstNotNullOfOrNull { n -> head.indexOfFirst { it.startsWith(n) }.takeIf { it >= 0 } } ?: -1
    }

    /** Header/label key: no quotes, spaces or brackets. */
    private fun norm(s: String) = s.lowercase(Locale.ROOT).replace(Regex("[\\s\"'״׳()₪]"), "")

    private fun List<String>.at(i: Int) = if (i >= 0) getOrNull(i)?.trim().orEmpty() else ""

    /** "1,5" / "1.5" / "₪ 12.90" / "1,250" → number. */
    fun number(s: String): Double? {
        var t = s.replace(Regex("[^0-9.,\\-]"), "")
        if (t.isEmpty()) return null
        t = if (!t.contains('.') && Regex("^-?\\d+,\\d{1,2}$").matches(t)) t.replace(',', '.') else t.replace(",", "")
        return t.toDoubleOrNull()
    }

    /** Unit word → base unit + factor to base. Blank = null. */
    private fun unit(s: String): Pair<BaseUnit, Double>? = when (norm(s)) {
        "" -> null
        "גרם", "גר", "ג", "g", "gr", "gram", "grams" -> BaseUnit.Gram to 1.0
        "קג", "קילו", "קילוגרם", "kg" -> BaseUnit.Gram to 1000.0
        "מל", "מיליליטר", "ml" -> BaseUnit.Milliliter to 1.0
        "ליטר", "ל", "l", "liter", "litre" -> BaseUnit.Milliliter to 1000.0
        "כפית", "tsp" -> BaseUnit.Milliliter to 5.0
        "כף", "tbsp" -> BaseUnit.Milliliter to 15.0
        "כוס", "cup" -> BaseUnit.Milliliter to 240.0
        else -> BaseUnit.Unit to 1.0
    }

    suspend fun read(app: CookiesApp, input: InputStream): Result = withContext(Dispatchers.IO) {
        val sheets = Xlsx.read(input)
        fun find(key: String, idx: Int) = sheets.firstOrNull { norm(it.name).contains(key) } ?: sheets.getOrNull(idx)?.takeIf { s -> sheets.none { norm(it.name).contains(key) } && sheets.size == 3 }
        val pSheet = find("מוצר", 0)
        val rSheet = find("מתכונ", 1)
        val iSheet = find("מרכיב", 2)
        if (pSheet == null && rSheet == null && iSheet == null) error("לא מצאנו גיליונות מוצרים / מתכונים / מרכיבים")

        val db = app.db
        val pDao = db.products(); val rDao = db.recipes()
        val skipped = ArrayList<String>()
        var pAdd = 0; var pUpd = 0; var rAdd = 0; var rUpd = 0; var iCount = 0
        val newCodes = ArrayList<String>()

        val products = pDao.all().first().toMutableList()
        fun byName(n: String) = products.firstOrNull { norm(it.name) == norm(n) }
        fun byCode(c: String) = c.takeIf { it.isNotBlank() }?.let { code -> products.firstOrNull { p -> p.barcode != null && (p.barcode == code || priceKey(p.barcode) == priceKey(code)) } }

        // Products
        pSheet?.let { Table(it.rows) }?.let { t ->
            val cName = t.col("שם", "שםמוצר", "מוצר"); val cBrand = t.col("מותג"); val cCode = t.col("ברקוד")
            val cAmt = t.col("גודלאריזה", "גודל", "כמות"); val cUnit = t.col("יחידה"); val cPrice = t.col("מחיר"); val cCat = t.col("קטגוריה")
            t.body.forEachIndexed { n, row ->
                val name = row.at(cName)
                if (name.isBlank()) { skipped += "מוצרים, שורה ${n + 2}: חסר שם"; return@forEachIndexed }
                val code = row.at(cCode).filter(Char::isDigit)
                val existing = byCode(code) ?: byName(name)
                val u = unit(row.at(cUnit))
                val amt = number(row.at(cAmt))?.let { it * (u?.second ?: 1.0) }
                val price = number(row.at(cPrice))?.let { Math.round(it * 100) }
                val cat = row.at(cCat).takeIf { it.isNotBlank() }?.let { category(app, it, KIND_PRODUCTS) }
                val p = (existing ?: ProductEntity(name = name, packAmount = 0.0, unit = BaseUnit.Gram)).copy(
                    name = name,
                    brand = row.at(cBrand).ifBlank { existing?.brand.orEmpty() },
                    barcode = code.ifBlank { existing?.barcode.orEmpty() }.ifBlank { null },
                    packAmount = amt ?: existing?.packAmount ?: 0.0,
                    unit = u?.first ?: existing?.unit ?: BaseUnit.Gram,
                    priceAgorot = price ?: existing?.priceAgorot,
                    category = cat ?: existing?.category ?: Cats.list(KIND_PRODUCTS).firstOrNull()?.gid ?: "Pantry",
                    updatedAt = System.currentTimeMillis(),
                )
                val id = pDao.upsert(p)
                val saved = p.copy(id = if (p.id == 0L) id else p.id)
                if (existing == null) { pAdd++; products += saved } else { pUpd++; products[products.indexOf(existing)] = saved }
                if (code.isNotBlank() && code != existing?.barcode) newCodes += code
            }
        }

        // Ingredient lines grouped by recipe name (in sheet order)
        val lines = LinkedHashMap<String, MutableList<IngredientEntity>>()
        val names = HashMap<String, String>()
        iSheet?.let { Table(it.rows) }?.let { t ->
            val cRec = t.col("מתכון"); val cName = t.col("מרכיב", "שם"); val cAmt = t.col("כמות"); val cUnit = t.col("יחידה"); val cCode = t.col("ברקוד")
            t.body.forEachIndexed { n, row ->
                val rec = row.at(cRec); val name = row.at(cName)
                if (rec.isBlank() || name.isBlank()) { skipped += "מרכיבים, שורה ${n + 2}: חסר מתכון או מרכיב"; return@forEachIndexed }
                val p = byCode(row.at(cCode).filter(Char::isDigit)) ?: byName(name)
                val m = Measure.parse(row.at(cUnit)) ?: p?.unit?.let(Measure::of) ?: Measure.Gram
                var amount = number(row.at(cAmt)) ?: 0.0
                val link = p
                val base = m.baseFor(link)
                if (link != null && IngredientEntity(recipeId = 0, position = 0, name = name, amount = 1.0, unit = base, icon = "", measure = m.name).inProduct(link) == null)
                    skipped += "מרכיבים, שורה ${n + 2}: אין המרה מ${m.one} ל${com.maites.cookies.ui.unitLabel(link.unit)} עבור $name — בלי מחיר"
                if (amount < 0) amount = 0.0
                names.putIfAbsent(norm(rec), rec)
                lines.getOrPut(norm(rec)) { ArrayList() } += IngredientEntity(
                    recipeId = 0, position = 0, productId = link?.id, name = link?.name ?: name, amount = amount, unit = base, measure = m.name,
                    icon = link?.let { Cats.product(it.category).emoji } ?: "🥣",
                )
            }
        }

        // Recipes
        val recipes = rDao.all().toMutableList()
        val seen = HashSet<String>()
        suspend fun saveRecipe(r: RecipeEntity, existing: RecipeEntity?, key: String) {
            val items = lines[key] ?: existing?.let { rDao.ingredientsOnce(it.id) } ?: emptyList()
            rDao.save(r.copy(updatedAt = System.currentTimeMillis()), items)
            if (lines.containsKey(key)) iCount += items.size
            if (existing == null) rAdd++ else rUpd++
            seen += key
        }
        rSheet?.let { Table(it.rows) }?.let { t ->
            val cName = t.col("שם", "שםמתכון", "מתכון"); val cCat = t.col("קטגוריה"); val cYield = t.col("כמותיחידות", "תפוקה", "כמות")
            val cLabel = t.col("שםהיחידה"); val cSale = t.col("מחירמכירה", "מחיר"); val cNotes = t.col("הערות")
            t.body.forEachIndexed { n, row ->
                val name = row.at(cName)
                if (name.isBlank()) { skipped += "מתכונים, שורה ${n + 2}: חסר שם"; return@forEachIndexed }
                val key = norm(name)
                val existing = recipes.firstOrNull { norm(it.name) == key }
                val cat = row.at(cCat).takeIf { it.isNotBlank() }?.let { category(app, it, KIND_RECIPES) }
                val r = (existing ?: RecipeEntity(name = name)).copy(
                    name = name,
                    category = cat ?: existing?.category ?: Cats.list(KIND_RECIPES).firstOrNull()?.gid ?: "Cookies",
                    yieldCount = number(row.at(cYield))?.toInt() ?: existing?.yieldCount ?: 0,
                    yieldLabel = row.at(cLabel).ifBlank { existing?.yieldLabel.orEmpty() },
                    salePrice = number(row.at(cSale))?.let { Math.round(it * 100) } ?: existing?.salePrice ?: 0,
                    notes = row.at(cNotes).ifBlank { existing?.notes.orEmpty() },
                )
                saveRecipe(r, existing, key)
            }
        }
        // Recipes that only appear in מרכיבים
        lines.keys.filter { it !in seen }.forEach { key ->
            val existing = recipes.firstOrNull { norm(it.name) == key }
            val name = existing?.name ?: names[key].orEmpty()
            saveRecipe(existing ?: RecipeEntity(name = name, category = Cats.list(KIND_RECIPES).firstOrNull()?.gid ?: "Cookies"), existing, key)
        }

        if (newCodes.isNotEmpty()) app.applicationScope.launch {
            newCodes.distinct().forEach { app.priceSync.watchNow(it) }
            app.priceSync.sync(force = true)
        }
        Result(pAdd, pUpd, rAdd, rUpd, iCount, skipped)
    }

    /** Category by label; a new label becomes a new category. */
    private suspend fun category(app: CookiesApp, label: String, kind: String): String {
        Cats.list(kind).firstOrNull { norm(it.label) == norm(label) }?.let { return it.gid }
        val list = app.db.categories().ofKind(kind)
        list.firstOrNull { norm(it.label) == norm(label) }?.let { return it.gid }
        val c = CategoryEntity(
            gid = java.util.UUID.randomUUID().toString(), kind = kind, label = label.trim(),
            emoji = if (kind == KIND_PRODUCTS) "🫙" else "🥣", tint = list.size % 6, position = (list.maxOfOrNull { it.position } ?: -1) + 1,
        )
        app.db.categories().put(c)
        Cats.all = Cats.all + c
        return c.gid
    }
}
