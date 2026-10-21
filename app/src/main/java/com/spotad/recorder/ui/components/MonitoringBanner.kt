package com.spotad.recorder.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spotad.recorder.service.MonitoringState
import com.spotad.recorder.service.ServiceUiState

/**
 * The single place where the user can see and control the recorder.
 *
 * It deliberately reads as a status panel rather than a "hero" banner: state, the three Android
 * prerequisites, and one primary action.
 */
@Composable
fun MonitoringCard(
    serviceState: ServiceUiState,
    micGranted: Boolean,
    notificationAccess: Boolean,
    onToggleMonitoring: () -> Unit,
    onRequestMic: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onRequestCapture: () -> Unit,
    onStopRecording: () -> Unit,
    onRecordNow: () -> Unit,
    modifier: Modifier = Modifier
) {
    val recording = serviceState.state == MonitoringState.RECORDING
    val listening = serviceState.state == MonitoringState.LISTENING

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            Row(verticalAlignment = Alignment.CenterVertically) {
                val dotColor = when {
                    recording -> MaterialTheme.colorScheme.error
                    listening -> MaterialTheme.colorScheme.primary
                    serviceState.monitoringEnabled -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.outline
                }
                StatusDot(dotColor, size = 10)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = when {
                        recording -> "Recording ad"
                        listening -> "Listening for ads"
                        serviceState.monitoringEnabled -> "Waiting for access"
                        else -> "Monitoring is off"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(6.dp))

            Text(
                text = when {
                    recording -> {
                        val title = serviceState.recordingTitle.ifBlank { "Spotify ad break" }
                        val advertiser = serviceState.recordingAdvertiser
                        if (advertiser.isBlank()) title else "$title · $advertiser"
                    }
                    listening -> "Spotify is being watched in the background. The next ad break is saved automatically."
                    serviceState.monitoringEnabled -> "Spotify can be watched, but audio capture is not available yet."
                    else -> "SpotAd only records while you have monitoring switched on."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            if (recording) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatTimer(serviceState.recordingSeconds),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.width(14.dp))
                    AmplitudeMeter(
                        level = serviceState.liveAmplitude,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            if (serviceState.lastError != null) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = serviceState.lastError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            if (!micGranted || !notificationAccess || !serviceState.captureGranted) {
                SetupRow(
                    label = "Microphone permission",
                    done = micGranted,
                    actionLabel = "Allow",
                    onAction = onRequestMic
                )
                SetupRow(
                    label = "Notification access (detects ads)",
                    done = notificationAccess,
                    actionLabel = "Open settings",
                    onAction = onOpenNotificationSettings
                )
                SetupRow(
                    label = "Audio capture access",
                    done = serviceState.captureGranted,
                    actionLabel = "Allow",
                    onAction = onRequestCapture
                )
                Spacer(Modifier.height(8.dp))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (recording) {
                    Button(
                        onClick = onStopRecording,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    ) {
                        Text("Stop recording")
                    }
                } else if (serviceState.monitoringEnabled) {
                    OutlinedButton(onClick = onToggleMonitoring) {
                        Text("Pause monitoring")
                    }
                } else {
                    Button(onClick = onToggleMonitoring) {
                        Text("Start listening")
                    }
                }

                if (!recording && serviceState.monitoringEnabled &&
                    serviceState.captureGranted && micGranted
                ) {
                    TextButton(onClick = onRecordNow) {
                        Text("Record now")
                    }
                }
                if (!serviceState.captureGranted && serviceState.monitoringEnabled && !recording) {
                    TextButton(onClick = onRequestCapture) {
                        Text("Enable audio capture")
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupRow(
    label: String,
    done: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (done) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Done",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(4.dp)
                    )
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = if (done) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (!done) {
            TextButton(onClick = onAction) {
                Text(actionLabel, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** Simple, honest level display driven by real captured amplitude. */
@Composable
fun AmplitudeMeter(level: Float, modifier: Modifier = Modifier) {
    val bars = 24
    val active = (level.coerceIn(0f, 1f) * bars).toInt().coerceIn(1, bars)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(bars) { index ->
            val filled = index < active
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(if (filled) 16.dp else 6.dp)
                    .background(
                        color = if (filled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(2.dp)
                    )
            )
        }
    }
}

private fun formatTimer(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    return "%d:%02d".format(safe / 60, safe % 60)
}
