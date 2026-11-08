package com.lsa.app.indexing.docs

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream

/** One slide, page, section or sheet of an Office document. */
data class OfficeUnit(
    val label: String?,
    /** Slide / page number when the format has one. */
    val page: Int?,
    val text: String,
    /** Sheets keep their cells so rows can be grouped with the header repeated. */
    val rows: List<List<String>>? = null,
    /** True when [text] uses `# ` headings and should go through the markdown chunker. */
    val markdown: Boolean = false,
)

data class OfficeDoc(val title: String?, val units: List<OfficeUnit>, val style: TextCard.Style)

/**
 * Text extraction for OOXML (docx, pptx, xlsx) and OpenDocument (odt, odp, ods) files using only the zip + XML
 * parser of the platform. Binary formats (doc, ppt, xls) are not supported.
 */
object OfficeParsers {
    const val MAX_UNITS = 400
    private const val NS_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    fun parse(ext: String, z: ZipSource): OfficeDoc? = when (ext.lowercase()) {
        "docx" -> docx(z)
        "pptx" -> pptx(z)
        "xlsx" -> xlsx(z)
        "odt", "odp", "ods" -> odf(ext.lowercase(), z)
        else -> null
    }

    // ---------------------------------------------------------------- helpers

    private fun parser(bytes: ByteArray): XmlPullParser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
        .newPullParser().apply { setInput(ByteArrayInputStream(bytes), null) }

    private fun XmlPullParser.attr(local: String): String? {
        for (i in 0 until attributeCount) if (getAttributeName(i) == local) return getAttributeValue(i)
        return null
    }

    private fun XmlPullParser.attrNs(ns: String, local: String): String? {
        for (i in 0 until attributeCount) if (getAttributeName(i) == local && getAttributeNamespace(i) == ns) return getAttributeValue(i)
        return null
    }

