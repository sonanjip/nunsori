package com.lsa.app.indexing

import org.junit.Assert.assertEquals
import org.junit.Test

class KeywordsTest {
    @Test fun splitsOnCommasAndLineBreaksAndTrims() {
        assertEquals(listOf("여권 사본", "travel", "2024"), Keywords.parse(" 여권   사본 , travel\n2024,, "))
    }

    @Test fun dropsRepeatsIgnoringCaseAndOnesTheFileHas() {
        assertEquals(listOf("Beach"), Keywords.parse("Beach, beach, BEACH, trip", existing = listOf("TRIP")))
    }

    @Test fun cutsLongKeywordsAndStopsAtTheLimit() {
        assertEquals(Keywords.MAX_LENGTH, Keywords.parse("x".repeat(80)).single().length)
        val existing = (1..Keywords.MAX_PER_FILE - 1).map { "k$it" }
        assertEquals(listOf("a"), Keywords.parse("a, b, c", existing))
        assertEquals(emptyList<String>(), Keywords.parse("z", existing + "last"))
    }
}
