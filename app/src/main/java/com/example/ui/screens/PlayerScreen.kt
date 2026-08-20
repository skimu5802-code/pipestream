package com.example.ui.screens

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import android.view.WindowManager
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.CommentItem
import com.example.data.model.StreamChapter
import com.example.data.model.StreamDetails
import com.example.data.model.StreamItem
import com.example.ui.components.CompactStreamItem
import com.example.ui.components.DownloadBottomSheet
import com.example.ui.components.PlaybackSpeedDialog
import com.example.ui.components.QualityPickerDialog
import com.example.ui.components.SleepTimerDialog
import com.example.ui.components.VideoPlayerView
import com.example.ui.theme.AccentAmber
import com.example.ui.theme.BackgroundDark
import com.example.ui.theme.CrimsonRed
import com.example.ui.theme.SurfaceBorder
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.SurfaceElevated
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.viewmodel.MainViewModel

@Composable
fun PlayerScreen(
    viewModel: MainViewModel,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val playbackState by viewModel.playbackState.collectAsState()
    val activeDetails by viewModel.activeStreamDetails.collectAsState()
    val comments by viewModel.comments.collectAsState()
    val isBookmarked by viewModel.isBookmarked.collectAsState()
    val isSubscribed by viewModel.isSubscribed.collectAsState()
    val isDetailsLoading by viewModel.isDetailsLoading.collectAsState()

    val showDownloadSheet by viewModel.showDownloadSheet.collectAsState()
    val showQualityDialog by viewModel.showQualityDialog.collectAsState()
    val showSpeedDialog by viewModel.showSpeedDialog.collectAsState()
    val showSleepTimerDialog by viewModel.showSleepTimerDialog.collectAsState()

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var isLiked by remember { mutableStateOf(false) }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // When in landscape full screen, pressing Back returns to portrait player
    BackHandler(enabled = isLandscape) {
        val activity = context as? Activity
        activity?.let { act ->
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            WindowInsetsControllerCompat(act.window, act.window.decorView).show(WindowInsetsCompat.Type.systemBars())
        }
    }

    val tabs = listOf("Up Next", "Comments (${comments.size})", "Details & Chapters")

    // Keep screen awake while video playback is active and player screen is visible
    DisposableEffect(playbackState.isPlaying, playbackState.isAudioOnly) {
        val window = (context as? Activity)?.window
        val shouldKeepScreenOn = playbackState.isPlaying && !playbackState.isAudioOnly
        if (shouldKeepScreenOn) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .testTag("full_player_screen")
    ) {
        // ExoPlayer viewport (clean edge-to-edge player without top screen title bar)
        VideoPlayerView(
            playbackManager = viewModel.playbackManager,
            playbackState = playbackState,
            onQualityClick = { viewModel.setShowQualityDialog(true) },
            onSpeedClick = { viewModel.setShowSpeedDialog(true) },
            onSleepTimerClick = { viewModel.setShowSleepTimerDialog(true) },
            onCollapse = onCollapse
        )

        // Scrollable Info & Related Content (shown in portrait mode)
        if (!isLandscape) {
            val stream = activeDetails ?: playbackState.currentStream

            if (stream == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = CrimsonRed, strokeWidth = 3.dp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 32.dp)
                ) {
                // Stream Title & Meta (Supports swipe down to collapse)
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDrag = { change, dragAmount ->
                                        if (dragAmount.y > 18f) {
                                            change.consume()
                                            onCollapse()
                                        }
                                    }
                                )
                            }
                            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp)
                    ) {
                        Text(
                            text = stream.title,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp,
                                color = TextPrimary,
                                lineHeight = 22.sp
                            ),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${formatViews(stream.views)} • ${formatRelativeTime(stream.uploadDate)}",
                                color = TextSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                // Action Bar (Compact, tight vertical spacing)
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Like Button
                        PlayerActionButton(
                            icon = if (isLiked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                            label = if (isLiked) "Liked" else "${stream.likes.takeIf { it > 0 } ?: "Like"}",
                            isActive = isLiked,
                            onClick = { isLiked = !isLiked }
                        )

                        // Bookmark Button
                        PlayerActionButton(
                            icon = if (isBookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                            label = if (isBookmarked) "Saved" else "Save",
                            isActive = isBookmarked,
                            onClick = { viewModel.toggleBookmark() }
                        )

                        // Download Button
                        PlayerActionButton(
                            icon = Icons.Default.Download,
                            label = "Download",
                            onClick = { viewModel.setShowDownloadSheet(true) }
                        )

                        // Audio Only / Background Button
                        PlayerActionButton(
                            icon = if (playbackState.isAudioOnly) Icons.Default.VideoLibrary else Icons.Default.Audiotrack,
                            label = if (playbackState.isAudioOnly) "Video" else "Audio",
                            isActive = playbackState.isAudioOnly,
                            onClick = { viewModel.playbackManager.toggleAudioOnly() }
                        )

                        // Sleep Timer Button
                        val isSleepTimerActive = playbackState.sleepTimerSecondsRemaining != null ||
                                playbackState.sleepTimerMinutesRemaining != null ||
                                playbackState.stopAtEndOfTrack
                        PlayerActionButton(
                            icon = Icons.Default.LockClock,
                            label = if (playbackState.stopAtEndOfTrack) "Sleep (End)" else if (playbackState.sleepTimerMinutesRemaining != null) "${playbackState.sleepTimerMinutesRemaining}m" else "Sleep Timer",
                            isActive = isSleepTimerActive,
                            activeColor = com.example.ui.theme.AccentAmber,
                            onClick = { viewModel.setShowSleepTimerDialog(true) }
                        )

                        // Share Button
                        PlayerActionButton(
                            icon = Icons.Default.Share,
                            label = "Share",
                            onClick = {
                                val sendIntent: Intent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_TEXT, "Watch '${stream.title}' on PipeStream without ads: https://youtu.be/${stream.id}")
                                    type = "text/plain"
                                }
                                context.startActivity(Intent.createChooser(sendIntent, "Share Stream"))
                            }
                        )
                    }
                }

                // Active Sleep Timer Banner
                if (playbackState.sleepTimerSecondsRemaining != null ||
                    playbackState.sleepTimerMinutesRemaining != null ||
                    playbackState.stopAtEndOfTrack
                ) {
                    item {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { viewModel.setShowSleepTimerDialog(true) },
                            color = com.example.ui.theme.AccentAmber.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, com.example.ui.theme.AccentAmber.copy(alpha = 0.35f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.LockClock,
                                        contentDescription = "Sleep timer active",
                                        tint = com.example.ui.theme.AccentAmber,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    val timeStr = if (playbackState.stopAtEndOfTrack) {
                                        "Sleep timer: Stops after current stream"
                                    } else {
                                        val sec = playbackState.sleepTimerSecondsRemaining
                                            ?: (playbackState.sleepTimerMinutesRemaining?.times(60) ?: 0)
                                        val mins = sec / 60
                                        val secs = sec % 60
                                        "Sleep timer: Pauses in ${String.format("%02d:%02d", mins, secs)}"
                                    }
                                    Text(
                                        text = timeStr,
                                        color = com.example.ui.theme.AccentAmber,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (!playbackState.stopAtEndOfTrack) {
                                        Surface(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .clickable { viewModel.playbackManager.extendSleepTimer(5) },
                                            color = com.example.ui.theme.SurfaceElevated,
                                            border = BorderStroke(1.dp, com.example.ui.theme.SurfaceBorder)
                                        ) {
                                            Text(
                                                text = "+5m",
                                                color = com.example.ui.theme.AccentAmber,
                                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                                fontSize = 11.sp,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }
                                    IconButton(
                                        onClick = { viewModel.playbackManager.cancelSleepTimer() },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Cancel timer",
                                            tint = com.example.ui.theme.TextMuted,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Channel Header Card
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceElevated),
                        border = BorderStroke(1.dp, SurfaceBorder)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (stream.uploaderAvatar.isNotBlank()) {
                                    AsyncImage(
                                        model = stream.uploaderAvatar,
                                        contentDescription = stream.uploaderName,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(CircleShape)
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(CircleShape)
                                            .background(CrimsonRed),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = stream.uploaderName.take(1).uppercase(),
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 18.sp
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column {
                                    Text(
                                        text = stream.uploaderName,
                                        color = TextPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = if (stream.uploaderSubscribers > 0) "${formatViews(stream.uploaderSubscribers)} subscribers" else "Channel Creator",
                                        color = TextSecondary,
                                        fontSize = 11.sp
                                    )
                                }
                            }

                            // Subscribe Button
                            Button(
                                onClick = { viewModel.toggleSubscription() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isSubscribed) SurfaceDark else CrimsonRed,
                                    contentColor = Color.White
                                ),
                                border = if (isSubscribed) BorderStroke(1.dp, SurfaceBorder) else null,
                                shape = RoundedCornerShape(20.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = if (isSubscribed) "Subscribed" else "Subscribe",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                // Tabs Selector (Up Next, Comments, Details & Chapters)
                item {
                    val tabTitles = listOf("Up Next", "Comments (${comments.size})", "Details")
                    TabRow(
                        selectedTabIndex = selectedTabIndex,
                        containerColor = BackgroundDark,
                        contentColor = TextPrimary,
                        indicator = { tabPositions ->
                            TabRowDefaults.SecondaryIndicator(
                                Modifier.tabIndicatorOffset(tabPositions[selectedTabIndex]),
                                color = CrimsonRed
                            )
                        }
                    ) {
                        tabTitles.forEachIndexed { index, title ->
                            Tab(
                                selected = selectedTabIndex == index,
                                onClick = { selectedTabIndex = index },
                                text = {
                                    Text(
                                        text = title,
                                        fontSize = 12.sp,
                                        fontWeight = if (selectedTabIndex == index) FontWeight.Bold else FontWeight.Medium,
                                        color = if (selectedTabIndex == index) CrimsonRed else TextSecondary
                                    )
                                }
                            )
                        }
                    }
                }

                // Tab Content
                when (selectedTabIndex) {
                    0 -> {
                        // Up Next / Related Streams
                        val related = stream.relatedStreams
                        if (related.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isDetailsLoading) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            CircularProgressIndicator(color = CrimsonRed, strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
                                            Spacer(modifier = Modifier.height(10.dp))
                                            Text("Loading recommendations...", color = TextSecondary, fontSize = 12.sp)
                                        }
                                    } else {
                                        Text("No recommendations found", color = TextSecondary, fontSize = 13.sp)
                                    }
                                }
                            }
                        } else {
                            items(related, key = { it.id }) { item ->
                                CompactStreamItem(
                                    stream = item,
                                    onStreamClick = { viewModel.selectAndPlayStream(it) },
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                            }
                        }
                    }
                    1 -> {
                        // Comments
                        if (comments.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isDetailsLoading) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            CircularProgressIndicator(color = CrimsonRed, strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
                                            Spacer(modifier = Modifier.height(10.dp))
                                            Text("Loading comments...", color = TextSecondary, fontSize = 12.sp)
                                        }
                                    } else {
                                        Text("No comments loaded for this stream", color = TextSecondary, fontSize = 13.sp)
                                    }
                                }
                            }
                        } else {
                            items(comments, key = { it.id }) { comment ->
                                CommentRow(comment)
                            }
                        }
                    }
                    2 -> {
                        // Description & Technical Details & Chapters
                        item {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "Description",
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary,
                                    fontSize = 14.sp
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = stream.description.ifBlank { "High-speed stream with pure audio/video decoding." },
                                    color = TextSecondary,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp
                                )

                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "Stream Details",
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary,
                                    fontSize = 14.sp
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = SurfaceElevated),
                                    border = BorderStroke(1.dp, SurfaceBorder),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text("Format / Quality", color = TextSecondary, fontSize = 12.sp)
                                            Text(playbackState.selectedQuality, color = CrimsonRed, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text("Extraction Protocol", color = TextSecondary, fontSize = 12.sp)
                                            Text("NewPipeExtractor v0.26.5", color = Color(0xFF4CAF50), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text("Video ID", color = TextSecondary, fontSize = 12.sp)
                                            Text(stream.id, color = TextPrimary, fontSize = 12.sp)
                                        }
                                    }
                                }

                                if (stream.chapters.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(18.dp))
                                    Text(
                                        text = "Chapters",
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary,
                                        fontSize = 14.sp
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    stream.chapters.forEach { chapter ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    viewModel.playbackManager.seekTo(chapter.startSeconds * 1000)
                                                }
                                                .padding(vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Surface(
                                                color = CrimsonRed.copy(alpha = 0.15f),
                                                border = BorderStroke(1.dp, CrimsonRed.copy(alpha = 0.3f)),
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Text(
                                                    text = formatSeconds(chapter.startSeconds),
                                                    color = CrimsonRed,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 11.sp,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Text(
                                                text = chapter.title,
                                                color = TextPrimary,
                                                fontWeight = FontWeight.Medium,
                                                fontSize = 13.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    }

    // Download format selection sheet
    if (showDownloadSheet && activeDetails != null) {
        DownloadBottomSheet(
            stream = activeDetails!!,
            onDismiss = { viewModel.setShowDownloadSheet(false) },
            onDownloadSelected = { quality, isAudioOnly ->
                viewModel.startDownload(quality, isAudioOnly)
            }
        )
    }

    // Quality picker dialog
    if (showQualityDialog) {
        QualityPickerDialog(
            currentQuality = playbackState.selectedQuality,
            onQualitySelected = { viewModel.playbackManager.changeQuality(it) },
            onDismiss = { viewModel.setShowQualityDialog(false) }
        )
    }

    // Playback speed dialog
    if (showSpeedDialog) {
        PlaybackSpeedDialog(
            currentSpeed = playbackState.playbackSpeed,
            onSpeedSelected = { viewModel.playbackManager.setPlaybackSpeed(it) },
            onDismiss = { viewModel.setShowSpeedDialog(false) }
        )
    }

    // Sleep timer dialog
    if (showSleepTimerDialog) {
        SleepTimerDialog(
            playbackState = playbackState,
            onSetTimer = { minutes, optionLabel, endOfTrack ->
                viewModel.playbackManager.setSleepTimer(minutes, optionLabel, endOfTrack)
            },
            onExtendTimer = { extraMinutes ->
                viewModel.playbackManager.extendSleepTimer(extraMinutes)
            },
            onDismiss = { viewModel.setShowSleepTimerDialog(false) }
        )
    }
}

@Composable
fun PlayerActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isActive: Boolean = false,
    activeColor: androidx.compose.ui.graphics.Color = CrimsonRed,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = if (isActive) activeColor else SurfaceElevated,
            border = BorderStroke(1.dp, if (isActive) activeColor else SurfaceBorder),
            modifier = Modifier.size(36.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = if (isActive) (if (activeColor == com.example.ui.theme.AccentAmber) androidx.compose.ui.graphics.Color.Black else androidx.compose.ui.graphics.Color.White) else TextPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            color = if (isActive) activeColor else TextSecondary,
            fontSize = 10.sp,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
fun CommentRow(comment: CommentItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        if (comment.authorAvatar.isNotBlank()) {
            AsyncImage(
                model = comment.authorAvatar,
                contentDescription = comment.author,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(SurfaceElevated),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = comment.author.take(1).uppercase(),
                    color = CrimsonRed,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = comment.author,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "• ${comment.timeAgo}",
                    color = TextMuted,
                    fontSize = 11.sp
                )
            }
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = comment.content,
                color = TextSecondary,
                fontSize = 13.sp,
                lineHeight = 17.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.ThumbUp,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(13.dp)
                )
                if (comment.likeCount > 0) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${comment.likeCount}",
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

private fun formatSeconds(seconds: Long): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return String.format("%02d:%02d", mins, secs)
}

private fun formatViews(views: Long): String {
    if (views <= 0) return "0 views"
    return when {
        views >= 1_000_000_000 -> String.format("%.1fB views", views / 1_000_000_000.0)
        views >= 1_000_000 -> String.format("%.1fM views", views / 1_000_000.0)
        views >= 1_000 -> String.format("%.1fK views", views / 1_000.0)
        else -> "$views views"
    }
}

fun formatRelativeTime(rawDate: String): String {
    if (rawDate.isBlank()) return "Recently uploaded"
    val clean = rawDate.trim()

    // If already contains relative marker (e.g. "3 days ago"), return formatted
    if (clean.contains("ago", ignoreCase = true)) {
        return clean
    }

    val formats = listOf(
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") },
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") },
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US),
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US),
        java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.US),
        java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US),
        java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.US)
    )

    var dateMillis: Long? = null
    for (df in formats) {
        try {
            val d = df.parse(clean)
            if (d != null) {
                dateMillis = d.time
                break
            }
        } catch (_: Exception) {}
    }

    if (dateMillis == null) {
        val parsedEpoch = clean.toLongOrNull()
        if (parsedEpoch != null && parsedEpoch > 1000000000L) {
            dateMillis = if (parsedEpoch < 100000000000L) parsedEpoch * 1000L else parsedEpoch
        }
    }

    if (dateMillis != null) {
        val now = System.currentTimeMillis()
        val diffMs = (now - dateMillis).coerceAtLeast(0L)
        val diffSec = diffMs / 1000L
        val diffMin = diffSec / 60L
        val diffHour = diffMin / 60L
        val diffDay = diffHour / 24L
        val diffMonth = diffDay / 30L
        val diffYear = diffDay / 365L

        return when {
            diffYear >= 1 -> "${diffYear} ${if (diffYear == 1L) "Year" else "Years"} Ago"
            diffMonth >= 1 -> "${diffMonth} ${if (diffMonth == 1L) "Month" else "Months"} Ago"
            diffDay >= 1 -> "${diffDay} ${if (diffDay == 1L) "Day" else "Days"} Ago"
            diffHour >= 1 -> "${diffHour} ${if (diffHour == 1L) "Hour" else "Hours"} Ago"
            diffMin >= 1 -> "${diffMin} ${if (diffMin == 1L) "Minute" else "Minutes"} Ago"
            else -> "Just now"
        }
    }

    return clean
}

