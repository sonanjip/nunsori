package com.lsa.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** A group of faces of (presumably) one person. [name] is null until the user names it. */
@Entity(tableName = "people")
data class PersonEntity(
    @PrimaryKey(autoGenerate = true) val personId: Long = 0,
    val name: String? = null,
    /** Face shown for the group; null = the best face. */
    val coverFaceId: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/** One face found in a photo (biometric data: never leaves the device, deleted when face grouping is turned off). */
@Entity(
    tableName = "faces",
    foreignKeys = [
        ForeignKey(ItemEntity::class, parentColumns = ["itemId"], childColumns = ["itemId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(PersonEntity::class, parentColumns = ["personId"], childColumns = ["personId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("itemId"), Index("personId")],
)
data class FaceEntity(
    @PrimaryKey(autoGenerate = true) val faceId: Long = 0,
    val itemId: Long,
    /** Box in 0..1 image coordinates. */
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    /** Detection quality 0..1 (frontal faces score higher). */
    val score: Float,
    /** int8-quantised identity vector, see [com.lsa.app.ml.embedding.Quant]. */
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val vector: ByteArray,
    val personId: Long?,
    /** The user said this face is not that person ("not this person"): never put it back there. */
    val blockedPersonId: Long? = null,
    /** Cropped preview (relative to the thumbs directory). */
    val thumbPath: String?,
)

/** A person with what the People screen needs. */
data class PersonSummary(
    val personId: Long,
    val name: String?,
    val faceCount: Int,
    val photoCount: Int,
    /** Cover face's crop (relative to the thumbs directory). */
    val thumbPath: String?,
    val updatedAt: Long,
)

/** A photo whose faces have to be found. */
data class FaceTodo(val itemId: Long, val sourceKey: String, val uri: String, val displayName: String)

/** What clustering needs of a face. */
data class FaceVector(val faceId: Long, val personId: Long?, val blockedPersonId: Long?, val vector: ByteArray, val score: Float)

@Dao
interface FaceDao {
    @Insert suspend fun insertFaces(faces: List<FaceEntity>): List<Long>
    @Insert suspend fun insertPerson(p: PersonEntity): Long

    @Query("SELECT faceId, personId, blockedPersonId, vector, score FROM faces")
    suspend fun allVectors(): List<FaceVector>

    @Query("SELECT faceId, personId, blockedPersonId, vector, score FROM faces WHERE personId IS NULL")
    suspend fun ungroupedVectors(): List<FaceVector>

    /**
     * Puts the result of grouping everything again in place: each group keeps the id it inherits (name, cover) or
     * gets a new person; [blocked] maps faces taken out of a group to the group index that stands for it now (others
     * lose their block); covers that left their group are reset; people left without faces are deleted.
     */
    @Transaction
    suspend fun applyRegroup(groups: List<Pair<Long?, List<Long>>>, blocked: Map<Long, Int>, now: Long) {
        val ids = groups.map { (inherits, faceIds) ->
            val id = inherits ?: insertPerson(PersonEntity(createdAt = now, updatedAt = now))
            faceIds.chunked(500).forEach { assign(it, id) }
            id
        }
        clearBlocks()
        blocked.entries.groupBy({ it.value }, { it.key }).forEach { (gi, faceIds) -> faceIds.chunked(500).forEach { setBlocked(it, ids[gi]) } }
        resetLostCovers()
        deleteEmptyPeople()
    }

    @Query("UPDATE faces SET blockedPersonId = NULL WHERE blockedPersonId IS NOT NULL")
    suspend fun clearBlocks()

    @Query("UPDATE faces SET blockedPersonId = :personId WHERE faceId IN (:faceIds)")
    suspend fun setBlocked(faceIds: List<Long>, personId: Long)

    @Query("UPDATE people SET coverFaceId = NULL WHERE coverFaceId IS NOT NULL AND coverFaceId NOT IN (SELECT faceId FROM faces f WHERE f.personId = people.personId)")
    suspend fun resetLostCovers()

    @Query("SELECT COUNT(*) FROM faces") suspend fun faceCount(): Int

    @Query("UPDATE faces SET personId = :personId WHERE faceId IN (:faceIds)")
    suspend fun assign(faceIds: List<Long>, personId: Long?)

    @Query("SELECT * FROM faces WHERE itemId = :itemId")
    suspend fun facesOfItem(itemId: Long): List<FaceEntity>

    @Query("SELECT thumbPath FROM faces WHERE itemId = :itemId AND thumbPath IS NOT NULL")
    suspend fun thumbsOfItem(itemId: Long): List<String>

    @Query("DELETE FROM faces WHERE itemId = :itemId")
    suspend fun deleteFacesOfItem(itemId: Long)

    @Query(
        """SELECT p.personId, p.name, COUNT(f.faceId) AS faceCount, COUNT(DISTINCT f.itemId) AS photoCount,
           COALESCE((SELECT c.thumbPath FROM faces c WHERE c.faceId = p.coverFaceId),
                    (SELECT b.thumbPath FROM faces b WHERE b.personId = p.personId ORDER BY b.score DESC LIMIT 1)) AS thumbPath,
           p.updatedAt
           FROM people p JOIN faces f ON f.personId = p.personId
           GROUP BY p.personId
           ORDER BY (p.name IS NULL), photoCount DESC, p.personId""",
    )
    fun observePeople(): Flow<List<PersonSummary>>

    @Query("SELECT * FROM people WHERE personId = :id")
    fun observePerson(id: Long): Flow<PersonEntity?>

    @Query("SELECT * FROM people WHERE personId = :id")
    suspend fun person(id: Long): PersonEntity?

    @Query("SELECT * FROM people WHERE name IS NOT NULL ORDER BY name")
    suspend fun namedPeople(): List<PersonEntity>

    @Query("SELECT * FROM people")
    suspend fun allPeople(): List<PersonEntity>

    @Query("UPDATE people SET name = :name, updatedAt = :now WHERE personId = :id")
    suspend fun rename(id: Long, name: String?, now: Long)

    @Query("UPDATE people SET coverFaceId = :faceId, updatedAt = :now WHERE personId = :id")
    suspend fun setCover(id: Long, faceId: Long?, now: Long)

    @Query("DELETE FROM people WHERE personId = :id")
    suspend fun deletePerson(id: Long)

    /** Groups left without faces (after merges, "not this person", deleted photos). */
    @Query("DELETE FROM people WHERE personId NOT IN (SELECT DISTINCT personId FROM faces WHERE personId IS NOT NULL)")
    suspend fun deleteEmptyPeople(): Int

    /** Photos of a person, newest first. */
    @Query(
        """SELECT DISTINCT i.itemId, i.kind, i.uri, i.displayName, i.folderKey, i.title, i.sizeBytes, i.width, i.height,
           i.durationMs, i.pageCount, i.status, i.indexedAt, NULL AS thumbPath, i.sourceKey
           FROM items i JOIN faces f ON f.itemId = i.itemId
           WHERE f.personId = :personId ORDER BY COALESCE(i.dateTaken, i.modifiedAt) DESC""",
    )
    fun observePhotosOf(personId: Long): Flow<List<ItemSummary>>

    @Query("SELECT DISTINCT itemId FROM faces WHERE personId = :personId")
    suspend fun itemIdsOf(personId: Long): List<Long>

    @Query("SELECT * FROM faces WHERE personId = :personId ORDER BY score DESC")
    fun observeFacesOf(personId: Long): Flow<List<FaceEntity>>

    /** "Not this person": the face leaves the group and may not join it again. */
    @Query("UPDATE faces SET personId = NULL, blockedPersonId = personId WHERE faceId = :faceId")
    suspend fun reject(faceId: Long)

    /** Moves every face of [from] to [into] (merging two groups); [from] is removed afterwards. */
    @Transaction
    suspend fun merge(from: Long, into: Long, now: Long) {
        moveFaces(from, into)
        unblock(from, into)
        deletePerson(from)
        touch(into, now)
    }

    @Query("UPDATE faces SET personId = :into WHERE personId = :from")
    suspend fun moveFaces(from: Long, into: Long)

    @Query("UPDATE faces SET blockedPersonId = :into WHERE blockedPersonId = :from")
    suspend fun unblock(from: Long, into: Long)

    @Query("UPDATE people SET updatedAt = :now WHERE personId = :id")
    suspend fun touch(id: Long, now: Long)

    @Query("SELECT thumbPath FROM faces WHERE thumbPath IS NOT NULL")
    suspend fun allThumbs(): List<String>

    /** Face grouping turned off: everything goes (items' faceKey is cleared separately). */
    @Transaction
    suspend fun deleteAll() { deleteAllFaces(); deleteAllPeople() }

    @Query("DELETE FROM faces") suspend fun deleteAllFaces()
    @Query("DELETE FROM people") suspend fun deleteAllPeople()

    @Query("SELECT COUNT(*) FROM faces") fun observeFaceCount(): Flow<Int>

    // ---- which photos still need a face scan (items.faceKey) ----

    @Query("SELECT itemId, sourceKey, uri, displayName FROM items WHERE kind = 'IMAGE' AND status = 'DONE' AND (faceKey IS NULL OR faceKey != :key) ORDER BY itemId LIMIT :limit")
    suspend fun facesTodo(key: String, limit: Int): List<FaceTodo>

    @Query("SELECT COUNT(*) FROM items WHERE kind = 'IMAGE' AND status = 'DONE' AND (faceKey IS NULL OR faceKey != :key)")
    suspend fun countFacesTodo(key: String): Int

    /** Photos scanned with another face model: their vectors cannot be compared with new ones. */
    @Query("SELECT COUNT(*) FROM items WHERE faceKey IS NOT NULL AND faceKey != :key")
    suspend fun countScannedWithOtherModel(key: String): Int

    @Query("UPDATE items SET faceKey = :key WHERE itemId = :itemId")
    suspend fun setFaceKey(itemId: Long, key: String)

    @Query("UPDATE items SET faceKey = NULL")
    suspend fun clearFaceKeys()

    @Query("UPDATE faces SET thumbPath = NULL")
    suspend fun clearFaceThumbs()
}
