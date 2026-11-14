package com.lsa.app.indexing

import com.lsa.app.data.db.ItemDao
import com.lsa.app.data.db.KeywordDao
import com.lsa.app.ml.embedding.Quant
import com.lsa.app.indexing.places.PlaceFinder
import com.lsa.app.data.db.ItemEntity
import com.lsa.app.data.db.ItemKind
import com.lsa.app.data.db.ItemState
import com.lsa.app.data.db.ItemStatus
import com.lsa.app.data.db.SegmentEntity
import com.lsa.app.data.db.SegmentType
import com.lsa.app.data.media.FolderSpec
import com.lsa.app.data.media.ItemSource
import com.lsa.app.data.media.SourceEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class IndexProgress(
    val total: Int = 0,
    val done: Int = 0,
    val failed: Int = 0,
    val current: String? = null,
    val error: IndexError? = null,
    val finished: Boolean = false,
    /** Stopped early by the thermal/stop gate; remaining work is picked up by the next run. */
    val stoppedEarly: Boolean = false,
)

enum class IndexError { MODEL_MISSING, NO_FOLDERS, LOAD_FAILED }

/** A segment as produced by an extractor, before it is bound to an item row. */
@Suppress("ArrayInDataClass")
data class NewSegment(
    val type: SegmentType,
    val ordinal: Int,
    val startMs: Long? = null,
    val endMs: Long? = null,
    val page: Int? = null,
    val text: String? = null,
    val embedding: ByteArray? = null,
    val thumbPath: String? = null,
)

/** Where an extractor writes its output. Everything passed in is committed immediately. */
interface ItemSink {
    suspend fun meta(
        title: String? = null, latitude: Double? = null, longitude: Double? = null,
        durationMs: Long? = null, pageCount: Int? = null,
    )

    /** Persists [segments] and records [progress] so an interrupted run can continue from there. */
    suspend fun segments(segments: List<NewSegment>, progress: Long)
}

/** Thrown by an extractor to mark a file as deliberately not indexed (unsupported, protected, ...). */
class SkipItem(val reason: String) : Exception(reason)

/** Turns one file into segments using the loaded models. */
interface IndexSession {
    val embeddingModelId: String

    /** [resume] is the `progress` stored by an interrupted run of the same pipeline (0 = start over). */
    suspend fun process(entry: SourceEntry, resume: Long, sink: ItemSink)

    /** Embeds one of the user's keywords; null leaves it text-only until [IndexRunner.syncKeywords] fills it in. */
    suspend fun embedKeyword(text: String, title: String?): FloatArray? = null
}

/** A file that still has to be processed, plus what is already stored for it. */
data class TodoItem(val entry: SourceEntry, val existing: ItemState?)

data class IndexPlan(val total: Int, val done: Int, val todo: List<TodoItem>)

/**
 * Incremental, resumable indexing.
 *
 * [sync] needs no model: it removes rows for files that disappeared, records moves/renames and works out what has to
 * be (re)processed. [process] does the expensive part; every batch an extractor emits is committed immediately, so
 * cancelling loses at most the batch in flight.
 */
