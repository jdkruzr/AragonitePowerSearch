package dev.aragonite.powersearch.ui

// pattern: Imperative Shell

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.aragonite.powersearch.data.db.IndexedShape

@Composable
fun SearchScreen(viewModel: SearchViewModel) {
    val query by viewModel.query.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // Search input
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::onQueryChange,
                label = { Text("Search handwriting") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Reindex controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${uiState.indexedShapeCount} shapes indexed",
                    style = MaterialTheme.typography.bodySmall
                )
                Button(
                    onClick = viewModel::reindex,
                    enabled = !uiState.isIndexing
                ) {
                    Text(if (uiState.isIndexing) "Indexing..." else "Reindex")
                }
            }

            // Progress indicator
            if (uiState.isIndexing) {
                val progress = uiState.indexProgress
                if (progress != null && progress.total > 0) {
                    LinearProgressIndicator(
                        progress = { progress.current.toFloat() / progress.total },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .testTag("LinearProgressIndicator")
                    )
                    Text(
                        text = "${progress.phase}: ${progress.current}/${progress.total}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .testTag("LinearProgressIndicator")
                    )
                    Text(
                        text = progress?.phase ?: "Starting\u2026",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // Error display
            uiState.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Results
            when {
                query.isBlank() && uiState.indexedShapeCount == 0 -> {
                    EmptyState("No indexed notes yet. Tap Reindex to start.")
                }
                query.isBlank() -> {
                    // AC3.4: Empty query shows no results
                }
                uiState.results.isEmpty() -> {
                    // AC3.5: No matches empty state
                    EmptyState("No results for \"$query\"")
                }
                else -> {
                    LazyColumn {
                        items(uiState.results, key = { it.shapeId }) { shape ->
                            SearchResultCard(
                                shape = shape,
                                onClick = { openNote(context, shape) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultCard(shape: IndexedShape, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = shape.noteTitle.ifBlank { "Untitled Note" },
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = shape.recognizedText,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3
            )
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = message, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Builds an Intent to open a note in BOOX Notes (ScribbleActivity).
 *
 * Extracts the Intent construction logic for testability.
 * The intent includes documentId, parentUniqueId, and a jump_from_document_path identifier.
 */
fun buildNoteIntent(shape: IndexedShape): Intent {
    // ScribbleActivity reads an OpenNoteBean as JSON from the "OPEN_NOTE_BEAN" string extra.
    // Discovered via JADX decompilation of knote2-release.apk (ScribbleActivity line 158).
    val openNoteJson = """{"documentId":"${shape.documentId}","parentUniqueId":"${shape.parentUniqueId}","title":"${shape.noteTitle.replace("\"", "\\\"")}"}"""
    return Intent().apply {
        component = ComponentName(
            "com.onyx.android.note",
            "com.onyx.android.note.note.ui.ScribbleActivity"
        )
        putExtra("OPEN_NOTE_BEAN", openNoteJson)
    }
}

private fun openNote(context: Context, shape: IndexedShape) {
    val intent = buildNoteIntent(shape)
    context.startActivity(intent)
}
