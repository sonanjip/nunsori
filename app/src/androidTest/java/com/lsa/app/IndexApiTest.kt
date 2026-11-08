package com.lsa.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lsa.app.service.tools.FileOps
import com.lsa.app.data.db.AppDatabase
import com.lsa.app.data.db.ItemEntity
import com.lsa.app.data.db.ItemKind
import com.lsa.app.data.db.ItemStatus
import com.lsa.app.data.db.SegmentEntity
import com.lsa.app.data.db.SegmentType
import com.lsa.app.data.prefs.SettingsStore
import com.lsa.app.search.SearchEngine
import com.lsa.app.service.Approval
import com.lsa.app.service.Approvals
import com.lsa.app.service.CallerCheck
import com.lsa.app.service.IndexApi
import io.github.taehunkim.nunsori.sdk.Nunsori
import io.github.taehunkim.nunsori.sdk.NunsoriClient
import io.github.taehunkim.nunsori.sdk.NunsoriErrors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The index API other apps use (service/IndexApi), on an in-memory index; plus one real bind to the service. */
@RunWith(AndroidJUnit4::class)
class IndexApiTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var db: AppDatabase
    private lateinit var api: IndexApi
    private var access = false

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        val settings = SettingsStore(ctx)
        val ops = FileOps(ctx, db.itemDao(), folders = { emptyList() }, root = File(ctx.cacheDir, "api-root"), allowed = { access })
        api = IndexApi(ctx, db.itemDao(), SearchEngine(db.itemDao(), { null }, settings), db.faceDao(), ops, "9.9.9")
    }

    @After fun tearDown() { db.close(); scope.cancel() }

    private fun add(name: String, text: String, indexedAt: Long, kind: ItemKind = ItemKind.TEXT): Long = runBlocking {
        val id = db.itemDao().insertItem(
            ItemEntity(
                sourceKey = "k:$name", kind = kind, uri = "content://$name", folderKey = "Documents/", displayName = name, mimeType = "text/plain", sizeBytes = 1,
                modifiedAt = 1_700_000_000_000, title = null, durationMs = null, pageCount = null, width = 0, height = 0, dateTaken = null, latitude = null,
                longitude = null, status = ItemStatus.DONE, error = null, embeddingModelId = "test-model", pipelineKey = "p", progress = 0, indexedAt = indexedAt,
            ),
        )
        db.itemDao().insertSegments(listOf(SegmentEntity(itemId = id, type = SegmentType.TEXT_CHUNK, ordinal = 0, startMs = null, endMs = null, page = null, text = text, embedding = null, thumbPath = null)))
        id
    }

    @Test fun helloExchangesVersionsAndRefusesOldClients() {
        val o = JSONObject(api.hello("""{"api":1,"sdk":"0.1.0"}"""))
        assertEquals(Nunsori.API_VERSION, o.getInt("api"))
        assertEquals("9.9.9", o.getString("appVersion"))
        try { api.hello("""{"api":0}"""); fail() } catch (e: IllegalStateException) {
            assertEquals(Nunsori.Errors.CLIENT_TOO_OLD, NunsoriErrors.decode(e)!!.code)
        }
    }

    @Test fun toolsListSearchReadAndConfirmedFileActions() {
        val tools = JSONArray(api.tools())
        val byName = (0 until tools.length()).associate { tools.getJSONObject(it).let { t -> t.getString("name") to t.getBoolean("confirm") } }
        assertEquals(mapOf("search_index" to false, "read_file" to false, "search_in_file" to false, "move_files" to true, "delete_files" to true), byName)
    }

    @Test fun searchInsideOneFileDoesNotReadAnotherFile() {
        val id = add("report.txt", "Launch code cobalt", 10)
        add("other.txt", "Launch code orange", 11)
        val found = JSONObject(api.runTool("search_in_file", """{"item_id":$id,"query":"cobalt"}"""))
        assertTrue(found.toString().contains("cobalt"))
        assertFalse(found.toString().contains("orange"))
        assertEquals("words", found.getString("method"))
    }

    @Test fun searchAndReadLikeTheChatTools() {
        val id = add("receipt.txt", "Coffee receipt total 4500 won", 10)
        add("notes.txt", "Meeting notes about the launch", 11)
        val hits = JSONArray(api.runTool("search_index", """{"query":"receipt"}"""))
        assertEquals(id, hits.getJSONObject(0).getLong("item_id"))
        val read = JSONObject(api.runTool("read_file", """{"item_id":$id}"""))
        assertTrue(read.getString("text").contains("4500"))
        assertEquals("Coffee receipt", api.readText(id, 14))
        val info = JSONArray(api.items(longArrayOf(id, 999))).getJSONObject(0)
        assertEquals("receipt.txt", info.getString("name"))
        assertEquals("document", info.getString("kind"))
        assertEquals(java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(1_700_000_000_000)), info.getString("date"))
        assertEquals(1, JSONArray(api.items(longArrayOf(id, 999))).length())
    }

    @Test fun moveIsPreparedForConfirmationThenCommitted() {
        val id = add("a.txt", "x", 10)
        assertTrue(api.runTool("move_files", """{"item_ids":"$id","destination":"/storage/x"}""").startsWith("Error"))
        val c = NunsoriClient.parseConfirmation(api.runTool("move_files", """{"item_ids":"$id","destination":"Documents/Old"}"""))
        assertNotNull(c)
        assertEquals(listOf(id), c!!.files.map { it.itemId })
        assertEquals("Documents/Old", JSONObject(c.payload).getString("destination"))
        // without "All files access" nothing happens and the caller learns why
        val r = JSONObject(api.commit("move_files", c.payload))
        assertEquals(false, r.getBoolean("ok"))
        assertEquals(Nunsori.Errors.NEEDS_FILE_ACCESS, r.getInt("error"))
        try { api.runTool("rm_rf", "{}"); fail() } catch (e: IllegalStateException) {
            assertEquals(Nunsori.Errors.BAD_INPUT, NunsoriErrors.decode(e)!!.code)
        }
    }

    @Test fun changesSinceHandsOutNewFilesOnce() {
        add("old.txt", "old", 100)
        val start = JSONObject(api.changesSince(0, "", 50))
        assertEquals("cursor 0 starts from now", 0, start.getJSONArray("ids").length())
        assertEquals(100, start.getLong("cursor"))
        val a = add("new.txt", "new receipt", 200)
        val photo = add("new.jpg", "", 300, ItemKind.IMAGE)
        val docs = JSONObject(api.changesSince(100, "document", 50))
        assertEquals(listOf(a), docs.getJSONArray("ids").let { arr -> (0 until arr.length()).map { arr.getLong(it) } })
        val all = JSONObject(api.changesSince(100, "", 50))
        assertEquals(2, all.getJSONArray("ids").length())
        assertEquals(300, all.getLong("cursor"))
        assertEquals(0, JSONObject(api.changesSince(300, "", 50)).getJSONArray("ids").length())
        assertEquals(listOf(a), api.matchItems("receipt", longArrayOf(a, photo), "").toList())
    }

    @Test fun approvalsAreKeptPerCertificate() {
        var stored = ""
        val ap = Approvals({ stored }, { stored = it }, scope)
        assertTrue(ap.request("com.example.notes", "abc", 5))
        assertEquals(CallerCheck.Decision.WAIT, CallerCheck.decide(ap.get("com.example.notes"), "abc"))
        ap.decide("com.example.notes", true)
        assertEquals(CallerCheck.Decision.ALLOW, CallerCheck.decide(ap.get("com.example.notes"), "abc"))
        assertEquals("another key asks again", CallerCheck.Decision.ASK, CallerCheck.decide(ap.get("com.example.notes"), "def"))
        assertEquals(Approval.State.ALLOWED, Approvals.decode(Approvals.encode(ap.all.value))["com.example.notes"]!!.state)
    }

    @Test fun theServiceAnswersOverBinder() = runBlocking {
        // the test runs in Nunsori's own process, so the caller check lets it in like an app with Nunsori's key
        val client = NunsoriClient(ctx)
        val info = client.info()
        assertEquals(Nunsori.API_VERSION, info.apiVersion)
        assertEquals(BuildConfig.VERSION_NAME, info.appVersion)
        assertEquals(5, client.tools().size)
        client.disconnect()
    }
}
