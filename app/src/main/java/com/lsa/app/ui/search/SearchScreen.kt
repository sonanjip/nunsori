package com.lsa.app.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.TextButton
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.core.net.toUri
import com.lsa.app.ui.common.shareImages
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lsa.app.R
import com.lsa.app.data.db.ItemStatus
import com.lsa.app.rememberContainer
import com.lsa.app.search.MetaMatch
import com.lsa.app.ui.common.ItemDetailSheet
import com.lsa.app.ui.common.ItemThumb
import com.lsa.app.ui.common.locationLabel
import com.lsa.app.ui.common.WarningCard

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(onOpenSettings: () -> Unit) {
    val container = rememberContainer()
    val vm: SearchViewModel = viewModel(factory = viewModelFactory { initializer { SearchViewModel(container) } })
    val state by vm.state.collectAsStateWithLifecycle()
    var detailId by remember { mutableStateOf<Long?>(null) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    val ctx = LocalContext.current
    val selecting = selected.isNotEmpty()
    var askChat by remember { mutableStateOf(false) }
    // New results invalidate the selection.
    LaunchedEffect(state) { selected = emptySet() }
    BackHandler(enabled = selecting) { selected = emptySet() }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = vm.query, onValueChange = { vm.query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.search_hint)) },
            trailingIcon = {
                IconButton(onClick = vm::search) { Icon(Icons.Filled.Search, stringResource(R.string.nav_search)) }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.search() }),
        )
        FilterBar(vm)
        val stale by vm.staleCount.collectAsStateWithLifecycle()
        if (stale > 0) WarningCard(pluralStringResource(R.plurals.search_reindexing, stale, stale))
        when (val s = state) {
            SearchUi.Idle -> Hint(stringResource(R.string.search_idle))
            SearchUi.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            is SearchUi.Failed -> WarningCard(s.message)
            is SearchUi.Done -> {
                if (selecting) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(onClick = { selected = emptySet() }) {
                            Icon(Icons.Filled.Close, stringResource(R.string.action_cancel))
                        }
                        Text(
                            pluralStringResource(R.plurals.selection_count, selected.size, selected.size),
                            style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { selected = s.hits.map { it.hit.itemId }.toSet() }) {
                            Text(stringResource(R.string.select_all))
                        }
                        TextButton(onClick = { askChat = true }) { Text(stringResource(R.string.chat_ask)) }
                        IconButton(onClick = {
                            shareImages(ctx, s.hits.filter { it.hit.itemId in selected }.map { it.summary.uri.toUri() })
                        }) { Icon(Icons.Filled.Share, stringResource(R.string.action_share)) }
                    }
                }
                if (!s.semanticAvailable) {
                    WarningCard(stringResource(R.string.search_model_missing), stringResource(R.string.go_settings), onOpenSettings)
                }
                if (s.hits.isEmpty()) Hint(stringResource(R.string.search_no_results))
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(s.hits, key = { it.hit.itemId }) { r ->
                        val id = r.hit.itemId
                        val isSel = id in selected
                        fun toggle() { selected = if (isSel) selected - id else selected + id }
                        Card(
                            Modifier.combinedClickable(
                                onClick = { if (selecting) toggle() else detailId = id },
                                onLongClick = { toggle() },
                            ),
                            border = if (isSel) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null,
                        ) {
                            Box {
                                ItemThumb(r.summary.kind, r.summary.uri, r.thumbPath, r.summary.displayName, r.summary.status == ItemStatus.FAILED, Modifier.fillMaxWidth().aspectRatio(1f))
                                if (isSel) {
                                    Icon(
                                        Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                                    )
                                }
                            }
                            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    r.hit.visualSim?.let { Badge(stringResource(R.string.badge_image), "%.2f".format(it)) }
                                    r.hit.textSim?.let { Badge(stringResource(R.string.badge_ocr), "%.2f".format(it)) }
                                    r.hit.audioSim?.let { Badge(stringResource(R.string.badge_audio), "%.2f".format(it)) }
                                    if (r.hit.userKeyword) Badge(stringResource(R.string.badge_my_keyword), null)
                                    else if (r.hit.keywordMatches > 0) Badge(stringResource(R.string.badge_keyword), null)
                                    when (r.hit.metaMatch) {
                                        MetaMatch.PLACE -> Badge(stringResource(R.string.badge_place), null)
                                        MetaMatch.NAME -> Badge(stringResource(R.string.badge_name), null)
                                        null -> {}
                                    }
                                }
                                Text(
                                    listOfNotNull(r.summary.displayName, locationLabel(r.hit.best?.page, r.hit.best?.startMs)).joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall, maxLines = 1,
                                )
                                r.snippet?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (askChat) {
        // the chat lives in the Malbotdari app: hand it the selected files, or say where to get it
        val ids = (state as? SearchUi.Done)?.hits?.map { it.hit.itemId }?.filter { it in selected }.orEmpty()
        val ctx = androidx.compose.ui.platform.LocalContext.current
        androidx.compose.runtime.LaunchedEffect(Unit) {
            if (com.lsa.app.service.Malbotdari.open(ctx, ids)) { askChat = false; selected = emptySet() }
        }
        if (!com.lsa.app.service.Malbotdari.isInstalled(ctx)) androidx.compose.material3.AlertDialog(
            onDismissRequest = { askChat = false },
            title = { Text(stringResource(R.string.malbot_title)) },
            text = { Text(stringResource(R.string.malbot_needed)) },
            confirmButton = { TextButton(onClick = { com.lsa.app.service.Malbotdari.openReleases(ctx); askChat = false }) { Text(stringResource(R.string.malbot_get)) } },
            dismissButton = { TextButton(onClick = { askChat = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    detailId?.let { ItemDetailSheet(it) { detailId = null } }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
}

@Composable
private fun Badge(label: String, value: String?) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(
            if (value == null) label else "$label $value",
            style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterBar(vm: SearchViewModel) {
    var pickRange by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                FileCategory.DOCUMENT to R.string.filter_documents, FileCategory.IMAGE to R.string.filter_images,
                FileCategory.AUDIO to R.string.filter_audio, FileCategory.VIDEO to R.string.filter_video,
            ).forEach { (cat, label) ->
                FilterChip(selected = cat in vm.categories, onClick = { vm.toggleCategory(cat) }, label = { Text(stringResource(label)) })
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                DatePreset.ALL to R.string.date_all, DatePreset.TODAY to R.string.date_today, DatePreset.WEEK to R.string.date_7d,
                DatePreset.MONTH to R.string.date_30d, DatePreset.YEAR to R.string.date_year,
            ).forEach { (p, label) ->
                FilterChip(selected = vm.datePreset == p, onClick = { vm.selectDate(p) }, label = { Text(stringResource(label)) })
            }
            FilterChip(
                selected = vm.datePreset == DatePreset.CUSTOM, onClick = { pickRange = true },
                label = {
                    val r = vm.customRange
                    Text(
                        if (vm.datePreset == DatePreset.CUSTOM && r != null) {
                            val f = android.text.format.DateFormat.getMediumDateFormat(LocalContext.current)
                            "${f.format(r.first)} - ${f.format(r.second)}"
                        } else stringResource(R.string.date_custom),
                    )
                },
            )
        }
    }
    if (pickRange) {
        val state = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = { pickRange = false },
            confirmButton = {
                TextButton(
                    enabled = state.selectedStartDateMillis != null,
                    onClick = {
                        val s = state.selectedStartDateMillis!!
                        vm.setCustomRange(s, state.selectedEndDateMillis ?: s); pickRange = false
                    },
                ) { Text(stringResource(R.string.dialog_ok)) }
            },
            dismissButton = { TextButton(onClick = { pickRange = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) { DateRangePicker(state, Modifier.padding(top = 8.dp), showModeToggle = false) }
    }
}
