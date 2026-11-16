package com.maites.cookies.data

import android.content.Context
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * Chains whose price sites block non-Israeli IPs (so the daily Action can't reach them).
 * The phone is in Israel, so it reads them directly — only stores in the chosen city.
 */
class LocalChains(private val ctx: Context) {
    /** [prefix] keeps store ids of a chain's second company id apart. */
    data class FileRef(val name: String, val url: String, val prefix: String = "") {
        val store get() = prefix + storeOf(name)
    }
    data class Result(val stores: List<ChainStore>, val prices: List<ChainPrice>)

    private val cities: Map<String, String> by lazy {
        runCatching {
            val o = JSONObject(ctx.assets.open("cities.json").bufferedReader().readText())
            o.keys().asSequence().associateWith { o.getString(it) }
        }.getOrDefault(emptyMap())
    }

    /** Files listing for a chain: (stores file, latest PriceFull per store id). */
    private fun listing(chain: Chain): List<FileRef> = when (chain) {
        Chain.Victory -> VICTORY_IDS.flatMapIndexed { idx, edi ->
            val arr = JSONArray(get("$LAIB/webapi/api/getfiles?edi=$edi").decodeToString())
            (0 until arr.length()).mapNotNull { i ->
                val n = arr.getJSONObject(i).optString("fileName").ifBlank { null } ?: return@mapNotNull null
                FileRef(n, "$LAIB/webapi/$edi/$n", if (idx == 0) "" else "${idx + 1}-")
            }
        }
        Chain.Carrefour -> {
            val html = get(CARREFOUR).decodeToString()
            val path = Regex("""const path\s*=\s*['"]([^'"]*)['"]""").find(html)?.groupValues?.get(1).orEmpty().trim('.', '/')
            val json = Regex("""const files\s*=\s*(\[.*?\])\s*;""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)
                ?: error("carrefour: no file list")
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val n = arr.optJSONObject(i)?.optString("name")?.ifBlank { null } ?: return@mapNotNull null
                FileRef(n, if (path.isBlank()) "$CARREFOUR$n" else "$CARREFOUR$path/$n")
            }
        }
        else -> emptyList()
    }

    /**
     * Reads stores (always) and prices for [codes] in stores of [city] (when set).
     * [canon] maps a normalized city name to the spelling already used by other chains.
     */
    fun fetch(chain: Chain, codes: Set<String>, city: String, canon: Map<String, String>): Result {
        val files = listing(chain)
        val stores = files.filter { it.name.startsWith("store", true) }.groupBy { it.prefix }
            .values.flatMap { g -> runCatching { parseStores(chain, g.maxBy { stamp(it.name) }, canon) }.getOrDefault(emptyList()) }
        if (stores.isEmpty()) error("${chain.key}: no stores")
        if (city.isBlank() || codes.isEmpty()) return Result(stores, emptyList())

        val wanted = stores.filter { it.city == city }.map { it.storeId }.toSet()
        val latest = files.filter { it.name.startsWith("pricefull", true) }
            .groupBy { it.store }
            .filterKeys { it in wanted }
            .mapValues { (_, v) -> v.maxBy { stamp(it.name) } }
            .values.take(MAX_STORES)
        val prices = latest.flatMap { f -> runCatching { parsePrices(chain, f, codes) }.getOrDefault(emptyList()) }
        return Result(stores, prices)
    }

    // ---- parsing ----

    private fun parseStores(chain: Chain, f: FileRef, canon: Map<String, String>): List<ChainStore> {
        val out = HashMap<String, ChainStore>()
        walk(f, setOf("store", "branch")) { m ->
            val id = f.prefix + (m["storeid"]?.toIntOrNull()?.toString() ?: return@walk)
            out[id] = ChainStore(chain.key, id, m["storename"].orEmpty(), cityName(m["city"], canon), m["address"].orEmpty())
        }
        return out.values.toList()
    }

    private fun parsePrices(chain: Chain, f: FileRef, codes: Set<String>): List<ChainPrice> {
        val store = f.store
        val out = ArrayList<ChainPrice>()
        walk(f, setOf("item", "product")) { m ->
            val code = priceKey(m["itemcode"]) ?: return@walk
            if (code !in codes) return@walk
            val p = m["itemprice"]?.toBigDecimalOrNull() ?: return@walk
            out += ChainPrice(code, chain.key, store, p.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).toLong())
        }
        return out
    }

    /** Streams the XML; for every closing [records] element, hands its leaf fields (lower-cased tags). */
    private fun walk(f: FileRef, records: Set<String>, each: (Map<String, String>) -> Unit) {
        open(f.url).use { input ->
            val p = Xml.newPullParser()
            p.setInput(input, null)
            val m = HashMap<String, String>()
            var text = ""
            while (true) {
                when (p.next()) {
                    XmlPullParser.START_TAG -> text = ""
                    XmlPullParser.TEXT -> text += p.text
                    XmlPullParser.END_TAG -> {
                        val tag = p.name.lowercase()
                        if (tag in records) { each(m); m.clear() } else if (text.isNotBlank()) m[tag] = text.trim()
                        text = ""
                    }
                    XmlPullParser.END_DOCUMENT -> return
                }
            }
        }
    }

    private fun cityName(raw: String?, canon: Map<String, String>): String {
        val r = raw.orEmpty().trim().replace(Regex("\\s+"), " ")
        val name = if (r.isNotEmpty() && r.all(Char::isDigit)) cities[r.toInt().toString()].orEmpty() else r
        return canon[norm(name)] ?: name
    }

    // ---- http ----

    private fun connect(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15000; readTimeout = 60000
        setRequestProperty("User-Agent", UA)
        if (responseCode !in 200..299) { disconnect(); error("HTTP $responseCode $url") }
    }

    private fun get(url: String): ByteArray = connect(url).let { c -> try { c.inputStream.readBytes() } finally { c.disconnect() } }

    /** Opens a (maybe gzip / zip) file as a plain XML stream. */
    private fun open(url: String): InputStream {
        val raw = BufferedInputStream(connect(url).inputStream, 64 * 1024)
        raw.mark(4)
        val b0 = raw.read(); val b1 = raw.read()
        raw.reset()
        return when {
            b0 == 0x1f && b1 == 0x8b -> BufferedInputStream(GZIPInputStream(raw, 64 * 1024))
            b0 == 'P'.code && b1 == 'K'.code -> ZipInputStream(raw).also { it.nextEntry }
            else -> raw
        }
    }

    companion object {
        const val LAIB = "https://laibcatalog.co.il"
        const val CARREFOUR = "https://prices.carrefour.co.il/"
        val VICTORY_IDS = listOf("7290696200003", "7290058103393")
        val LOCAL = setOf(Chain.Victory, Chain.Carrefour)
        const val MAX_STORES = 12
        private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

        /** "PriceFull7290696200003-001-202611160300.xml.gz" → "1". */
        fun storeOf(name: String) = name.split('-').getOrNull(1)?.toIntOrNull()?.toString().orEmpty()
        fun stamp(name: String) = name.substringBefore('.').split('-').lastOrNull().orEmpty()
        fun norm(s: String) = s.filter { it.isLetterOrDigit() }
    }
}
