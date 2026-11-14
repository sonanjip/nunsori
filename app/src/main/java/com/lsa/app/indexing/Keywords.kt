package com.lsa.app.indexing

/** Rules for the user's own search keywords ("My keywords"). */
object Keywords {
    const val MAX_LENGTH = 50
    const val MAX_PER_FILE = 20

    /** Segment ordinals of keyword segments start here, after anything an extractor writes. */
    const val ORDINAL_BASE = 1_000_000

    /**
     * Splits what the user typed into keywords: commas and line breaks separate them, spaces inside one are
     * collapsed, each is cut to [MAX_LENGTH], and repeats (ignoring case) are dropped, as are ones in [existing].
     */
    fun parse(input: String, existing: Collection<String> = emptyList()): List<String> {
        val seen = existing.mapTo(HashSet()) { it.lowercase() }
        val out = ArrayList<String>()
        for (raw in input.split(',', '\n', '，', '、')) {
            val k = raw.trim().replace(Regex("\\s+"), " ").take(MAX_LENGTH).trim()
            if (k.isEmpty() || !seen.add(k.lowercase())) continue
            out += k
        }
        return out.take((MAX_PER_FILE - existing.size).coerceAtLeast(0))
    }
}
