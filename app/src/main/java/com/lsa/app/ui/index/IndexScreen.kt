package com.lsa.app.ui.index

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import com.lsa.app.indexing.PendingFile
import com.lsa.app.indexing.PendingReason
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lsa.app.R
import com.lsa.app.indexing.IndexError
import com.lsa.app.rememberContainer
import com.lsa.app.ui.common.ItemDetailSheet
import com.lsa.app.ui.common.ItemThumb
import com.lsa.app.ui.common.WarningCard

/** Photos via the media permission, or any file via "all files access". */
fun hasAnyStorageAccess(ctx: android.content.Context): Boolean =
    hasPhotoPermission(ctx) || hasAllFilesAccess()

/** "All files access" (Android 11+); Android 10 has no such permission, so plain files are never readable there. */
fun hasAllFilesAccess(): Boolean = Build.VERSION.SDK_INT >= 30 && android.os.Environment.isExternalStorageManager()

fun hasPhotoPermission(ctx: android.content.Context): Boolean {
    val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
    return ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
}

@Composable
fun IndexScreen(onOpenSettings: () -> Unit) {
    val container = rememberContainer()
    val vm: IndexViewModel = viewModel(factory = viewModelFactory { initializer { IndexViewModel(container) } })
    val ctx = LocalContext.current

    val progress by vm.progress.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val folderCount by vm.enabledFolderCount.collectAsStateWithLifecycle()
    val modelReady by vm.modelReady.collectAsStateWithLifecycle()
    val stale by vm.staleCount.collectAsStateWithLifecycle()
    val failed by vm.failedCount.collectAsStateWithLifecycle()
    val indexed by vm.indexed.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val pendingLoaded by vm.pendingLoaded.collectAsStateWithLifecycle()
    val keywordKeys by vm.keywordKeys.collectAsStateWithLifecycle()
    var detailId by remember { mutableStateOf<Long?>(null) }
    var detailFile by remember { mutableStateOf<PendingFile?>(null) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var reason by rememberSaveable { mutableStateOf<PendingReason?>(null) }
    val reasonCounts = pending.groupingBy { it.reason }.eachCount()
    if (reason != null && (reasonCounts[reason] ?: 0) == 0) reason = null
    val waiting = if (reason == null) pending else pending.filter { it.reason == reason }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.start() }
    fun onStart() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.start()
    }

    val permitted = hasAnyStorageAccess(ctx)
    val warning: String? = when {
        !permitted -> stringResource(R.string.warn_no_permission)
        folderCount == 0 -> stringResource(R.string.warn_no_folders)
        !modelReady -> stringResource(R.string.warn_no_model)
        else -> null
    }
    val canStart = warning == null

    LazyVerticalGrid(
        columns = GridCells.Adaptive(104.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                if (warning != null) WarningCard(warning, stringResource(R.string.go_settings), onOpenSettings)
                if (warning == null && stale > 0) {
                    WarningCard(pluralStringResource(R.plurals.warn_stale, stale, stale))
                }
                progress.error?.let {
                    WarningCard(
                        stringResource(
                            when (it) {
                                IndexError.MODEL_MISSING -> R.string.err_model_missing
                                IndexError.NO_FOLDERS -> R.string.err_no_folders
                                IndexError.LOAD_FAILED -> R.string.err_load_failed
                            },
                        ),
                    )
                }
                StatusCard(progress, running, canStart, onStart = ::onStart, onPause = vm::pause)
                val faces by vm.faceProgress.collectAsStateWithLifecycle()
                if (faces.running) {
                    Column(Modifier.fillMaxWidth().testTag("face_progress"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.people_scanning, faces.done, faces.total), style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(progress = { if (faces.total > 0) faces.done.toFloat() / faces.total else 0f }, modifier = Modifier.fillMaxWidth())
                    }
                }
                com.lsa.app.ui.onboarding.LastAutoRunLine()
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            PrimaryTabRow(selectedTabIndex = tab, modifier = Modifier.padding(bottom = 8.dp)) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, modifier = Modifier.testTag("tab_indexed"),
                    text = { Text(stringResource(R.string.index_tab_indexed, indexed.size)) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, modifier = Modifier.testTag("tab_pending"),
                    text = { Text(stringResource(R.string.index_tab_pending, pending.size)) })
            }
        }
        if (tab == 0) {
            if (indexed.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { EmptyLine(stringResource(R.string.index_empty)) }
            items(indexed, key = { it.itemId }) { s ->
                Box(Modifier.aspectRatio(1f).clickable { detailId = s.itemId }) {
                    ItemThumb(s.kind, s.uri, s.thumbPath, s.displayName, false, Modifier.fillMaxSize())
                    if (s.sourceKey in keywordKeys) KeywordMark(Modifier.align(Alignment.TopEnd))
                }
            }
        } else {
            if (pending.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        FilterChip(selected = reason == null, onClick = { reason = null }, label = { Text(stringResource(R.string.pending_all, pending.size)) })
                        PendingReason.entries.forEach { r ->
                            val n = reasonCounts[r] ?: 0
                            if (n > 0) FilterChip(selected = reason == r, onClick = { reason = if (reason == r) null else r },
                                label = { Text("${stringResource(r.label)} $n") })
                        }
                        if (failed > 0 && (reason == null || reason == PendingReason.FAILED)) {
                            OutlinedButton(onClick = vm::retryFailed, enabled = canStart && !running) {
                                Text(stringResource(R.string.index_retry_failed))
                            }
                        }
                    }
                }
            }
            if (pending.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyLine(stringResource(if (pendingLoaded) R.string.pending_empty else R.string.pending_loading))
                }
            }
            items(waiting, key = { it.sourceKey }) { f ->
                Box(Modifier.aspectRatio(1f).clickable { if (f.itemId != null) detailId = f.itemId else detailFile = f }) {
                    ItemThumb(f.kind, f.uri, null, f.displayName, f.reason == PendingReason.FAILED, Modifier.fillMaxSize())
                    if (f.sourceKey in keywordKeys) KeywordMark(Modifier.align(Alignment.TopEnd))
                    Text(
                        stringResource(f.reason.label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                        modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)
                            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f), MaterialTheme.shapes.extraSmall)
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
        }
    }
    detailFile?.let { PendingFileSheet(it) { detailFile = null } }
    detailId?.let { ItemDetailSheet(it) { detailId = null } }
}

