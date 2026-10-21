package com.spotad.recorder.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.spotad.recorder.audio.ExportManager
import com.spotad.recorder.data.RecordedAdEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdDetailsSheet(
    ad: RecordedAdEntity,
    onDismiss: () -> Unit,
    onSave: (id: Long, title: String, advertiser: String, themes: String, notes: String) -> Unit,
    onShare: () -> Unit,
    onExport: () -> Unit,
    onTrim: (startMillis: Long, endMillis: Long) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val clipboard = LocalClipboardManager.current

    var title by remember(ad.id) { mutableStateOf(ad.title) }
    var advertiser by remember(ad.id) { mutableStateOf(ad.advertiser) }
    var themes by remember(ad.id) { mutableStateOf(ad.themeTags) }
    var notes by remember(ad.id) { mutableStateOf(ad.notes) }
    var confirmDelete by remember { mutableStateOf(false) }
    val durationSeconds = (ad.durationMillis / 1000f).coerceAtLeast(0f)
    var trimStart by remember(ad.id, ad.durationMillis) { mutableStateOf(0f) }
    var trimEnd by remember(ad.id, ad.durationMillis) { mutableStateOf(durationSeconds) }
    val trimmedSeconds = (trimEnd - trimStart).coerceAtLeast(0f)
    val selectionIsWholeClip = trimmedSeconds >= durationSeconds - 0.5f

    val fileSize = remember(ad.id) { File(ad.filePath).length() }
    val recordedAt = remember(ad.id) {
        SimpleDateFormat("d MMMM yyyy, HH:mm", Locale.getDefault()).format(Date(ad.timestamp))
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(
                text = "Recording details",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Everything here stays on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(20.dp))

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = advertiser,
                onValueChange = { advertiser = it },
                label = { Text("Advertiser") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = themes,
                onValueChange = { themes = it },
                label = { Text("Tags (comma separated)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notes") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth()
            )

            if (durationSeconds >= 4f) {
                Spacer(Modifier.height(20.dp))
                SectionLabel("TRIM")
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Cut the start or the end. The audio is copied, never re-encoded.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                RangeSlider(
                    value = trimStart..trimEnd,
                    onValueChange = { range ->
                        trimStart = range.start
                        trimEnd = range.endInclusive
                    },
                    valueRange = 0f..durationSeconds
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${ExportManager.formatDuration((trimStart * 1000).toLong())} – " +
                            ExportManager.formatDuration((trimEnd * 1000).toLong()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = ExportManager.formatDuration((trimmedSeconds * 1000).toLong()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            onTrim((trimStart * 1000).toLong(), (trimEnd * 1000).toLong())
                        },
                        enabled = !selectionIsWholeClip,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Trim to selection")
                    }
                    OutlinedButton(
                        onClick = {
                            trimStart = 0f
                            trimEnd = durationSeconds
                        },
                        enabled = !selectionIsWholeClip
                    ) {
                        Text("Reset")
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            SectionLabel("FILE")
            Spacer(Modifier.height(8.dp))
            KeyValueRow("Recorded", recordedAt)
            KeyValueRow("Length", ExportManager.formatDuration(ad.durationMillis))
            KeyValueRow("Size", ExportManager.formatFileSize(fileSize))
            KeyValueRow("Sample rate", "${ad.sampleRate} Hz, mono")
            if (ad.source.isNotBlank()) KeyValueRow("Detected via", ad.source)
            KeyValueRow("File", File(ad.filePath).name)

            if (ad.transcriptSnippet.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                SectionLabel("TRANSCRIPT")
                Spacer(Modifier.height(6.dp))
                Text(
                    text = ad.transcriptSnippet,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (ad.keywords.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                SectionLabel("KEYWORDS")
                Spacer(Modifier.height(6.dp))
                Text(
                    text = ad.keywords.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    onSave(
                        ad.id,
                        title.trim(),
                        advertiser.trim(),
                        themes.split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", "),
                        notes.trim()
                    )
                },
                enabled = title.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save changes")
            }

            Spacer(Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(onClick = onShare) { Text("Share") }
                TextButton(onClick = onExport) { Text("Export") }
                TextButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(ExportManager.detailsText(ad)))
                    }
                ) { Text("Copy") }
                TextButton(onClick = { confirmDelete = true }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this recording?") },
            text = { Text("The audio file and its metadata will be removed from this device. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            }
        )
    }
}
