package com.lsa.app.service.tools

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.lsa.app.data.db.ItemDao
import com.lsa.app.data.db.ItemEntity
import com.lsa.app.data.media.FolderSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** The outcome for one file of a file action. */
data class FileResult(val itemId: Long, val name: String, val ok: Boolean, val reason: String? = null)

/** Pure helpers (no Android types) so they can be unit tested. */
object FilePaths {
    const val MAX_FILES = 50
    const val TRASH_DIR = ".LSA_Trash"

    /** `12, 15;18` or a JSON-like `[12,15]` to ids; at most [MAX_FILES]. */
    fun parseIds(text: String): List<Long> =
        Regex("\\d+").findAll(text).mapNotNull { it.value.toLongOrNull() }.distinct().take(MAX_FILES).toList()

    /**
     * A destination folder relative to the shared storage root (`Pictures/Wedding`), or null when it is not acceptable:
     * empty, absolute, containing `..`, hidden (`.x`) or inside `Android/`.
     */
    fun safeDestination(raw: String): String? {
        val parts = raw.trim().replace('\\', '/').split('/').filter { it.isNotBlank() && it != "." }
        if (parts.isEmpty()) return null
        if (parts.first().equals("storage", ignoreCase = true) || parts.first().equals("sdcard", ignoreCase = true)) return null
        if (parts.any { it == ".." || it.startsWith(".") }) return null
        if (parts.first().equals("Android", ignoreCase = true)) return null
        return parts.joinToString("/") { it.trim() }
    }

    /** `name.ext` → `name (1).ext`, `name (2).ext`, … until [taken] says the name is free. */
    fun uniqueName(name: String, taken: (String) -> Boolean): String {
        if (!taken(name)) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val base = name.substring(0, dot); val ext = name.substring(dot)
        var i = 1
        while (taken("$base ($i)$ext")) i++
        return "$base ($i)$ext"
    }

    /** True when a file in [folderKey] (volume-relative, trailing slash) lies in one of the indexed [folders]. */
    fun isIndexed(folderKey: String, folders: List<FolderSpec>): Boolean = folders.any { f ->
        val rel = f.relativePath.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        folderKey == rel || (f.recursive && folderKey.startsWith(rel))
    }
}

/**
 * Moves, trashes and deletes indexed files after the user approved a card in the calling app. Needs "All files access" (Android 11+);
 * the index is updated at once so search does not show files that are gone. Plain files go to an app trash folder
 * ([FilePaths.TRASH_DIR], hidden from the index and the gallery); MediaStore files use the system trash.
 */
class FileOps(
    private val context: Context,
    private val items: ItemDao,
    private val folders: suspend () -> List<FolderSpec>,
    private val root: File = Environment.getExternalStorageDirectory(),
    private val allowed: () -> Boolean = { com.lsa.app.ui.index.hasAllFilesAccess() },
    private val clock: () -> Long = System::currentTimeMillis,
    /** The user's keywords follow a plain file to its new path. */
    private val keywords: com.lsa.app.data.db.KeywordDao? = null,
) {
    val hasAccess: Boolean get() = allowed()

    suspend fun trash(ids: List<Long>): List<FileResult> = each(ids) { item, file ->
        if (item.isPlain()) {
            val dir = File(root, FilePaths.TRASH_DIR).also { it.mkdirs() }
            File(dir, ".nomedia").let { if (!it.exists()) runCatching { it.createNewFile() } }
            val target = File(dir, FilePaths.uniqueName("${clock()}_${item.displayName}") { File(dir, it).exists() })
            if (!file.renameTo(target)) return@each "could not move to trash"
        } else if (!setTrashed(item)) return@each "could not move to trash"
        items.deleteItems(listOf(item.itemId)); null
    }

    suspend fun delete(ids: List<Long>): List<FileResult> = each(ids) { item, file ->
        val ok = if (item.isPlain()) file.delete() else context.contentResolver.delete(Uri.parse(item.uri), null, null) > 0
        if (!ok) return@each "could not delete"
        items.deleteItems(listOf(item.itemId)); null
    }

    suspend fun move(ids: List<Long>, destination: String): List<FileResult> {
        val dest = FilePaths.safeDestination(destination) ?: return ids.map { FileResult(it, "#$it", false, "destination not allowed") }
        return each(ids) { item, file ->
            val newKey = "$dest/"
            if (item.folderKey == newKey) return@each "already there"
            if (item.isPlain()) {
                val dir = File(root, dest).also { it.mkdirs() }
                val target = File(dir, FilePaths.uniqueName(file.name) { File(dir, it).exists() })
                if (!file.renameTo(target)) return@each "could not move"
                items.relocate(item.itemId, "fs:${target.absolutePath}", Uri.fromFile(target).toString(), newKey, target.name)
                keywords?.rekey(item.sourceKey, "fs:${target.absolutePath}")
            } else {
                val moved = moveInMediaStore(item, newKey) ?: return@each "this folder is not allowed for this file type"
                items.updateLocation(item.itemId, moved.first, moved.second)
            }
            val now = items.getItem(item.itemId)
            if (now != null && !FilePaths.isIndexed(now.folderKey, folders())) items.deleteItems(listOf(item.itemId))
            null
        }
    }

    /** Runs [op] per item; it returns a failure reason or null. Files that no longer exist are dropped from the index. */
    private suspend fun each(ids: List<Long>, op: suspend (ItemEntity, File) -> String?): List<FileResult> = withContext(Dispatchers.IO) {
        if (!allowed()) return@withContext ids.map { FileResult(it, "#$it", false, NEEDS_ACCESS) }
        ids.take(FilePaths.MAX_FILES).map { id ->
            val item = items.getItem(id) ?: return@map FileResult(id, "#$id", false, "not in the index any more")
            val file = fileOf(item)
            if (!file.exists()) {
                items.deleteItems(listOf(id))
                return@map FileResult(id, item.displayName, false, "the file is already gone")
            }
            val reason = try { op(item, file) } catch (e: Exception) {
                Log.w(TAG, "file action failed for ${item.displayName}", e)
                e.message ?: e.javaClass.simpleName
            }
            FileResult(id, item.displayName, reason == null, reason)
        }
    }

    private fun ItemEntity.isPlain() = sourceKey.startsWith("fs:")

    private fun fileOf(item: ItemEntity): File =
        if (item.isPlain()) File(item.sourceKey.removePrefix("fs:")) else File(root, item.folderKey + item.displayName)

    private fun setTrashed(item: ItemEntity): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val v = ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 1) }
        return context.contentResolver.update(Uri.parse(item.uri), v, null, null) > 0
    }

    /** Moves a MediaStore file by changing its RELATIVE_PATH (keeps its id); returns the new folder key and name. */
    private fun moveInMediaStore(item: ItemEntity, relativePath: String): Pair<String, String>? {
        val uri = Uri.parse(item.uri)
        val v = ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath) }
        try {
            if (context.contentResolver.update(uri, v, null, null) <= 0) return null
        } catch (_: IllegalArgumentException) { return null }
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return (c.getString(0) ?: relativePath) to c.getString(1)
        }
        return relativePath to item.displayName
    }

    companion object {
        private const val TAG = "LSA.FileOps"
        const val NEEDS_ACCESS = "needs_all_files_access"
    }
}
