package com.lsa.app.service.tools

import com.lsa.app.data.db.SegmentEntity
import com.lsa.app.data.db.SegmentType
import com.lsa.app.ml.embedding.Quant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchInFileToolTest {
    private fun seg(ordinal: Int, text: String?, vec: FloatArray? = null, type: SegmentType = SegmentType.TEXT_CHUNK, page: Int? = ordinal / 1000 + 1) =
        SegmentEntity(itemId = 1, type = type, ordinal = ordinal, startMs = null, endMs = null, page = page, text = text, embedding = vec?.let { Quant.encode(it) }, thumbPath = null)

    private val doc = listOf(
        seg(0, "Intro about the company history", floatArrayOf(1f, 0f, 0f)),
        seg(1000, "Quarterly revenue grew twelve percent", floatArrayOf(0f, 1f, 0f)),
        seg(2000, "Appendix with the office address", floatArrayOf(0f, 0f, 1f)),
        seg(2900, null, floatArrayOf(0f, 1f, 0f), SegmentType.PAGE_IMAGE),
    )

    @Test fun semanticRankingPicksTheClosestPassageFirst() {
        val hits = SearchInFileTool.rank(doc, floatArrayOf(0.1f, 0.9f, 0f), "revenue", 2)
        assertEquals(listOf(1000, 0), hits.map { it.segment.ordinal })
        assertTrue(hits[0].score > hits[1].score)
    }

    @Test fun imageAndEmptySegmentsAreNeverReturned() {
        val hits = SearchInFileTool.rank(doc, floatArrayOf(0f, 1f, 0f), "x", 10)
        assertEquals(3, hits.size)
        assertTrue(hits.none { it.segment.type == SegmentType.PAGE_IMAGE })
    }

    @Test fun withoutAModelWordsDecide() {
        val hits = SearchInFileTool.rank(doc, null, "office address", 3)
        assertEquals(listOf(2000), hits.map { it.segment.ordinal })
    }

    @Test fun mismatchedVectorSizesFallBackToWords() {
        val hits = SearchInFileTool.rank(doc, floatArrayOf(1f, 0f), "revenue", 3)
        assertEquals(listOf(1000), hits.map { it.segment.ordinal })
    }

    @Test fun nothingMatchesGivesAnEmptyList() {
        assertTrue(SearchInFileTool.rank(doc, null, "zebra", 3).isEmpty())
        assertTrue(SearchInFileTool.rank(emptyList(), floatArrayOf(1f, 0f, 0f), "x", 3).isEmpty())
    }
}