    private fun coreTitle(z: ZipSource): String? {
        val bytes = z.read("docProps/core.xml") ?: z.read("meta.xml") ?: return null
        return try {
            val p = parser(bytes)
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.name == "title") return p.nextText().trim().takeIf { it.isNotEmpty() }
            }
            null
        } catch (_: Exception) { null }
    }

    /** `Relationship Id -> Target` from a `.rels` part. */
    private fun relationships(z: ZipSource, path: String): Map<String, String> {
        val bytes = z.read(path) ?: return emptyMap()
        val out = HashMap<String, String>()
        val p = parser(bytes)
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.name == "Relationship") {
                val id = p.attr("Id"); val target = p.attr("Target")
                if (id != null && target != null) out[id] = target
            }
        }
        return out
    }

    private fun resolve(baseDir: String, target: String): String {
        if (target.startsWith("/")) return target.removePrefix("/")
        val parts = ArrayList(baseDir.split('/').filter { it.isNotEmpty() })
        for (seg in target.split('/')) when (seg) { "..", -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1); ".", "" -> Unit; else -> parts += seg }
        return parts.joinToString("/")
    }

    // ---------------------------------------------------------------- docx

    private fun headingLevel(style: String?): Int {
        if (style == null) return 0
        val m = Regex("(?i)^(heading|제목|title)\\s*(\\d?)").find(style.trim()) ?: return 0
        return if (m.groupValues[1].equals("title", true)) 1 else m.groupValues[2].toIntOrNull()?.coerceIn(1, 6) ?: 1
    }

    private class Block(val level: Int, val text: String, val page: Int, val row: Boolean = false)

    fun docx(z: ZipSource): OfficeDoc? {
        val xml = z.read("word/document.xml") ?: return null
        // Word records where it last laid out page breaks; when present they are the reliable signal.
        val useRendered = String(xml, Charsets.UTF_8).contains("lastRenderedPageBreak")
        val p = parser(xml)
        val blocks = ArrayList<Block>()
        var page = 1
        var sawBreak = false
        val para = StringBuilder()
        var style: String? = null
        var tableDepth = 0
        val cell = ArrayList<String>()
        val row = ArrayList<String>()
        fun breakPage() { page++; sawBreak = true }
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "p" -> { para.setLength(0); style = null }
                    "pStyle" -> style = p.attr("val")
                    "t" -> para.append(p.nextText())
                    "tab" -> para.append('\t')
                    "br" -> if (p.attr("type") == "page") { if (!useRendered) breakPage() } else para.append('\n')
                    "lastRenderedPageBreak" -> if (useRendered) breakPage()
                    "tbl" -> tableDepth++
                    "tr" -> row.clear()
                    "tc" -> cell.clear()
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "p" -> {
                        val t = para.toString().trim()
                        if (tableDepth > 0) { if (t.isNotEmpty()) cell += t }
                        else if (t.isNotEmpty()) blocks += Block(headingLevel(style), t, page)
                    }
                    "tc" -> row += cell.joinToString(" ")
                    "tr" -> if (row.any { it.isNotBlank() }) blocks += Block(0, row.joinToString(" | "), page, row = true)
                    "tbl" -> tableDepth = (tableDepth - 1).coerceAtLeast(0)
                }
            }
        }
        if (blocks.isEmpty()) return OfficeDoc(coreTitle(z), emptyList(), TextCard.Style.DOCUMENT)
        val byPage = blocks.groupBy { it.page }.toSortedMap()
        val units = byPage.map { (pg, bs) ->
            val md = StringBuilder()
            var prevRow = false
            for (b in bs) {
                if (md.isNotEmpty()) md.append(if (b.row && prevRow) "\n" else "\n\n")
                md.append(if (b.level > 0) "#".repeat(b.level) + " " + b.text else b.text)
                prevRow = b.row
            }
            val label = bs.firstOrNull { it.level > 0 }?.text
            OfficeUnit(label, if (sawBreak) pg else null, md.toString(), markdown = true)
        }
        return OfficeDoc(coreTitle(z), units.take(MAX_UNITS), TextCard.Style.DOCUMENT)
    }

    // ---------------------------------------------------------------- pptx

    private fun paragraphsOf(bytes: ByteArray): List<String> {
        val out = ArrayList<String>()
        val p = parser(bytes)
        val sb = StringBuilder()
        var depth = 0
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG) when (p.name) {
                "p" -> { depth++; if (depth == 1) sb.setLength(0) }
                "t" -> if (depth > 0) sb.append(p.nextText())
                "br" -> if (depth > 0) sb.append('\n')
            } else if (p.eventType == XmlPullParser.END_TAG && p.name == "p") {
                if (depth == 1) sb.toString().trim().takeIf { it.isNotEmpty() }?.let { out += it }
                depth = (depth - 1).coerceAtLeast(0)
            }
        }
        return out
    }

    private fun orderedSlides(z: ZipSource): List<String> {
        val ordered = ArrayList<String>()
        try {
            val rels = relationships(z, "ppt/_rels/presentation.xml.rels")
            val pres = z.read("ppt/presentation.xml")
            if (pres != null) {
                val p = parser(pres)
                while (p.next() != XmlPullParser.END_DOCUMENT) {
                    if (p.eventType == XmlPullParser.START_TAG && p.name == "sldId") {
                        p.attrNs(NS_REL, "id")?.let { rels[it] }?.let { ordered += resolve("ppt", it) }
                    }
                }
            }
        } catch (_: Exception) { ordered.clear() }
        val existing = ordered.filter { z.read(it) != null }
        if (existing.isNotEmpty()) return existing
        return z.names().filter { Regex("ppt/slides/slide\\d+\\.xml").matches(it) }.sortedBy { Regex("\\d+").find(it.substringAfterLast('/'))!!.value.toInt() }
    }

    fun pptx(z: ZipSource): OfficeDoc? {
        val slides = orderedSlides(z)
        if (slides.isEmpty()) return null
        val units = slides.take(MAX_UNITS).mapIndexed { i, path ->
            val paras = paragraphsOf(z.read(path)!!)
            val relPath = path.substringBeforeLast('/') + "/_rels/" + path.substringAfterLast('/') + ".rels"
            val notesPath = relationships(z, relPath).values.firstOrNull { "notesSlide" in it }?.let { resolve(path.substringBeforeLast('/'), it) }
            val notes = notesPath?.let { z.read(it) }?.let(::paragraphsOf)?.filter { it.any(Char::isLetter) }.orEmpty()
            val text = (paras + notes.map { "Notes: $it" }).joinToString("\n")
            OfficeUnit(paras.firstOrNull(), i + 1, text)
        }.filter { it.text.isNotBlank() }
        return OfficeDoc(coreTitle(z), units, TextCard.Style.SLIDE)
    }

    // ---------------------------------------------------------------- xlsx

    private fun sharedStrings(z: ZipSource): List<String> {
        val bytes = z.read("xl/sharedStrings.xml") ?: return emptyList()
        val out = ArrayList<String>()
        val p = parser(bytes)
        val sb = StringBuilder()
        var inSi = false; var phonetic = 0
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG) when (p.name) {
                "si" -> { inSi = true; sb.setLength(0) }
                "rPh" -> phonetic++
                "t" -> if (inSi && phonetic == 0) sb.append(p.nextText())
            } else if (p.eventType == XmlPullParser.END_TAG) when (p.name) {
                "rPh" -> phonetic--
                "si" -> { out += sb.toString(); inSi = false }
            }
        }
        return out
    }

    /** `C5` -> 2 (zero-based column index). */
    private fun columnIndex(ref: String?): Int {
        if (ref == null) return -1
        var n = 0
        for (ch in ref) { if (ch in 'A'..'Z') n = n * 26 + (ch - 'A' + 1) else break }
        return n - 1
    }

    fun xlsx(z: ZipSource): OfficeDoc? {
        val wb = z.read("xl/workbook.xml") ?: return null
        val rels = relationships(z, "xl/_rels/workbook.xml.rels")
        val sheets = ArrayList<Pair<String, String>>() // name, path
        val wp = parser(wb)
        while (wp.next() != XmlPullParser.END_DOCUMENT) {
            if (wp.eventType == XmlPullParser.START_TAG && wp.name == "sheet") {
                val name = wp.attr("name") ?: continue
                val target = wp.attrNs(NS_REL, "id")?.let { rels[it] } ?: continue
                sheets += name to resolve("xl", target)
            }
        }
        val shared = sharedStrings(z)
        val units = ArrayList<OfficeUnit>()
        for ((name, path) in sheets.take(MAX_UNITS)) {
            val bytes = z.read(path) ?: continue
            val rows = ArrayList<List<String>>()
            val p = parser(bytes)
            var row = ArrayList<String>()
            var type: String? = null; var col = -1; var value = StringBuilder(); var inline = false
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG) when (p.name) {
                    "row" -> row = ArrayList()
                    "c" -> { type = p.attr("t"); col = columnIndex(p.attr("r")); value = StringBuilder(); inline = false }
                    "v" -> value.append(p.nextText())
                    "is" -> inline = true
                    "t" -> if (inline) value.append(p.nextText())
                } else if (p.eventType == XmlPullParser.END_TAG) when (p.name) {
                    "c" -> {
                        val raw = value.toString()
                        val text = when (type) {
                            "s" -> raw.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                            "b" -> if (raw == "1") "TRUE" else "FALSE"
                            "e" -> ""
                            else -> raw
                        }
                        val at = if (col >= 0) col else row.size
                        if (at < MAX_COLS) { while (row.size < at) row += ""; if (row.size == at) row += text else row[at] = text }
                    }
                    "row" -> {
                        while (row.isNotEmpty() && row.last().isBlank()) row.removeAt(row.size - 1)
                        if (row.isNotEmpty() && rows.size < MAX_ROWS) rows += row
                    }
                }
            }
            if (rows.isNotEmpty()) units += OfficeUnit(name, null, rows.joinToString("\n") { it.joinToString(" | ") }, rows = rows)
        }
        return OfficeDoc(coreTitle(z), units, TextCard.Style.SHEET)
    }

    private const val MAX_ROWS = 20_000
    private const val MAX_COLS = 100

    // ---------------------------------------------------------------- OpenDocument

    fun odf(ext: String, z: ZipSource): OfficeDoc? {
        val bytes = z.read("content.xml") ?: return null
        val style = TextCard.Style.OPEN_DOCUMENT
        val p = parser(bytes)
        val title = coreTitle(z)
        return when (ext) {
            "odp" -> {
                val units = ArrayList<OfficeUnit>()
                var paras = ArrayList<String>(); var inPage = 0
                var sb: StringBuilder? = null; var depth = 0
                while (p.next() != XmlPullParser.END_DOCUMENT) {
                    if (p.eventType == XmlPullParser.START_TAG) when (p.name) {
                        "page" -> { inPage++; if (inPage == 1) paras = ArrayList() }
                        "p", "h" -> if (inPage > 0) { depth++; if (depth == 1) sb = StringBuilder() }
                        "s" -> sb?.append(' '); "tab" -> sb?.append('\t'); "line-break" -> sb?.append('\n')
                    } else if (p.eventType == XmlPullParser.TEXT) { sb?.append(p.text) }
                    else if (p.eventType == XmlPullParser.END_TAG) when (p.name) {
                        "p", "h" -> if (inPage > 0) { if (depth == 1) sb?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { paras += it }; depth = (depth - 1).coerceAtLeast(0) }
                        "page" -> { inPage--; if (inPage == 0 && paras.isNotEmpty()) units += OfficeUnit(paras.first(), units.size + 1, paras.joinToString("\n")) }
                    }
                }
                OfficeDoc(title, units.take(MAX_UNITS), TextCard.Style.SLIDE)
            }
            "ods" -> {
                val units = ArrayList<OfficeUnit>()
                var name: String? = null; var tableDepth = 0
                var rows = ArrayList<List<String>>(); var row = ArrayList<String>()
                var cellText = StringBuilder(); var colRepeat = 1; var rowRepeat = 1; var inCell = false; var pDepth = 0
                while (p.next() != XmlPullParser.END_DOCUMENT) {
                    if (p.eventType == XmlPullParser.START_TAG) when (p.name) {
                        "table" -> { tableDepth++; if (tableDepth == 1) { name = p.attr("name"); rows = ArrayList() } }
                        "table-row" -> if (tableDepth == 1) { row = ArrayList(); rowRepeat = p.attr("number-rows-repeated")?.toIntOrNull() ?: 1 }
                        "table-cell", "covered-table-cell" -> if (tableDepth == 1) { inCell = true; cellText = StringBuilder(); colRepeat = p.attr("number-columns-repeated")?.toIntOrNull() ?: 1 }
                        "p" -> if (inCell) { pDepth++; if (cellText.isNotEmpty() && pDepth == 1) cellText.append(' ') }
                        "s" -> if (inCell) cellText.append(' ')
                    } else if (p.eventType == XmlPullParser.TEXT) { if (inCell && pDepth > 0) cellText.append(p.text) }
                    else if (p.eventType == XmlPullParser.END_TAG) when (p.name) {
                        "p" -> if (inCell) pDepth = (pDepth - 1).coerceAtLeast(0)
                        "table-cell", "covered-table-cell" -> if (tableDepth == 1) {
                            inCell = false
                            val t = cellText.toString().trim()
                            repeat(colRepeat.coerceIn(1, MAX_COLS)) { if (row.size < MAX_COLS) row += t }
                        }
                        "table-row" -> if (tableDepth == 1) {
                            while (row.isNotEmpty() && row.last().isBlank()) row.removeAt(row.size - 1)
                            if (row.isNotEmpty()) repeat(rowRepeat.coerceAtMost(50)) { if (rows.size < MAX_ROWS) rows += row.toList() }
                        }
                        "table" -> { if (tableDepth == 1 && rows.isNotEmpty()) units += OfficeUnit(name, null, rows.joinToString("\n") { it.joinToString(" | ") }, rows = rows); tableDepth-- }
                    }
                }
                OfficeDoc(title, units.take(MAX_UNITS), TextCard.Style.SHEET)
            }
            else -> { // odt
                val md = StringBuilder()
                var level = 0; var sb: StringBuilder? = null; var depth = 0
                var inTable = 0; val cells = ArrayList<String>(); var cellText = StringBuilder()
                fun add(text: String) { if (md.isNotEmpty()) md.append("\n\n"); md.append(text) }
                while (p.next() != XmlPullParser.END_DOCUMENT) {
                    if (p.eventType == XmlPullParser.START_TAG) when (p.name) {
                        "h" -> { level = p.attr("outline-level")?.toIntOrNull()?.coerceIn(1, 6) ?: 1; depth++; if (depth == 1) sb = StringBuilder() }
                        "p" -> { if (depth == 0) level = 0; depth++; if (depth == 1) sb = StringBuilder() }
                        "table" -> inTable++
                        "table-row" -> cells.clear()
                        "table-cell" -> cellText = StringBuilder()
                        "s" -> sb?.append(' '); "tab" -> sb?.append('\t'); "line-break" -> sb?.append('\n')
                    } else if (p.eventType == XmlPullParser.TEXT) { sb?.append(p.text) }
                    else if (p.eventType == XmlPullParser.END_TAG) when (p.name) {
                        "h", "p" -> { depth = (depth - 1).coerceAtLeast(0); if (depth == 0) {
                            val t = sb?.toString()?.trim().orEmpty(); sb = null
                            if (t.isNotEmpty()) { if (inTable > 0) { if (cellText.isNotEmpty()) cellText.append(' '); cellText.append(t) } else add(if (p.name == "h") "#".repeat(level) + " " + t else t) }
                        } }
                        "table-cell" -> cells += cellText.toString()
                        "table-row" -> if (inTable > 0 && cells.any { it.isNotBlank() }) add(cells.joinToString(" | "))
                        "table" -> inTable = (inTable - 1).coerceAtLeast(0)
                    }
                }
                OfficeDoc(title, if (md.isBlank()) emptyList() else listOf(OfficeUnit(null, null, md.toString(), markdown = true)), style)
            }
        }
    }
}
