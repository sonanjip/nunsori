package com.lsa.app.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

private const val SUMMARY_COLS = """
    i.itemId, i.kind, i.uri, i.displayName, i.folderKey, i.title, i.sizeBytes, i.width, i.height, i.durationMs,
    i.pageCount, i.status, i.indexedAt, i.sourceKey,
    (SELECT s.thumbPath FROM segments s WHERE s.itemId = i.itemId AND s.thumbPath IS NOT NULL ORDER BY s.ordinal LIMIT 1) AS thumbPath"""

@Dao
interface ItemDao {
    // ---- items
    @Insert
    suspend fun insertItem(item: ItemEntity): Long

    @Query("DELETE FROM items WHERE itemId IN (:ids)")
    suspend fun deleteItems(ids: List<Long>)

    @Query("DELETE FROM items WHERE folderKey = :folder")
    suspend fun deleteItemsInFolder(folder: String)

    /** [pattern] is a LIKE pattern using `\` as escape, e.g. `Documents/%`. */
    @Query("DELETE FROM items WHERE folderKey LIKE :pattern ESCAPE '\\'")
    suspend fun deleteItemsLike(pattern: String)

    @Query("DELETE FROM items")
    suspend fun deleteAllItems()

    @Query("SELECT itemId, sourceKey, kind, modifiedAt, sizeBytes, status, pipelineKey, progress, folderKey, displayName FROM items")
    suspend fun states(): List<ItemState>

    @Query("UPDATE items SET folderKey = :folderKey, displayName = :displayName WHERE itemId = :id")
    suspend fun updateLocation(id: Long, folderKey: String, displayName: String)

    /** A plain file moved to another path: its identity (sourceKey) changes, its index data stays. */
    @Query("UPDATE items SET sourceKey = :sourceKey, uri = :uri, folderKey = :folderKey, displayName = :displayName WHERE itemId = :id")
    suspend fun relocate(id: Long, sourceKey: String, uri: String, folderKey: String, displayName: String)

    @Query("UPDATE items SET progress = :progress WHERE itemId = :id")
    suspend fun updateProgress(id: Long, progress: Long)

    @Query(
        """UPDATE items SET title = COALESCE(:title, title), latitude = COALESCE(:lat, latitude),
           longitude = COALESCE(:lon, longitude), durationMs = COALESCE(:durationMs, durationMs),
           pageCount = COALESCE(:pageCount, pageCount), place = COALESCE(:place, place) WHERE itemId = :id""",
    )
    suspend fun updateMeta(id: Long, title: String?, lat: Double?, lon: Double?, durationMs: Long?, pageCount: Int?, place: String?)

    /** Items with a GPS position whose place name was never looked up (place IS NULL; "" means looked up, nothing near). */
    @Query("SELECT itemId, latitude AS lat, longitude AS lon FROM items WHERE latitude IS NOT NULL AND longitude IS NOT NULL AND place IS NULL")
    suspend fun itemsNeedingPlace(): List<GeoPoint>

    /** Forget all place names so the next sync looks them up again (after the places data changed). */
    @Query("UPDATE items SET place = NULL")
    suspend fun resetPlaces()

    @Query("UPDATE items SET place = :place WHERE itemId = :id")
    suspend fun updatePlace(id: Long, place: String)

    @Query("UPDATE items SET status = :status, error = :error, indexedAt = :at WHERE itemId = :id")
    suspend fun setStatus(id: Long, status: ItemStatus, error: String?, at: Long)

    @Query("SELECT * FROM items WHERE itemId = :id")
    suspend fun getItem(id: Long): ItemEntity?

    @Query("SELECT i.* FROM items i WHERE i.sourceKey = :key")
    suspend fun getItemBySourceKey(key: String): ItemEntity?

    @Query("SELECT $SUMMARY_COLS FROM items i ORDER BY i.indexedAt DESC")
    fun observeSummaries(): Flow<List<ItemSummary>>

    @Query("SELECT $SUMMARY_COLS FROM items i WHERE i.itemId IN (:ids)")
    suspend fun summaries(ids: List<Long>): List<ItemSummary>

    /** Finished items; all of them are re-embedded when the embedding model changes. */
    @Query("SELECT COUNT(*) FROM items WHERE status = 'DONE'")
    suspend fun doneCount(): Int

