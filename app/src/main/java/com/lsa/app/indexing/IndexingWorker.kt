package com.lsa.app.indexing

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.lsa.app.LsaApplication
import com.lsa.app.Notifications
import com.lsa.app.R
import com.lsa.app.ui.index.hasAnyStorageAccess
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock

/**
 * Runs one indexing pass. Source "manual" is the Start button; "auto" runs come from the scheduler (new photos,
 * daily schedule, idle) and are throttled when the device gets hot.
 */
class IndexingWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val onlyFailed get() = inputData.getBoolean(KEY_ONLY_FAILED, false)
    private val auto get() = inputData.getString(KEY_SOURCE) == SOURCE_AUTO

    private fun info(p: IndexProgress): ForegroundInfo {
        val n = Notifications.progress(
            applicationContext, id, Notifications.CH_INDEXING,
            applicationContext.getString(R.string.notif_indexing_title),
            p.current ?: applicationContext.getString(R.string.notif_indexing_preparing),
            p.done, p.total, applicationContext.getString(R.string.action_pause),
        )
        return Notifications.foregroundInfo(Notifications.ID_INDEXING, n)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = info(IndexProgress())

    override suspend fun doWork(): Result {
        val c = (applicationContext as LsaApplication).container
        Log.i(TAG, "worker started auto=$auto")
        if (idleRun) {
            val preset = c.settings.idlePreset.first()
            val s = idle.snapshot()
            if (!io.github.taehunkim.ondolsaem.sdk.IdleRule.isIdle(preset, s.charging, s.batteryPct, s.screenOffSinceMs, System.currentTimeMillis())) {
                Log.d(TAG, "idle check: not idle yet ($preset, $s)")
                return Result.success()
            }
        }
        // Starting a foreground service from the background is not allowed on Android 12+; run silently instead.
        try { setForeground(info(IndexProgress())) } catch (e: Exception) { Log.d(TAG, "no foreground: ${e.message}") }
        // Arm the next trigger first: a photo taken during this run must not fall into a gap.
        if (detail == DETAIL_TRIGGER) c.autoIndex.armNextTrigger(inputData.getString(KEY_SLOT))
        outcome = AutoOutcome.NOTHING_NEW; indexed = 0
        val result = try {
            c.indexing.runLock.withLock { runPass(c) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never end an automatic run in "failure": retry later (with a short backoff) instead.
            Log.w(TAG, "indexing pass failed", e)
            outcome = AutoOutcome.ERROR
            if (auto) Result.retry() else Result.failure()
        }
        if (auto) {
            Log.i(TAG, "auto run ($detail): $outcome $indexed")
            runCatching { c.settings.setLastAutoRun(LastAutoRun(System.currentTimeMillis(), detail ?: "auto", outcome, indexed)) }
        }
        return result
    }

    private val detail get() = inputData.getString(KEY_SOURCE_DETAIL)
    private val idleRun get() = detail == DETAIL_IDLE
    private val idle by lazy { io.github.taehunkim.ondolsaem.sdk.IdleState(applicationContext) }
    /** An idle run stops as soon as the phone is used again (screen on); the rest waits for the next idle time. */
    private fun userBack() = idleRun && idle.screenOn()
    private var outcome = AutoOutcome.NOTHING_NEW
    private var indexed = 0

    private suspend fun runPass(c: com.lsa.app.AppContainer): Result {
        val ctl = c.indexing
        ctl.resetStop()
        val folders = ctl.folderSpecs()
        if (folders.isEmpty()) {
            outcome = AutoOutcome.NO_FOLDERS
            if (!auto) ctl.update(IndexProgress(error = IndexError.NO_FOLDERS))
            return if (auto) Result.success() else Result.failure()
        }
        if (!hasAnyStorageAccess(applicationContext)) {
            outcome = AutoOutcome.NO_ACCESS
            return if (auto) Result.success() else Result.failure()
        }

        c.models.refresh() // pipeline keys and availability follow OndolSaem's current models
        val keys = ctl.pipelineKeys()
        val full = c.indexRunner.sync(folders, ctl.enabledKinds(), keys)
        ctl.publishPlan(full, keys)
        // "Retry failed" touches only the failed files; paused work stays paused.
        var plan = if (!onlyFailed) full else {
            val failed = full.todo.filter { it.existing?.status == com.lsa.app.data.db.ItemStatus.FAILED }
            com.lsa.app.indexing.IndexPlan(total = failed.size, done = 0, todo = failed)
        }
        c.thumbs.cleanup(c.db.itemDao().thumbPaths().toSet() + c.db.faceDao().allThumbs()) // previews of deleted files
        Log.i(TAG, "sync: total=${plan.total} todo=${plan.todo.size} auto=$auto")
        if (plan.todo.isEmpty()) {
            ctl.update(IndexProgress(total = plan.total, done = plan.done, finished = true))
            c.keywords.embedPendingLocked(background = true)
            facePass(c, ctl)
            return Result.success()
        }
        if (auto && ctl.isHot()) { outcome = AutoOutcome.HOT; return Result.retry() }

        val embLease = try { c.models.embedder(background = true) } catch (e: Exception) {
            Log.w(TAG, "model load failed", e)
            outcome = AutoOutcome.LOAD_FAILED
            if (!auto) ctl.update(IndexProgress(error = IndexError.LOAD_FAILED))
            return if (auto) Result.retry() else Result.failure()
        }
        if (embLease == null) {
            outcome = AutoOutcome.NO_MODEL
            if (!auto) ctl.update(IndexProgress(error = IndexError.MODEL_MISSING))
            return if (auto) Result.success() else Result.failure()
        }
        val ocrLease = c.models.ocr()
        var lastNotify = 0L
        var result = IndexProgress()
        try {
            val session = ItemSession(applicationContext, embLease.value, ocrLease.value, c.thumbs, { c.models.speech() }, java.io.File(applicationContext.cacheDir, "audio"),
                VideoOptions(c.settings.videoDetail.first(), c.settings.videoTranscribe.first()))
            var rounds = 0
            while (true) {
                val before = plan.done
                result = c.indexRunner.process(
                    plan, session, keys,
                    gate = { !ctl.stopRequested && !(auto && ctl.isHot()) && !userBack() },
                ) { p ->
                    ctl.update(p)
                    val now = System.currentTimeMillis()
                    if (now - lastNotify > 1000 || p.finished) {
                        lastNotify = now
                        runCatching { Notifications.notify(applicationContext, Notifications.ID_INDEXING, info(p).notification) }
                    }
                }
                indexed += (result.done - before).coerceAtLeast(0)
                rounds++
                if (result.stoppedEarly || ctl.stopRequested || onlyFailed || rounds >= MAX_ROUNDS) break
                // Files added while this pass was running: take them now instead of waiting for the next trigger.
                // Files that failed are not retried within the same pass.
                val next = c.indexRunner.sync(folders, ctl.enabledKinds(), keys)
                ctl.publishPlan(next, keys)
                val fresh = next.todo.filter { it.existing?.status != com.lsa.app.data.db.ItemStatus.FAILED }
                if (fresh.isEmpty()) break
                Log.i(TAG, "another round: ${fresh.size} file(s) arrived during the run")
                plan = com.lsa.app.indexing.IndexPlan(total = next.total, done = next.done, todo = fresh)
            }
        } finally {
            ocrLease.close(); embLease.close()
        }
        if (!result.stoppedEarly && !ctl.stopRequested) {
            c.keywords.embedPendingLocked(background = true)
            facePass(c, ctl)
        }
        // Other apps (Malbotdari's scheduled file tasks) ask what is new when told the index changed.
        if (indexed > 0) runCatching { com.lsa.app.service.IndexEvents.notifyChanged(applicationContext) }
        if (indexed > 0) outcome = AutoOutcome.INDEXED
        // The phone is in use again: done for now, the next idle check continues.
        if (result.stoppedEarly && userBack()) { Log.i(TAG, "idle run stopped: screen on"); return Result.success() }
        // Stopped by the thermal gate (not by the user): try again later.
        if (result.stoppedEarly && !ctl.stopRequested) { outcome = AutoOutcome.HOT; return Result.retry() }
        if (indexed > 0) outcome = AutoOutcome.INDEXED
        return Result.success()
    }

    /** Faces of photos not scanned yet (new photos, or all of them after face grouping was turned on again). */
    private suspend fun facePass(c: com.lsa.app.AppContainer, ctl: com.lsa.app.indexing.IndexingController) {
        runCatching { c.faces.pass { !ctl.stopRequested && !(auto && ctl.isHot()) && !userBack() } }
            .onFailure { Log.w(TAG, "face pass failed", it) }
    }

    companion object {
        private const val TAG = "LSA.Index"
        const val KEY_ONLY_FAILED = "only_failed"
        const val KEY_SOURCE = "source"
        const val SOURCE_AUTO = "auto"
        const val KEY_SOURCE_DETAIL = "detail"
        const val DETAIL_TRIGGER = "trigger"
        const val DETAIL_CATCH_UP = "catch-up"
        const val DETAIL_NET = "net"
        const val DETAIL_SCHEDULED = "scheduled"
        const val DETAIL_IDLE = "idle"
        /** Which of the two alternating trigger slots this run was started by. */
        const val KEY_SLOT = "slot"
        /** A run keeps going while new files arrive during it, at most this many rounds. */
        private const val MAX_ROUNDS = 3
    }
}
