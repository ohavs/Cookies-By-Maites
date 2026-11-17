package com.maites.cookies.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import com.maites.cookies.CookiesApp
import com.maites.cookies.R
import com.maites.cookies.data.Cats
import com.maites.cookies.data.Costing
import com.maites.cookies.data.ExtraKind
import com.maites.cookies.data.IngredientEntity
import com.maites.cookies.data.ProductEntity
import com.maites.cookies.data.RecipeEntity
import com.maites.cookies.data.ingredientCost
import com.maites.cookies.data.priceKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** PDF export: one recipe, a costing sheet, or a cookbook. Built on the phone with [PdfDocument]. */
object PdfExport {
    private const val INK = 0xFF17140F.toInt()
    private const val DIM = 0xFF625D55.toInt()
    private const val FAINT = 0xFFA19B92.toInt()
    private const val LINE = 0xFFE3E1DA.toInt()
    private const val SOFT = 0xFFF6F5F1.toInt()
    private const val ACCENT = 0xFFB5560A.toInt()
    private const val GOOD = 0xFF1F9D57.toInt()
    private const val BAD = 0xFFD92D20.toInt()

    class Data(val products: Map<Long, ProductEntity>, val prices: Map<Long, Long>)

    /** Products + pack prices (manual, else cheapest in the chosen city) — same rule as the app. */
    suspend fun load(app: CookiesApp): Data {
        val products = app.db.products().all().first()
        val city = app.prefs.city.first()
        val cheap = app.db.prices().cheapestInCity(city).first().associate { it.barcode to it.agorot }
        return Data(products.associateBy { it.id }, products.mapNotNull { p -> (p.priceAgorot ?: priceKey(p.barcode)?.let(cheap::get))?.let { p.id to it } }.toMap())
    }

    suspend fun recipe(app: CookiesApp, id: Long, mult: Double, prices: Boolean): File = withContext(Dispatchers.IO) {
        val r = app.db.recipes().byId(id).first() ?: error("המתכון לא נמצא")
        val items = app.db.recipes().ingredientsOnce(id)
        val d = load(app)
        val f = Flow(app, measure = false)
        f.newPage()
        drawRecipe(f, r, items, d, mult, prices = prices, photo = true, notes = true)
        f.save(file(app, r.name))
    }

    suspend fun costing(app: CookiesApp, id: Long): File = withContext(Dispatchers.IO) {
        val r = app.db.recipes().byId(id).first() ?: error("המתכון לא נמצא")
        val items = app.db.recipes().ingredientsOnce(id)
        val d = load(app)
        val f = Flow(app, measure = false)
        f.newPage()
        f.text("תמחיר", 11f, ACCENT, bold = true, after = 2f)
        drawRecipe(f, r, items, d, 1.0, prices = true, photo = false, notes = false)
        f.save(file(app, "תמחיר - " + r.name))
    }

    suspend fun cookbook(app: CookiesApp, ids: List<Long>, prices: Boolean): File = withContext(Dispatchers.IO) {
        val all = app.db.recipes().all().associateBy { it.id }
        val order = Cats.list(com.maites.cookies.data.KIND_RECIPES).map { it.gid }
        val recipes = ids.mapNotNull(all::get).sortedWith(compareBy({ order.indexOf(Cats.recipe(it.category).gid) }, { it.name }))
        val items = recipes.associate { it.id to app.db.recipes().ingredientsOnce(it.id) }
        val d = load(app)
        fun render(f: Flow, pages: Map<Long, Int>): Map<Long, Int> {
            val starts = HashMap<Long, Int>()
            // Cover
            f.newPage()
            recipes.firstOrNull { it.photoList.isNotEmpty() }?.let { f.image(it.photoList.first(), 380f) }
            f.gap(28f)
            f.text("ספר המתכונים", 40f, INK, bold = true, after = 6f)
            f.text("Cookies by Maites", 16f, ACCENT, bold = true, after = 14f)
            f.text("${recipes.size} מתכונים · ${SimpleDateFormat("d.M.yyyy", Locale.US).format(Date())}", 12f, DIM)
            // Contents
            f.newPage()
            f.text("תוכן העניינים", 24f, INK, bold = true, after = 14f)
            var cat: String? = null
            recipes.forEach { r ->
                val c = Cats.recipe(r.category)
                if (c.gid != cat) { cat = c.gid; f.gap(8f); f.text(c.label, 11f, ACCENT, bold = true, after = 4f) }
                f.row(r.name, null, pages[r.id]?.toString() ?: "00", 12f, INK)
            }
            // Recipes
            recipes.forEach { r ->
                f.newPage()
                starts[r.id] = f.pageNo
                drawRecipe(f, r, items[r.id].orEmpty(), d, 1.0, prices = prices, photo = true, notes = true)
            }
            return starts
        }
        val pages = render(Flow(app, measure = true), emptyMap())
        val f = Flow(app, measure = false)
        render(f, pages)
        f.save(file(app, "ספר המתכונים"))
    }

