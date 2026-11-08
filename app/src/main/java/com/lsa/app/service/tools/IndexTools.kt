package com.lsa.app.service.tools

import com.lsa.app.data.db.ItemDao
import com.lsa.app.data.db.ItemKind
import com.lsa.app.data.db.ItemSummary
import com.lsa.app.data.db.SegmentEntity
import com.lsa.app.data.db.SegmentType
import com.lsa.app.ml.embedding.Quant
import com.lsa.app.search.KeywordMatcher
import com.lsa.app.search.SearchEngine
import com.lsa.app.search.SearchFilter
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/** A tool other apps offer to their chat model (service/IndexApi): what the model sees and how it runs here. */
interface IndexTool {
    val name: String
    val description: String
    /** JSON schema of the arguments. */
    val parameters: String
    fun execute(args: JSONObject): String
}

object ToolNames {
    const val SEARCH = "search_index"
    const val READ = "read_file"
    const val SEARCH_IN = "search_in_file"
    const val MOVE = "move_files"
    const val DELETE = "delete_files"
}

/** Kind names the model uses; "document" covers text files and PDF/Office. */
private val KIND_NAMES = mapOf(
    ItemKind.TEXT to "document", ItemKind.DOC to "document", ItemKind.IMAGE to "image", ItemKind.AUDIO to "audio", ItemKind.VIDEO to "video",
)

internal fun kindName(kind: ItemKind) = KIND_NAMES.getValue(kind)

private fun formatTime(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
}

private fun dateOf(s: ItemSummary, takenOrModified: Long?): String? =
    takenOrModified?.let { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(it)) }

/** Browse lists stop at 100 files (SearchEngine.BROWSE_LIMIT), so offset + limit must stay within it. */
private const val MAX_OFFSET = 90

/** Lets another app's chat model search the index the way the Search screen does. */
class SearchIndexTool(private val search: SearchEngine, private val dao: ItemDao, private val people: PeopleDirectory? = null) : IndexTool {
    override val name = ToolNames.SEARCH
    override val description =
        "Search the user's indexed files (photos, documents, audio, video) by meaning and keywords. " +
            "Returns the best matching files with their item_id; use read_file to read one. " +
            "The query is optional when kinds or a date range is given: then it lists the files of that kind/period, newest first " +
            "(use offset to get the next ones). With person, only photos that show that person (a name from the People screen)."
    override val parameters = """{"type":"object","properties":{
        "query":{"type":"string","description":"What to look for: words, a description, a place or a file name. Leave out to list by kinds/dates only."},
        "kinds":{"type":"string","description":"Optional comma separated filter: document, image, audio, video."},
        "date_from":{"type":"string","description":"Optional start date, YYYY-MM-DD."},
        "date_to":{"type":"string","description":"Optional end date, YYYY-MM-DD."},
        "limit":{"type":"integer","description":"Number of results, 1 to 10 (default 5)."},
        "offset":{"type":"integer","description":"Skip this many results, to page through a long list (default 0)."},
        "person":{"type":"string","description":"Optional: only photos of this person, by the name given in the People screen."}}}"""

    override fun execute(args: JSONObject): String = runBlocking {
        val query = args.optString("query").trim()
        val kinds = args.optString("kinds").split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.flatMap { k ->
            when (k) {
                "document", "documents", "text" -> listOf(ItemKind.TEXT, ItemKind.DOC)
                "image", "images", "photo", "photos" -> listOf(ItemKind.IMAGE)
                "audio" -> listOf(ItemKind.AUDIO)
                "video", "videos" -> listOf(ItemKind.VIDEO)
                else -> emptyList()
            }
        }.toSet()
        val zone = ZoneId.systemDefault()
        fun day(key: String, endOfDay: Boolean): Long? = runCatching {
            val d = LocalDate.parse(args.optString(key).trim())
            (if (endOfDay) d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1 else d.atStartOfDay(zone).toInstant().toEpochMilli())
        }.getOrNull()
        val limit = args.optInt("limit", 5).coerceIn(1, 10)
        val offset = args.optInt("offset", 0).coerceIn(0, MAX_OFFSET)
        val personName = args.optString("person").trim()
        val only: Set<Long>? = if (personName.isEmpty()) null else when (val p = people?.find(personName) ?: PeopleDirectory.Lookup.Missing("Error: people are not available.")) {
            is PeopleDirectory.Lookup.Found -> p.itemIds.toSet()
            is PeopleDirectory.Lookup.Missing -> return@runBlocking p.error
        }
        val filter = SearchFilter(kinds, day("date_from", false), day("date_to", true), only)
        if (query.isEmpty() && !filter.active) return@runBlocking "Error: give a query, or kinds / date_from / date_to / person to list files."
        val all = search.search(query, filter, offset + limit).hits
        val result = all.drop(offset).take(limit)
        val summaries = dao.summaries(result.map { it.itemId }).associateBy { it.itemId }
        val texts = dao.segmentTexts(result.mapNotNull { it.best?.segId }).associate { it.segId to it.text }
        val out = JSONArray()
        for (h in result) {
            val s = summaries[h.itemId] ?: continue
            val item = dao.getItem(h.itemId)
            val place = item?.place?.takeIf { it.isNotBlank() }?.split(' ')?.take(2)?.joinToString(" ")
            out.put(
                JSONObject().put("item_id", h.itemId).put("name", s.displayName).put("kind", kindName(s.kind))
                    .also { o ->
                        h.best?.page?.let { o.put("page", it) }
                        h.best?.startMs?.let { o.put("time", formatTime(it)) }
                        dateOf(s, item?.dateTaken ?: item?.modifiedAt)?.let { o.put("date", it) }
                        place?.let { o.put("place", it) }
                        (h.snippet ?: h.best?.segId?.let(texts::get))?.replace(Regex("\\s+"), " ")?.take(160)?.let { o.put("snippet", it) }
                    },
            )
        }
        if (out.length() == 0) "No matching files." else out.toString()
    }
}

