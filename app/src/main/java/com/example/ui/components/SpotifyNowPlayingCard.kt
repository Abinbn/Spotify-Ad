package com.example.ui.components

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.service.MonitoringState
import com.example.service.ServiceUiState
import com.example.service.SpotifyNowPlaying
import com.example.ui.theme.AmberGold
import com.example.ui.theme.SignalRed
import com.example.ui.theme.SpotifyGreen
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SpotifyNowPlayingCard(
    nowPlaying: SpotifyNowPlaying,
    serviceState: ServiceUiState,
    isNotificationAccessGranted: Boolean,
    onOpenNotificationSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val bar1Phase by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar1"
    )
    val bar2Phase by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(650, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar2"
    )
    val bar3Phase by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(520, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar3"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Notification Access Warning Banner if not enabled
        if (!isNotificationAccessGranted) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFF381F14),
                border = androidx.compose.foundation.BorderStroke(1.dp, AmberGold.copy(alpha = 0.8f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.NotificationsActive,
                            contentDescription = "Notification Access",
                            tint = AmberGold,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Notification Access Needed",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary
                            )
                            Text(
                                text = "Required for MediaSession & Notification reader to detect Spotify in background",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = TextSecondary
                            )
                        }
                    }

                    Button(
                        onClick = onOpenNotificationSettings,
                        colors = ButtonDefaults.buttonColors(containerColor = AmberGold, contentColor = Color.Black),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .height(34.dp)
                            .testTag("grant_notification_access_button")
                    ) {
                        Text("Enable", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        val hasActiveSpotify = nowPlaying.isSpotifyDetected &&
                (nowPlaying.title.isNotBlank() || nowPlaying.artist.isNotBlank())

        val displayTitle = when {
            nowPlaying.title.isNotBlank() -> nowPlaying.title
            nowPlaying.isAdBreak -> "Spotify Advertisement"
            hasActiveSpotify -> "Spotify Audio Track"
            else -> ""
        }

        val displayArtist = when {
            nowPlaying.artist.isNotBlank() -> nowPlaying.artist
            nowPlaying.isAdBreak -> "Spotify Sponsor"
            hasActiveSpotify -> "Spotify"
            else -> ""
        }

        // Live Spotify Now Playing Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                when {
                    nowPlaying.isAdBreak -> SignalRed.copy(alpha = 0.85f)
                    hasActiveSpotify && nowPlaying.isPlaying -> MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                    hasActiveSpotify -> MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                    else -> MaterialTheme.colorScheme.outline
                }
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("spotify_now_playing_card")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp)
            ) {
                // Header with live status & Content Type Badge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        nowPlaying.isAdBreak -> SignalRed.copy(alpha = pulseAlpha)
                                        hasActiveSpotify && nowPlaying.isPlaying -> MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha)
                                        hasActiveSpotify -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                        else -> TextMuted
                                    }
                                )
                        )
                        Spacer(modifier = Modifier.width(7.dp))
                        Text(
                            text = when {
                                nowPlaying.isAdBreak -> "🔴 DETECTED SPOTIFY AD (RECORDING)"
                                hasActiveSpotify && nowPlaying.isPlaying && serviceState.state == MonitoringState.IDLE -> "SPOTIFY ACTIVE (MONITOR PAUSED)"
                                hasActiveSpotify && nowPlaying.isPlaying -> "NOW PLAYING ON SPOTIFY"
                                serviceState.state == MonitoringState.IDLE -> "MONITOR PAUSED (STANDBY)"
                                serviceState.isTravelModeActive || serviceState.state == MonitoringState.TRAVEL_INACTIVE -> "TRAVEL MODE (MONITOR DISABLED)"
                                else -> "LISTENING FOR SPOTIFY (ACTIVE)"
                            },
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp
                            ),
                            color = when {
                                nowPlaying.isAdBreak -> SignalRed
                                hasActiveSpotify && nowPlaying.isPlaying -> MaterialTheme.colorScheme.primary
                                serviceState.state == MonitoringState.IDLE -> AmberGold
                                else -> TextMuted
                            }
                        )
                    }

                    // Content Type Pill Badge (Song, Podcast, Ad)
                    if (hasActiveSpotify) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = when (nowPlaying.contentType) {
                                "Advertisement" -> Color(0xFF4A181C)
                                "Podcast Episode" -> Color(0xFF1E2D40)
                                else -> MaterialTheme.colorScheme.primaryContainer
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = when (nowPlaying.contentType) {
                                        "Advertisement" -> Icons.Default.Radio
                                        "Podcast Episode" -> Icons.Default.Mic
                                        else -> Icons.Default.MusicNote
                                    },
                                    contentDescription = null,
                                    tint = when (nowPlaying.contentType) {
                                        "Advertisement" -> SignalRed
                                        "Podcast Episode" -> Color(0xFF64D2FF)
                                        else -> MaterialTheme.colorScheme.primary
                                    },
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = nowPlaying.contentType,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp
                                    ),
                                    color = when (nowPlaying.contentType) {
                                        "Advertisement" -> SignalRed
                                        "Podcast Episode" -> Color(0xFF64D2FF)
                                        else -> MaterialTheme.colorScheme.primary
                                    }
                                )
                            }
                        }
                    } else if (isNotificationAccessGranted) {
                        // MediaSession listener active badge
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(11.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "Listener Ready",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (hasActiveSpotify) {
                    // Active Spotify item playing: Show Title, Artist, Total Duration, and Timestamp
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            // Track Name
                            Text(
                                text = displayTitle,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 17.sp
                                ),
                                color = if (nowPlaying.isAdBreak) SignalRed else TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            // Artist
                            if (displayArtist.isNotBlank()) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = displayArtist,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                    color = if (nowPlaying.isAdBreak) SignalRed else MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            // Album / Playlist
                            if (nowPlaying.album.isNotBlank()) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Album / Source: ${nowPlaying.album}",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                    color = TextMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        // Equalizer bars when playing
                        if (nowPlaying.isPlaying) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Canvas(modifier = Modifier.size(width = 24.dp, height = 24.dp)) {
                                val barColor = if (nowPlaying.isAdBreak) SignalRed else SpotifyGreen
                                val barWidth = 4.dp.toPx()
                                val gap = 3.dp.toPx()

                                drawRoundRect(
                                    color = barColor,
                                    topLeft = Offset(0f, size.height * (1f - bar1Phase)),
                                    size = Size(barWidth, size.height * bar1Phase),
                                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                                )
                                drawRoundRect(
                                    color = barColor,
                                    topLeft = Offset(barWidth + gap, size.height * (1f - bar2Phase)),
                                    size = Size(barWidth, size.height * bar2Phase),
                                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                                )
                                drawRoundRect(
                                    color = barColor,
                                    topLeft = Offset((barWidth + gap) * 2, size.height * (1f - bar3Phase)),
                                    size = Size(barWidth, size.height * bar3Phase),
                                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                                )
                            }
                        }
                    }

                    // Progress bar if position and duration are known
                    val totalSec = (nowPlaying.totalLengthMillis / 1000).toInt()
                    val posSec = (nowPlaying.currentPositionMillis / 1000).toInt()

                    if (totalSec > 0 && posSec > 0 && posSec <= totalSec) {
                        Spacer(modifier = Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { (posSec.toFloat() / totalSec.toFloat()).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = if (nowPlaying.isAdBreak) SignalRed else MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Duration and Timestamp row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Total Duration
                        val durationFormatted = when {
                            posSec > 0 && totalSec > 0 -> {
                                String.format(
                                    "%02d:%02d / %02d:%02d",
                                    posSec / 60, posSec % 60,
                                    totalSec / 60, totalSec % 60
                                )
                            }
                            totalSec > 0 -> {
                                String.format("%02d:%02d", totalSec / 60, totalSec % 60)
                            }
                            else -> "Live Stream"
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Duration: $durationFormatted",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 11.sp),
                                color = TextSecondary
                            )
                        }

                        // Time Stamp of detection
                        val timestampFormatted = remember(nowPlaying.lastDetectedTimestamp) {
                            if (nowPlaying.lastDetectedTimestamp > 0) {
                                SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date(nowPlaying.lastDetectedTimestamp))
                            } else "Just now"
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Schedule,
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Detected: $timestampFormatted",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = TextMuted
                            )
                        }
                    }

                    // Source label (Notification / MediaSession / Broadcast)
                    if (nowPlaying.source.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Source: ${nowPlaying.source}",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                            color = TextMuted.copy(alpha = 0.8f)
                        )
                    }
                } else {
                    // Spotify idle / waiting - synchronized with monitor state
                    val (idleIcon, idleTitle, idleSubtitle) = when {
                        serviceState.isTravelModeActive || serviceState.state == MonitoringState.TRAVEL_INACTIVE -> Triple(
                            Icons.Default.Flight,
                            "Travel Mode Active",
                            "Background monitoring is disabled to conserve battery while travelling. You can toggle this in Settings."
                        )
                        serviceState.state == MonitoringState.IDLE -> Triple(
                            Icons.Default.PauseCircle,
                            "Spotify Monitor is Paused",
                            "Tap 'Start' above to activate background Spotify monitoring and automatic ad detection."
                        )
                        else -> Triple(
                            Icons.Default.Headphones,
                            "Monitoring Active • Ready for Spotify",
                            "Listening in the background. Play any song, podcast, or ad on Spotify and active metadata will appear here dynamically."
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = idleIcon,
                            contentDescription = null,
                            tint = if (serviceState.state == MonitoringState.IDLE) AmberGold else TextMuted,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = idleTitle,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = if (serviceState.state == MonitoringState.IDLE) AmberGold else TextSecondary
                            )
                            Text(
                                text = idleSubtitle,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 15.sp),
                                color = TextMuted
                            )
                        }
                    }
                }
            }
        }
    }
}