@Composable
private fun EmptyLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}

/** Small tag mark on files that have "My keywords". */
@Composable
private fun KeywordMark(modifier: Modifier) {
    Icon(
        Icons.AutoMirrored.Filled.Label, null,
        tint = MaterialTheme.colorScheme.onPrimary,
        modifier = modifier.padding(4.dp).size(18.dp)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f), CircleShape).padding(3.dp),
    )
}

val PendingReason.label: Int
    get() = when (this) {
        PendingReason.NEW -> R.string.pending_reason_new
        PendingReason.CHANGED -> R.string.pending_reason_changed
        PendingReason.MODEL -> R.string.pending_reason_model
        PendingReason.RESUME -> R.string.pending_reason_resume
        PendingReason.FAILED -> R.string.pending_reason_failed
        PendingReason.SKIPPED -> R.string.pending_reason_skipped
    }

@Composable
private fun StatusCard(
    p: com.lsa.app.indexing.IndexProgress,
    running: Boolean,
    canStart: Boolean,
    onStart: () -> Unit,
    onPause: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val title = when {
                running -> R.string.index_status_running
                p.finished -> R.string.index_status_done
                p.total > 0 && p.done < p.total -> R.string.index_status_paused
                else -> R.string.index_status_idle
            }
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            if (p.total > 0) {
                LinearProgressIndicator(progress = { p.done.toFloat() / p.total }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.index_progress, p.done, p.total), style = MaterialTheme.typography.bodyMedium)
                    if (p.failed > 0) {
                        Text(
                            pluralStringResource(R.plurals.index_failed_count, p.failed, p.failed),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            if (running && p.current != null) {
                Text(
                    stringResource(R.string.index_current, p.current),
                    style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (running) {
                Button(onClick = onPause) {
                    Icon(Icons.Filled.Pause, null); Text(stringResource(R.string.btn_pause), Modifier.padding(start = 8.dp))
                }
            } else {
                Button(onClick = onStart, enabled = canStart) {
                    Icon(Icons.Filled.PlayArrow, null)
                    Text(
                        stringResource(if (p.total > 0 && !p.finished) R.string.btn_resume else R.string.btn_start),
                        Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}
