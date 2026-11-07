package com.lsa.app.search

import android.util.Log
import com.lsa.app.data.db.ItemDao
import com.lsa.app.data.db.ItemKind
import com.lsa.app.data.db.SegmentType
import com.lsa.app.data.db.SignalGroup
import com.lsa.app.data.db.VectorRow
import com.lsa.app.data.prefs.SettingsStore
import com.lsa.app.ml.embedding.Quant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The segment of an item that matched best. */
data class SegmentRef(val segId: Long, val type: SegmentType, val startMs: Long?, val endMs: Long?, val page: Int?)

data class SearchHit(
    val itemId: Long,
    val score: Float,
    val best: SegmentRef?,
    val visualSim: Float?,
    val textSim: Float?,
    val audioSim: Float?,
    val keywordMatches: Int,
    val exactKeyword: Boolean,
    val snippet: String?,
    /** Which metadata matched the query (place, file name or title), if any. */
    val metaMatch: MetaMatch? = null,
)

enum class MetaMatch { PLACE, NAME }

/** Hard filters applied before scoring. Empty [kinds] means all kinds; dates are epoch milliseconds (taken date, else modified date). */
data class SearchFilter(
    val kinds: Set<ItemKind> = emptySet(), val fromMs: Long? = null, val toMs: Long? = null,
    /** Only these items (e.g. the photos of one person); null = no restriction. */
    val items: Set<Long>? = null,
) {
    val active: Boolean get() = kinds.isNotEmpty() || fromMs != null || toMs != null || items != null
}

/** Why a new item was judged to match a query: [strength] is the semantic z-score (or a large value for a word match). */
data class ItemMatch(val itemId: Long, val strength: Float, val keyword: Boolean)

data class SearchResult(val hits: List<SearchHit>, val semanticAvailable: Boolean)

/**
 * Hybrid search over segments: one semantic signal per [SignalGroup] plus keywords, combined per item.
 * Each signal is judged against its own distribution (see [Fusion]) because cosine ranges differ by modality.
 */
