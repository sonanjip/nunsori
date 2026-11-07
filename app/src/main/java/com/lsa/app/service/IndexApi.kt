package com.lsa.app.service

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.lsa.app.service.tools.FileActionPrep
import com.lsa.app.service.tools.FileActionTools
import com.lsa.app.service.tools.FileOps
import com.lsa.app.service.tools.FilePrep
import com.lsa.app.service.tools.PeopleDirectory
import com.lsa.app.service.tools.ReadFileTool
import com.lsa.app.service.tools.SearchInFileTool
import com.lsa.app.service.tools.SearchIndexTool
import com.lsa.app.service.tools.kindName
import com.lsa.app.service.tools.parseKinds
import com.lsa.app.data.db.FaceDao
import com.lsa.app.data.db.ItemDao
import com.lsa.app.data.db.ItemKind
import com.lsa.app.indexing.BitmapLoader
import com.lsa.app.search.SearchEngine
import io.github.taehunkim.nunsori.sdk.Nunsori
import io.github.taehunkim.nunsori.sdk.NunsoriErrors
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * What other apps may do with the index (the INunsori service, see docs/architecture.md, "Index API"): search and
 * read (service/tools), prepare file moves/deletes that the user confirms in the calling app, and learn which
 * files are new. Plain logic so tests can call it without Binder.
 */
