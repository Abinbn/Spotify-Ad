package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.audio.ExportManager
import com.example.data.AppSettings
import com.example.data.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: AppSettings,
    totalRecordings: Int,
    storageBytes: Long,
    oldRecordings: Int,
    notificationAccess: Boolean,
    onOpenNotificationSettings: () -> Unit,
    onMaxAdSecondsChange: (Int) -> Unit,
    onRetentionChange: (Int) -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onCleanupOld: () -> Unit,
    onDeleteNonFavorites: () -> Unit,
    onDeleteAll: () -> Unit,
    onReplayOnboarding: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var confirmDeleteNonFavorites by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(24.dp))

            SectionLabel("RECORDING")
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Maximum clip length. Recording stops sooner when the ad ends.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            OptionChips(
                options = listOf(
                    "1 min" to (settings.maxAdSeconds == 60),
                    "2 min" to (settings.maxAdSeconds == 120),
                    "5 min" to (settings.maxAdSeconds == 300)
                ),
                onSelect = { index ->
                    onMaxAdSecondsChange(listOf(60, 120, 300)[index])
                }
            )

            Spacer(Modifier.height(24.dp))

            SectionLabel("STORAGE")
            Spacer(Modifier.height(8.dp))
            Text(
                text = "$totalRecordings recordings · ${ExportManager.formatFileSize(storageBytes)} used",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Automatic cleanup never touches starred recordings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            OptionChips(
                options = listOf(
                    "Forever" to (settings.retentionDays == 0),
                    "7 days" to (settings.retentionDays == 7),
                    "30 days" to (settings.retentionDays == 30),
                    "90 days" to (settings.retentionDays == 90)
                ),
                onSelect = { index ->
                    onRetentionChange(listOf(0, 7, 30, 90)[index])
                }
            )

            if (settings.retentionDays > 0) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = if (oldRecordings > 0) {
                        "$oldRecordings unstarred recordings are older than ${settings.retentionDays} days."
                    } else {
                        "No unstarred recordings are older than ${settings.retentionDays} days."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onCleanupOld, enabled = oldRecordings > 0) {
                    Text("Clean up now")
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { confirmDeleteNonFavorites = true },
                    enabled = totalRecordings > 0,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Delete unstarred", style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(
                    onClick = { confirmDeleteAll = true },
                    enabled = totalRecordings > 0,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        "Delete all",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            SectionLabel("APPEARANCE")
            Spacer(Modifier.height(8.dp))
            OptionChips(
                options = listOf(
                    "System" to (settings.themeMode == ThemeMode.SYSTEM),
                    "Light" to (settings.themeMode == ThemeMode.LIGHT),
                    "Dark" to (settings.themeMode == ThemeMode.DARK)
                ),
                onSelect = { index ->
                    onThemeChange(ThemeMode.entries[index])
                }
            )

            Spacer(Modifier.height(24.dp))

            SectionLabel("ACCESS")
            Spacer(Modifier.height(8.dp))
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(
                            if (notificationAccess) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (notificationAccess) {
                                "Notification access granted"
                            } else {
                                "Notification access missing"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = "SpotAd reads only the Spotify media notification to detect ads.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = onOpenNotificationSettings) {
                        Text(if (notificationAccess) "Manage access" else "Grant access")
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            SectionLabel("ABOUT")
            Spacer(Modifier.height(8.dp))
            Text(
                text = "SpotAd records ads for personal listening and reference. Recordings stay in " +
                    "this app's private storage until you share or export them. Respect the rights " +
                    "of the artists and brands you capture.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Version ${com.example.BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onReplayOnboarding) {
                Text("Show welcome screen again")
            }
        }
    }

    if (confirmDeleteAll) {
        ConfirmDialog(
            title = "Delete every recording?",
            body = "All audio files and metadata will be permanently removed, including starred recordings.",
            confirmLabel = "Delete all",
            onConfirm = {
                confirmDeleteAll = false
                onDeleteAll()
            },
            onDismiss = { confirmDeleteAll = false }
        )
    }

    if (confirmDeleteNonFavorites) {
        ConfirmDialog(
            title = "Delete unstarred recordings?",
            body = "Starred recordings are kept. This cannot be undone.",
            confirmLabel = "Delete",
            onConfirm = {
                confirmDeleteNonFavorites = false
                onDeleteNonFavorites()
            },
            onDismiss = { confirmDeleteNonFavorites = false }
        )
    }
}

@Composable
private fun OptionChips(
    options: List<Pair<String, Boolean>>,
    onSelect: (Int) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState())
    ) {
        options.forEachIndexed { index, (label, selected) ->
            FilterChip(
                selected = selected,
                onClick = { onSelect(index) },
                label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                shape = MaterialTheme.shapes.small
            )
        }
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * First-run explainer. Three sentences, one button; the actual permission checklist lives on the
 * main screen so it can reflect real system state instead of a fake wizard.
 */
@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 48.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "SpotAd",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Keep the Spotify ads worth remembering.",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(32.dp))

            OnboardingPoint(
                index = "1",
                title = "Spotify keeps playing",
                body = "SpotAd watches the Spotify media notification in the background. It does not open Spotify and does not interrupt playback."
            )
            OnboardingPoint(
                index = "2",
                title = "Ad breaks are captured",
                body = "When an ad starts, the app records the device audio until the music returns, then saves a WAV file in your library."
            )
            OnboardingPoint(
                index = "3",
                title = "Everything stays local",
                body = "No account, no cloud, no analytics. Files remain in SpotAd until you share or export them, and you can delete them any time."
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Android will ask for microphone, notification and screen-capture consent. " +
                    "SpotAd uses them only to capture playback audio while an ad is detected.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start
            )

            Spacer(Modifier.height(32.dp))

            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                Text("Continue")
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Recordings are for personal use. Please respect copyright.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun OnboardingPoint(index: String, title: String, body: String) {
    Row(modifier = Modifier.padding(vertical = 10.dp)) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.padding(end = 14.dp)
        ) {
            Text(
                text = index,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
