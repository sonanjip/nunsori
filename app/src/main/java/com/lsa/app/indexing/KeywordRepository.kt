package com.lsa.app.indexing

import android.util.Log
import com.lsa.app.data.db.KeywordDao
import com.lsa.app.data.db.UserKeywordEntity
import com.lsa.app.ml.ModelHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * The user's own search keywords ("My keywords"). They can be added to any file, indexed or not. A finished item
 * gets text-only keyword segments at once (keyword search works immediately); their vectors are filled in right
 * after by a short model pass, or by the next indexing run when no run can take the models now.
 */
class KeywordRepository(
    private val dao: KeywordDao,
    private val runner: IndexRunner,
    private val indexing: IndexingController,
    private val models: ModelHost,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun observe(sourceKey: String): Flow<List<UserKeywordEntity>> = dao.observe(sourceKey)

    /** Files that have keywords. */
    val keys: Flow<List<String>> = dao.observeKeys()

    /** Adds what the user typed (comma separated); returns how many keywords were new. */
    suspend fun add(sourceKey: String, input: String): Int {
        val existing = dao.of(sourceKey).map { it.text }
        val words = Keywords.parse(input, existing)
        if (words.isEmpty()) return 0
        val now = clock()
        dao.insert(words.mapIndexed { i, w -> UserKeywordEntity(sourceKey = sourceKey, text = w, createdAt = now + i) })
        changed(sourceKey)
        return words.size
    }

    suspend fun remove(sourceKey: String, text: String) {
        dao.delete(sourceKey, text)
        changed(sourceKey)
    }

    private suspend fun changed(sourceKey: String) {
        runner.syncKeywords(onlyKey = sourceKey)
        embedSoon()
    }

    /** Embeds pending keywords now unless an indexing run holds the models (that run does it when it ends). */
    fun embedSoon() {
        scope.launch {
            if (!indexing.runLock.tryLock()) return@launch
            try { embedPendingLocked(background = false) } finally { indexing.runLock.unlock() }
        }
    }

    /** Call with [IndexingController.runLock] held. Loads the embedding model only when there is work. */
    suspend fun embedPendingLocked(background: Boolean) {
        try {
            if (!runner.keywordWorkPending()) return
            val lease = models.embedder(background = background) ?: return
            lease.use { l -> runner.syncKeywords(embed = { text, title -> l.value.embedDocument(text, title) }) }
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (e: Exception) {
            Log.w("LSA.Keywords", "keyword embedding failed", e)
        }
    }
}
