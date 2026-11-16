package com.maites.cookies.ui

import com.maites.cookies.data.BaseUnit
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
