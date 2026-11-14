package com.lsa.app

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lsa.app.data.db.AppDatabase
import com.lsa.app.data.db.ItemKind
import com.lsa.app.data.db.SegmentType
import com.lsa.app.data.db.UserKeywordEntity
import com.lsa.app.data.media.FolderSpec
import com.lsa.app.data.media.ItemSource
import com.lsa.app.data.media.SourceEntry
import com.lsa.app.indexing.IndexRunner
import com.lsa.app.indexing.IndexSession
import com.lsa.app.indexing.ItemSink
import com.lsa.app.indexing.NewSegment
import com.lsa.app.ml.embedding.Quant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** "My keywords": kept per file across reindexing, turned into searchable segments, following moves. */
@RunWith(AndroidJUnit4::class)
class KeywordIndexTest {
    private lateinit var db: AppDatabase
    private lateinit var runner: IndexRunner
    private val entries = mutableListOf<SourceEntry>()
    private val existing = HashSet<String>()
    private val folder = listOf(FolderSpec("Docs/"))
    private val kinds = setOf(ItemKind.TEXT)
    private val vec = Quant.encode(floatArrayOf(1f, 0f))

    private val source = object : ItemSource {
        override suspend fun list(folders: Collection<FolderSpec>, kinds: Set<ItemKind>) = entries.toList()
    }

    private fun entry(path: String, mod: Long = 1) = SourceEntry(
        sourceKey = "fs:/sdcard/$path", kind = ItemKind.TEXT, uri = Uri.parse("file:///sdcard/$path"), folderKey = "Docs/",
        displayName = path.substringAfterLast('/'), mimeType = "text/plain", sizeBytes = 10, modifiedAt = mod,
    )

    private fun session(model: String, embedKeywords: Boolean = true) = object : IndexSession {
        override val embeddingModelId = model
        override suspend fun process(entry: SourceEntry, resume: Long, sink: ItemSink) {
            sink.segments(listOf(NewSegment(SegmentType.TEXT_CHUNK, 0, text = "body", embedding = vec)), progress = 1)
        }
        override suspend fun embedKeyword(text: String, title: String?): FloatArray? = if (embedKeywords) floatArrayOf(0f, 1f) else null
    }

    private suspend fun index(model: String = "m1", embed: Boolean = true) {
        val key: (ItemKind) -> String = { "$model|v1" }
        runner.process(runner.sync(folder, kinds, key), session(model, embed), key) {}
    }

    private suspend fun keywordSegs(key: String) = db.itemDao().getItemBySourceKey(key)!!.let { item ->
        db.itemDao().segmentsOf(item.itemId).filter { it.type == SegmentType.USER_KEYWORD }
    }

    private suspend fun addKeyword(key: String, text: String) =
        db.keywordDao().insert(listOf(UserKeywordEntity(sourceKey = key, text = text, createdAt = 1)))

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        runner = IndexRunner(db.itemDao(), source, keywords = db.keywordDao(), fileExists = { it in existing })
    }
    @After fun tearDown() = db.close()

    @Test fun keywordsAddedBeforeIndexingBecomeEmbeddedSegments() = runBlocking {
        val e = entry("a.txt"); entries += e
        addKeyword(e.sourceKey, "여권 사본")
        index()
        val segs = keywordSegs(e.sourceKey)
        assertEquals(listOf("여권 사본"), segs.map { it.text })
        assertTrue(segs.single().embedding != null)
    }

    @Test fun keywordsSurviveReindexingWithAnotherModel() = runBlocking {
        val e = entry("a.txt"); entries += e
        addKeyword(e.sourceKey, "trip")
        index("m1")
        index("m2")
        assertEquals(listOf("trip"), keywordSegs(e.sourceKey).map { it.text })
        assertEquals("m2", db.itemDao().getItemBySourceKey(e.sourceKey)!!.embeddingModelId)
    }

    @Test fun syncKeywordsAddsRemovesAndFillsVectors() = runBlocking {
        val e = entry("a.txt"); entries += e
        index()
        addKeyword(e.sourceKey, "one"); addKeyword(e.sourceKey, "two")
        // text-only first (what the detail sheet does right away)
        runner.syncKeywords(onlyKey = e.sourceKey)
        assertEquals(setOf("one", "two"), keywordSegs(e.sourceKey).map { it.text }.toSet())
        assertTrue(keywordSegs(e.sourceKey).all { it.embedding == null })
        assertTrue(runner.keywordWorkPending())
        // the text is found by keyword search at once
        assertEquals(1, db.itemDao().textCandidates("%two%", 10).size)

        db.keywordDao().delete(e.sourceKey, "one")
        runner.syncKeywords(embed = { _, _ -> floatArrayOf(0f, 1f) })
        val segs = keywordSegs(e.sourceKey)
        assertEquals(listOf("two"), segs.map { it.text })
        assertTrue(segs.single().embedding != null)
        assertFalse(runner.keywordWorkPending())
        assertTrue("vector search sees it", db.itemDao().vectors("m1").any { it.type == SegmentType.USER_KEYWORD })
    }

    @Test fun keywordsOfDeletedFilesGoAndThoseOfHiddenFilesStay() = runBlocking {
        val gone = entry("gone.txt"); val hidden = entry("hidden.txt")
        entries += listOf(gone, hidden, entry("other.txt"))
        index()
        addKeyword(gone.sourceKey, "x"); addKeyword(hidden.sourceKey, "y")
        existing += hidden.sourceKey // folder turned off, the file is still there
        entries.removeAll { it == gone || it == hidden }
        runner.sync(folder, kinds) { "m1|v1" }
        assertEquals(emptyList<UserKeywordEntity>(), db.keywordDao().of(gone.sourceKey))
        assertEquals(listOf("y"), db.keywordDao().of(hidden.sourceKey).map { it.text })
    }

    @Test fun keywordsFollowAPlainFileThatMoved() = runBlocking {
        val old = entry("a.txt", mod = 7); entries += listOf(old, entry("keep.txt"))
        index()
        addKeyword(old.sourceKey, "contract")
        entries.remove(old)
        val moved = entry("sub/a.txt", mod = 7); entries += moved
        index()
        assertEquals(listOf("contract"), db.keywordDao().of(moved.sourceKey).map { it.text })
        assertEquals(listOf("contract"), keywordSegs(moved.sourceKey).map { it.text })
    }
}