    @Query("SELECT COUNT(*) FROM items WHERE status = 'DONE' AND embeddingModelId != :modelId")
    fun observeStaleCount(modelId: String): Flow<Int>

    /** Other apps' "what is new" (service/IndexApi.changesSince): finished after [after], oldest first. */
    @Query("SELECT itemId, indexedAt FROM items WHERE status = 'DONE' AND indexedAt > :after AND kind IN (:kinds) ORDER BY indexedAt, itemId LIMIT :limit")
    suspend fun doneAfter(after: Long, kinds: List<ItemKind>, limit: Int): List<ItemStamp>

    @Query("SELECT COALESCE(MAX(indexedAt), 0) FROM items WHERE status = 'DONE'")
    suspend fun lastIndexedAt(): Long

    /** Cheap fingerprint of table contents, used to invalidate the in-memory search cache. */
    @Query("SELECT COUNT(*) || ':' || COALESCE(MAX(indexedAt), 0) || ':' || (SELECT COUNT(*) FROM segments) FROM items")
    suspend fun stamp(): String

    // ---- segments
    @Insert
    suspend fun insertSegments(segments: List<SegmentEntity>)

    @Query("SELECT thumbPath FROM segments WHERE thumbPath IS NOT NULL")
    suspend fun thumbPaths(): List<String>

    @Query("UPDATE segments SET thumbPath = NULL")
    suspend fun clearThumbPaths()

    @Query("SELECT thumbPath FROM segments WHERE itemId = :itemId AND type = 'VIDEO_CLIP' AND startMs <= :ms AND endMs > :ms LIMIT 1")
    suspend fun clipThumbAt(itemId: Long, ms: Long): String?

    @Query("SELECT thumbPath FROM segments WHERE segId = :segId")
    suspend fun thumbOfSegment(segId: Long): String?

    @Query("SELECT thumbPath FROM segments WHERE itemId = :itemId AND page = :page AND thumbPath IS NOT NULL LIMIT 1")
    suspend fun thumbOfPage(itemId: Long, page: Int): String?

    @Query("SELECT segId, text FROM segments WHERE segId IN (:ids) AND text IS NOT NULL")
    suspend fun segmentTexts(ids: List<Long>): List<SegText>

    @Query("SELECT * FROM segments WHERE itemId = :itemId ORDER BY ordinal")
    suspend fun segmentsOf(itemId: Long): List<SegmentEntity>

    @Query("DELETE FROM segments WHERE itemId = :itemId")
    suspend fun deleteSegmentsOf(itemId: Long)

    /** The [SegmentType.USER_KEYWORD] segments of finished items, to compare with the keyword table. */
    @Query(
        """SELECT s.segId, s.itemId, i.sourceKey, i.displayName, s.text, (s.embedding IS NOT NULL) AS embedded
           FROM segments s JOIN items i ON i.itemId = s.itemId WHERE s.type = 'USER_KEYWORD' AND i.status = 'DONE'""",
    )
    suspend fun keywordSegments(): List<KeywordSegRow>

    /** Finished items among [keys] (files that have keywords), to find the ones missing a keyword segment. */
    @Query("SELECT itemId, sourceKey, displayName FROM items WHERE status = 'DONE' AND sourceKey IN (:keys)")
    suspend fun doneItemsFor(keys: List<String>): List<KeywordItemRow>

    /** Keyword segments of [sourceKey]'s finished item that have a vector (the rest wait for the model). */
    @Query(
        """SELECT COUNT(*) FROM segments s JOIN items i ON i.itemId = s.itemId
           WHERE i.sourceKey = :sourceKey AND i.status = 'DONE' AND s.type = 'USER_KEYWORD' AND s.embedding IS NOT NULL""",
    )
    fun observeEmbeddedKeywords(sourceKey: String): Flow<Int>

    @Query("DELETE FROM segments WHERE segId IN (:ids)")
    suspend fun deleteSegments(ids: List<Long>)

    @Query("UPDATE segments SET embedding = :embedding WHERE segId = :segId")
    suspend fun setSegmentEmbedding(segId: Long, embedding: ByteArray)

    /** Marks an item as changed (search cache, other apps' "what is new") after its keywords changed. */
    @Query("UPDATE items SET indexedAt = :at WHERE itemId = :id")
    suspend fun touch(id: Long, at: Long)

