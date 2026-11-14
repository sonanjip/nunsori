package com.lsa.app.data.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.lsa.app.data.db.ItemKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One file found by a source, before it has been processed. */
data class SourceEntry(
    /** Stable identity, e.g. `ms:image:123`. */
    val sourceKey: String,
    val kind: ItemKind,
    val uri: Uri,
    /** Volume-relative folder with trailing slash, same namespace as MediaStore RELATIVE_PATH. */
    val folderKey: String,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long,
    /** Milliseconds. */
    val modifiedAt: Long,
    val width: Int = 0,
    val height: Int = 0,
    val dateTaken: Long? = null,
    val durationMs: Long? = null,
)

data class FolderInfo(val relativePath: String, val imageCount: Int)

/** Which folders to look at (all kinds share the same folder list). */
data class FolderSpec(val relativePath: String, val recursive: Boolean = true)

interface ItemSource {
    suspend fun list(folders: Collection<FolderSpec>, kinds: Set<ItemKind>): List<SourceEntry>
}

class MediaStoreRepository(private val context: Context) : ItemSource {
    /** Songs from the music library are skipped unless turned off in Settings. */
    @Volatile var skipMusic: Boolean = true

    private val resolver: ContentResolver get() = context.contentResolver
    private val collection: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    private val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.RELATIVE_PATH,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.MIME_TYPE,
        MediaStore.Images.Media.SIZE,
        MediaStore.Images.Media.WIDTH,
        MediaStore.Images.Media.HEIGHT,
        MediaStore.Images.Media.DATE_TAKEN,
        MediaStore.Images.Media.DATE_MODIFIED,
    )

    private fun queryAllImages(): List<SourceEntry> {
        val out = ArrayList<SourceEntry>()
        resolver.query(collection, projection, null, null, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val iPath = c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            val iName = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val iMime = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val iW = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val iH = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val iTaken = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val iMod = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                out += SourceEntry(
                    sourceKey = "ms:image:$id",
                    kind = ItemKind.IMAGE,
                    uri = ContentUris.withAppendedId(collection, id),
                    folderKey = c.getString(iPath) ?: "",
                    displayName = c.getString(iName) ?: "",
                    mimeType = c.getString(iMime),
                    sizeBytes = c.getLong(iSize),
                    modifiedAt = c.getLong(iMod) * 1000,
                    width = c.getInt(iW),
                    height = c.getInt(iH),
                    dateTaken = if (c.isNull(iTaken)) null else c.getLong(iTaken).takeIf { it > 0 },
                )
            }
        }
        return out
    }

    private fun queryAudio(): List<SourceEntry> {
        val coll = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val cols = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.RELATIVE_PATH, MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.IS_MUSIC,
        )
        val out = ArrayList<SourceEntry>()
        resolver.query(coll, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (skipMusic && c.getInt(7) != 0) continue
                val id = c.getLong(0)
                out += SourceEntry(
                    sourceKey = "ms:audio:$id", kind = ItemKind.AUDIO, uri = ContentUris.withAppendedId(coll, id),
                    folderKey = c.getString(1) ?: "", displayName = c.getString(2) ?: "", mimeType = c.getString(3),
                    sizeBytes = c.getLong(4), modifiedAt = c.getLong(5) * 1000, durationMs = c.getLong(6).takeIf { it > 0 },
                )
            }
        }
        return out
    }

    private fun queryVideo(): List<SourceEntry> {
        val coll = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val cols = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.RELATIVE_PATH, MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.MIME_TYPE, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.DURATION, MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT, MediaStore.Video.Media.DATE_TAKEN,
        )
        val out = ArrayList<SourceEntry>()
        resolver.query(coll, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                out += SourceEntry(
                    sourceKey = "ms:video:$id", kind = ItemKind.VIDEO, uri = ContentUris.withAppendedId(coll, id),
                    folderKey = c.getString(1) ?: "", displayName = c.getString(2) ?: "", mimeType = c.getString(3),
                    sizeBytes = c.getLong(4), modifiedAt = c.getLong(5) * 1000, durationMs = c.getLong(6).takeIf { it > 0 },
                    width = c.getInt(7), height = c.getInt(8), dateTaken = if (c.isNull(9)) null else c.getLong(9).takeIf { it > 0 },
                )
            }
        }
        return out
    }

    /**
     * Whether the file behind [sourceKey] still exists, whatever folder it is in now. Unknown keys and failed
     * queries count as gone.
     */
    suspend fun exists(sourceKey: String): Boolean = withContext(Dispatchers.IO) {
        if (sourceKey.startsWith("fs:")) return@withContext java.io.File(sourceKey.removePrefix("fs:")).exists()
        val parts = sourceKey.split(':')
        if (parts.size != 3 || parts[0] != "ms") return@withContext false
        val id = parts[2].toLongOrNull() ?: return@withContext false
        val coll = when (parts[1]) {
            "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else -> return@withContext false
        }
        runCatching {
            resolver.query(ContentUris.withAppendedId(coll, id), arrayOf(MediaStore.MediaColumns._ID), null, null, null)
                ?.use { it.count > 0 } ?: false
        }.getOrDefault(false)
    }

    suspend fun listFolders(): List<FolderInfo> = withContext(Dispatchers.IO) {
        queryAllImages().groupingBy { it.folderKey }.eachCount()
            .map { FolderInfo(it.key, it.value) }
            .sortedBy { it.relativePath.lowercase() }
    }

    override suspend fun list(folders: Collection<FolderSpec>, kinds: Set<ItemKind>): List<SourceEntry> = withContext(Dispatchers.IO) {
        val exact = folders.filter { !it.recursive }.mapTo(HashSet()) { it.relativePath }
        val roots = folders.filter { it.recursive }.map { it.relativePath }
        val all = (if (ItemKind.IMAGE in kinds) queryAllImages() else emptyList()) +
            (if (ItemKind.AUDIO in kinds) queryAudio() else emptyList()) +
            (if (ItemKind.VIDEO in kinds) queryVideo() else emptyList())
        all.filter { e ->
            e.folderKey in exact || roots.any { e.folderKey.startsWith(it) }
        }
    }
}
