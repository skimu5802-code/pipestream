package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.example.data.model.StreamItem
import com.example.player.PlaybackNotificationService
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ui.components.ClipboardLinkModal
import com.example.ui.components.DownloadBottomSheet
import com.example.ui.components.MiniPlayerBar
import com.example.ui.components.UpdateDialog
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.PlayerScreen
import com.example.ui.screens.SearchScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.ShortsScreen
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.size
import com.example.ui.theme.BackgroundDark
import com.example.ui.theme.CrimsonRed
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.SurfaceBorder
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.SurfaceElevated
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        // Permission result handled
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(
                android.graphics.Color.TRANSPARENT
            )
        )

        checkNotificationPermission()
        handleIncomingIntent(intent)

        // Automatically check for updates on launch in background
        lifecycleScope.launch {
            viewModel.updateManager.checkForUpdates(isManualCheck = false)
        }

        setContent {
            val dynamicColor by viewModel.dynamicColorEnabled.collectAsState()
            val themeMode by viewModel.themeMode.collectAsState()
            val isSystemDark = androidx.compose.foundation.isSystemInDarkTheme()
            val darkTheme = when (themeMode) {
                "light" -> false
                "dark" -> true
                else -> isSystemDark
            }

            MyApplicationTheme(
                darkTheme = darkTheme,
                dynamicColor = dynamicColor
            ) {
                PipeStreamApp(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        viewModel.playbackManager.setAppInForeground(true)
    }

    override fun onResume() {
        super.onResume()
        handleIncomingIntent(intent)
        checkClipboardForYouTubeLink()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            checkClipboardForYouTubeLink()
        }
    }

    override fun onPause() {
        super.onPause()
        val currentStream = viewModel.playbackState.value.currentStream
        val currentPos = viewModel.playbackManager.player.currentPosition
        if (currentStream != null && currentStream.id.isNotBlank()) {
            com.example.player.PlaybackPositionWorker.enqueue(
                context = this,
                streamId = currentStream.id,
                title = currentStream.title,
                uploader = currentStream.uploaderName,
                avatar = currentStream.uploaderAvatar,
                thumbnail = currentStream.description.takeIf { it.startsWith("http") } ?: "",
                durationSec = currentStream.durationSeconds,
                positionMs = currentPos,
                isLocal = viewModel.playbackState.value.isLocalFile
            )
        }
    }

    override fun onStop() {
        super.onStop()
        viewModel.playbackManager.setAppInForeground(false)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return

        if (intent.getBooleanExtra(PlaybackNotificationService.EXTRA_EXPAND_PLAYER, false)) {
            viewModel.setPlayerExpanded(true)
            intent.removeExtra(PlaybackNotificationService.EXTRA_EXPAND_PLAYER)
            return
        }

        // Handle text/plain share from YouTube or other apps (Appears on Share sheet)
        if (Intent.ACTION_SEND == intent.action && intent.type?.startsWith("text/") == true) {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrBlank()) {
                viewModel.playYouTubeUrl(sharedText)
                intent.action = null // Prevent re-triggering on config changes
            }
            return
        }

        // Handle VIEW deep links (e.g. youtube.com/watch?v=...)
        if (Intent.ACTION_VIEW == intent.action && intent.data != null) {
            val url = intent.data.toString()
            if (url.isNotBlank()) {
                viewModel.playYouTubeUrl(url)
                intent.action = null
            }
            return
        }
    }

    private fun checkClipboardForYouTubeLink() {
        try {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            if (clipboard != null && clipboard.hasPrimaryClip()) {
                val clipData = clipboard.primaryClip
                if (clipData != null && clipData.itemCount > 0) {
                    val text = clipData.getItemAt(0)?.text?.toString()
                    if (!text.isNullOrBlank()) {
                        viewModel.onClipboardTextDetected(text)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error checking clipboard: ${e.message}")
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

sealed class Screen(
    val route: String,
    val title: String,
    val selectedIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    val unselectedIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    val iconRes: Int? = null
) {
    object Home : Screen("home", "Home", Icons.Filled.Home, Icons.Outlined.Home)
    object Search : Screen("search", "Search", Icons.Filled.Search, Icons.Outlined.Search)
    object Library : Screen("library", "Library", Icons.Filled.VideoLibrary, Icons.Outlined.VideoLibrary)
    object Settings : Screen("settings", "Settings", Icons.Filled.Settings, Icons.Outlined.Settings)
}

@Composable
fun PipeStreamApp(viewModel: MainViewModel) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val playbackState by viewModel.playbackState.collectAsState()
    val isPlayerExpanded by viewModel.isPlayerExpanded.collectAsState()
    val detectedClipboardVideo by viewModel.detectedClipboardVideo.collectAsState()
    val showDownloadSheet by viewModel.showDownloadSheet.collectAsState()
    val downloadTargetStream by viewModel.downloadTargetStream.collectAsState()
    val downloadTargetDetails by viewModel.downloadTargetDetails.collectAsState()
    val activeDetails by viewModel.activeStreamDetails.collectAsState()
    val snackbarMsg by viewModel.snackBarMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val updateState by viewModel.updateManager.updateState.collectAsState()

    LaunchedEffect(snackbarMsg) {
        if (snackbarMsg != null) {
            snackbarHostState.showSnackbar(snackbarMsg!!)
            viewModel.clearSnackbar()
        }
    }

    // Handle Android system Back button
    BackHandler(enabled = isPlayerExpanded) {
        viewModel.setPlayerExpanded(false)
    }

    val bottomNavItems = listOf(Screen.Home, Screen.Search, Screen.Library, Screen.Settings)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            bottomBar = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Floating Mini-Player bar (slides up smoothly from bottom above nav bar)
                    AnimatedVisibility(
                        visible = playbackState.currentStream != null && !isPlayerExpanded,
                        enter = slideInVertically(
                            initialOffsetY = { it },
                            animationSpec = spring(
                                dampingRatio = 0.85f,
                                stiffness = Spring.StiffnessMediumLow
                            )
                        ) + fadeIn(animationSpec = tween(200)),
                        exit = slideOutVertically(
                            targetOffsetY = { it },
                            animationSpec = spring(
                                dampingRatio = 0.85f,
                                stiffness = Spring.StiffnessMediumLow
                            )
                        ) + fadeOut(animationSpec = tween(200))
                    ) {
                        MiniPlayerBar(
                            playbackState = playbackState,
                            playbackManager = viewModel.playbackManager,
                            onExpand = { viewModel.setPlayerExpanded(true) },
                            onClose = { viewModel.closeMiniPlayer() }
                        )
                    }

                    // Bottom Navigation Bar
                    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        tonalElevation = 2.dp,
                        modifier = Modifier.testTag("bottom_navigation_bar")
                    ) {
                        bottomNavItems.forEach { screen ->
                            val isSelected = currentRoute == screen.route
                            NavigationBarItem(
                                selected = isSelected,
                                onClick = {
                                    if (currentRoute != screen.route) {
                                        navController.navigate(screen.route) {
                                            popUpTo(Screen.Home.route) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                },
                                icon = {
                                    if (screen.iconRes != null) {
                                        Icon(
                                            painter = painterResource(id = screen.iconRes),
                                            contentDescription = screen.title,
                                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    } else {
                                        Icon(
                                            imageVector = if (isSelected) screen.selectedIcon!! else screen.unselectedIcon!!,
                                            contentDescription = screen.title,
                                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                label = {
                                    Text(
                                        text = screen.title,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                NavHost(
                    navController = navController,
                    startDestination = Screen.Home.route,
                    modifier = Modifier.fillMaxSize()
                ) {
                    composable(Screen.Home.route) {
                        HomeScreen(
                            viewModel = viewModel,
                            onNavigateToSearch = { navController.navigate(Screen.Search.route) },
                            onNavigateToSettings = { navController.navigate(Screen.Settings.route) }
                        )
                    }
                    composable(Screen.Search.route) {
                        SearchScreen(
                            viewModel = viewModel,
                            onBackClick = null
                        )
                    }
                    composable(Screen.Library.route) {
                        LibraryScreen(viewModel = viewModel)
                    }
                    composable(Screen.Settings.route) {
                        SettingsScreen(
                            viewModel = viewModel,
                            onBackClick = { navController.popBackStack() }
                        )
                    }
                }
            }
        }

        // Full Screen Player Modal with animated slide-up
        AnimatedVisibility(
            visible = isPlayerExpanded,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.fillMaxSize()
        ) {
            val config = androidx.compose.ui.platform.LocalConfiguration.current
            val isLandscapeMode = config.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            PlayerScreen(
                viewModel = viewModel,
                onCollapse = { viewModel.setPlayerExpanded(false) },
                modifier = if (isLandscapeMode) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                }
            )
        }

        // Clipboard YouTube Link Modal
        if (detectedClipboardVideo != null) {
            ClipboardLinkModal(
                detectedVideo = detectedClipboardVideo!!,
                onPlay = { viewModel.playDetectedClipboardVideo() },
                onDownload = { viewModel.downloadDetectedClipboardVideo() },
                onDismiss = { viewModel.dismissClipboardModal() }
            )
        }

        // Standalone Download Bottom Sheet (when triggered outside player)
        val standaloneTarget: StreamItem? = downloadTargetStream 
            ?: activeDetails?.toStreamItem() 
            ?: playbackState.currentStream?.toStreamItem()
        if (showDownloadSheet && standaloneTarget != null && !isPlayerExpanded) {
            DownloadBottomSheet(
                stream = standaloneTarget,
                streamDetails = downloadTargetDetails ?: activeDetails,
                onDismiss = { viewModel.setShowDownloadSheet(false) },
                onDownloadSelected = { quality, isAudioOnly ->
                    viewModel.startDownload(quality, isAudioOnly, standaloneTarget)
                }
            )
        }

        // GitHub In-App Updater Dialog
        UpdateDialog(
            updateState = updateState,
            onDismiss = { viewModel.updateManager.dismissUpdate() },
            onUpdateClick = { release ->
                coroutineScope.launch {
                    viewModel.updateManager.downloadAndInstallApk(release)
                }
            },
            onInstallClick = { apkFile ->
                viewModel.updateManager.installApk(apkFile)
            }
        )
    }
}
