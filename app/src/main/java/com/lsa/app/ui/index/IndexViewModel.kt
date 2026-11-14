package com.lsa.app.ui.index

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lsa.app.AppContainer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.lsa.app.data.db.ItemSummary
import com.lsa.app.data.db.ItemStatus
import com.lsa.app.indexing.PendingFile
import com.lsa.app.indexing.PendingReason
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalCoroutinesApi::class)
class IndexViewModel(private val c: AppContainer) : ViewModel() {
    private val dao = c.db.itemDao()
    private fun <T> kotlinx.coroutines.flow.Flow<T>.state(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initial)

    val summaries: StateFlow<List<ItemSummary>> = dao.observeSummaries().state(emptyList())
    /** Failed files, counted from the database (not just the current run). */
    val failedCount = summaries.map { l -> l.count { it.status == ItemStatus.FAILED } }.state(0)
    val progress = c.indexing.progress
    /** The face pass that follows indexing (People); shown so the run does not look finished while it works. */
    val faceProgress = c.faces.progress
    val running = c.indexing.running.state(false)
    val enabledFolderCount = dao.observeFolders().map { f -> f.count { it.enabled } }.state(0)
    val modelReady = c.models.state.map { it.status == com.lsa.app.ml.HostState.Status.UNKNOWN || it.ready(io.github.taehunkim.ondolsaem.sdk.OndolSaem.Categories.EMBEDDING) }.state(true)
    /** Why the models are unavailable (OndolSaem missing, not allowed, ...). */
    val host = c.models.state
    fun refreshHost() { viewModelScope.launch { c.models.refresh() } }
    val staleCount = c.settings.embeddingModelId.flatMapLatest { dao.observeStaleCount(it) }.state(0)

    /** Files that have "My keywords" (marked in the grid). */
    val keywordKeys: StateFlow<Set<String>> = c.keywords.keys.map { it.toHashSet() }.state(emptySet())

    /**
     * The "Waiting" tab: the last work list, minus files finished since, plus index rows that failed, were skipped or
     * are unfinished but not on it.
     */
    val pending: StateFlow<List<PendingFile>> = combine(c.indexing.pending, summaries) { p, all ->
        val byKey = all.associateBy { it.sourceKey }
        val listed = p.files.filter { f ->
            val s = byKey[f.sourceKey]
            !(s != null && s.status == ItemStatus.DONE && s.indexedAt >= p.at)
        }
        val keys = listed.mapTo(HashSet()) { it.sourceKey }
        listed + all.filter { it.status != ItemStatus.DONE && it.sourceKey !in keys }.map { s ->
            PendingFile(
                sourceKey = s.sourceKey, kind = s.kind, uri = s.uri, displayName = s.displayName, folderKey = s.folderKey,
                sizeBytes = s.sizeBytes, modifiedAt = 0, itemId = s.itemId,
                reason = when (s.status) {
                    ItemStatus.FAILED -> PendingReason.FAILED
                    ItemStatus.SKIPPED -> PendingReason.SKIPPED
                    else -> PendingReason.RESUME
                },
            )
        }
    }.state(emptyList())
    val pendingLoaded = c.indexing.pending.map { it.loaded }.state(false)

    /** The "Indexed" tab: finished and up to date (not waiting to be redone). */
    val indexed: StateFlow<List<ItemSummary>> = combine(summaries, pending) { all, waiting ->
        val keys = waiting.mapTo(HashSet()) { it.sourceKey }
        all.filter { it.status == ItemStatus.DONE && it.sourceKey !in keys }
    }.state(emptyList())

    init {
        // Recompute the waiting list when the screen opens, when what is indexed changes and after every run.
        viewModelScope.launch {
            combine(dao.observeFolders(), c.settings.enabledKinds, c.settings.embeddingModelId) { a, b, m -> Triple(a, b, m) }
                .collectLatest { refreshPending() }
        }
        viewModelScope.launch {
            progress.map { it.finished }.distinctUntilChanged().collectLatest { if (it) refreshPending() }
        }
    }

    fun refreshPending() { viewModelScope.launch { runCatching { c.indexing.refreshPending() } } }

    /** Runs only the failed files (a normal run also retries them, together with everything still pending). */
    fun retryFailed() = c.indexing.retryFailed()
    fun start() = c.indexing.start()
    fun pause() = c.indexing.pause()
}
