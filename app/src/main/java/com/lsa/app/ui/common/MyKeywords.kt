package com.lsa.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.lsa.app.R
import com.lsa.app.indexing.Keywords
import com.lsa.app.rememberContainer
import kotlinx.coroutines.launch

/**
 * "My keywords": words the user adds to a file to find it by. Works for any file, indexed or not ([indexed] only
 * changes the hint underneath).
 */
@Composable
fun MyKeywordsSection(sourceKey: String, indexed: Boolean) {
    val container = rememberContainer()
    val scope = rememberCoroutineScope()
    val words by remember(sourceKey) { container.keywords.observe(sourceKey) }.collectAsState(initial = emptyList())
    val embedded by remember(sourceKey) { container.db.itemDao().observeEmbeddedKeywords(sourceKey) }.collectAsState(initial = 0)
    var input by remember(sourceKey) { mutableStateOf("") }
    val full = words.size >= Keywords.MAX_PER_FILE
    fun add() {
        val text = input
        if (text.isBlank()) return
        input = ""
        scope.launch { container.keywords.add(sourceKey, text) }
    }
    Column(Modifier.fillMaxWidth().testTag("my_keywords"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.my_keywords_title), style = MaterialTheme.typography.titleSmall)
        if (words.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                words.forEach { w ->
                    val remove = stringResource(R.string.my_keywords_remove, w.text)
                    InputChip(
                        selected = false,
                        onClick = { scope.launch { container.keywords.remove(sourceKey, w.text) } },
                        label = { Text(w.text) },
                        trailingIcon = { Icon(Icons.Filled.Close, null, Modifier.size(InputChipDefaults.IconSize)) },
                        modifier = Modifier.semantics { contentDescription = remove },
                    )
                }
            }
        }
        if (!full) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = input, onValueChange = { input = it },
                    label = { Text(stringResource(R.string.my_keywords_field)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add() }),
                    modifier = Modifier.weight(1f).testTag("my_keywords_input"),
                )
                TextButton(onClick = ::add, enabled = input.isNotBlank()) { Text(stringResource(R.string.my_keywords_add)) }
            }
        }
        val hint = when {
            full -> stringResource(R.string.my_keywords_full, Keywords.MAX_PER_FILE)
            !indexed && words.isNotEmpty() -> stringResource(R.string.my_keywords_later)
            indexed && embedded < words.size -> stringResource(R.string.my_keywords_pending)
            else -> stringResource(R.string.my_keywords_hint)
        }
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
