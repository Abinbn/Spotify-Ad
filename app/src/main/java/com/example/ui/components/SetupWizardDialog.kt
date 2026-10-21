package com.example.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoDelete
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Launch
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.service.SpotifyNotificationListener
import com.example.ui.theme.AmberGold
import com.example.ui.theme.OrangePrimary
import com.example.ui.theme.SignalRed
import com.example.ui.theme.SpotifyGreen
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBottomDrawer(
    isTravelModeActive: Boolean,
    onToggleTravelMode: () -> Unit,
    oldAdsCount: Int,
    totalAdsCount: Int,
    onManualCleanup: () -> Unit,
    onDeleteNonStarredAds: () -> Unit,
    onClearAllAds: () -> Unit,
    onRequestMediaProjection: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showDeleteNonStarredConfirm by remember { mutableStateOf(false) }
    var showClearAllConfirm by remember { mutableStateOf(false) }

    val isNotifAccessGranted = remember(context) {
        SpotifyNotificationListener.isNotificationAccessGranted(context)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        modifier = modifier.testTag("settings_bottom_drawer")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header with Title and Close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Settings & Preferences",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                    Text(
                        text = "Monitoring, storage deletion & permissions",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("close_settings_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close settings",
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Section 1: Travel Mode Toggle
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (isTravelModeActive) Color(0xFF2A1C10) else MaterialTheme.colorScheme.surfaceVariant,
                border = androidx.compose.foundation.BorderStroke(
                    1.5.dp,
                    if (isTravelModeActive) OrangePrimary else MaterialTheme.colorScheme.outline
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isTravelModeActive) OrangePrimary else Color(0xFF333333)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Flight,
                                contentDescription = "Travel Mode",
                                tint = if (isTravelModeActive) Color.Black else TextSecondary,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Travel Mode",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = TextPrimary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isTravelModeActive) "Orange Theme ON" else "Off",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    ),
                                    color = if (isTravelModeActive) OrangePrimary else TextMuted
                                )
                            }
                            Text(
                                text = "Completely pauses background monitoring while travelling to conserve battery. Transforms app UI into vibrant Orange theme.",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                                color = TextSecondary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Switch(
                        checked = isTravelModeActive,
                        onCheckedChange = { onToggleTravelMode() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = OrangePrimary
                        ),
                        modifier = Modifier.testTag("travel_mode_switch")
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))

            // Section 2: Storage & Recording Deletion (Moved from Top Bar)
            Text(
                text = "STORAGE & RECORDING DELETION",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                ),
                color = TextMuted
            )

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(AmberGold.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AutoDelete,
                                    contentDescription = null,
                                    tint = AmberGold,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Ad Storage Cleaner",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = TextPrimary
                                )
                                Text(
                                    text = "$totalAdsCount total ads • $oldAdsCount older than 30d",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                    color = TextSecondary
                                )
                            }
                        }
                    }

                    Text(
                        text = "Recordings older than 30 days are purged automatically to save storage unless starred as Favorite. You can also run immediate deletions below.",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                        color = TextSecondary
                    )

                    // Primary Delete Button 1: 30-Day Cleanup
                    Button(
                        onClick = onManualCleanup,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .testTag("run_cleanup_now_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoDelete,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (oldAdsCount > 0) "Delete $oldAdsCount Old Ads (>30 Days)" else "Clean 30+ Day Ads (0 Eligible)",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }

                    // Secondary Delete Button 2: Delete All Non-Starred Ads
                    OutlinedButton(
                        onClick = { showDeleteNonStarredConfirm = true },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = SignalRed
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, SignalRed.copy(alpha = 0.6f)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp)
                            .testTag("delete_non_starred_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = null,
                            tint = SignalRed,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Delete All Non-Starred Recordings",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = SignalRed
                        )
                    }

                    // Tertiary Delete Button 3: Clear All Data
                    if (totalAdsCount > 0) {
                        TextButton(
                            onClick = { showClearAllConfirm = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(36.dp)
                                .testTag("clear_all_recordings_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteForever,
                                contentDescription = null,
                                tint = TextMuted,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Clear All Library Recordings",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))

            // Section 3: Spotify Detection Setup & Real-time Permissions
            Text(
                text = "SPOTIFY DETECTION SETUP",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                ),
                color = TextMuted
            )

            // Step 1: Notification Access (CRITICAL)
            SetupStepItem(
                stepNumber = "1",
                icon = Icons.Default.NotificationsActive,
                title = "Notification Access",
                statusBadge = if (isNotifAccessGranted) "GRANTED" else "REQUIRED",
                isStatusPositive = isNotifAccessGranted,
                description = "Required to identify active Spotify audio, reading track name, artist, duration, and timestamp in real-time.",
                buttonText = if (isNotifAccessGranted) "Re-check / Open Notification Settings" else "Open Notification Settings",
                onAction = {
                    val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        context.startActivity(intent)
                    } catch (_: Exception) {}
                }
            )

            // Step 2: Spotify Device Broadcast Status
            SetupStepItem(
                stepNumber = "2",
                icon = Icons.Default.Radio,
                title = "Spotify Device Broadcast",
                statusBadge = "RECOMMENDED",
                isStatusPositive = true,
                description = "In Spotify app > Settings > enable 'Device Broadcast Status' so Spotify sends instant track change broadcasts.",
                buttonText = "Open Spotify App",
                onAction = {
                    val launchIntent = context.packageManager.getLaunchIntentForPackage("com.spotify.music")
                    if (launchIntent != null) {
                        context.startActivity(launchIntent)
                    } else {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.spotify.music")).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        try {
                            context.startActivity(intent)
                        } catch (_: Exception) {}
                    }
                }
            )

            // Step 3: Internal Audio Capture
            SetupStepItem(
                stepNumber = "3",
                icon = Icons.Default.VolumeUp,
                title = "Internal Audio Capture",
                statusBadge = "OPTIONAL",
                isStatusPositive = true,
                description = "Enables crystal clear internal audio recording of ad breaks without ambient room noise.",
                buttonText = "Enable Internal Audio Capture",
                onAction = onRequestMediaProjection
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // Confirmation Dialog for Delete All Non-Starred
    if (showDeleteNonStarredConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteNonStarredConfirm = false },
            title = {
                Text(
                    text = "Delete Non-Starred Recordings?",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "This will delete all recordings that are not starred as Favorite. Starred recordings will remain safe.",
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteNonStarredConfirm = false
                        onDeleteNonStarredAds()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SignalRed)
                ) {
                    Text("Delete", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteNonStarredConfirm = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    // Confirmation Dialog for Clear All Recordings
    if (showClearAllConfirm) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirm = false },
            title = {
                Text(
                    text = "Clear Entire Recording Library?",
                    color = SignalRed,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to permanently delete ALL recorded ad audio files and metadata? This action cannot be undone.",
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearAllConfirm = false
                        onClearAllAds()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SignalRed)
                ) {
                    Text("Delete All", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllConfirm = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }
}

// Backwards-compatible alias for SettingsDialog
@Composable
fun SettingsDialog(
    isTravelModeActive: Boolean,
    onToggleTravelMode: () -> Unit,
    oldAdsCount: Int = 0,
    totalAdsCount: Int = 0,
    onManualCleanup: () -> Unit,
    onDeleteNonStarredAds: () -> Unit = onManualCleanup,
    onClearAllAds: () -> Unit = onManualCleanup,
    onRequestMediaProjection: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsBottomDrawer(
        isTravelModeActive = isTravelModeActive,
        onToggleTravelMode = onToggleTravelMode,
        oldAdsCount = oldAdsCount,
        totalAdsCount = totalAdsCount,
        onManualCleanup = onManualCleanup,
        onDeleteNonStarredAds = onDeleteNonStarredAds,
        onClearAllAds = onClearAllAds,
        onRequestMediaProjection = onRequestMediaProjection,
        onDismiss = onDismiss,
        modifier = modifier
    )
}

@Composable
private fun SetupStepItem(
    stepNumber: String,
    icon: ImageVector,
    title: String,
    statusBadge: String? = null,
    isStatusPositive: Boolean = true,
    description: String,
    buttonText: String,
    onAction: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stepNumber,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                }

                if (statusBadge != null) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (isStatusPositive) SpotifyGreen.copy(alpha = 0.2f) else AmberGold.copy(alpha = 0.2f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isStatusPositive) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (isStatusPositive) SpotifyGreen else AmberGold,
                                modifier = Modifier.size(10.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = statusBadge,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 9.sp
                                ),
                                color = if (isStatusPositive) SpotifyGreen else AmberGold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 16.sp),
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedButton(
                onClick = onAction,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
            ) {
                Icon(
                    Icons.Default.Launch,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = buttonText,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = TextPrimary
                )
            }
        }
    }
}
