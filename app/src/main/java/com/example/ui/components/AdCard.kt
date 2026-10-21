package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio.PlaybackState
import com.example.data.RecordedAdEntity
import com.example.ui.theme.AmberGold
import com.example.ui.theme.SignalRed
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
fun AdCard(
    ad: RecordedAdEntity,
    playbackState: PlaybackState,
    onPlayToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onFavoriteToggle: () -> Unit,
    onExportMp3: () -> Unit,
    onShare: () -> Unit,
    onInspect: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val isCurrentPlaying = playbackState.adId == ad.id && playbackState.isPlaying
    val isCurrentSelected = playbackState.adId == ad.id

    // Parse waveform points
    val waveformPoints = remember(ad.waveformPoints) {
        if (ad.waveformPoints.isNotBlank()) {
            ad.waveformPoints.split(",").mapNotNull { it.trim().toFloatOrNull() }
        } else {
            List(36) { 0.2f + (it % 4) * 0.2f }
        }
    }

    val playProgress = if (isCurrentSelected && playbackState.durationMs > 0) {
        (playbackState.currentPositionMs.toFloat() / playbackState.durationMs).coerceIn(0f, 1f)
    } else 0f

    val dateFormatted = remember(ad.timestamp) {
        val now = System.currentTimeMillis()
        val diff = now - ad.timestamp
        when {
            diff < 3600_000L -> "${maxOf(1, (diff / 60_000L).toInt())}m ago"
            diff < 86400_000L -> "${(diff / 3600_000L).toInt()}h ago"
            else -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(ad.timestamp))
        }
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrentPlaying) StudioElevatedSurface else StudioCardSurface
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isCurrentPlaying) SpotifyGreen.copy(alpha = 0.8f) else StudioBorder
        ),
        modifier = modifier
            .fillMaxWidth()
            .testTag("ad_card_${ad.id}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header: Advertiser badge, timestamp & favorite star
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Advertiser Tag
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF1B3D26)
                    ) {
                        Text(
                            text = ad.advertiser.ifBlank { "Spotify" },
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = SpotifyGreen,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = dateFormatted,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = TextMuted
                    )

                    // 30-day automated cleanup warning if non-favorite and older than 20 days
                    val daysOld = remember(ad.timestamp) {
                        ((System.currentTimeMillis() - ad.timestamp) / (24L * 60L * 60L * 1000L)).toInt()
                    }
                    if (!ad.isFavorite && daysOld >= 20) {
                        val daysLeft = maxOf(1, 30 - daysOld)
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFF3B1E12)
                        ) {
                            Text(
                                text = "Auto-delete in ${daysLeft}d",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = SignalRed,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                // Favorite Star
                IconButton(
                    onClick = onFavoriteToggle,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = if (ad.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = if (ad.isFavorite) "Unfavorite" else "Favorite",
                        tint = if (ad.isFavorite) AmberGold else TextMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Ad Campaign Title
            Text(
                text = ad.title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                ),
                color = TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // Transcript snippet preview if present
            if (ad.transcriptSnippet.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "\"${ad.transcriptSnippet}\"",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    ),
                    color = TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Interactive Waveform with Playback Scrubber
            WaveformDisplay(
                waveformPoints = waveformPoints,
                playProgress = playProgress,
                isCurrentPlaying = isCurrentPlaying,
                onSeekFraction = { fraction ->
                    val targetMs = (fraction * ad.durationMillis).toLong()
                    onSeek(targetMs)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Extracted spoken keywords & themes
            val keywordsList = remember(ad.spokenKeywords) {
                if (ad.spokenKeywords.isNotBlank()) {
                    ad.spokenKeywords.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                } else emptyList()
            }
            val themesList = remember(ad.themeTags) {
                if (ad.themeTags.isNotBlank()) {
                    ad.themeTags.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                } else emptyList()
            }

            if (keywordsList.isNotEmpty() || themesList.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    keywordsList.take(3).forEach { kw ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = StudioElevatedSurface
                        ) {
                            Text(
                                text = "🗣 $kw",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                color = TextSecondary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    themesList.take(2).forEach { theme ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF282512)
                        ) {
                            Text(
                                text = "#$theme",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                ),
                                color = AmberGold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Footer controls: Play button, Duration pill, MP3 Export, Share, Info, Delete
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Play / Pause + Duration
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledIconButton(
                        onClick = onPlayToggle,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (isCurrentPlaying) SpotifyGreen else StudioElevatedSurface,
                            contentColor = if (isCurrentPlaying) Color.Black else TextPrimary
                        ),
                        modifier = Modifier
                            .size(38.dp)
                            .testTag("play_button_${ad.id}")
                    ) {
                        Icon(
                            imageVector = if (isCurrentPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isCurrentPlaying) "Pause" else "Play",
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    val durationSec = (ad.durationMillis / 1000).toInt()
                    val min = durationSec / 60
                    val sec = durationSec % 60
                    Text(
                        text = String.format("%02d:%02d", min, sec),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        ),
                        color = TextSecondary
                    )
                }

                // Action buttons: Export MP3, Share, Inspect, Delete
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Export to Music/SpotifyAds folder
                    IconButton(
                        onClick = onExportMp3,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("export_button_${ad.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.FileDownload,
                            contentDescription = "Export MP3",
                            tint = SpotifyGreen,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Share
                    IconButton(
                        onClick = onShare,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("share_button_${ad.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share MP3",
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Metadata details
                    IconButton(
                        onClick = onInspect,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("inspect_button_${ad.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Inspect Details",
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Delete
                    IconButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("delete_button_${ad.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Delete",
                            tint = TextMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Recorded Ad?", color = TextPrimary) },
            text = {
                Text(
                    "Are you sure you want to delete \"${ad.title}\"? The audio file and its metadata will be permanently removed.",
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    }
                ) {
                    Text("Delete", color = SignalRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = StudioElevatedSurface
        )
    }
}

@Composable
fun WaveformDisplay(
    waveformPoints: List<Float>,
    playProgress: Float,
    isCurrentPlaying: Boolean,
    onSeekFraction: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(StudioCardSurface)
            .pointerInput(waveformPoints) {
                detectTapGestures { offset ->
                    val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                    onSeekFraction(fraction)
                }
            }
    ) {
        val count = waveformPoints.size
        if (count == 0) return@Canvas

        val barWidth = size.width / (count * 1.4f)
        val gap = barWidth * 0.4f

        for (i in 0 until count) {
            val fraction = i.toFloat() / count
            val isPlayed = fraction <= playProgress
            val amp = waveformPoints[i].coerceIn(0.15f, 1.0f)
            val barHeight = size.height * amp
            val x = i * (barWidth + gap)
            val y = (size.height - barHeight) / 2f

            val barColor = when {
                isPlayed -> SpotifyGreen
                isCurrentPlaying -> Color(0xFF4A4A4A)
                else -> Color(0xFF383838)
            }

            drawRoundRect(
                color = barColor,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2, barWidth / 2)
            )
        }

        // Draw active playhead line if playing
        if (playProgress > 0f) {
            val playheadX = size.width * playProgress
            drawLine(
                color = Color.White,
                start = Offset(playheadX, 0f),
                end = Offset(playheadX, size.height),
                strokeWidth = 2.dp.toPx()
            )
        }
    }
}
