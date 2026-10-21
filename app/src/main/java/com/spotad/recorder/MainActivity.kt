package com.spotad.recorder

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotad.recorder.audio.ExportManager
import com.spotad.recorder.data.RecordedAdEntity
import com.spotad.recorder.service.SpotifyNotificationListener
import com.spotad.recorder.ui.components.AdDetailsSheet
import com.spotad.recorder.ui.components.AdListItem
import com.spotad.recorder.ui.components.AppTopBar
import com.spotad.recorder.ui.components.LibraryToolbar
import com.spotad.recorder.ui.components.MonitoringCard
import com.spotad.recorder.ui.components.NowPlayingCard
import com.spotad.recorder.ui.components.OnboardingScreen
import com.spotad.recorder.ui.components.PlayerBar
import com.spotad.recorder.ui.components.SettingsSheet
import com.spotad.recorder.ui.components.TagPill
import com.spotad.recorder.ui.theme.SpotAdTheme
import com.spotad.recorder.ui.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Restore the monitor after a reboot or a process kill, but only if the user opted in.
        if (com.spotad.recorder.data.SettingsStore.get(this).snapshot().monitoringEnabled) {
            com.spotad.recorder.service.AdRecordingService.start(this)
        }

        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            SpotAdTheme(themeMode = settings.themeMode) {
                val context = LocalContext.current
                val settingsNow by viewModel.settings.collectAsStateWithLifecycle()

                if (!settingsNow.onboardingComplete) {
                    OnboardingScreen(onDone = { viewModel.completeOnboarding() })
                } else {
                    MainScreen(viewModel = viewModel, context = context)
                }
            }
        }
    }
}

