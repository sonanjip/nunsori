package com.lsa.app.ui.index

import android.net.Uri
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lsa.app.R
import com.lsa.app.data.db.ItemKind
import com.lsa.app.indexing.PendingFile
import com.lsa.app.ui.common.ItemThumb
import com.lsa.app.ui.common.Meta
import com.lsa.app.ui.common.MyKeywordsSection
import com.lsa.app.ui.common.openFile
import com.lsa.app.ui.common.shareImage

/** A file that has no index row yet: what it is, why it waits, and its "My keywords". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingFileSheet(f: PendingFile, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ItemThumb(f.kind, f.uri, null, f.displayName, false, Modifier.fillMaxWidth().height(if (f.kind == ItemKind.IMAGE) 280.dp else 96.dp))
            Text(f.displayName, style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { shareImage(ctx, Uri.parse(f.uri), null) }) {
                    Icon(Icons.Filled.Share, null); Text(stringResource(R.string.action_share), Modifier.padding(start = 8.dp))
                }
                OutlinedButton(onClick = { openFile(ctx, Uri.parse(f.uri), f.displayName) }) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, null); Text(stringResource(R.string.action_open_with), Modifier.padding(start = 8.dp))
                }
            }
            Meta(stringResource(R.string.detail_status), stringResource(f.reason.label))
            Meta(stringResource(R.string.detail_path), f.folderKey.ifEmpty { "/" })
            Meta(stringResource(R.string.detail_size), Formatter.formatShortFileSize(ctx, f.sizeBytes))
            if (f.modifiedAt > 0) Meta(stringResource(R.string.detail_modified), DateFormat.format("yyyy-MM-dd HH:mm", f.modifiedAt).toString())
            MyKeywordsSection(f.sourceKey, indexed = false)
        }
    }
}
