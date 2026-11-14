package com.lsa.app

import android.app.Application
import android.content.Context
import android.os.Environment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.room.Room
import com.lsa.app.data.db.AppDatabase
import com.lsa.app.data.db.ALL_MIGRATIONS
import com.lsa.app.data.media.CompositeSource
import com.lsa.app.data.media.FileSource
import com.lsa.app.data.media.MediaStoreRepository
import com.lsa.app.data.prefs.SettingsStore
import com.lsa.app.indexing.IndexingController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.lsa.app.indexing.AutoIndexScheduler
import com.lsa.app.indexing.docs.ThumbStore
import com.lsa.app.indexing.IndexRunner
import com.lsa.app.indexing.places.PlaceFinder
import com.lsa.app.ml.ModelHost
import com.lsa.app.search.SearchEngine

class AppContainer(val context: Context) {
    /** Lives as long as the process; used for background housekeeping (model unloading, schedule updates). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db: AppDatabase by lazy {
        com.lsa.app.data.db.onChatTasksDropped = { ids -> AppUpgrade.cancelChatAlarms(context, ids) }
        Room.databaseBuilder(context, AppDatabase::class.java, "lsa.db").addMigrations(*ALL_MIGRATIONS)
            // The index is derived data: after a downgrade (older build installed over a newer one) rebuilding beats crashing.
            .fallbackToDestructiveMigrationOnDowngrade(true).build()
    }
    val settings = SettingsStore(context)
    val mediaRepo = MediaStoreRepository(context).also { repo -> appScope.launch { settings.skipMusic.collect { repo.skipMusic = it } } }
    val thumbs = ThumbStore(context)
    /** AI models, served by the OndolSaem app. */
    val models = ModelHost(context, settings, appScope)
    private val source = CompositeSource(
        listOf(
            mediaRepo,
            // Plain files (documents, text) need "all files access"; without it this source simply lists nothing.
            FileSource(Environment.getExternalStorageDirectory()) { com.lsa.app.ui.index.hasAllFilesAccess() },
        ),
    )
    val places by lazy { PlaceFinder.fromAssets(context) }
    val indexRunner by lazy { IndexRunner(db.itemDao(), source, places = places, keywords = db.keywordDao(), fileExists = mediaRepo::exists) }
    val indexing by lazy { IndexingController(context, db.itemDao(), indexRunner, settings) }
    /** The user's own search keywords ("My keywords"). */
    val keywords by lazy { com.lsa.app.indexing.KeywordRepository(db.keywordDao(), indexRunner, indexing, models, appScope) }
    val faces by lazy { com.lsa.app.indexing.faces.FaceIndexer(context, db.faceDao(), thumbs, models, settings) }
    val autoIndex = AutoIndexScheduler(
        context, settings, appScope,
        catchUpSignals = combine(
            db.itemDao().observeFolders().map { f -> f.filter { it.enabled }.map { it.relativePath }.toSet() },
            settings.embeddingModelId,
            settings.enabledKinds,
            // OCR / speech models becoming available (or changing) in OndolSaem
            combine(settings.ocrModelId, settings.asrModelId) { ocr, asr -> "$ocr|$asr" },
        ) { folders, model, kinds, others -> listOf(folders, model, kinds, others) },
    )
    val fileOps: com.lsa.app.service.tools.FileOps by lazy { com.lsa.app.service.tools.FileOps(context, db.itemDao(), folders = { indexing.folderSpecs() }, keywords = db.keywordDao()) }
    val approvals by lazy { com.lsa.app.service.Approvals({ settings.apiApprovalsJson.first() }, settings::setApiApprovalsJson, appScope) }
    val callerCheck by lazy {
        com.lsa.app.service.CallerCheck(context, approvals).apply {
            onRequest = { pkg -> Notifications.approvalRequest(context, pkg, appLabel(pkg)) }
        }
    }
    val indexApi by lazy { com.lsa.app.service.IndexApi(context, db.itemDao(), search, db.faceDao(), fileOps, BuildConfig.VERSION_NAME) }

    /** The name another app shows in the launcher (its package name if it has none). */
    fun appLabel(pkg: String): String = runCatching {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
    val search by lazy { SearchEngine(db.itemDao(), { q -> models.embedder()?.use { it.value.embedQuery(q) } }, settings) }
}

class LsaApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.ensureChannels(this)
        container.autoIndex.start()
        // exact screen-off times for "When idle" while the app runs (IdleRule)
        io.github.taehunkim.ondolsaem.sdk.IdleState(this).watchScreen()
        container.appScope.launch {
            AppUpgrade.run(this@LsaApplication, container.settings, container.db.itemDao(), BuildConfig.VERSION_CODE)
            // Groups made with an older threshold or method (0.11.0 raised the default) are made again from the stored vectors.
            runCatching { container.faces.ensureGrouping() }.onFailure { android.util.Log.w("LSA", "regrouping faces failed", it) }
            // Mirror OndolSaem's active models into the settings (a change there re-indexes what it affects).
            container.models.refresh()
        }
        // OndolSaem announces model changes by broadcast too (also when this app is not running): re-check.
        io.github.taehunkim.ondolsaem.sdk.ModelChangedReceiver.handler = { container.appScope.launch { container.models.refresh() } }
    }

}

@Composable
fun rememberContainer(): AppContainer {
    val ctx = LocalContext.current.applicationContext
    return remember { (ctx as LsaApplication).container }
}
