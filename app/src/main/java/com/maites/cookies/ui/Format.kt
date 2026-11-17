package com.maites.cookies.ui

import com.maites.cookies.data.BaseUnit
import com.maites.cookies.data.IngredientEntity
import com.maites.cookies.data.Measure
import com.maites.cookies.data.ProductEntity
import com.maites.cookies.data.inProduct
import com.maites.cookies.data.m
import java.util.Locale

/** Money is always agorot (Long); shown as ₪ with 2 decimals. */
fun formatShekel(agorot: Long): String = "₪" + String.format(Locale.US, "%,.2f", agorot / 100.0)

private fun num(v: Double): String =
    if (v == Math.floor(v)) String.format(Locale.US, "%,d", v.toLong()) else String.format(Locale.US, "%,.2f", v).trimEnd('0').trimEnd('.')

fun formatPack(amount: Double, unit: BaseUnit): String = when (unit) {
    BaseUnit.Gram -> if (amount >= 1000) "${num(amount / 1000)} ק״ג" else "${num(amount)} גרם"
    BaseUnit.Milliliter -> if (amount >= 1000) "${num(amount / 1000)} ליטר" else "${num(amount)} מ״ל"
    BaseUnit.Unit -> "${num(amount)} יח׳"
}

fun unitLabel(unit: BaseUnit) = when (unit) {
    BaseUnit.Gram -> "גרם"; BaseUnit.Milliliter -> "מ״ל"; BaseUnit.Unit -> "יחידות"
}

/** "₪2.45 ל־100 גרם" / "₪0.80 ליחידה". */
fun formatUnitPrice(agorot: Long, amount: Double, unit: BaseUnit): String? {
    if (amount <= 0) return null
    return when (unit) {
        BaseUnit.Unit -> formatShekel(Math.round(agorot / amount)) + " ליחידה"
        else -> formatShekel(Math.round(agorot * 100 / amount)) + " ל־100 " + (if (unit == BaseUnit.Gram) "גרם" else "מ״ל")
    }
}

/** ½, ¼, ¾, ⅓, ⅔ for kitchen measures; plain numbers otherwise. */
private fun kitchenNum(v: Double): String {
    val whole = Math.floor(v + 1e-9).toLong()
    val frac = v - whole
    val f = listOf(0.25 to "¼", 0.5 to "½", 0.75 to "¾", 1.0 / 3 to "⅓", 2.0 / 3 to "⅔").firstOrNull { Math.abs(frac - it.first) < 0.01 }?.second
    return when {
        frac < 0.01 -> whole.toString()
        f != null -> (if (whole > 0) "$whole" else "") + f
        else -> num(v)
    }
}

/** "2 כוסות", "½ כפית", "250 גרם", "1.2 ק״ג", "קורט". */
fun formatMeasure(amount: Double, m: Measure): String = when {
    m.isPinch -> m.one
    m == Measure.Gram || m == Measure.Ml -> formatPack(amount, m.unit!!)
    m == Measure.Unit -> "${num(amount)} יח׳"
    m == Measure.Kg || m == Measure.Liter -> "${num(amount)} ${m.one}"
    else -> "${kitchenNum(amount)} ${m.label(amount)}"
}

/** The line as written, plus "≈ 130 גרם" when it is converted for the linked product. */
fun formatIngredient(i: IngredientEntity, p: ProductEntity?): String {
    val m = i.m
    val main = formatMeasure(i.amount, m)
    if (p == null || m.isPinch) return main
    val q = i.inProduct(p) ?: return main
    return if (m.unit == p.unit && m.factor == 1.0) main else "$main · ≈${formatPack(Math.round(q).toDouble().coerceAtLeast(1.0), p.unit)}"
}