class IndexRunner(
    private val dao: ItemDao,
    private val source: ItemSource,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Turns GPS positions into searchable place names; null disables it. */
    private val places: PlaceFinder? = null,
    /** The user's own keywords; null (tests of other things) disables them. */
    private val keywords: KeywordDao? = null,
    /** Whether a file that left the listing still exists (folder turned off vs. deleted); keywords of deleted files go. */
    private val fileExists: suspend (sourceKey: String) -> Boolean = { false },
) {
    /**
     * @param kinds kinds that were looked at; rows of other kinds are left alone.
     * @param embeddingModelId stored with new rows.
     * @param pipelineKey what a finished item of that kind must have been produced with.
     */
    suspend fun sync(
        folders: List<FolderSpec>,
        kinds: Set<ItemKind>,
        pipelineKey: (ItemKind) -> String,
    ): IndexPlan {
        backfillPlaces()
        val entries = source.list(folders, kinds).filter { it.kind in kinds }
        val states = dao.states().filter { it.kind in kinds }.associateBy { it.sourceKey }

        // An empty listing while rows exist usually means lost permission / unmounted storage, not "all deleted".
        if (entries.isEmpty() && states.isNotEmpty()) return IndexPlan(0, 0, emptyList())

        val present = entries.mapTo(HashSet()) { it.sourceKey }
        states.values.filter { it.sourceKey !in present }.map { it.itemId }.chunked(500).forEach { dao.deleteItems(it) }
        followKeywords(entries, present, states)

        val todo = ArrayList<TodoItem>()
        for (e in entries) {
            val s = states[e.sourceKey]
            val unchanged = s != null && s.modifiedAt == e.modifiedAt && s.sizeBytes == e.sizeBytes &&
                s.pipelineKey == pipelineKey(e.kind) && s.status != ItemStatus.IN_PROGRESS &&
                s.status != ItemStatus.FAILED
            when {
                s == null || !unchanged -> todo += TodoItem(e, s)
                s.folderKey != e.folderKey || s.displayName != e.displayName ->
                    dao.updateLocation(s.itemId, e.folderKey, e.displayName)
            }
        }
        todo.sortByDescending { it.entry.dateTaken ?: it.entry.modifiedAt }
        return IndexPlan(total = entries.size, done = entries.size - todo.size, todo = todo)
    }

    /** [gate] is checked before every file; returning false stops early (thermal throttling, user pause). */
    suspend fun process(
        plan: IndexPlan,
        session: IndexSession,
        pipelineKey: (ItemKind) -> String,
        gate: suspend () -> Boolean = { true },
        onProgress: suspend (IndexProgress) -> Unit,
    ): IndexProgress {
        var progress = IndexProgress(total = plan.total, done = plan.done)
        onProgress(progress)
        for (todo in plan.todo) {
            currentCoroutineContext().ensureActive()
            if (!gate()) {
                progress = progress.copy(current = null, stoppedEarly = true)
                onProgress(progress)
                return progress
            }
            val entry = todo.entry
            progress = progress.copy(current = entry.displayName)
            onProgress(progress)
            val ok = processOne(todo, session, pipelineKey(entry.kind))
            progress = if (ok) progress.copy(done = progress.done + 1) else progress.copy(failed = progress.failed + 1)
            onProgress(progress)
        }
        progress = progress.copy(current = null, finished = true)
        onProgress(progress)
        return progress
    }

    private suspend fun processOne(todo: TodoItem, session: IndexSession, key: String): Boolean {
        val e = todo.entry
        val old = todo.existing
        // Only an interrupted run of the very same file + pipeline that already committed something can resume.
        val resumable = old != null && old.status == ItemStatus.IN_PROGRESS && old.pipelineKey == key &&
            old.modifiedAt == e.modifiedAt && old.sizeBytes == e.sizeBytes && old.progress > 0
        val itemId: Long
        val resume: Long
        if (resumable) {
            itemId = old!!.itemId; resume = old.progress
        } else {
            old?.let { dao.deleteItems(listOf(it.itemId)) }
            itemId = dao.insertItem(newItem(e, session.embeddingModelId, key))
            resume = 0
        }
        val sink = DbSink(itemId)
        return try {
            session.process(e, resume, sink)
            addKeywordSegments(itemId, e, session)
            dao.setStatus(itemId, ItemStatus.DONE, null, clock())
            true
        } catch (c: CancellationException) {
            throw c // stays IN_PROGRESS; whatever was committed is kept for the next run
        } catch (s: SkipItem) {
            dao.deleteSegmentsOf(itemId)
            dao.setStatus(itemId, ItemStatus.SKIPPED, s.reason, clock())
            true
        } catch (t: Exception) {
            dao.deleteSegmentsOf(itemId)
            dao.setStatus(itemId, ItemStatus.FAILED, t.message ?: t.javaClass.simpleName, clock())
            false
        }
    }

    /** The user's keywords for a freshly processed file, embedded with the same model as the rest of it. */
    private suspend fun addKeywordSegments(itemId: Long, e: SourceEntry, session: IndexSession) {
        val words = keywords?.of(e.sourceKey)?.map { it.text } ?: return
        if (words.isEmpty()) return
        val segs = words.mapIndexed { i, w ->
            val vec = try { session.embedKeyword(w, e.displayName) } catch (c: CancellationException) { throw c } catch (_: Exception) { null }
            SegmentEntity(
                itemId = itemId, type = SegmentType.USER_KEYWORD, ordinal = Keywords.ORDINAL_BASE + i, startMs = null, endMs = null,
                page = null, text = w, embedding = vec?.let(Quant::encode), thumbPath = null,
            )
        }
        dao.insertSegments(segs)
    }

    /**
     * Keywords of files that left the listing: a plain file that moved (same kind, size and time under a new path,
     * found once) takes them along; those of deleted files are dropped; those of files that are only out of view
     * (folder or kind turned off) are kept for when they come back.
     */
    private suspend fun followKeywords(entries: List<SourceEntry>, present: Set<String>, states: Map<String, ItemState>) {
        val kw = keywords ?: return
        val missing = kw.keys().filter { it !in present }
        if (missing.isEmpty()) return
        val newcomers = entries.filter { it.sourceKey !in states && it.sourceKey.startsWith("fs:") }
            .groupBy { Triple(it.kind, it.sizeBytes, it.modifiedAt) }
        val gone = ArrayList<String>()
        for (key in missing) {
            if (fileExists(key)) continue
            val old = states[key]
            val moved = if (old != null && key.startsWith("fs:")) newcomers[Triple(old.kind, old.sizeBytes, old.modifiedAt)]?.singleOrNull() else null
            if (moved != null) kw.rekey(key, moved.sourceKey) else gone += key
        }
        gone.chunked(500).forEach { kw.deleteFor(it) }
    }

    /**
     * Brings the [SegmentType.USER_KEYWORD] segments of finished items in line with the keyword table: removes
     * deleted keywords, adds new ones and embeds the ones still without a vector (when [embed] is given).
     * [onlyKey] limits the work to one file. Returns how many items changed.
     */
    suspend fun syncKeywords(onlyKey: String? = null, embed: (suspend (text: String, title: String) -> FloatArray?)? = null): Int {
        val kw = keywords ?: return 0
        val wanted = (if (onlyKey != null) kw.of(onlyKey) else kw.all()).groupBy({ it.sourceKey }, { it.text })
        val segs = dao.keywordSegments().filter { onlyKey == null || it.sourceKey == onlyKey }
        val changed = HashSet<Long>()
        val stale = segs.filter { it.text !in wanted[it.sourceKey].orEmpty() }
        stale.map { it.segId }.chunked(500).forEach { dao.deleteSegments(it) }
        stale.mapTo(changed) { it.itemId }
        val have = segs.filter { it !in stale }.groupBy({ it.itemId }, { it.text })
        val items = wanted.keys.toList().chunked(500).flatMap { dao.doneItemsFor(it) }
        for (item in items) {
            val texts = have[item.itemId].orEmpty()
            val add = wanted[item.sourceKey].orEmpty().filter { it !in texts }
            if (add.isEmpty()) continue
            val base = Keywords.ORDINAL_BASE + texts.size
            dao.insertSegments(
                add.mapIndexed { i, w ->
                    SegmentEntity(
                        itemId = item.itemId, type = SegmentType.USER_KEYWORD, ordinal = base + i, startMs = null, endMs = null,
                        page = null, text = w, embedding = embed?.let { e -> safeEmbed(e, w, item.displayName) }?.let(Quant::encode), thumbPath = null,
                    )
                },
            )
            changed += item.itemId
        }
        if (embed != null) {
            for (s in dao.keywordSegments()) {
                if (s.embedded || s.text == null || (onlyKey != null && s.sourceKey != onlyKey)) continue
                currentCoroutineContext().ensureActive()
                val vec = safeEmbed(embed, s.text, s.displayName) ?: continue
                dao.setSegmentEmbedding(s.segId, Quant.encode(vec))
                changed += s.itemId
            }
        }
        val now = clock()
        changed.forEach { dao.touch(it, now) }
        return changed.size
    }

    /** Whether [syncKeywords] with an embedder has anything to do (cheap; checked before loading a model). */
    suspend fun keywordWorkPending(): Boolean {
        val kw = keywords ?: return false
        val wanted = kw.all().groupBy({ it.sourceKey }, { it.text })
        if (wanted.isEmpty()) return dao.keywordSegments().isNotEmpty()
        val segs = dao.keywordSegments()
        if (segs.any { !it.embedded || it.text !in wanted[it.sourceKey].orEmpty() }) return true
        val have = segs.groupBy({ it.sourceKey }, { it.text })
        return wanted.keys.toList().chunked(500).flatMap { dao.doneItemsFor(it) }
            .any { item -> wanted[item.sourceKey].orEmpty().any { it !in have[item.sourceKey].orEmpty() } }
    }

    private suspend fun safeEmbed(embed: suspend (String, String) -> FloatArray?, text: String, title: String): FloatArray? =
        try { embed(text, title) } catch (c: CancellationException) { throw c } catch (_: Exception) { null }

    /** Fills `place` for rows that have a position but no name yet (older rows, or places added after indexing). Needs no model. */
    private suspend fun backfillPlaces() {
        val finder = places ?: return
        for (g in dao.itemsNeedingPlace()) dao.updatePlace(g.itemId, finder.find(g.lat, g.lon) ?: "")
    }

    private fun newItem(e: SourceEntry, modelId: String, key: String) = ItemEntity(
        sourceKey = e.sourceKey, kind = e.kind, uri = e.uri.toString(), folderKey = e.folderKey,
        displayName = e.displayName, mimeType = e.mimeType, sizeBytes = e.sizeBytes, modifiedAt = e.modifiedAt,
        title = null, durationMs = e.durationMs, pageCount = null, width = e.width, height = e.height,
        dateTaken = e.dateTaken, latitude = null, longitude = null, status = ItemStatus.IN_PROGRESS, error = null,
        embeddingModelId = modelId, pipelineKey = key, progress = 0, indexedAt = clock(),
    )

    private inner class DbSink(private val itemId: Long) : ItemSink {
        override suspend fun meta(title: String?, latitude: Double?, longitude: Double?, durationMs: Long?, pageCount: Int?) =
            dao.updateMeta(itemId, title, latitude, longitude, durationMs, pageCount, place = if (latitude != null && longitude != null) places?.find(latitude, longitude) ?: "" else null)

        override suspend fun segments(segments: List<NewSegment>, progress: Long) {
            dao.insertSegments(
                segments.map {
                    SegmentEntity(
                        itemId = itemId, type = it.type, ordinal = it.ordinal, startMs = it.startMs, endMs = it.endMs,
                        page = it.page, text = it.text, embedding = it.embedding, thumbPath = it.thumbPath,
                    )
                },
            )
            dao.updateProgress(itemId, progress)
        }
    }
}
