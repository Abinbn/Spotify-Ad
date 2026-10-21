package com.spotad.recorder.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spotad.recorder.service.ContentKind
import com.spotad.recorder.service.NowPlaying

/**
 * A compact "what Spotify is doing" line. Not a hero card: it is reference information, so it
 * sits between the monitoring controls and the library.
 */
@Composable
fun NowPlayingCard(
    nowPlaying: NowPlaying,
    notificationAccess: Boolean,
    onOpenNotificationSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Spotify")
                Spacer(Modifier.width(8.dp))
                if (nowPlaying.detected) {
                    TagPill(
                        text = when {
                            nowPlaying.isAd -> "Ad playing"
                            nowPlaying.kind == ContentKind.PODCAST -> "Podcast"
                            nowPlaying.kind == ContentKind.MUSIC -> "Music"
                            else -> "Audio"
                        }
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            if (!notificationAccess) {
                Text(
                    text = "Notification access is off",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Without it SpotAd cannot tell when an ad starts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = onOpenNotificationSettings,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                ) {
                    Text("Open notification access")
                }
                return@Column
            }

            if (!nowPlaying.detected) {
                Text(
                    text = "Nothing playing",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Start something in Spotify and the current track will appear here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = nowPlaying.title.ifBlank { "Unknown title" },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (nowPlaying.artist.isNotBlank()) {
                    Text(
                        text = nowPlaying.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (nowPlaying.isPlaying) "Playing" else "Paused",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
