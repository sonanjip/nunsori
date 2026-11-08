package com.lsa.app.indexing.docs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OfficeParsersTest {
    private fun zip(vararg parts: Pair<String, String>): ZipSource {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> for ((n, c) in parts) { z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() } }
        return ZipSource.fromBytes(bos.toByteArray())
    }

    private val W = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
    private val A = "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""
    private val R = "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
    private val S = "xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""

    private fun p(text: String, style: String? = null, extra: String = "") =
        "<w:p>" + (if (style != null) "<w:pPr><w:pStyle w:val=\"$style\"/></w:pPr>" else "") + extra + "<w:r><w:t>$text</w:t></w:r></w:p>"

    // ------------------------------------------------------------ docx
    @Test fun docxHeadingsParagraphsAndTables() {
        val body = p("Annual Report", "Heading1") + p("Intro paragraph.") + p("Budget", "Heading2") +
            "<w:tbl><w:tr><w:tc>${p("Item")}</w:tc><w:tc>${p("Cost")}</w:tc></w:tr><w:tr><w:tc>${p("Server")}</w:tc><w:tc>${p("100")}</w:tc></w:tr></w:tbl>"
        val d = OfficeParsers.docx(zip("word/document.xml" to "<w:document $W><w:body>$body</w:body></w:document>",
            "docProps/core.xml" to "<cp:coreProperties xmlns:cp=\"x\" xmlns:dc=\"y\"><dc:title>Q4 Report</dc:title></cp:coreProperties>"))!!
        assertEquals("Q4 Report", d.title)
        val u = d.units.single()
        assertNull("no page markers means no page numbers", u.page)
        assertTrue(u.markdown)
        assertTrue(u.text.startsWith("# Annual Report"))
        assertTrue(u.text.contains("## Budget"))
        assertTrue(u.text.contains("Item | Cost\nServer | 100"))
        assertEquals("Annual Report", u.label)
    }

    @Test fun docxKoreanHeadingStyleIsRecognised() {
        val d = OfficeParsers.docx(zip("word/document.xml" to "<w:document $W><w:body>${p("회의 결과", "제목 1")}${p("본문입니다.")}</w:body></w:document>"))!!
        assertTrue(d.units.single().text.startsWith("# 회의 결과"))
    }

    @Test fun docxRenderedPageBreaksGivePageNumbers() {
        val lrpb = "<w:r><w:lastRenderedPageBreak/></w:r>"
        val body = p("Page one text") + p("Page two text", extra = lrpb) + p("Page three text", extra = lrpb)
        val d = OfficeParsers.docx(zip("word/document.xml" to "<w:document $W><w:body>$body</w:body></w:document>"))!!
        assertEquals(listOf(1, 2, 3), d.units.map { it.page })
        assertTrue(d.units[1].text.contains("Page two text"))
    }

    @Test fun docxExplicitBreaksCountWhenNothingWasRendered() {
        val br = "<w:r><w:br w:type=\"page\"/></w:r>"
        val body = p("First") + "<w:p>$br</w:p>" + p("Second")
        val d = OfficeParsers.docx(zip("word/document.xml" to "<w:document $W><w:body>$body</w:body></w:document>"))!!
        assertEquals(listOf(1, 2), d.units.map { it.page })
    }

    @Test fun docxExplicitAndRenderedBreaksAreNotCountedTwice() {
        val both = "<w:r><w:br w:type=\"page\"/></w:r><w:r><w:lastRenderedPageBreak/></w:r>"
        val body = p("One") + "<w:p>$both</w:p>" + p("Two")
        assertEquals(listOf(1, 2), OfficeParsers.docx(zip("word/document.xml" to "<w:document $W><w:body>$body</w:body></w:document>"))!!.units.map { it.page })
    }

    @Test fun docxWithoutDocumentPartIsNull() = assertNull(OfficeParsers.docx(zip("other.xml" to "<a/>")))

    // ------------------------------------------------------------ pptx
    private fun slide(vararg paras: String) = "<p:sld xmlns:p=\"urn:p\" $A><p:cSld><p:spTree>" +
        paras.joinToString("") { "<p:sp><p:txBody><a:p><a:r><a:t>$it</a:t></a:r></a:p></p:txBody></p:sp>" } + "</p:spTree></p:cSld></p:sld>"

    @Test fun pptxSlidesFollowThePresentationOrderNotTheFileNames() {
        val z = zip(
            "ppt/presentation.xml" to "<p:presentation xmlns:p=\"urn:p\" $R><p:sldIdLst><p:sldId id=\"256\" r:id=\"rId5\"/><p:sldId id=\"257\" r:id=\"rId2\"/></p:sldIdLst></p:presentation>",
            "ppt/_rels/presentation.xml.rels" to "<Relationships xmlns=\"x\"><Relationship Id=\"rId2\" Target=\"slides/slide1.xml\"/><Relationship Id=\"rId5\" Target=\"slides/slide2.xml\"/></Relationships>",
            "ppt/slides/slide1.xml" to slide("Closing remarks", "Thank you"),
            "ppt/slides/slide2.xml" to slide("Opening", "Agenda for today"),
        )
        val d = OfficeParsers.pptx(z)!!
        assertEquals(listOf("Opening", "Closing remarks"), d.units.map { it.label })
        assertEquals(listOf(1, 2), d.units.map { it.page })
        assertTrue(d.units[0].text.contains("Agenda for today"))
        assertEquals(TextCard.Style.SLIDE, d.style)
    }

    @Test fun pptxFallsBackToNumericSlideOrderAndIncludesNotes() {
        val z = zip(
            "ppt/slides/slide10.xml" to slide("Ten"), "ppt/slides/slide2.xml" to slide("Two"),
            "ppt/slides/_rels/slide2.xml.rels" to "<Relationships xmlns=\"x\"><Relationship Id=\"r1\" Target=\"../notesSlides/notesSlide1.xml\" Type=\"notesSlide\"/></Relationships>",
            "ppt/notesSlides/notesSlide1.xml" to slide("Remember to mention the deadline"),
        )
        val d = OfficeParsers.pptx(z)!!
        assertEquals(listOf("Two", "Ten"), d.units.map { it.label })
        assertTrue(d.units[0].text.contains("Notes: Remember to mention the deadline"))
    }

    // ------------------------------------------------------------ xlsx
    @Test fun xlsxSheetsSharedStringsAndEmptyCells() {
        val z = zip(
            "xl/workbook.xml" to "<workbook $S $R><sheets><sheet name=\"People\" sheetId=\"1\" r:id=\"rId1\"/><sheet name=\"Totals\" sheetId=\"2\" r:id=\"rId2\"/></sheets></workbook>",
            "xl/_rels/workbook.xml.rels" to "<Relationships xmlns=\"x\"><Relationship Id=\"rId1\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"rId2\" Target=\"/xl/worksheets/sheet2.xml\"/></Relationships>",
            "xl/sharedStrings.xml" to "<sst $S><si><t>Name</t></si><si><t>City</t></si><si><r><t>Min</t></r><r><t>su</t></r></si><si><t>Seoul</t><rPh><t>ignored</t></rPh></si></sst>",
            "xl/worksheets/sheet1.xml" to "<worksheet $S><sheetData><row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"C1\" t=\"s\"><v>1</v></c></row>" +
                "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>2</v></c><c r=\"C2\" t=\"s\"><v>3</v></c></row><row r=\"3\"/></sheetData></worksheet>",
            "xl/worksheets/sheet2.xml" to "<worksheet $S><sheetData><row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>Total</t></is></c><c r=\"B1\"><v>42.5</v></c><c r=\"C1\" t=\"b\"><v>1</v></c></row></sheetData></worksheet>",
        )
        val d = OfficeParsers.xlsx(z)!!
        assertEquals(listOf("People", "Totals"), d.units.map { it.label })
        assertEquals(listOf(listOf("Name", "", "City"), listOf("Minsu", "", "Seoul")), d.units[0].rows)
        assertEquals(listOf(listOf("Total", "42.5", "TRUE")), d.units[1].rows)
        assertEquals(TextCard.Style.SHEET, d.style)
    }

    // ------------------------------------------------------------ OpenDocument
    private val T = "xmlns:text=\"urn:text\" xmlns:table=\"urn:table\" xmlns:draw=\"urn:draw\" xmlns:office=\"urn:office\""

    @Test fun odtHeadingsParagraphsAndTables() {
        val body = "<text:h text:outline-level=\"1\">Plan</text:h><text:p>First<text:s/>line<text:span> bold</text:span></text:p>" +
            "<table:table><table:table-row><table:table-cell><text:p>A</text:p></table:table-cell><table:table-cell><text:p>B</text:p></table:table-cell></table:table-row></table:table>"
        val d = OfficeParsers.odf("odt", zip("content.xml" to "<office:document-content $T><office:body><office:text>$body</office:text></office:body></office:document-content>"))!!
        val t = d.units.single().text
        assertTrue(t.startsWith("# Plan")); assertTrue(t.contains("First line bold")); assertTrue(t.contains("A | B"))
    }

    @Test fun odpSlidesBecomeUnits() {
        val body = "<draw:page draw:name=\"p1\"><draw:frame><draw:text-box><text:p>Title slide</text:p><text:p>Subtitle</text:p></draw:text-box></draw:frame></draw:page>" +
            "<draw:page draw:name=\"p2\"><draw:frame><draw:text-box><text:p>Second</text:p></draw:text-box></draw:frame></draw:page>"
        val d = OfficeParsers.odf("odp", zip("content.xml" to "<office:document-content $T><office:body><office:presentation>$body</office:presentation></office:body></office:document-content>"))!!
        assertEquals(listOf("Title slide", "Second"), d.units.map { it.label }); assertEquals(listOf(1, 2), d.units.map { it.page })
    }

    @Test fun repeatedEmptyCellsKeepTheFollowingColumn() {
        val body = "<table:table table:name=\"Sheet\"><table:table-row><table:table-cell table:number-columns-repeated=\"3\"/><table:table-cell><text:p>correct fourth column</text:p></table:table-cell></table:table-row></table:table>"
        val d = OfficeParsers.odf("ods", zip("content.xml" to "<office:document-content $T><office:body><office:spreadsheet>$body</office:spreadsheet></office:body></office:document-content>"))!!
        assertEquals(listOf("", "", "", "correct fourth column"), d.units.single().rows!!.single())
    }

    @Test fun odsRepeatedCellsAndRowsAreBounded() {
        val body = "<table:table table:name=\"Sheet1\"><table:table-row><table:table-cell><text:p>id</text:p></table:table-cell><table:table-cell><text:p>name</text:p></table:table-cell>" +
            "<table:table-cell table:number-columns-repeated=\"1000\"/></table:table-row>" +
            "<table:table-row table:number-rows-repeated=\"2\"><table:table-cell><text:p>1</text:p></table:table-cell><table:table-cell><text:p>kim</text:p></table:table-cell></table:table-row>" +
            "<table:table-row table:number-rows-repeated=\"1048000\"><table:table-cell/></table:table-row></table:table>"
        val d = OfficeParsers.odf("ods", zip("content.xml" to "<office:document-content $T><office:body><office:spreadsheet>$body</office:spreadsheet></office:body></office:document-content>"))!!
        val u = d.units.single()
        assertEquals("Sheet1", u.label)
        assertEquals(listOf(listOf("id", "name"), listOf("1", "kim"), listOf("1", "kim")), u.rows)
    }

    @Test fun unknownExtensionIsNull() = assertNull(OfficeParsers.parse("doc", zip("x" to "y")))
    @Test fun parseDispatchesByExtension() = assertNotNull(OfficeParsers.parse("DOCX", zip("word/document.xml" to "<w:document $W><w:body>${p("x")}</w:body></w:document>")))
}