/** Reads the indexed text of one file (pages, transcript lines, OCR text) in slices. */
class ReadFileTool(private val dao: ItemDao) : IndexTool {
    override val name = ToolNames.READ
    override val description =
        "Read the indexed text of a file found with search_index: document text (optionally one page), a transcript of audio or video " +
            "(optionally a time range) or the text recognized in an image. Long files are read in slices; continue with next_offset."
    override val parameters = """{"type":"object","properties":{
        "item_id":{"type":"integer","description":"The item_id from search_index."},
        "page":{"type":"integer","description":"Only this page (documents)."},
        "from_sec":{"type":"integer","description":"Start of a time range in seconds (audio and video)."},
        "to_sec":{"type":"integer","description":"End of a time range in seconds (audio and video)."},
        "offset":{"type":"integer","description":"Character offset to continue from (default 0)."},
        "max_chars":{"type":"integer","description":"Slice length, up to 4000 (default 2000)."}},
        "required":["item_id"]}"""

    override fun execute(args: JSONObject): String = runBlocking {
        val id = args.optLong("item_id", -1)
        if (id < 0) return@runBlocking "Error: item_id is required."
        val page = if (args.has("page")) args.optInt("page") else null
        val from = if (args.has("from_sec")) args.optLong("from_sec") * 1000 else null
        val to = if (args.has("to_sec")) args.optLong("to_sec") * 1000 else null
        val offset = args.optInt("offset", 0).coerceAtLeast(0)
        val max = args.optInt("max_chars", DEFAULT_CHARS).coerceIn(200, MAX_CHARS)
        read(dao, id, page, from, to, offset, max)?.toString() ?: "Error: no file with item_id $id."
    }

    companion object {
        const val DEFAULT_CHARS = 2000
        const val MAX_CHARS = 4000

        /** The readable text of an item, with position markers (`[p.3]`, `[01:20]`). */
        fun fullText(segments: List<SegmentEntity>, page: Int? = null, fromMs: Long? = null, toMs: Long? = null): String {
            val sb = StringBuilder()
            var lastPage: Int? = null
            for (s in segments.sortedBy { it.ordinal }) {
                val t = s.text?.takeIf { it.isNotBlank() } ?: continue
                if (s.type != SegmentType.TEXT_CHUNK && s.type != SegmentType.TRANSCRIPT && s.type != SegmentType.OCR_TEXT) continue
                if (page != null && s.page != page) continue
                if (fromMs != null && (s.endMs ?: Long.MAX_VALUE) < fromMs) continue
                if (toMs != null && (s.startMs ?: 0L) > toMs) continue
                if (s.page != null && s.page != lastPage) { sb.append("[p.").append(s.page).append("]\n"); lastPage = s.page }
                if (s.type == SegmentType.TRANSCRIPT && s.startMs != null) sb.append('[').append(formatTime(s.startMs)).append("] ")
                sb.append(t.trim()).append("\n\n")
            }
            return sb.toString().trimEnd()
        }

        suspend fun read(dao: ItemDao, id: Long, page: Int?, fromMs: Long?, toMs: Long?, offset: Int, max: Int): JSONObject? {
            val item = dao.getItem(id) ?: return null
            val text = fullText(dao.segmentsOf(id), page, fromMs, toMs)
            val end = (offset + max).coerceAtMost(text.length)
            val slice = if (offset >= text.length) "" else text.substring(offset, end)
            return JSONObject().put("item_id", id).put("name", item.displayName).put("kind", kindName(item.kind)).also { o ->
                (item.dateTaken ?: item.modifiedAt).let { o.put("date", SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(it))) }
                item.place?.takeIf { it.isNotBlank() }?.split(' ')?.take(2)?.joinToString(" ")?.let { o.put("place", it) }
                item.pageCount?.let { o.put("pages", it) }
                item.durationMs?.let { o.put("duration", formatTime(it)) }
                o.put("total_chars", text.length).put("offset", offset)
                o.put("next_offset", if (end < text.length) end else JSONObject.NULL)
                o.put("text", slice.ifEmpty { if (text.isEmpty()) "(no indexed text for this file)" else "(offset is past the end)" })
            }
        }
    }
}

