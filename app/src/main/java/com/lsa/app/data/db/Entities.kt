package com.lsa.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class ItemKind { IMAGE, TEXT, DOC, AUDIO, VIDEO }

enum class ItemStatus {
    /** Queued or interrupted; [ItemEntity.progress] says where to resume. */
    IN_PROGRESS,
    DONE,
    FAILED,
    /** Deliberately not indexed (unsupported, protected, too large). */
    SKIPPED,
}

/** What a [SegmentEntity] holds; decides how it is searched and displayed. */
enum class SegmentType {
    IMAGE, OCR_TEXT, TEXT_CHUNK, PAGE_IMAGE, CARD_IMAGE, TRANSCRIPT, AUDIO_CLIP, VIDEO_CLIP,

    /** One of the user's own keywords ("My keywords"), derived from [UserKeywordEntity]; rebuilt whenever the item is. */
    USER_KEYWORD;

    /** Signal group used when scoring search results. */
    val group: SignalGroup
        get() = when (this) {
            IMAGE, PAGE_IMAGE, CARD_IMAGE, VIDEO_CLIP -> SignalGroup.VISUAL
            AUDIO_CLIP -> SignalGroup.AUDIO
            OCR_TEXT, TEXT_CHUNK, TRANSCRIPT, USER_KEYWORD -> SignalGroup.TEXT
        }
}

enum class SignalGroup { VISUAL, TEXT, AUDIO }

/** One indexed file. */
@Entity(
    tableName = "items",
    indices = [Index(value = ["sourceKey"], unique = true), Index("kind"), Index("folderKey")],
)
data class ItemEntity(
    @PrimaryKey(autoGenerate = true) val itemId: Long = 0,
    /** Stable identity of the file: `ms:image:123` for MediaStore, `fs:<absolute path>` for plain files. */
    val sourceKey: String,
    val kind: ItemKind,
    val uri: String,
    /** Volume-relative folder, same namespace as MediaStore RELATIVE_PATH (e.g. `Pictures/fixtures/`). */
    val folderKey: String,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long,
    /** Milliseconds; used for change detection together with [sizeBytes]. */
    val modifiedAt: Long,
    val title: String?,
    val durationMs: Long?,
    val pageCount: Int?,
    val width: Int,
    val height: Int,
    val dateTaken: Long?,
    val latitude: Double?,
    val longitude: Double?,
    val status: ItemStatus,
    val error: String?,
    /** Embedding model the vectors belong to; search only uses vectors of the selected model. */
    val embeddingModelId: String,
    /** Everything that affects this kind's output (models, chunker version, ...). A change means reprocess. */
    val pipelineKey: String,
    /** Resume position while [ItemStatus.IN_PROGRESS] (page index, milliseconds, ...). */
    val progress: Long,
    val indexedAt: Long,
    /** Searchable place names (city and country in English and Korean) derived from [latitude]/[longitude]; null when unknown. */
    val place: String? = null,
    /** Face model key the faces of this photo were found with (`<face model id>|f<version>`); null = not scanned. */
    val faceKey: String? = null,
)

/** One searchable unit of an item: a chunk of text, a page, an audio window, a video clip, ... */
@Entity(
    tableName = "segments",
    foreignKeys = [ForeignKey(ItemEntity::class, parentColumns = ["itemId"], childColumns = ["itemId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("itemId"), Index("type")],
)
data class SegmentEntity(
    @PrimaryKey(autoGenerate = true) val segId: Long = 0,
    val itemId: Long,
    val type: SegmentType,
    val ordinal: Int,
    val startMs: Long?,
    val endMs: Long?,
    val page: Int?,
    val text: String?,
    /** int8-quantised vector, see [com.lsa.app.ml.embedding.Quant]. */
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val embedding: ByteArray?,
    /** Preview image file (relative to the app's thumbs directory) for non-image items. */
    val thumbPath: String?,
)

@Entity(tableName = "indexed_folders")
data class IndexedFolderEntity(
    @PrimaryKey val relativePath: String,
    val enabled: Boolean = true,
    @ColumnInfo(defaultValue = "1") val recursive: Boolean = true,
)

/** Lightweight projection for lists (no vectors, no text). */
data class ItemSummary(
    val itemId: Long,
    val kind: ItemKind,
    val uri: String,
    val displayName: String,
    val folderKey: String,
    val title: String?,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long?,
    val pageCount: Int?,
    val status: ItemStatus,
    val indexedAt: Long,
    val thumbPath: String?,
    /** Default only for hand-built summaries; Room always fills it from the row. */
    val sourceKey: String = "",
)

/** An item id with the time its indexing finished. */
data class ItemStamp(val itemId: Long, val indexedAt: Long)

/** What sync needs to decide whether a file must be (re)processed. */
data class ItemState(
    val itemId: Long,
    val sourceKey: String,
    val kind: ItemKind,
    val modifiedAt: Long,
    val sizeBytes: Long,
    val status: ItemStatus,
    val pipelineKey: String,
    val progress: Long,
    val folderKey: String,
    val displayName: String,
)

/** A vector plus where it came from, for the in-memory search cache. */
@Suppress("ArrayInDataClass")
data class VectorRow(
    val segId: Long,
    val itemId: Long,
    val type: SegmentType,
    val startMs: Long?,
    val endMs: Long?,
    val page: Int?,
    val embedding: ByteArray,
)

/** Text of a segment, for keyword matching. */
data class TextRow(
    val segId: Long,
    val itemId: Long,
    val type: SegmentType,
    val startMs: Long?,
    val endMs: Long?,
    val page: Int?,
    val text: String,
)

data class MetaRow(val itemId: Long, val displayName: String, val title: String?, val place: String?)

data class GeoPoint(val itemId: Long, val lat: Double, val lon: Double)

data class SegText(val segId: Long, val text: String)

/**
 * A search keyword the user added to a file ("My keywords"). Keyed by [sourceKey], not by item id: item rows are
 * replaced on every reprocess and do not exist yet for files that were never indexed. The searchable copies are
 * [SegmentType.USER_KEYWORD] segments, rebuilt from this table.
 */
@Entity(
    tableName = "user_keywords",
    indices = [Index(value = ["sourceKey", "text"], unique = true)],
)
data class UserKeywordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceKey: String,
    val text: String,
    val createdAt: Long,
)

/** A [SegmentType.USER_KEYWORD] segment with the item it belongs to. */
data class KeywordSegRow(val segId: Long, val itemId: Long, val sourceKey: String, val displayName: String, val text: String?, val embedded: Boolean)

data class KeywordItemRow(val itemId: Long, val sourceKey: String, val displayName: String)
