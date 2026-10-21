package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.service.AdRecordingService
import com.example.service.SpotifyNotificationListener
import com.example.ui.components.AdCard
import com.example.ui.components.AdDetailsDialog
import com.example.ui.components.BuiltInPlayerBar
import com.example.ui.components.FilterAndSortRow
import com.example.ui.components.MonitoringBanner
import com.example.ui.components.SettingsDialog
import com.example.ui.components.SpotifyNowPlayingCard
import com.example.ui.components.StudioTopBar
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.SpotifyGreen
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var mediaProjectionManager: MediaProjectionManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager

        // Automatically start the background monitoring service if not in travel mode
        val travel = AdRecordingService.isTravelMode(this)
        AdRecordingService.setServiceState(
            AdRecordingService.serviceUiState.value.copy(
                isRunning = !travel,
                isTravelModeActive = travel,
                state = if (travel) com.example.service.MonitoringState.TRAVEL_INACTIVE else com.example.service.MonitoringState.SLEEPING
            )
        )
        if (!travel) {
            val serviceIntent = Intent(this, AdRecordingService::class.java).apply {
                action = AdRecordingService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    startForegroundService(serviceIntent)
                } catch (e: Exception) {
                    try {
                        startService(serviceIntent)
                    } catch (_: Exception) {}
                }
            } else {
                try {
                    startService(serviceIntent)
                } catch (_: Exception) {}
            }
        }

        setContent {
            val serviceState by viewModel.serviceState.collectAsStateWithLifecycle()

            // When Travel Mode is active, entire app UI transitions to the Orange theme
            MyApplicationTheme(isTravelMode = serviceState.isTravelModeActive) {
                val context = LocalContext.current
                var showSettingsDialog by remember { mutableStateOf(false) }

                // Screen capture / internal audio launcher
                val mediaProjectionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    if (result.resultCode == RESULT_OK && result.data != null) {
                        try {
                            val projection = mediaProjectionManager?.getMediaProjection(result.resultCode, result.data!!)
                            AdRecordingService.setMediaProjection(projection)
                            Toast.makeText(context, "Internal audio capture active!", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, "Could not start audio capture", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                // Permission launcher for audio and notifications
                val permissionsLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { _ ->
                    // Notification permission handled
                }

                LaunchedEffect(Unit) {
                    val neededPerms = mutableListOf<String>()
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        neededPerms.add(Manifest.permission.RECORD_AUDIO)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            neededPerms.add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    if (neededPerms.isNotEmpty()) {
                        permissionsLauncher.launch(neededPerms.toTypedArray())
                    }
                }

                SpotAdApp(
                    viewModel = viewModel,
                    onOpenSettings = { showSettingsDialog = true }
                )

                // Dialogs
                val inspectingAd by viewModel.inspectingAd.collectAsStateWithLifecycle()
                if (inspectingAd != null) {
                    AdDetailsDialog(
                        ad = inspectingAd!!,
                        onDismiss = { viewModel.inspectAd(null) },
                        onSaveMetadata = { id, title, adv, themes ->
                            viewModel.updateAdMetadata(id, title, adv, themes)
                        }
                    )
                }

                val allAds by viewModel.recordedAds.collectAsStateWithLifecycle()
                val oldAdsEligible by viewModel.oldAdsEligibleCount.collectAsStateWithLifecycle()

                if (showSettingsDialog) {
                    com.example.ui.components.SettingsBottomDrawer(
                        isTravelModeActive = serviceState.isTravelModeActive,
                        onToggleTravelMode = { viewModel.toggleTravelInactiveMode(context) },
                        oldAdsCount = oldAdsEligible,
                        totalAdsCount = allAds.size,
                        onManualCleanup = { viewModel.performManualCleanup(context) },
                        onDeleteNonStarredAds = { viewModel.deleteAllNonFavoriteAds(context) },
                        onClearAllAds = { viewModel.clearAllRecordings(context) },
                        onRequestMediaProjection = {
                            val intent = mediaProjectionManager?.createScreenCaptureIntent()
                            if (intent != null) {
                                mediaProjectionLauncher.launch(intent)
                            }
                        },
                        onDismiss = { showSettingsDialog = false }
                    )
                }
            }
        }
    }
}

@Composable
fun SpotAdApp(
    viewModel: MainViewModel,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val ads by viewModel.recordedAds.collectAsStateWithLifecycle()
    val serviceState by viewModel.serviceState.collectAsStateWithLifecycle()
    val spotifyNowPlaying by viewModel.spotifyNowPlaying.collectAsStateWithLifecycle()
    val playerState by viewModel.playerState.collectAsStateWithLifecycle()
    val sortOrder by viewModel.sortOrder.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val uiNotification by viewModel.uiNotification.collectAsStateWithLifecycle()
    val oldAdsCount by viewModel.oldAdsEligibleCount.collectAsStateWithLifecycle()

    val currentPlayingAd = remember(playerState.adId, ads) {
        ads.firstOrNull { it.id == playerState.adId }
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var isNotifAccess by remember { mutableStateOf(SpotifyNotificationListener.isNotificationAccessGranted(context)) }

    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                val granted = SpotifyNotificationListener.isNotificationAccessGranted(context)
                isNotifAccess = granted
                if (granted) {
                    SpotifyNotificationListener.rebindIfPossible(context)
                    SpotifyNotificationListener.queryActiveSpotifyMediaSession(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(uiNotification) {
        uiNotification?.let {
            Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show()
            viewModel.clearNotification()
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
                StudioTopBar(
                    serviceState = serviceState,
                    oldAdsCount = oldAdsCount,
                    onOpenSettings = onOpenSettings
                )
            }
        },
        bottomBar = {
            BuiltInPlayerBar(
                playbackState = playerState,
                currentAd = currentPlayingAd,
                onPlayPause = {
                    if (playerState.isPlaying) viewModel.pausePlayback() else viewModel.resumePlayback()
                },
                onSeek = { pos -> viewModel.seekPlayback(pos) },
                onCycleSpeed = {
                    val nextSpeed = when (playerState.speed) {
                        1.0f -> 1.25f
                        1.25f -> 1.5f
                        1.5f -> 2.0f
                        else -> 1.0f
                    }
                    viewModel.setPlaybackSpeed(nextSpeed)
                },
                onClose = { viewModel.pausePlayback() },
                onShare = {
                    currentPlayingAd?.let { viewModel.shareAd(context, it) }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 90.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Background Monitoring State Banner
            item(key = "monitoring_banner") {
                MonitoringBanner(
                    serviceState = serviceState,
                    onToggleMonitoring = { viewModel.toggleMonitoring(context) },
                    onToggleTravelMode = { viewModel.toggleTravelInactiveMode(context) },
                    onStopRecordingNow = { viewModel.stopRecordingNow(context) }
                )
            }

            // Real-Time Spotify Now Playing Card: identifies songs, ads, podcasts with name, artist, total length, timestamp
            item(key = "spotify_now_playing") {
                SpotifyNowPlayingCard(
                    nowPlaying = spotifyNowPlaying,
                    serviceState = serviceState,
                    isNotificationAccessGranted = isNotifAccess,
                    onOpenNotificationSettings = {
                        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        try {
                            context.startActivity(intent)
                        } catch (_: Exception) {}
                    }
                )
            }

            // Search & Sort Controls (Cleaned without badges below search field)
            item(key = "filter_sort_row") {
                FilterAndSortRow(
                    searchQuery = searchQuery,
                    onSearchQueryChange = { viewModel.setSearchQuery(it) },
                    currentSort = sortOrder,
                    onSortChange = { viewModel.setSortOrder(it) },
                    totalCount = ads.size
                )
            }

            // Recorded Ad Cards List
            if (ads.isEmpty()) {
                item(key = "empty_state") {
                    EmptyLibraryView(
                        searchQuery = searchQuery,
                        onResetFilter = {
                            viewModel.setSearchQuery("")
                        }
                    )
                }
            } else {
                items(
                    items = ads,
                    key = { it.id }
                ) { ad ->
                    AdCard(
                        ad = ad,
                        playbackState = playerState,
                        onPlayToggle = { viewModel.playAd(ad) },
                        onSeek = { pos -> viewModel.seekPlayback(pos) },
                        onFavoriteToggle = { viewModel.toggleFavorite(ad) },
                        onExportMp3 = { viewModel.exportAdToMusic(context, ad) },
                        onShare = { viewModel.shareAd(context, ad) },
                        onInspect = { viewModel.inspectAd(ad) },
                        onDelete = { viewModel.deleteAd(ad) },
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun EmptyLibraryView(
    searchQuery: String,
    onResetFilter: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isFiltered = searchQuery.isNotEmpty()

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.GraphicEq,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = if (isFiltered) "No Matching Ads Found" else "No Recorded Ads Yet",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (isFiltered) {
                    "No ads matched your search query. Try clearing the search to view all recordings."
                } else {
                    "SpotAd is monitoring Spotify in the background. As you listen, any detected commercial ad breaks will be automatically recorded and saved here as MP3."
                },
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp),
                color = TextSecondary,
                textAlign = TextAlign.Center
            )

            if (isFiltered) {
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onResetFilter,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = TextPrimary
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Clear Search")
                }
            }
        }
    }
}
