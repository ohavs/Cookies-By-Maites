package com.maites.cookies.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class OffProduct(val name: String, val brand: String, val packAmount: Double?, val unit: BaseUnit?, val imageUrl: String?)

/** Barcode lookup in Open Food Facts (free, no key). Null when not found or offline. */
object OpenFoodFacts {
    suspend fun lookup(code: String): OffProduct? = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL("https://world.openfoodfacts.org/api/v2/product/$code.json?fields=product_name,product_name_he,brands,quantity,image_front_small_url,image_front_url")
            val c = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000; readTimeout = 8000
                setRequestProperty("User-Agent", "CookiesByMaites/0.1 (Android)")
            }
            try {
                if (c.responseCode != 200) return@runCatching null
                val root = JSONObject(c.inputStream.bufferedReader().readText())
                if (root.optInt("status") != 1) return@runCatching null
                val p = root.getJSONObject("product")
                val name = p.optString("product_name_he").ifBlank { p.optString("product_name") }.trim()
                val (amt, unit) = parseQuantity(p.optString("quantity"))
                OffProduct(
                    name = name,
                    brand = p.optString("brands").split(',').first().trim(),
                    packAmount = amt, unit = unit,
                    imageUrl = p.optString("image_front_small_url").ifBlank { p.optString("image_front_url") }.ifBlank { null },
                )
            } finally { c.disconnect() }
        }.getOrNull()
    }

    /** "200 g", "1 ק\"ג", "1L", "250 מ\"ל", "12 יח'" → base units. */
    fun parseQuantity(q: String): Pair<Double?, BaseUnit?> {
        val m = Regex("""([\d.,]+)\s*([^\d\s.,]+)?""").find(q.trim()) ?: return null to null
        val n = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null to null
        val u = m.groupValues[2].lowercase().replace("\"", "").replace("״", "").replace("'", "")
        return when (u) {
            "g", "gr", "גר", "גרם", "ג" -> n to BaseUnit.Gram
            "kg", "קג", "קילו" -> n * 1000 to BaseUnit.Gram
            "ml", "מל" -> n to BaseUnit.Milliliter
            "cl" -> n * 10 to BaseUnit.Milliliter
            "l", "ליטר", "ל" -> n * 1000 to BaseUnit.Milliliter
            "", "יח", "יחידות", "pcs", "x" -> n to BaseUnit.Unit
            else -> null to null
        }
    }
}
