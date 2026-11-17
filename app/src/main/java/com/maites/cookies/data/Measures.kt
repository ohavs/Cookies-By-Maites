package com.maites.cookies.data

/**
 * Kitchen measures an ingredient can be written in. [amount] on an ingredient is a count of its measure;
 * [unit] + [factor] turn it into base units. Packs follow the linked product's pack size; a pinch costs nothing.
 */
enum class Measure(val one: String, val many: String, val unit: BaseUnit?, val factor: Double) {
    Gram("גרם", "גרם", BaseUnit.Gram, 1.0),
    Kg("ק״ג", "ק״ג", BaseUnit.Gram, 1000.0),
    Ml("מ״ל", "מ״ל", BaseUnit.Milliliter, 1.0),
    Liter("ליטר", "ליטר", BaseUnit.Milliliter, 1000.0),
    Cup("כוס", "כוסות", BaseUnit.Milliliter, 240.0),
    Tbsp("כף", "כפות", BaseUnit.Milliliter, 15.0),
    Tsp("כפית", "כפיות", BaseUnit.Milliliter, 5.0),
    Unit("יחידה", "יחידות", BaseUnit.Unit, 1.0),
    Pack("חבילה", "חבילות", null, 1.0),
    Bag("שקית", "שקיות", null, 1.0),
    Box("קופסה", "קופסאות", null, 1.0),
    Pinch("קורט", "קורט", null, 0.0),
    Smidge("קמצוץ", "קמצוץ", null, 0.0);

    val isPack get() = unit == null && factor > 0
    val isPinch get() = factor == 0.0
    /** Needs grams-per-cup to meet a product in the other unit. */
    val isVolume get() = unit == BaseUnit.Milliliter

    fun label(amount: Double) = if (amount > 1.0 + 1e-9) many else one

    companion object {
        fun of(u: BaseUnit) = when (u) { BaseUnit.Gram -> Gram; BaseUnit.Milliliter -> Ml; BaseUnit.Unit -> Unit }

        /** Free text (Excel, typing) → measure. Null when unknown. */
        fun parse(s: String): Measure? = when (s.lowercase().filter { it.isLetter() }) {
            "גרם", "גר", "ג", "g", "gr", "gram", "grams" -> Gram
            "קג", "קילו", "קילוגרם", "kg" -> Kg
            "מל", "מיליליטר", "ml" -> Ml
            "ליטר", "ל", "l", "liter", "litre" -> Liter
            "כוס", "כוסות", "cup", "cups" -> Cup
            "כף", "כפות", "tbsp" -> Tbsp
            "כפית", "כפיות", "tsp" -> Tsp
            "יחידה", "יחידות", "יח", "unit", "units", "pcs" -> Unit
            "חבילה", "חבילות", "pack" -> Pack
            "שקית", "שקיות", "bag" -> Bag
            "קופסה", "קופסא", "קופסאות", "box", "can" -> Box
            "קורט", "pinch" -> Pinch
            "קמצוץ" -> Smidge
            else -> null
        }
    }
}

val IngredientEntity.m: Measure get() = runCatching { Measure.valueOf(measure) }.getOrNull() ?: Measure.of(unit)

/** Base unit an ingredient written in [m] is stored under (packs/pinches fall back to the product's unit). */
fun Measure.baseFor(p: ProductEntity?): BaseUnit = unit ?: p?.unit ?: BaseUnit.Unit

object Density {
    /** Typical grams in one 240 ml cup, by name. Specific names first. */
    private val table = listOf(
        "אבקת סוכר" to 120.0, "סוכר חום" to 220.0, "חמאת בוטנים" to 260.0, "אבקת אפייה" to 230.0, "אבקת אפיה" to 230.0,
        "סוכר" to 200.0, "קמח" to 140.0, "קורנפלור" to 130.0, "עמילן" to 130.0, "קקאו" to 100.0,
        "חמאה" to 225.0, "מרגרינה" to 225.0, "שמן" to 220.0, "חלב" to 245.0, "שמנת" to 240.0, "יוגורט" to 245.0,
        "גבינ" to 240.0, "דבש" to 340.0, "סילאן" to 330.0, "סירופ" to 320.0, "מייפל" to 320.0, "ריבה" to 320.0,
        "שוקולד" to 170.0, "שיבולת" to 90.0, "קוקוס" to 80.0, "אגוז" to 120.0, "פקאן" to 110.0, "שקד" to 100.0,
        "אורז" to 200.0, "מלח" to 290.0, "סודה" to 230.0, "שמרים" to 150.0, "פירורי" to 110.0, "פרורי" to 110.0,
        "פרג" to 140.0, "שומשום" to 140.0, "צימוק" to 150.0, "וניל" to 210.0, "מים" to 240.0,
    )

    fun guess(name: String): Double? = table.firstOrNull { name.contains(it.first) }?.second

    /** Grams per cup for [p]: what the user set, else a guess from the name, else water. */
    fun cup(p: ProductEntity): Double = p.gramsPerCup ?: guess(p.name) ?: 240.0
    fun isGuess(p: ProductEntity) = p.gramsPerCup == null
}

/** How much of [p] (in [p]'s unit) this line uses. Null when it can't be converted. */
fun IngredientEntity.inProduct(p: ProductEntity?): Double? {
    val m = m
    if (m.isPinch) return 0.0
    if (p == null) return null
    if (m.isPack) return if (p.packAmount > 0) amount * p.packAmount else null
    val base = amount * m.factor
    val g = Density.cup(p) / 240.0
    return when {
        m.unit == p.unit -> base
        m.unit == BaseUnit.Milliliter && p.unit == BaseUnit.Gram -> base * g
        m.unit == BaseUnit.Gram && p.unit == BaseUnit.Milliliter -> base / g
        else -> null
    }
}

/** For a line without a product: amount in base units, or null for packs/pinches. */
fun IngredientEntity.ownBase(): Pair<Double, BaseUnit>? = m.unit?.takeIf { !m.isPinch }?.let { amount * m.factor to it }
