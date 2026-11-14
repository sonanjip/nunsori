package com.lsa.app.indexing

import com.lsa.app.data.db.ItemKind
import com.lsa.app.data.db.ItemState
import com.lsa.app.data.db.ItemStatus

/** Why a file is on the index screen's "Waiting" tab. */
enum class PendingReason { NEW, CHANGED, MODEL, RESUME, FAILED, SKIPPED }

/** A file that is not (or not currently) searchable as it is now. [itemId] is set when an index row exists. */
data class PendingFile(
    val sourceKey: String,
    val kind: ItemKind,
    val uri: String,
    val displayName: String,
    val folderKey: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val reason: PendingReason,
    val itemId: Long? = null,
)

/** The last known work list; [at] is when it was taken (files finished after that are no longer waiting). */
data class PendingState(val files: List<PendingFile> = emptyList(), val at: Long = 0, val loaded: Boolean = false)

object PendingReasons {
    /** The reason [IndexRunner.sync] put a file (now [modifiedAt], [sizeBytes]) on the work list, given what is stored for it. */
    fun of(modifiedAt: Long, sizeBytes: Long, existing: ItemState?, pipelineKey: String): PendingReason = when {
        existing == null -> PendingReason.NEW
        existing.status == ItemStatus.FAILED -> PendingReason.FAILED
        existing.modifiedAt != modifiedAt || existing.sizeBytes != sizeBytes -> PendingReason.CHANGED
        existing.status == ItemStatus.IN_PROGRESS -> PendingReason.RESUME
        existing.pipelineKey != pipelineKey -> PendingReason.MODEL
        else -> PendingReason.NEW
    }

    fun files(plan: IndexPlan, pipelineKey: (ItemKind) -> String): List<PendingFile> = plan.todo.map { t ->
        val e = t.entry
        PendingFile(
            sourceKey = e.sourceKey, kind = e.kind, uri = e.uri.toString(), displayName = e.displayName, folderKey = e.folderKey,
            sizeBytes = e.sizeBytes, modifiedAt = e.modifiedAt, reason = of(e.modifiedAt, e.sizeBytes, t.existing, pipelineKey(e.kind)), itemId = t.existing?.itemId,
        )
    }
}
