package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.RecordedAdEntity
import com.example.ui.theme.AmberGold
import com.example.ui.theme.SpotifyGreen
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioCardSurface
import com.example.ui.theme.StudioElevatedSurface
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdDetailsDialog(
    ad: RecordedAdEntity,
    onDismiss: () -> Unit,
    onSaveMetadata: (Long, String, String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    var editMode by remember { mutableStateOf(false) }
    var editTitle by remember { mutableStateOf(ad.title) }
    var editAdvertiser by remember { mutableStateOf(ad.advertiser) }
    var editThemes by remember { mutableStateOf(ad.themeTags) }

    val formattedDate = remember(ad.timestamp) {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ad.timestamp))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (editMode) "Edit Ad Metadata" else "Ad Metadata & Speech Tags",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                if (editMode) {
                    // Editable fields
                    OutlinedTextField(
                        value = editTitle,
                        onValueChange = { editTitle = it },
                        label = { Text("Ad Title") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = SpotifyGreen,
                            unfocusedBorderColor = StudioBorder
                        )
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = editAdvertiser,
                        onValueChange = { editAdvertiser = it },
                        label = { Text("Advertiser / Brand") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = SpotifyGreen,
                            unfocusedBorderColor = StudioBorder
                        )
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = editThemes,
                        onValueChange = { editThemes = it },
                        label = { Text("Themes (comma-separated)") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = SpotifyGreen,
                            unfocusedBorderColor = StudioBorder
                        )
                    )
                } else {
                    // View mode
                    Text(
                        text = ad.title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                    Text(
                        text = "Advertiser: ${ad.advertiser}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SpotifyGreen
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Discernible Spoken Keywords
                    Text(
                        text = "DISCERNIBLE SPOKEN KEYWORDS:",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        ),
                        color = TextMuted
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    val kwList = ad.spokenKeywords.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    if (kwList.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            kwList.forEach { kw ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = StudioElevatedSurface
                                ) {
                                    Text(
                                        text = "🗣 $kw",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextPrimary,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    } else {
                        Text("No specific keywords detected", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Thematic Classification
                    Text(
                        text = "CLASSIFIED THEMES:",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        ),
                        color = TextMuted
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    val themeList = ad.themeTags.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        themeList.forEach { th ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF282512)
                            ) {
                                Text(
                                    text = "#$th",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = AmberGold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Transcript Snippet
                    if (ad.transcriptSnippet.isNotBlank()) {
                        Text(
                            text = "AUDIO TRANSCRIPT SNIPPET:",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            ),
                            color = TextMuted
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = StudioElevatedSurface,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "\"${ad.transcriptSnippet}\"",
                                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                                color = TextSecondary,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // Technical specs
                    Text(
                        text = "RECORDING & FILE SPECS:",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        ),
                        color = TextMuted
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(StudioElevatedSurface)
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("Recorded At: $formattedDate", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        Text("Duration: ${ad.durationMillis / 1000}s (${ad.durationMillis} ms)", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        Text("Fingerprint Match: ${(ad.audioConfidence * 100).toInt()}% Confidence", style = MaterialTheme.typography.bodySmall, color = SpotifyGreen)
                        val sizeKb = ad.fileSizeBytes / 1024
                        Text("File Size: ${if (sizeKb > 1024) "%.1f MB".format(sizeKb / 1024f) else "$sizeKb KB"}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        Text("Companion Metadata: Saved alongside MP3 (.json)", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                    }
                }
            }
        },
        confirmButton = {
            if (editMode) {
                Button(
                    onClick = {
                        onSaveMetadata(ad.id, editTitle, editAdvertiser, editThemes)
                        editMode = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black)
                ) {
                    Icon(Icons.Default.Save, contentDescription = "Save", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Save", fontWeight = FontWeight.Bold)
                }
            } else {
                TextButton(onClick = { editMode = true }) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Edit Metadata", color = SpotifyGreen)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                if (editMode) editMode = false else onDismiss()
            }) {
                Text(if (editMode) "Cancel" else "Close", color = TextSecondary)
            }
        },
        containerColor = StudioCardSurface,
        modifier = modifier
    )
}