class SearchEngine(
    private val dao: ItemDao,
    /** Embeds a query with the selected model, or null when it is not installed. */
    private val queryEmbedder: suspend (String) -> FloatArray?,
    private val settings: SettingsStore,
) {
    private val cacheMutex = Mutex()
    private var cacheKey: String? = null
    private var vectors: List<VectorRow> = emptyList()

    private suspend fun loadCache(modelId: String) = cacheMutex.withLock {
        val key = "$modelId|${dao.stamp()}"
        if (key != cacheKey) {
            vectors = dao.vectors(modelId)
            cacheKey = key
        }
    }

    private class Acc(val itemId: Long) {
        val groupScore = FloatArray(SignalGroup.entries.size)
        val groupSim = arrayOfNulls<Float>(SignalGroup.entries.size)
        var best: SegmentRef? = null
        var bestScore = 0f
        var kwScore = 0f
        var kwMatches = 0
        var exact = false
        var snippet: String? = null
        var kwRef: SegmentRef? = null
        var metaScore = 0f
        var metaExact = false
        var metaField: MetaMatch? = null
        /** Best matching video clip, to narrow down where in a video a transcript hit happened. */
        var clipRef: SegmentRef? = null
        var clipScore = 0f
    }

    /** The query vector and the id of the embedding model it was made with, or null when no model is installed. */
    suspend fun queryVector(query: String): Pair<String, FloatArray>? =
        queryEmbedder(query)?.let { settings.embeddingModelId.first() to it }

    suspend fun search(query: String, filter: SearchFilter = SearchFilter(), limit: Int = 50): SearchResult {
        val q = query.trim()
        val kinds = filter.kinds.ifEmpty { ItemKind.entries.toSet() }.toList()
        if (q.isEmpty()) {
            // Filters without a query: browse, newest first.
            if (!filter.active) return SearchResult(emptyList(), true)
            val ids = if (filter.items != null) {
                dao.browseAmong(filter.items.toList(), kinds, filter.fromMs ?: Long.MIN_VALUE, filter.toMs ?: Long.MAX_VALUE, BROWSE_LIMIT)
            } else dao.browseItemIds(kinds, filter.fromMs ?: Long.MIN_VALUE, filter.toMs ?: Long.MAX_VALUE, BROWSE_LIMIT)
            return SearchResult(ids.map { SearchHit(it, 0f, null, null, null, null, 0, false, null) }, true)
        }
        loadCache(settings.embeddingModelId.first())
        // Filter before scoring so the score distributions (see Fusion) are those of the filtered set.
        val allowed: Set<Long>? =
            if (filter.active) dao.allowedItemIds(kinds, filter.fromMs ?: Long.MIN_VALUE, filter.toMs ?: Long.MAX_VALUE).toHashSet()
                .also { a -> filter.items?.let { only -> a.retainAll(only) } } else null

        val qVec = queryEmbedder(q)
        val acc = HashMap<Long, Acc>()
        fun accOf(itemId: Long) = acc.getOrPut(itemId) { Acc(itemId) }

        if (qVec != null) {
            val sims = SignalGroup.entries.map { HashMap<Long, Float>() }
            val byId = HashMap<Long, VectorRow>(vectors.size)
            for (v in vectors) {
                if (allowed != null && v.itemId !in allowed) continue
                sims[v.type.group.ordinal][v.segId] = Quant.dot(qVec, v.embedding)
                byId[v.segId] = v
            }
            for (g in SignalGroup.entries) {
                val rel = Fusion.relative(sims[g.ordinal], g.margin, g.minSpread, g.minTop)
                for ((segId, r) in rel) {
                    val v = byId.getValue(segId)
                    val a = accOf(v.itemId)
                    val weighted = r * v.type.factor
                    if (weighted > a.groupScore[g.ordinal]) {
                        a.groupScore[g.ordinal] = weighted
                        a.groupSim[g.ordinal] = sims[g.ordinal].getValue(segId)
                    }
                    if (v.type == SegmentType.VIDEO_CLIP && weighted > a.clipScore) {
                        a.clipScore = weighted; a.clipRef = SegmentRef(v.segId, v.type, v.startMs, v.endMs, v.page)
                    }
                    if (weighted * g.weight > a.bestScore) {
                        a.bestScore = weighted * g.weight
                        a.best = SegmentRef(v.segId, v.type, v.startMs, v.endMs, v.page)
                    }
                }
            }
        }

        val tokens = KeywordMatcher.tokens(q)
        if (tokens.isNotEmpty()) {
            val seen = HashSet<Long>()
            for (t in tokens) {
                for (row in dao.textCandidates("%" + escapeLike(t) + "%", KEYWORD_CANDIDATES)) {
                    if (!seen.add(row.segId)) continue
                    if (allowed != null && row.itemId !in allowed) continue
                    val m = KeywordMatcher.match(row.text, tokens, q) ?: continue
                    val score = if (m.exact) 1f else 0.5f + 0.5f * (m.count.toFloat() / tokens.size)
                    val a = accOf(row.itemId)
                    if (score > a.kwScore) {
                        a.kwScore = score
                        a.kwMatches = m.count
                        a.exact = m.exact
                        a.snippet = KeywordMatcher.snippet(row.text, tokens)
                        a.kwRef = SegmentRef(row.segId, row.type, row.startMs, row.endMs, row.page)
                    }
                }
            }
        }

        if (tokens.isNotEmpty()) {
            val seenItems = HashSet<Long>()
            for (t in tokens) {
                for (row in dao.metaCandidates("%" + escapeLike(t) + "%", META_CANDIDATES)) {
                    if (!seenItems.add(row.itemId)) continue
                    if (allowed != null && row.itemId !in allowed) continue
                    val place = row.place?.takeIf { it.isNotBlank() }?.let { KeywordMatcher.match(it, tokens, q) }
                    val name = KeywordMatcher.match(listOfNotNull(row.displayName, row.title).joinToString(" "), tokens, q)
                    val placeScore = place?.let { if (it.exact) 1f else 0.5f + 0.5f * (it.count.toFloat() / tokens.size) } ?: 0f
                    val nameScore = name?.let { if (it.exact) 1f else 0.5f + 0.5f * (it.count.toFloat() / tokens.size) } ?: 0f
                    val a = accOf(row.itemId)
                    a.metaScore = maxOf(placeScore, nameScore)
                    a.metaField = if (placeScore >= nameScore) MetaMatch.PLACE else MetaMatch.NAME
                    a.metaExact = (if (placeScore >= nameScore) place else name)?.exact == true
                }
            }
        }

        val hits = acc.values.map { a ->
            var score = W_KEYWORD * a.kwScore + W_META * a.metaScore
            for (g in SignalGroup.entries) score += g.weight * a.groupScore[g.ordinal]
            // Several kinds of evidence agreeing (meaning + place, meaning + words, ...) beat one strong signal alone.
            val signals = SignalGroup.entries.count { a.groupScore[it.ordinal] > 0f } + (if (a.kwScore > 0f) 1 else 0) + (if (a.metaScore > 0f) 1 else 0)
            if (signals > 1) score += AGREEMENT_BONUS * (signals - 1)
            SearchHit(
                itemId = a.itemId,
                score = score,
                // A keyword match pins the location; otherwise the best semantic segment.
                best = narrowToClip(if (a.kwScore >= 1f || a.best == null) a.kwRef ?: a.best else a.best, a.clipRef),
                visualSim = a.groupSim[SignalGroup.VISUAL.ordinal],
                textSim = a.groupSim[SignalGroup.TEXT.ordinal],
                audioSim = a.groupSim[SignalGroup.AUDIO.ordinal],
                keywordMatches = a.kwMatches,
                exactKeyword = a.exact || a.metaExact,
                snippet = a.snippet,
                metaMatch = if (a.metaScore > 0f) a.metaField else null,
            )
        }.filter { it.score > 0f }
            .let { all ->
                // When something matches the typed words exactly, weak look-alikes only add noise.
                if (all.any { it.exactKeyword }) all.filter { it.exactKeyword || it.score >= EXACT_MATCH_FLOOR } else all
            }
            .let { all ->
                // Drop the long tail: anything far below the best result is rarely what was meant.
                val top = all.maxOfOrNull { it.score } ?: 0f
                all.filter { it.exactKeyword || it.score >= TAIL_FRACTION * top }
            }
            .sortedWith(compareByDescending<SearchHit> { it.exactKeyword }.thenByDescending { it.score })
            .take(limit)
        Log.d(TAG, "RESULT q='$q' tokens=$tokens top=${hits.map { it.itemId }}")
        return SearchResult(hits, semanticAvailable = qVec != null)
    }

    /**
     * Decides, for each of [itemIds] on its own, whether it matches [query] (used by scheduled "new file" tasks).
     * Unlike [search] it never compares an item with the best existing file: a semantic match must stand out from the
     * typical similarity of the whole index (z-score per signal group) and a keyword match needs all words (or the exact
     * phrase) in the file's text, name, title or place. It is meant as a recall-oriented pre-filter; the calling app's chat model checks the rest.
     */
    suspend fun matchItems(query: String, itemIds: Set<Long>, kinds: Set<ItemKind> = emptySet()): Map<Long, ItemMatch> {
        if (itemIds.isEmpty()) return emptyMap()
        val out = HashMap<Long, ItemMatch>()
        // "a|b|c": any alternative may match (the model lists the user's words plus concrete synonyms)
        for (alt in alternatives(query)) matchOne(alt, itemIds, kinds, out)
        return out
    }

    private suspend fun matchOne(q: String, itemIds: Set<Long>, kinds: Set<ItemKind>, out: HashMap<Long, ItemMatch>) {

        val tokens = KeywordMatcher.tokens(q)
        if (tokens.isNotEmpty()) for (id in itemIds) {
            val item = dao.getItem(id) ?: continue
            val texts = listOf(listOfNotNull(item.displayName, item.title, item.place).joinToString(" ")) + dao.segmentsOf(id).mapNotNull { it.text }
            if (texts.any { t -> KeywordMatcher.match(t, tokens, q)?.let { m -> m.exact || m.count == tokens.size } == true }) {
                out[id] = ItemMatch(id, KEYWORD_STRENGTH, true)
            }
        }

        loadCache(settings.embeddingModelId.first())
        val qVec = queryEmbedder(q) ?: return
        val allowed: Set<Long>? = if (kinds.isNotEmpty()) dao.allowedItemIds(kinds.toList(), Long.MIN_VALUE, Long.MAX_VALUE).toHashSet() else null
        val groups = SignalGroup.entries
        val n = IntArray(groups.size)
        val sum = DoubleArray(groups.size)
        val sumSq = DoubleArray(groups.size)
        val mine = groups.map { HashMap<Long, Float>() }
        for (v in vectors) {
            if (allowed != null && v.itemId !in allowed) continue
            val s = Quant.dot(qVec, v.embedding)
            val g = v.type.group.ordinal
            n[g]++; sum[g] += s; sumSq[g] += s.toDouble() * s
            if (v.itemId in itemIds) mine[g].merge(v.itemId, s) { a, b -> maxOf(a, b) }
        }
        for (g in groups) {
            val i = g.ordinal
            val enoughBaseline = n[i] >= MIN_BASELINE
            val mean = if (n[i] > 0) sum[i] / n[i] else 0.0
            val std = if (n[i] > 1) kotlin.math.sqrt((sumSq[i] / n[i] - mean * mean).coerceAtLeast(0.0)).coerceAtLeast(MIN_STD) else MIN_STD
            for ((id, s) in mine[i]) {
                val z = ((s - mean) / std).toFloat()
                val ok = s >= g.minTop && (if (enoughBaseline) z >= Z_MIN else s >= g.smallIndexFloor)
                if (ok && z > (out[id]?.strength ?: -1f)) out[id] = ItemMatch(id, z, out[id]?.keyword == true)
            }
        }
        Log.d(TAG, "MATCH q='$q' candidates=${itemIds.size} matched=${out.keys}")
    }

    /** A transcript chunk covers many seconds; if the best visual clip lies within it, that clip is the better answer. */
    private fun narrowToClip(chosen: SegmentRef?, clip: SegmentRef?): SegmentRef? {
        if (chosen == null || clip == null || chosen.type == SegmentType.VIDEO_CLIP) return chosen
        val cs = chosen.startMs ?: return chosen; val ce = chosen.endMs ?: return chosen
        val ks = clip.startMs ?: return chosen; val ke = clip.endMs ?: return chosen
        return if (ks < ce && ke > cs) clip else chosen
    }

    private fun escapeLike(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private val SignalGroup.margin get() = when (this) { SignalGroup.VISUAL -> 0.10f; SignalGroup.TEXT -> 0.08f; SignalGroup.AUDIO -> 0.10f }
    private val SignalGroup.minSpread get() = 0.08f
    private val SignalGroup.minTop get() = when (this) { SignalGroup.VISUAL -> 0.10f; SignalGroup.TEXT -> 0.30f; SignalGroup.AUDIO -> 0.30f }
    /** With a tiny index there is no distribution to judge against, so a fixed raw similarity is required instead. */
    private val SignalGroup.smallIndexFloor get() = when (this) { SignalGroup.VISUAL -> 0.20f; SignalGroup.TEXT -> 0.65f; SignalGroup.AUDIO -> 0.55f }
    private val SignalGroup.weight get() = when (this) { SignalGroup.VISUAL -> W_VISUAL; SignalGroup.TEXT -> W_TEXT; SignalGroup.AUDIO -> W_AUDIO }

    /** Text cards drawn from Office documents say little about how the document looks. */
    private val SegmentType.factor get() = if (this == SegmentType.CARD_IMAGE) 0.5f else 1f

    companion object {
        private const val TAG = "LSA.Search"
        const val KEYWORD_CANDIDATES = 2000
        /** With an exact keyword hit, other results must score at least this much (a full signal weighs ~1.0). */
        const val EXACT_MATCH_FLOOR = 0.6f
        const val TAIL_FRACTION = 0.25f
        const val W_VISUAL = 1.0f
        const val W_TEXT = 0.8f
        const val W_AUDIO = 0.8f
        const val W_KEYWORD = 1.2f
        const val W_META = 1.0f
        /** Added once per additional kind of signal (visual, text, audio, keyword, metadata) that matched an item. */
        const val AGREEMENT_BONUS = 0.15f
        const val META_CANDIDATES = 2000
        const val BROWSE_LIMIT = 100
        /** [matchItems]: a semantic match must be this many standard deviations above the mean similarity of the index. */
        const val Z_MIN = 2.5f
        const val MAX_ALTERNATIVES = 6

        /** A task query may list alternatives separated by `|`; any one of them can match. */
        fun alternatives(query: String): List<String> = query.split('|').map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_ALTERNATIVES)
        /** [matchItems]: fewer vectors than this and the z-score is not meaningful. */
        const val MIN_BASELINE = 20
        private const val MIN_STD = 1e-3
        private const val KEYWORD_STRENGTH = 100f
    }
}