    private fun drawRecipe(f: Flow, r: RecipeEntity, items: List<IngredientEntity>, d: Data, mult: Double, prices: Boolean, photo: Boolean, notes: Boolean) {
        if (photo) r.photoList.firstOrNull()?.let { f.image(it, 250f); f.gap(18f) }
        f.text(Cats.recipe(r.category).label, 11f, ACCENT, bold = true, after = 2f)
        f.text(r.name, 26f, INK, bold = true, after = 4f)
        val sub = buildList {
            if (r.yieldCount > 0) add("${num(r.yieldCount * mult)} ${r.yieldLabel.ifBlank { "יחידות" }}")
            if (kotlin.math.abs(mult - 1.0) > 1e-9) add("מכפיל ×${num(mult)}")
        }
        if (sub.isNotEmpty()) f.text(sub.joinToString(" · "), 12f, DIM)

        val costs = items.map { i -> i.productId?.let { ingredientCost(i.copy(amount = i.amount * mult), d.products[it], d.prices[it]) } }
        if (items.isNotEmpty()) {
            f.section("מרכיבים")
            items.forEachIndexed { idx, i ->
                if (idx > 0) f.divider()
                val p = i.productId?.let(d.products::get)
                f.row(p?.name ?: i.name, formatPack(i.amount * mult, i.unit), if (prices) costs[idx]?.let(::formatShekel) ?: "—" else null, 12f, INK)
            }
        }

        if (prices && items.isNotEmpty()) {
            val sum = costs.sumOf { it ?: 0L }
            val missing = costs.count { it == null }
            val cost = Costing.of(sum, r.extraList, r.yieldCount, mult)
            f.section("תמחיר")
            f.row("מרכיבים", null, formatShekel(cost.ingredients), 12f, INK)
            cost.extras.forEach { (e, v) ->
                val how = when (e.kind) {
                    ExtraKind.Batch -> formatShekel(e.value) + " לתבנית"
                    ExtraKind.PerUnit -> formatShekel(e.value) + " ליחידה"
                    ExtraKind.Percent -> num(e.value / 100.0) + "% מהמרכיבים"
                }
                f.row(e.name, how, formatShekel(v), 12f, INK)
            }
            if (r.yieldCount > 0) { f.divider(); f.row("סה״כ לתבנית", null, formatShekel(cost.total), 12f, INK) }
            f.divider()
            f.row(if (r.yieldCount > 0) "עלות ליחידה" else "עלות לתבנית", null, formatShekel(cost.perUnit), 14f, INK, bold = true)
            if (r.salePrice > 0) {
                val profit = cost.profit(r.salePrice)
                f.row("מחיר מכירה", null, formatShekel(r.salePrice), 12f, INK)
                f.row(if (profit >= 0) "רווח" else "הפסד", formatPercent(cost.margin(r.salePrice)), formatShekel(kotlin.math.abs(profit)), 12f, if (profit >= 0) GOOD else BAD, bold = true)
            }
            if (r.targetMargin > 0) cost.priceFor(r.targetMargin)?.let { f.row("מחיר מומלץ למרווח ${r.targetMargin}%", null, formatShekel(it), 12f, DIM) }
            if (missing > 0) { f.gap(4f); f.text("בלי $missing מרכיבים שאין להם מחיר", 10f, BAD) }
        }

        if (notes && r.notes.isNotBlank()) {
            f.section("הערות")
            f.text(r.notes, 12f, INK)
        }
    }

    private fun num(v: Double): String =
        if (v == Math.floor(v)) v.toLong().toString() else String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

    private fun file(ctx: Context, name: String): File {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        return File(dir, name.replace(Regex("[\\\\/:*?\"<>|\\n]"), " ").trim().ifBlank { "export" } + ".pdf")
    }

    /** Opens the share sheet (Drive, WhatsApp, print, save to Files…). */
    fun share(ctx: Context, f: File, mime: String = "application/pdf") {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_TITLE, f.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, f.nameWithoutExtension).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Top-to-bottom, right-to-left layout on A4 pages. [measure] = count pages only (no drawing). */
    private class Flow(ctx: Context, val measure: Boolean) {
        val W = 595; val H = 842; val M = 44f
        val cw = W - 2 * M
        private val doc = if (measure) null else PdfDocument()
        private var page: PdfDocument.Page? = null
        private val canvas: Canvas? get() = page?.canvas
        var pageNo = 0; private set
        private var y = M
        private val base: Typeface = runCatching { ResourcesCompat.getFont(ctx, R.font.rubik) }.getOrNull() ?: Typeface.DEFAULT
        private val bold: Typeface = if (Build.VERSION.SDK_INT >= 28) Typeface.create(base, 600, false) else Typeface.create(base, Typeface.BOLD)
        private val bottom get() = H - M - 18f