/**
 * Searches inside one file: the passages of that file closest to a question, so a chat model reads only those instead
 * of the whole (possibly huge) file. Semantic when the item's embeddings match the installed model, words otherwise.
 */
class SearchInFileTool(private val search: SearchEngine, private val dao: ItemDao) : IndexTool {
    override val name = ToolNames.SEARCH_IN
    override val description =
        "Find the passages of ONE file (found with search_index) that best answer a question or match some words: returns the " +
            "best few passages with their page or time. Use it for long documents, transcripts and recognized text instead of " +
            "reading the whole file; read_file can then read around a passage."
    override val parameters = """{"type":"object","properties":{
        "item_id":{"type":"integer","description":"The item_id from search_index."},
        "query":{"type":"string","description":"What to look for, as a question or a few words."},
        "limit":{"type":"integer","description":"How many passages, 1-$MAX_HITS (default $DEFAULT_HITS)."}},
        "required":["item_id","query"]}"""

    override fun execute(args: JSONObject): String = runBlocking {
        val id = args.optLong("item_id", -1)
        val query = args.optString("query").trim()
        if (id < 0) return@runBlocking "Error: item_id is required."
        if (query.isEmpty()) return@runBlocking "Error: query is required."
        val limit = args.optInt("limit", DEFAULT_HITS).coerceIn(1, MAX_HITS)
        val item = dao.getItem(id) ?: return@runBlocking "Error: no file with item_id $id."
        val segments = dao.segmentsOf(id)
        val qv = runCatching { search.queryVector(query) }.getOrNull()?.takeIf { it.first == item.embeddingModelId }?.second
        val hits = rank(segments, qv, query, limit)
        val out = JSONArray()
        for (h in hits) out.put(JSONObject().apply {
            put("ordinal", h.segment.ordinal)
            h.segment.page?.let { put("page", it) }
            h.segment.startMs?.let { put("time", formatTime(it)) }
            put("score", "%.2f".format(Locale.US, h.score))
            put("text", clip(h.segment.text.orEmpty()))
        })
        JSONObject().put("item_id", id).put("name", item.displayName).put("kind", kindName(item.kind))
            .put("method", if (qv != null) "semantic" else "words")
            .put("total_passages", segments.count { it.isReadable() })
            .also { o -> item.pageCount?.let { o.put("pages", it) } }
            .put("passages", out)
            .also { if (hits.isEmpty()) it.put("note", "Nothing in this file matches; try other words or read_file.") }
            .toString()
    }

    data class Hit(val segment: SegmentEntity, val score: Float)

    companion object {
        const val DEFAULT_HITS = 3
        const val MAX_HITS = 5
        /** One passage as the model sees it (chunks are about 800 characters). */
        const val PASSAGE_CHARS = 500

        private fun SegmentEntity.isReadable() = !text.isNullOrBlank() &&
            (type == SegmentType.TEXT_CHUNK || type == SegmentType.TRANSCRIPT || type == SegmentType.OCR_TEXT)

        private fun clip(t: String) = t.trim().let { if (it.length > PASSAGE_CHARS) it.take(PASSAGE_CHARS) + "…" else it }

        /**
         * The best [limit] readable passages: by cosine to [qVec] when given (raw similarity: one file has no baseline to
         * judge against), else by how many query words they contain. Passages come back in score order.
         */
        fun rank(segments: List<SegmentEntity>, qVec: FloatArray?, query: String, limit: Int): List<Hit> {
            val readable = segments.filter { it.isReadable() }
            if (qVec != null) {
                val scored = readable.mapNotNull { s -> s.embedding?.takeIf { Quant.dim(it) == qVec.size }?.let { Hit(s, Quant.dot(qVec, it)) } }
                if (scored.isNotEmpty()) return scored.sortedByDescending { it.score }.take(limit)
            }
            val tokens = KeywordMatcher.tokens(query)
            if (tokens.isEmpty()) return emptyList()
            return readable.mapNotNull { s ->
                KeywordMatcher.match(s.text.orEmpty(), tokens, query)?.let { m -> Hit(s, m.count.toFloat() / tokens.size + if (m.exact) 1f else 0f) }
            }.sortedByDescending { it.score }.take(limit)
        }
    }
}

/** Kind filter names (document, image, audio, video, and synonyms) to item kinds. */
fun parseKinds(s: String): Set<ItemKind> = s.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.flatMap { k ->
    when (k) {
        "document", "documents", "text" -> listOf(ItemKind.TEXT, ItemKind.DOC)
        "doc" -> listOf(ItemKind.DOC)
        "image", "images", "photo", "photos" -> listOf(ItemKind.IMAGE)
        "audio" -> listOf(ItemKind.AUDIO)
        "video", "videos" -> listOf(ItemKind.VIDEO)
        else -> runCatching { listOf(ItemKind.valueOf(k.uppercase())) }.getOrDefault(emptyList())
    }
}.toSet()