    @Query(
        """SELECT s.segId, s.itemId, s.type, s.startMs, s.endMs, s.page, s.embedding
           FROM segments s JOIN items i ON i.itemId = s.itemId
           WHERE s.embedding IS NOT NULL AND i.status = 'DONE' AND i.embeddingModelId = :modelId""",
    )
    suspend fun vectors(modelId: String): List<VectorRow>

    /** Candidate segments for keyword search; the caller ranks them. [pattern] is a LIKE pattern with `\` as escape. */
    @Query(
        """SELECT s.segId, s.itemId, s.type, s.startMs, s.endMs, s.page, s.text
           FROM segments s JOIN items i ON i.itemId = s.itemId
           WHERE s.text IS NOT NULL AND i.status = 'DONE' AND s.text LIKE :pattern ESCAPE '\' LIMIT :limit""",
    )
    suspend fun textCandidates(pattern: String, limit: Int): List<TextRow>

    /** Ids of finished items of [kinds] whose date (taken, else modified) lies in [from]..[to]. */
    @Query(
        """SELECT itemId FROM items WHERE status = 'DONE' AND kind IN (:kinds)
           AND COALESCE(dateTaken, modifiedAt) BETWEEN :from AND :to""",
    )
    suspend fun allowedItemIds(kinds: List<ItemKind>, from: Long, to: Long): List<Long>

    /** Newest first; used when only filters are set and there is no query. */
    @Query(
        """SELECT itemId FROM items WHERE status = 'DONE' AND kind IN (:kinds)
           AND COALESCE(dateTaken, modifiedAt) BETWEEN :from AND :to ORDER BY COALESCE(dateTaken, modifiedAt) DESC LIMIT :limit""",
    )
    suspend fun browseItemIds(kinds: List<ItemKind>, from: Long, to: Long, limit: Int): List<Long>

    /** [browseItemIds] among [ids] only (the photos of one person). */
    @Query(
        """SELECT itemId FROM items WHERE status = 'DONE' AND itemId IN (:ids) AND kind IN (:kinds)
           AND COALESCE(dateTaken, modifiedAt) BETWEEN :from AND :to ORDER BY COALESCE(dateTaken, modifiedAt) DESC LIMIT :limit""",
    )
    suspend fun browseAmong(ids: List<Long>, kinds: List<ItemKind>, from: Long, to: Long, limit: Int): List<Long>

    /** Items whose metadata (place, file name, title) contains [pattern]; the caller ranks them. */
    @Query(
        """SELECT itemId, displayName, title, place FROM items WHERE status = 'DONE'
           AND (place LIKE :pattern ESCAPE '\' OR displayName LIKE :pattern ESCAPE '\' OR title LIKE :pattern ESCAPE '\') LIMIT :limit""",
    )
    suspend fun metaCandidates(pattern: String, limit: Int): List<MetaRow>

    /** Replaces (or creates) an item and its segments atomically. */
    @Transaction
    suspend fun replaceItem(item: ItemEntity, segments: List<SegmentEntity>): Long {
        getItemBySourceKey(item.sourceKey)?.let { deleteItems(listOf(it.itemId)) }
        val id = insertItem(item.copy(itemId = 0))
        insertSegments(segments.map { it.copy(itemId = id) })
        return id
    }

    // ---- folders
    @Query("SELECT * FROM indexed_folders")
    fun observeFolders(): Flow<List<IndexedFolderEntity>>

    @Query("SELECT * FROM indexed_folders WHERE enabled = 1")
    suspend fun enabledFolderRows(): List<IndexedFolderEntity>

    @Query("SELECT relativePath FROM indexed_folders WHERE enabled = 1")
    suspend fun enabledFolders(): List<String>

    @Upsert
    suspend fun upsertFolder(folder: IndexedFolderEntity)

    @Query("DELETE FROM indexed_folders WHERE relativePath = :path")
    suspend fun deleteFolder(path: String)
}

@Database(
    entities = [
        ItemEntity::class, SegmentEntity::class, IndexedFolderEntity::class,
        PersonEntity::class, FaceEntity::class, UserKeywordEntity::class,
    ],
    version = AppDatabase.VERSION,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun itemDao(): ItemDao
    abstract fun faceDao(): FaceDao
    abstract fun keywordDao(): KeywordDao

    companion object {
        /** Bump together with a new migration in Migrations.kt (see docs/development.md). */
        const val VERSION = 10
    }
}