        fun newPage() {
            closePage()
            pageNo++
            y = M
            if (doc != null) page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
        }

        private fun closePage() {
            val p = page ?: return
            val c = p.canvas
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = base; textSize = 9f; color = FAINT; textAlign = Paint.Align.CENTER }
            c.drawText("Cookies by Maites · $pageNo", W / 2f, H - M / 2f, paint)
            doc!!.finishPage(p)
            page = null
        }

        fun save(out: File): File {
            closePage()
            out.outputStream().use { doc!!.writeTo(it) }
            doc!!.close()
            return out
        }

        fun gap(h: Float) { y += h }

        private fun ensure(h: Float) { if (y + h > bottom) newPage() }

        private fun layout(s: String, size: Float, color: Int, b: Boolean, width: Float, opposite: Boolean = false): StaticLayout {
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = if (b) bold else base; textSize = size; this.color = color }
            return StaticLayout.Builder.obtain(s, 0, s.length, tp, width.toInt().coerceAtLeast(1))
                .setAlignment(if (opposite) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL)
                .setTextDirection(TextDirectionHeuristics.RTL)
                .setLineSpacing(0f, 1.15f).setIncludePad(false).build()
        }

        /** A paragraph; long text continues on the next page line by line. */
        fun text(s: String, size: Float, color: Int, bold: Boolean = false, after: Float = 6f) {
            val l = layout(s, size, color, bold, cw)
            for (i in 0 until l.lineCount) {
                val top = l.getLineTop(i).toFloat(); val bot = l.getLineBottom(i).toFloat()
                ensure(bot - top)
                canvas?.let { c ->
                    c.save(); c.translate(M, y - top); c.clipRect(0f, top, cw, bot); l.draw(c); c.restore()
                }
                y += bot - top
            }
            y += after
        }

        fun section(title: String) {
            y += 18f
            ensure(60f)
            text(title, 15f, INK, bold = true, after = 8f)
        }

        fun divider() {
            canvas?.drawRect(M, y, M + cw, y + 0.8f, Paint().apply { color = LINE })
            y += 0.8f
        }

        /** Name on the right, [mid] in the middle, [end] on the left. */
        fun row(start: String, mid: String?, end: String?, size: Float, color: Int, bold: Boolean = false) {
            val endW = if (end != null) cw * 0.2f else 0f
            val midW = if (mid != null) cw * 0.26f else 0f
            val startW = cw - endW - midW - (if (end != null) 8f else 0f) - (if (mid != null) 8f else 0f)
            val a = layout(start, size, color, bold, startW)
            val b = mid?.let { layout(it, size * 0.92f, DIM, false, midW) }
            val e = end?.let { layout(it, size, color, bold, endW, opposite = true) }
            val h = maxOf(a.height, b?.height ?: 0, e?.height ?: 0) + 14f
            ensure(h)
            canvas?.let { c ->
                fun at(l: StaticLayout, x: Float) { c.save(); c.translate(x, y + 7f); l.draw(c); c.restore() }
                at(a, M + cw - startW)
                b?.let { at(it, M + endW + (if (end != null) 8f else 0f)) }
                e?.let { at(it, M) }
            }
            y += h
        }

        /** Full-width photo, centre-cropped, rounded. */
        fun image(path: String, height: Float) {
            ensure(height)
            val c = canvas
            if (c != null) {
                val bmp = decode(path, (cw * 2).toInt())
                val dst = RectF(M, y, M + cw, y + height)
                val clip = Path().apply { addRoundRect(dst, 22f, 22f, Path.Direction.CW) }
                c.save(); c.clipPath(clip)
                if (bmp != null) {
                    val k = maxOf(dst.width() / bmp.width, dst.height() / bmp.height)
                    val w = dst.width() / k; val h = dst.height() / k
                    val src = android.graphics.Rect(((bmp.width - w) / 2).toInt(), ((bmp.height - h) / 2).toInt(), ((bmp.width + w) / 2).toInt(), ((bmp.height + h) / 2).toInt())
                    c.drawBitmap(bmp, src, dst, Paint(Paint.FILTER_BITMAP_FLAG))
                } else c.drawRect(dst, Paint().apply { color = SOFT })
                c.restore()
            }
            y += height
        }

        private fun decode(path: String, max: Int): Bitmap? = runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, o)
            var s = 1
            while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= max) s *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
        }.getOrNull()
    }
}
