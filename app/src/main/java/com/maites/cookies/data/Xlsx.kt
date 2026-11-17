package com.maites.cookies.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Minimal .xlsx reader/writer (no Apache POI): sheets of string cells. */
object Xlsx {
    class Sheet(val name: String, val rows: List<List<String>>)

    // ---- read ----

    fun read(input: InputStream): List<Sheet> {
        val parts = HashMap<String, ByteArray>()
        ZipInputStream(input).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (!e.isDirectory) parts[e.name.removePrefix("/")] = z.readBytes()
            }
        }
        val shared = parts["xl/sharedStrings.xml"]?.let(::sharedStrings) ?: emptyList()
        val rels = parts["xl/_rels/workbook.xml.rels"]?.let(::relations) ?: emptyMap()
        val wb = parts["xl/workbook.xml"] ?: error("not an xlsx")
        return sheetRefs(wb).mapNotNull { (name, rid) ->
            val target = rels[rid] ?: return@mapNotNull null
            val path = if (target.startsWith("/")) target.removePrefix("/") else "xl/" + target.removePrefix("./")
            parts[path]?.let { Sheet(name, cells(it, shared)) }
        }
    }

    private fun parser(bytes: ByteArray): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        setInput(ByteArrayInputStream(bytes), null)
    }

    private fun sharedStrings(bytes: ByteArray): List<String> {
        val out = ArrayList<String>()
        val p = parser(bytes)
        val sb = StringBuilder()
        var inSi = false; var inT = false; var inRph = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "si" -> { inSi = true; sb.clear() }
                    "t" -> inT = true
                    "rPh" -> inRph = true
                }
                XmlPullParser.TEXT -> if (inSi && inT && !inRph) sb.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "si" -> { inSi = false; out.add(sb.toString()) }
                    "t" -> inT = false
                    "rPh" -> inRph = false
                }
            }
        }
        return out
    }

    private fun relations(bytes: ByteArray): Map<String, String> {
        val out = HashMap<String, String>()
        val p = parser(bytes)
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.name == "Relationship") {
                out[p.getAttributeValue(null, "Id")] = p.getAttributeValue(null, "Target")
            }
        }
        return out
    }

    private fun sheetRefs(bytes: ByteArray): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val p = parser(bytes)
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.name == "sheet") {
                out += p.getAttributeValue(null, "name").orEmpty() to p.getAttributeValue(null, "r:id").orEmpty()
            }
        }
        return out
    }

    private fun colIndex(ref: String): Int {
        var n = 0
        for (ch in ref) { if (ch !in 'A'..'Z') break; n = n * 26 + (ch - 'A' + 1) }
        return n - 1
    }

    private fun cells(bytes: ByteArray, shared: List<String>): List<List<String>> {
        val rows = ArrayList<List<String>>()
        val p = parser(bytes)
        var row: ArrayList<String>? = null
        var col = 0; var type = ""; val v = StringBuilder(); var inV = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> {
                        val r = p.getAttributeValue(null, "r")?.toIntOrNull()
                        if (r != null) while (rows.size < r - 1) rows.add(emptyList())
                        row = ArrayList()
                    }
                    "c" -> {
                        col = p.getAttributeValue(null, "r")?.let(::colIndex)?.takeIf { it >= 0 } ?: (row?.size ?: 0)
                        type = p.getAttributeValue(null, "t").orEmpty(); v.clear()
                    }
                    "v", "t" -> inV = true
                }
                XmlPullParser.TEXT -> if (inV) v.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "v", "t" -> inV = false
                    "c" -> row?.let { r ->
                        val raw = v.toString()
                        val text = when (type) {
                            "s" -> raw.trim().toIntOrNull()?.let(shared::getOrNull).orEmpty()
                            "b" -> if (raw == "1") "TRUE" else "FALSE"
                            "inlineStr", "str", "e" -> raw
                            else -> number(raw)
                        }
                        while (r.size < col) r.add("")
                        if (r.size == col) r.add(text) else r[col] = text
                    }
                    "row" -> { row?.let(rows::add); row = null }
                }
            }
        }
        return rows
    }

    /** "12.0" → "12", "0.30000000000000004" → "0.3". */
    private fun number(raw: String): String {
        val d = raw.trim().toDoubleOrNull() ?: return raw
        val r = Math.round(d * 1e6) / 1e6
        return if (r == Math.floor(r) && kotlin.math.abs(r) < 1e15) r.toLong().toString() else r.toString()
    }

    // ---- write ----

    /** Cells: String → text, Number → number. Sheets are right-to-left, first row bold. */
    fun write(out: OutputStream, sheets: List<Pair<String, List<List<Any?>>>>) {
        ZipOutputStream(out).use { z ->
            fun put(name: String, body: String) { z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray(Charsets.UTF_8)); z.closeEntry() }
            val head = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""
            put("[Content_Types].xml", head + """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
                """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>""" +
                """<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""" +
                """<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""" +
                sheets.indices.joinToString("") { """<Override PartName="/xl/worksheets/sheet${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""" } +
                "</Types>")
            put("_rels/.rels", head + """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            put("xl/workbook.xml", head + """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""" +
                sheets.withIndex().joinToString("") { (i, s) -> """<sheet name="${esc(s.first)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""" } + "</sheets></workbook>")
            put("xl/_rels/workbook.xml.rels", head + """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                sheets.indices.joinToString("") { """<Relationship Id="rId${it + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${it + 1}.xml"/>""" } +
                """<Relationship Id="rId${sheets.size + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
            put("xl/styles.xml", head + """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""" +
                """<fonts count="2"><font><sz val="11"/><name val="Arial"/></font><font><b/><sz val="11"/><name val="Arial"/></font></fonts>""" +
                """<fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFFFE6D6"/></patternFill></fill></fills>""" +
                """<borders count="1"><border/></borders><cellStyleXfs count="1"><xf/></cellStyleXfs>""" +
                """<cellXfs count="2"><xf/><xf fontId="1" fillId="2" applyFont="1" applyFill="1"/></cellXfs></styleSheet>""")
            sheets.forEachIndexed { i, (_, rows) ->
                val widths = (0 until (rows.maxOfOrNull { it.size } ?: 0)).map { c ->
                    (rows.maxOfOrNull { r -> r.getOrNull(c)?.toString()?.length ?: 0 } ?: 0).coerceIn(8, 40) + 2
                }
                val sb = StringBuilder(head)
                sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetViews><sheetView workbookViewId="0" rightToLeft="1"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
                if (widths.isNotEmpty()) sb.append("<cols>").append(widths.withIndex().joinToString("") { (c, w) -> """<col min="${c + 1}" max="${c + 1}" width="$w" customWidth="1"/>""" }).append("</cols>")
                sb.append("<sheetData>")
                rows.forEachIndexed { r, row ->
                    sb.append("""<row r="${r + 1}">""")
                    row.forEachIndexed { c, v ->
                        val ref = colName(c) + (r + 1)
                        val s = if (r == 0) """ s="1"""" else ""
                        when (v) {
                            null -> Unit
                            is Number -> sb.append("""<c r="$ref"$s><v>$v</v></c>""")
                            else -> v.toString().takeIf { it.isNotEmpty() }?.let { sb.append("""<c r="$ref"$s t="inlineStr"><is><t xml:space="preserve">${esc(it)}</t></is></c>""") }
                        }
                    }
                    sb.append("</row>")
                }
                sb.append("</sheetData></worksheet>")
                put("xl/worksheets/sheet${i + 1}.xml", sb.toString())
            }
        }
    }

    private fun colName(i: Int): String { var n = i + 1; val sb = StringBuilder(); while (n > 0) { val m = (n - 1) % 26; sb.insert(0, 'A' + m); n = (n - 1) / 26 }; return sb.toString() }

    private fun esc(s: String) = buildString {
        for (ch in s) when {
            ch == '&' -> append("&amp;"); ch == '<' -> append("&lt;"); ch == '>' -> append("&gt;"); ch == '"' -> append("&quot;")
            ch < ' ' && ch != '\n' && ch != '\t' -> Unit
            else -> append(ch)
        }
    }
}
