@file:OptIn(ExperimentalMaterial3Api::class)

package com.lsa.app.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.Surface
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import android.widget.Toast
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.lsa.app.R
import com.lsa.app.data.db.ItemEntity
import com.lsa.app.data.db.ItemKind
import com.lsa.app.data.db.ItemStatus
import com.lsa.app.data.db.ItemSummary
import com.lsa.app.data.db.SegmentEntity
import com.lsa.app.data.db.SegmentType
import com.lsa.app.rememberContainer
import com.lsa.app.service.Malbotdari

@Composable
fun Thumb(model: Any?, failed: Boolean, modifier: Modifier = Modifier, alignment: Alignment = Alignment.Center) {
    Box(modifier.then(Modifier), contentAlignment = Alignment.Center) {
        AsyncImage(
            model = model, contentDescription = null, contentScale = ContentScale.Crop, alignment = alignment,
            modifier = Modifier.fillMaxSize(),
        )
        if (failed) {
            Icon(Icons.Filled.BrokenImage, null, tint = MaterialTheme.colorScheme.error)
        }
    }
}

/** Preview for any item: the picture/preview image when there is one, otherwise an icon for the kind of file. */
@Composable
fun ItemThumb(kind: ItemKind, uri: String, thumbPath: String?, name: String, failed: Boolean, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    if (kind == ItemKind.IMAGE || thumbPath != null) {
        // pages and slides carry their content at the top: do not crop that away
        Thumb(thumbModel(ctx, kind, uri, thumbPath), failed, modifier, if (kind == ItemKind.IMAGE) Alignment.Center else Alignment.TopCenter)
        return
    }
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            val icon = when (kind) {
                ItemKind.AUDIO -> Icons.Filled.GraphicEq
                ItemKind.VIDEO -> Icons.Filled.Movie
                ItemKind.DOC -> if (name.endsWith(".pdf", true)) Icons.Filled.PictureAsPdf else Icons.Filled.Description
                else -> Icons.Filled.Description
            }
            Icon(icon, null, tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                name.substringAfterLast('.', "").uppercase().take(5),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

fun ItemSummary.thumbModelOrNull(ctx: Context): Any? = if (kind == ItemKind.IMAGE || thumbPath != null) thumbModel(ctx) else null

/** "p.12" or "03:21" for the part of a file that matched. */
fun locationLabel(page: Int?, startMs: Long?): String? = when {
    page != null -> "p.$page"
    startMs != null -> "%02d:%02d".format(startMs / 60_000, (startMs / 1000) % 60)
    else -> null
}

@Composable
fun WarningCard(text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.Warning, null)
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (actionLabel != null && onAction != null) TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** What to load for an item's preview: the picture itself for images, the stored preview file otherwise. */
fun thumbModel(ctx: Context, kind: ItemKind, uri: String, thumbPath: String?): Any =
    if (kind == ItemKind.IMAGE || thumbPath == null) uri else java.io.File(ctx.filesDir, "thumbs/$thumbPath")

fun ItemSummary.thumbModel(ctx: Context): Any = thumbModel(ctx, kind, uri, thumbPath)

@Composable
fun ItemDetailSheet(itemId: Long, onDismiss: () -> Unit) {
    val container = rememberContainer()
    val ctx = LocalContext.current
    var showMalbotDialog by remember { mutableStateOf(false) }
    val data by produceState<Pair<ItemEntity, List<SegmentEntity>>?>(null, itemId) {
        val dao = container.db.itemDao()
        value = dao.getItem(itemId)?.let { it to dao.segmentsOf(itemId) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        val (e, segments) = data ?: return@ModalBottomSheet
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val thumb = segments.firstNotNullOfOrNull { it.thumbPath }
            ItemThumb(e.kind, e.uri, thumb, e.displayName, e.status == ItemStatus.FAILED, Modifier.fillMaxWidth().height(if (thumb != null || e.kind == ItemKind.IMAGE) 280.dp else 96.dp))
            Text(e.title ?: e.displayName, style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (!Malbotdari.open(ctx, listOf(e.itemId))) {
                        if (!Malbotdari.isInstalled(ctx)) showMalbotDialog = true
                    }
                }) {
                    Icon(Icons.Outlined.ChatBubbleOutline, null)
                    Text(stringResource(R.string.chat_ask), Modifier.padding(start = 8.dp))
                }
                OutlinedButton(onClick = { shareImage(ctx, Uri.parse(e.uri), e.mimeType) }) {
                    Icon(Icons.Filled.Share, null); Text(stringResource(R.string.action_share), Modifier.padding(start = 8.dp))
                }
                OutlinedButton(onClick = { openImage(ctx, Uri.parse(e.uri), e.mimeType) }) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, null); Text(stringResource(R.string.action_open_with), Modifier.padding(start = 8.dp))
                }
            }
            Meta(stringResource(R.string.detail_path), e.folderKey.ifEmpty { "/" })
            if (e.width > 0) Meta(stringResource(R.string.detail_dimensions), "${e.width} × ${e.height}")
            Meta(stringResource(R.string.detail_size), Formatter.formatShortFileSize(ctx, e.sizeBytes))
            e.dateTaken?.let { Meta(stringResource(R.string.detail_taken), DateFormat.format("yyyy-MM-dd HH:mm", it).toString()) }
            if (e.latitude != null && e.longitude != null) {
                Meta(stringResource(R.string.detail_location), "%.5f, %.5f".format(e.latitude, e.longitude))
            }
            Meta(stringResource(R.string.detail_indexed), DateFormat.format("yyyy-MM-dd HH:mm", e.indexedAt).toString())
            Meta(stringResource(R.string.detail_model), e.embeddingModelId)
            e.error?.let { Meta(stringResource(R.string.detail_error), it) }
            if (e.kind == ItemKind.IMAGE) {
                val text = segments.filter { it.type == SegmentType.OCR_TEXT }.joinToString("\n") { it.text.orEmpty() }
                Text(stringResource(R.string.detail_ocr_text), style = MaterialTheme.typography.titleSmall)
                Text(
                    text.takeIf { it.isNotBlank() } ?: stringResource(R.string.detail_no_text),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                val parts = segments.filter { !it.text.isNullOrBlank() || it.type == SegmentType.VIDEO_CLIP || it.type == SegmentType.AUDIO_CLIP }
                Text(stringResource(R.string.detail_content), style = MaterialTheme.typography.titleSmall)
                parts.take(12).forEach { seg ->
                    val loc = locationLabel(seg.page, seg.startMs)
                    val end = seg.endMs?.let { locationLabel(null, it) }
                    val range = if (loc != null && end != null && seg.page == null) "$loc–$end" else loc
                    Text((if (range != null) "[$range] " else "") + (seg.text?.take(400) ?: seg.type.name.lowercase().replace('_', ' ')), style = MaterialTheme.typography.bodySmall)
                }
                if (parts.size > 12) Text("… +${parts.size - 12}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    if (showMalbotDialog) {
        AlertDialog(
            onDismissRequest = { showMalbotDialog = false },
            title = { Text(stringResource(R.string.malbot_title)) },
            text = { Text(stringResource(R.string.malbot_needed)) },
            confirmButton = {
                TextButton(onClick = {
                    Malbotdari.openReleases(ctx)
                    showMalbotDialog = false
                }) { Text(stringResource(R.string.malbot_get)) }
            },
            dismissButton = {
                TextButton(onClick = { showMalbotDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun Meta(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.35f))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.65f))
    }
}

fun displayFolder(relativePath: String): String = relativePath.trimEnd('/').ifEmpty { "/" }

/** `file://` URIs cannot be handed to other apps; wrap them with our FileProvider. */
fun shareableUri(ctx: Context, uri: Uri): Uri =
    if (uri.scheme == "file") androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", java.io.File(uri.path!!)) else uri

fun shareImage(ctx: Context, uri: Uri, mime: String?) {
    val uri = shareableUri(ctx, uri)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime ?: "image/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = android.content.ClipData.newRawUri(null, uri)
    }
    launch(ctx, Intent.createChooser(send, null))
}

fun openImage(ctx: Context, uri: Uri, mime: String?) {
    val uri = shareableUri(ctx, uri)
    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime ?: "image/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    launch(ctx, Intent.createChooser(view, null))
}

/** Open any indexed file in an app that can show it (the type comes from the system, then from the name). */
fun openFile(ctx: Context, uri: Uri, name: String) {
    val mime = ctx.contentResolver.getType(uri)?.takeIf { it != "application/octet-stream" }
        ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
    openImage(ctx, uri, mime ?: "*/*")
}

private fun launch(ctx: Context, intent: Intent) {
    try { ctx.startActivity(intent) } catch (_: ActivityNotFoundException) {
        Toast.makeText(ctx, R.string.no_app_found, Toast.LENGTH_SHORT).show()
    }
}

fun shareImages(ctx: Context, rawUris: List<Uri>) {
    val uris = rawUris.map { shareableUri(ctx, it) }
    if (uris.size == 1) return shareImage(ctx, uris[0], null)
    val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "image/*"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = android.content.ClipData.newRawUri(null, uris.first()).also { cd ->
            uris.drop(1).forEach { cd.addItem(android.content.ClipData.Item(it)) }
        }
    }
    launch(ctx, Intent.createChooser(send, null))
}