class IndexApi(
    private val context: Context,
    private val items: ItemDao,
    private val search: SearchEngine,
    faces: FaceDao,
    private val fileOps: FileOps,
    private val appVersion: String,
) {
    private val people = PeopleDirectory(faces)
    private val searchTool = SearchIndexTool(search, items, people)
    private val readTool = ReadFileTool(items)
    private val searchInTool = SearchInFileTool(search, items)

    fun hello(clientJson: String): String {
        val api = runCatching { JSONObject(clientJson).optInt("api") }.getOrDefault(0)
        if (api < MIN_CLIENT_API) throw NunsoriErrors.encode(Nunsori.Errors.CLIENT_TOO_OLD, "client API $api, need $MIN_CLIENT_API")
        return JSONObject().put("api", Nunsori.API_VERSION).put("minClientApi", MIN_CLIENT_API).put("appVersion", appVersion).toString()
    }

    fun tools(): String = JSONArray().apply {
        put(spec(searchTool.name, searchTool.description, searchTool.parameters, false))
        put(spec(readTool.name, readTool.description, readTool.parameters, false))
        put(spec(searchInTool.name, searchInTool.description, searchInTool.parameters, false))
        put(spec(MOVE, FileActionTools.MOVE_DESCRIPTION, FileActionTools.MOVE_PARAMETERS, true))
        put(spec(DELETE, FileActionTools.DELETE_DESCRIPTION, FileActionTools.DELETE_PARAMETERS, true))
    }.toString()

    private fun spec(name: String, description: String, parameters: String, confirm: Boolean) =
        JSONObject().put("name", name).put("description", description).put("parameters", JSONObject(parameters)).put("confirm", confirm)

    fun runTool(name: String, argsJson: String): String {
        val args = runCatching { JSONObject(argsJson.ifBlank { "{}" }) }.getOrNull() ?: return "Error: arguments must be a JSON object."
        return when (name) {
            searchTool.name -> searchTool.execute(args)
            readTool.name -> readTool.execute(args)
            searchInTool.name -> searchInTool.execute(args)
            MOVE -> confirm(FileActionPrep.move(args, items, people), MOVE)
            DELETE -> confirm(FileActionPrep.delete(args, items, people), DELETE)
            else -> throw NunsoriErrors.encode(Nunsori.Errors.BAD_INPUT, "unknown tool $name")
        }
    }

    private fun confirm(prep: FilePrep, name: String): String = when (prep) {
        is FilePrep.Invalid -> prep.error
        is FilePrep.Ready -> {
            val p = prep.payload
            val n = p.getJSONArray("files").length()
            val summary = if (name == MOVE) "Move $n file(s) to ${p.optString("destination")}"
            else if (p.optBoolean("permanent")) "Delete $n file(s) permanently" else "Move $n file(s) to the trash"
            JSONObject().put(
                "confirm",
                JSONObject().put("title", name).put("summary", summary).put("files", p.getJSONArray("files")).put("payload", p),
            ).put("status", "waiting_for_user_approval")
                .put("note", "The user sees a card with these files and decides. Tell them to approve it; do not say it is done.").toString()
        }
    }

    /** Runs a move/delete the user approved in the calling app. */
    fun commit(name: String, payloadJson: String): String {
        val p = runCatching { JSONObject(payloadJson) }.getOrNull() ?: throw NunsoriErrors.encode(Nunsori.Errors.BAD_INPUT, "payload must be JSON")
        val arr = p.optJSONArray("files") ?: throw NunsoriErrors.encode(Nunsori.Errors.BAD_INPUT, "payload has no files")
        val ids = (0 until arr.length()).map { arr.getJSONObject(it).optLong("id") }
        if (!fileOps.hasAccess) {
            return JSONObject().put("ok", false).put("note", FileOps.NEEDS_ACCESS).put("error", Nunsori.Errors.NEEDS_FILE_ACCESS).toString()
        }
        val results = runBlocking {
            when (name) {
                MOVE -> fileOps.move(ids, p.optString("destination"))
                DELETE -> if (p.optBoolean("permanent")) fileOps.delete(ids) else fileOps.trash(ids)
                else -> throw NunsoriErrors.encode(Nunsori.Errors.BAD_INPUT, "unknown action $name")
            }
        }
        return JSONObject().put("ok", results.any { it.ok }).put("note", FileActionTools.summary(results)).toString()
    }

    fun items(ids: LongArray): String = runBlocking {
        val out = JSONArray()
        val byId = items.summaries(ids.toList()).associateBy { it.itemId }
        for (id in ids) {
            val s = byId[id] ?: continue
            val item = items.getItem(id)
            out.put(
                JSONObject().put("item_id", id).put("name", s.displayName).put("kind", kindName(s.kind))
                    .put("folder", s.folderKey).put("status", s.status.name)
                    .also { o ->
                        item?.mimeType?.let { o.put("mime", it) }
                        (item?.dateTaken ?: item?.modifiedAt)?.let { o.put("date", SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(it))) }
                    },
            )
        }
        out.toString()
    }

    /** JPEG bytes of the stored preview (pages, video frames) or of the photo itself; null when there is none. */
    fun thumbnailBytes(id: Long, maxPx: Int): ByteArray? = runBlocking {
        val s = items.summaries(listOf(id)).firstOrNull() ?: return@runBlocking null
        val side = maxPx.coerceIn(32, MAX_THUMB)
        runCatching {
            val stored = s.thumbPath?.let { File(context.filesDir, "thumbs/$it") }?.takeIf { it.exists() }
            val bmp = when {
                stored != null -> BitmapFactory.decodeFile(stored.path, BitmapFactory.Options().apply { inSampleSize = sample(stored, side) })
                s.kind == ItemKind.IMAGE -> BitmapLoader.decode(context, Uri.parse(s.uri), side)
                else -> null
            } ?: return@runCatching null
            try { BitmapLoader.toJpeg(bmp, side, 85) } finally { bmp.recycle() }
        }.getOrNull()
    }

    private fun sample(f: File, side: Int): Int {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= side) s *= 2
        return s
    }

    fun readText(id: Long, maxChars: Int): String = runBlocking {
        ReadFileTool.fullText(items.segmentsOf(id)).take(maxChars.coerceIn(0, MAX_TEXT))
    }

    fun changesSince(cursor: Long, kinds: String, limit: Int): String = runBlocking {
        if (cursor <= 0) return@runBlocking JSONObject().put("ids", JSONArray()).put("cursor", items.lastIndexedAt()).toString()
        val k = parseKinds(kinds).ifEmpty { ItemKind.entries.toSet() }.toList()
        val rows = items.doneAfter(cursor, k, limit.coerceIn(1, MAX_CHANGES))
        // the cursor only moves past kinds the caller asked for; other kinds finishing later are not skipped since
        // the cursor is a time, so the next call still sees everything newer
        val next = rows.lastOrNull()?.indexedAt ?: if (k.size == ItemKind.entries.size) maxOf(cursor, items.lastIndexedAt()) else cursor
        JSONObject().put("ids", JSONArray(rows.map { it.itemId })).put("cursor", next).toString()
    }

    fun matchItems(query: String, ids: LongArray, kinds: String): LongArray = runBlocking {
        search.matchItems(query, ids.toSet(), parseKinds(kinds)).keys.toLongArray()
    }

    fun people(): String = runBlocking { JSONArray(people.names()).toString() }

    companion object {
        const val MOVE = "move_files"
        const val DELETE = "delete_files"
        const val MIN_CLIENT_API = Nunsori.Api.V1
        private const val MAX_THUMB = 1024
        private const val MAX_TEXT = 20_000
        private const val MAX_CHANGES = 500

        /** A pipe whose write end is filled with [bytes] on a background thread (Binder transactions are limited to ~1 MB). */
        fun pipeOf(bytes: ByteArray): ParcelFileDescriptor {
            val (read, write) = ParcelFileDescriptor.createReliablePipe()
            thread(name = "nunsori-pipe", isDaemon = true) {
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(write).use { it.write(bytes) }
                } catch (e: IOException) { runCatching { write.closeWithError(e.message) } }
            }
            return read
        }
    }
}
