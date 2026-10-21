package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoDelete
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.service.MonitoringState
import com.example.service.ServiceUiState
import com.example.ui.theme.AmberGold
import com.example.ui.theme.SignalRed
import com.example.ui.theme.SpotifyGreen
import com.example.ui.theme.StudioCardSurface
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun StudioTopBar(
    serviceState: ServiceUiState,
    oldAdsCount: Int,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // App Branding & State pill
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (serviceState.isTravelModeActive) Icons.Default.Flight else Icons.Default.GraphicEq,
                        contentDescription = "SpotAd Logo",
                        tint = if (serviceState.state == MonitoringState.RECORDING) SignalRed
                        else if (serviceState.isTravelModeActive) MaterialTheme.colorScheme.primary
                        else if (serviceState.isRunning) SpotifyGreen
                        else TextMuted,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "SpotAd",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = (-0.5).sp
                            ),
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (serviceState.isTravelModeActive) "TRAVEL" else "REC",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = if (serviceState.state == MonitoringState.RECORDING) SignalRed
                            else if (serviceState.isTravelModeActive) MaterialTheme.colorScheme.primary
                            else SpotifyGreen
                        )
                    }

                    // Live Status Subtitle
                    val (statusText, statusColor) = when (serviceState.state) {
                        MonitoringState.RECORDING -> Pair("Recording Live Ad", SignalRed)
                        MonitoringState.ECO_SLEEP -> Pair("Eco-Sleep (Screen Off)", Color(0xFF64D2FF))
                        MonitoringState.SLEEPING -> Pair("Monitoring Spotify (Active)", if (serviceState.isTravelModeActive) MaterialTheme.colorScheme.primary else SpotifyGreen)
                        MonitoringState.TRAVEL_INACTIVE -> Pair("Travel Inactive (Orange Mode)", MaterialTheme.colorScheme.primary)
                        MonitoringState.IDLE -> Pair("Monitor Paused (Standby)", TextMuted)
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(statusColor)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = TextSecondary
                        )
                    }
                }
            }

            // Settings & System Controls Trigger
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .size(44.dp)
                    .testTag("settings_button")
            ) {
                if (oldAdsCount > 0) {
                    BadgedBox(
                        badge = {
                            Badge(containerColor = AmberGold) {
                                Text("$oldAdsCount")
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings & Storage",
                            tint = if (serviceState.isTravelModeActive) MaterialTheme.colorScheme.primary else TextPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                } else {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings & Storage",
                        tint = if (serviceState.isTravelModeActive) MaterialTheme.colorScheme.primary else TextSecondary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}