@Composable
private fun MainScreen(
    viewModel: MainViewModel,
    context: Context
) {
    val library by viewModel.library.collectAsStateWithLifecycle()
    val serviceState by viewModel.serviceState.collectAsStateWithLifecycle()
    val nowPlaying by viewModel.nowPlaying.collectAsStateWithLifecycle()
    val player by viewModel.playerState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val inspectingAd by viewModel.inspectingAd.collectAsStateWithLifecycle()
    val oldRecordings by viewModel.oldRecordings.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val projectionManager = remember(context) {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
    }

    var notificationAccess by remember { mutableStateOf(SpotifyNotificationListener.hasAccess(context)) }
    var micGranted by remember { mutableStateOf(hasMicPermission(context)) }
    var showSettings by remember { mutableStateOf(false) }
    var startAfterPermission by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<RecordedAdEntity?>(null) }

    val projectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            viewModel.onProjectionResult(context, result.resultCode, result.data)
        } else {
            viewModel.onProjectionDenied()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        micGranted = hasMicPermission(context)
        if (micGranted && serviceState.monitoringEnabled) {
            // Refresh the service so it can leave NEEDS_ACCESS without another tap.
            com.spotad.recorder.service.AdRecordingService.start(context)
        }
        val notificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            grants[Manifest.permission.POST_NOTIFICATIONS] == true ||
                hasNotificationPermission(context)
        } else {
            true
        }
        if (startAfterPermission) {
            startAfterPermission = false
            if (micGranted) {
                viewModel.toggleMonitoring(context)
                if (!serviceState.captureGranted) launchProjection(projectionManager, projectionLauncher)
            } else {
                viewModel.postMessage("Microphone permission is required to capture audio", true)
            }
        }
        if (!notificationsGranted) {
            viewModel.postMessage("Notifications are hidden; Android may stop the recorder")
        }
    }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val ad = pendingExport
        pendingExport = null
        if (granted && ad != null) {
            viewModel.exportAd(context, ad)
        } else {
            viewModel.postMessage("Storage permission is needed to export on this Android version", true)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationAccess = SpotifyNotificationListener.hasAccess(context)
                micGranted = hasMicPermission(context)
                SpotifyNotificationListener.requestRebind(context)
                viewModel.onAppResumed(context)
                // If an OEM killed the service while the app was in the background, bring it back.
                if (viewModel.settings.value.monitoringEnabled) {
                    com.spotad.recorder.service.AdRecordingService.start(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it.text)
            viewModel.clearMessage()
        }
    }

    val requestCapture: () -> Unit = { launchProjection(projectionManager, projectionLauncher) }

    val exportAd: (RecordedAdEntity) -> Unit = { ad ->
        if (ExportManager.needsLegacyStoragePermission() &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingExport = ad
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            viewModel.exportAd(context, ad)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column(modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
                AppTopBar(
                    title = "Library",
                    subtitle = when {
                        serviceState.monitoringEnabled -> "SpotAd is available in the background"
                        else -> "Monitoring is off"
                    },
                    onOpenSettings = { showSettings = true }
                )
            }
        },
        bottomBar = {
            val playingAd = library.ads.firstOrNull { it.id == player.adId }
            if (player.adId != null && playingAd != null) {
                Box(modifier = Modifier.navigationBarsPadding()) {
                    PlayerBar(
                        playback = player,
                        ad = playingAd,
                        onPlayPause = {
                            if (player.isPlaying) viewModel.pausePlayback() else viewModel.resumePlayback()
                        },
                        onSeek = { viewModel.seekPlayback(it) },
                        onCycleSpeed = {
                            val next = when (player.speed) {
                                1.0f -> 1.25f
                                1.25f -> 1.5f
                                1.5f -> 2.0f
                                else -> 1.0f
                            }
                            viewModel.setPlaybackSpeed(next)
                        },
                        onShare = {
                            library.ads.firstOrNull { it.id == player.adId }
                                ?.let { viewModel.shareAd(context, it) }
                        },
                        onClose = { viewModel.closePlayer() }
                    )
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "monitoring") {
                MonitoringCard(
                    serviceState = serviceState,
                    micGranted = micGranted,
                    notificationAccess = notificationAccess,
                    onToggleMonitoring = {
                        if (serviceState.monitoringEnabled) {
                            viewModel.toggleMonitoring(context)
                        } else if (!micGranted) {
                            startAfterPermission = true
                            val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                permissions += Manifest.permission.POST_NOTIFICATIONS
                            }
                            permissionLauncher.launch(permissions.toTypedArray())
                        } else {
                            viewModel.toggleMonitoring(context)
                            if (!serviceState.captureGranted) requestCapture()
                        }
                    },
                    onRequestMic = {
                        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            permissions += Manifest.permission.POST_NOTIFICATIONS
                        }
                        startAfterPermission = false
                        permissionLauncher.launch(permissions.toTypedArray())
                    },
                    onOpenNotificationSettings = { openNotificationAccess(context) },
                    onRequestCapture = requestCapture,
                    onStopRecording = { viewModel.stopCurrentRecording(context) },
                    onRecordNow = { viewModel.startManualRecording(context) },
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            item(key = "now_playing") {
                NowPlayingCard(
                    nowPlaying = nowPlaying,
                    notificationAccess = notificationAccess,
                    onOpenNotificationSettings = { openNotificationAccess(context) },
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            item(key = "toolbar") {
                LibraryToolbar(
                    searchQuery = library.searchQuery,
                    onSearchQueryChange = viewModel::setSearchQuery,
                    filter = library.filter,
                    onFilterChange = viewModel::setFilter,
                    sort = library.sort,
                    onSortChange = viewModel::setSortOrder,
                    totalCount = library.totalCount,
                    visibleCount = library.ads.size
                )
            }

            if (library.ads.isEmpty()) {
                item(key = "empty") {
                    EmptyLibrary(
                        isFiltered = library.searchQuery.isNotBlank() ||
                            library.filter != com.spotad.recorder.ui.viewmodel.LibraryFilter.ALL,
                        onClear = {
                            viewModel.setSearchQuery("")
                            viewModel.setFilter(com.spotad.recorder.ui.viewmodel.LibraryFilter.ALL)
                        },
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            } else {
                items(items = library.ads, key = { it.id }) { ad ->
                    AdListItem(
                        ad = ad,
                        playback = player,
                        onPlayToggle = { viewModel.playAd(ad) },
                        onSeek = { position ->
                            if (player.adId != ad.id) viewModel.playAd(ad)
                            viewModel.seekPlayback(position)
                        },
                        onToggleFavorite = { viewModel.toggleFavorite(ad) },
                        onInspect = { viewModel.inspectAd(ad) },
                        onShare = { viewModel.shareAd(context, ad) },
                        onExport = { exportAd(ad) },
                        onDelete = { viewModel.deleteAd(ad) },
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
        }
    }

    if (showSettings) {
        SettingsSheet(
            settings = settings,
            totalRecordings = library.totalCount,
            storageBytes = library.storageBytes,
            oldRecordings = oldRecordings,
            notificationAccess = notificationAccess,
            onOpenNotificationSettings = { openNotificationAccess(context) },
            onMaxAdSecondsChange = viewModel::setMaxAdSeconds,
            onRetentionChange = viewModel::setRetentionDays,
            onThemeChange = viewModel::setThemeMode,
            onCleanupOld = viewModel::cleanupOldRecordings,
            onDeleteNonFavorites = viewModel::deleteUnstarredRecordings,
            onDeleteAll = viewModel::deleteAllRecordings,
            onReplayOnboarding = {
                showSettings = false
                viewModel.replayOnboarding()
            },
            onDismiss = { showSettings = false }
        )
    }

    inspectingAd?.let { ad ->
        AdDetailsSheet(
            ad = ad,
            onDismiss = { viewModel.inspectAd(null) },
            onSave = { id, title, advertiser, themes, notes ->
                viewModel.updateMetadata(id, title, advertiser, themes, notes)
            },
            onShare = { viewModel.shareAd(context, ad) },
            onExport = { exportAd(ad) },
            onTrim = { startMillis, endMillis -> viewModel.trimAd(ad, startMillis, endMillis) },
            onDelete = { viewModel.deleteAd(ad) }
        )
    }
}

@Composable
private fun EmptyLibrary(
    isFiltered: Boolean,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (isFiltered) "No matching recordings" else "No recordings yet",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        Text(
            text = if (isFiltered) {
                "Try a different search or show all recordings."
            } else {
                "Play something in Spotify. When an ad break starts, SpotAd saves it here."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp, start = 12.dp, end = 12.dp)
        )
        if (isFiltered) {
            androidx.compose.material3.TextButton(onClick = onClear) {
                Text("Clear filters")
            }
        } else {
            Row(modifier = Modifier.padding(top = 12.dp)) {
                TagPill("WAV audio")
                Box(modifier = Modifier.padding(horizontal = 4.dp))
                TagPill("On-device only")
            }
        }
    }
}

private fun hasMicPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

private fun hasNotificationPermission(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    } else {
        true
    }

private fun launchProjection(
    manager: MediaProjectionManager?,
    launcher: androidx.activity.result.ActivityResultLauncher<Intent>
) {
    val intent = try {
        manager?.createScreenCaptureIntent()
    } catch (_: Exception) {
        null
    }
    if (intent != null) {
        try {
            launcher.launch(intent)
        } catch (_: Exception) {
        }
    }
}

private fun openNotificationAccess(context: Context) {
    val component = ComponentName(context, SpotifyNotificationListener::class.java)
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).apply {
            putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                component.flattenToString()
            )
        }
    } else {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
        }
    }
}
