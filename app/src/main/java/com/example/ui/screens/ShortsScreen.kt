package com.example.ui.screens

import android.app.Activity
import android.content.Intent
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.example.data.model.CommentItem
import com.example.data.model.StreamItem
import com.example.ui.theme.BackgroundDark
import com.example.ui.theme.CrimsonRed
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.SurfaceElevated
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.delay

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShortsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val shortsList by viewModel.shortsStreams.collectAsState()
    val isShortsLoading by viewModel.isShortsLoading.collectAsState()
    val playbackState by viewModel.playbackState.collectAsState()
    val subscriptions by viewModel.subscriptionsFlow.collectAsState()
    val bookmarks by viewModel.bookmarksFlow.collectAsState()

    val context = LocalContext.current
    var showCommentsSheet by remember { mutableStateOf(false) }
    val likedShorts = remember { mutableStateMapOf<String, Boolean>() }

    // Keep screen awake while Shorts video is actively playing
    DisposableEffect(playbackState.isPlaying) {
        val window = (context as? Activity)?.window
        if (playbackState.isPlaying) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    if (shortsList.isEmpty() && isShortsLoading) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(BackgroundDark),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(
                    color = CrimsonRed,
                    modifier = Modifier.size(40.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Loading YouTube Shorts...",
                    color = TextSecondary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        return
    }

    if (shortsList.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(BackgroundDark),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "No Shorts available",
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = CrimsonRed,
                    modifier = Modifier.clickable { viewModel.loadShorts(forceRefresh = true) }
                ) {
                    Text(
                        text = "Refresh",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                    )
                }
            }
        }
        return
    }

    val pagerState = rememberPagerState(pageCount = { shortsList.size })

    // When page changes, automatically select and play the corresponding short
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            if (page in shortsList.indices) {
                val shortItem = shortsList[page]
                if (playbackState.currentStream?.id != shortItem.id) {
                    viewModel.playShort(shortItem)
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("shorts_screen_container")
    ) {
        VerticalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val short = shortsList.getOrNull(page)
            if (short != null) {
                val isCurrentPage = pagerState.currentPage == page
                val isPlayingCurrent = isCurrentPage && playbackState.currentStream?.id == short.id && playbackState.isPlaying
                val isSubscribed = subscriptions.any { it.channelName == short.uploaderName }
                val isBookmarked = bookmarks.any { it.streamId == short.id }
                val isLiked = likedShorts[short.id] == true

                ShortItemPage(
                    short = short,
                    isCurrentPage = isCurrentPage,
                    isPlaying = isPlayingCurrent,
                    playbackState = playbackState,
                    playbackManager = viewModel.playbackManager,
                    isSubscribed = isSubscribed,
                    isBookmarked = isBookmarked,
                    isLiked = isLiked,
                    onToggleLike = {
                        likedShorts[short.id] = !isLiked
                        if (!isLiked) {
                            viewModel.showSnackbar("Added to liked videos")
                        }
                    },
                    onToggleBookmark = {
                        viewModel.toggleBookmarkForStream(short)
                    },
                    onToggleSubscribe = {
                        viewModel.toggleSubscriptionForChannel(short.uploaderName, short.uploaderAvatar, short.uploaderUrl)
                    },
                    onOpenComments = {
                        viewModel.loadCommentsForStream(short.id)
                        showCommentsSheet = true
                    },
                    onShare = {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, short.title)
                            putExtra(Intent.EXTRA_TEXT, "Watch this YouTube Short: https://www.youtube.com/shorts/${short.id}")
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share Short via"))
                    },
                    onExpandFullPlayer = {
                        viewModel.selectAndPlayStream(short)
                    }
                )
            }
        }

        // Top Shorts Header with branding & refresh
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)
                    )
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Shorts",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-0.5).sp
                )
                Spacer(modifier = Modifier.width(6.dp))
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = CrimsonRed,
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Text(
                        text = "FEED",
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }

            IconButton(
                onClick = { viewModel.loadShorts(forceRefresh = true) },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Refresh Shorts",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Comments Bottom Sheet
        if (showCommentsSheet) {
            val comments by viewModel.comments.collectAsState()
            val isDetailsLoading by viewModel.isDetailsLoading.collectAsState()
            ShortsCommentsSheet(
                comments = comments,
                isLoading = isDetailsLoading,
                onDismiss = { showCommentsSheet = false }
            )
        }
    }
}

@Composable
fun ShortItemPage(
    short: StreamItem,
    isCurrentPage: Boolean,
    isPlaying: Boolean,
    playbackState: com.example.player.PlaybackState,
    playbackManager: com.example.player.MediaPlaybackManager,
    isSubscribed: Boolean,
    isBookmarked: Boolean,
    isLiked: Boolean,
    onToggleLike: () -> Unit,
    onToggleBookmark: () -> Unit,
    onToggleSubscribe: () -> Unit,
    onOpenComments: () -> Unit,
    onShare: () -> Unit,
    onExpandFullPlayer: () -> Unit
) {
    var showPlayPauseIndicator by remember { mutableStateOf(false) }
    var showHeartAnimation by remember { mutableStateOf(false) }

    LaunchedEffect(showPlayPauseIndicator) {
        if (showPlayPauseIndicator) {
            delay(650)
            showPlayPauseIndicator = false
        }
    }

    LaunchedEffect(showHeartAnimation) {
        if (showHeartAnimation) {
            delay(750)
            showHeartAnimation = false
        }
    }

    // Infinite rotating transition for audio vinyl disk
    val infiniteTransition = rememberInfiniteTransition(label = "vinyl_disc")
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "vinyl_rot"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        if (!isLiked) onToggleLike()
                        showHeartAnimation = true
                    },
                    onTap = {
                        if (isCurrentPage) {
                            playbackManager.togglePlayPause()
                            showPlayPauseIndicator = true
                        }
                    }
                )
            }
    ) {
        // Video Player viewport or fallback thumbnail
        if (isCurrentPage && playbackState.currentStream?.id == short.id) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = playbackManager.player
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                        keepScreenOn = true
                    }
                },
                update = { view ->
                    view.player = playbackManager.player
                    view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    view.keepScreenOn = isPlaying
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            AsyncImage(
                model = "https://img.youtube.com/vi/${short.id}/hqdefault.jpg",
                contentDescription = short.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Top, bottom, and right side gradient shadows for crisp readability
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.4f),
                        0.3f to Color.Transparent,
                        0.65f to Color.Black.copy(alpha = 0.4f),
                        1f to Color.Black.copy(alpha = 0.85f)
                    )
                )
        )

        // Center Big Animated Play/Pause Feedback Indicator
        AnimatedVisibility(
            visible = showPlayPauseIndicator,
            enter = fadeIn(tween(100)) + scaleIn(tween(150)),
            exit = fadeOut(tween(250)) + scaleOut(tween(250)),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.65f),
                border = BorderStroke(1.5.dp, Color.White.copy(alpha = 0.3f)),
                modifier = Modifier.size(68.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
        }

        // Center Double-Tap Bursting Heart Animation
        AnimatedVisibility(
            visible = showHeartAnimation,
            enter = fadeIn(tween(80)) + scaleIn(tween(200, easing = FastOutSlowInEasing)),
            exit = fadeOut(tween(300)) + scaleOut(tween(300)),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Icon(
                imageVector = Icons.Default.Favorite,
                contentDescription = null,
                tint = CrimsonRed,
                modifier = Modifier.size(90.dp)
            )
        }

        // Right-Side Vertical Action Buttons Overlay
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp, bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Like Button
            ShortActionButton(
                icon = if (isLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                label = if (isLiked) "Liked" else formatCount(short.views / 20),
                tint = if (isLiked) CrimsonRed else Color.White,
                onClick = onToggleLike
            )

            // Comments Button
            ShortActionButton(
                icon = Icons.Filled.ChatBubbleOutline,
                label = "Comments",
                tint = Color.White,
                onClick = onOpenComments
            )

            // Bookmark / Save Button
            ShortActionButton(
                icon = if (isBookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                label = if (isBookmarked) "Saved" else "Save",
                tint = if (isBookmarked) CrimsonRed else Color.White,
                onClick = onToggleBookmark
            )

            // Share Button
            ShortActionButton(
                icon = Icons.Filled.Share,
                label = "Share",
                tint = Color.White,
                onClick = onShare
            )

            // Full Player / Expand Button
            ShortActionButton(
                icon = Icons.Filled.OpenInFull,
                label = "Expand",
                tint = Color.White,
                onClick = onExpandFullPlayer
            )

            // Rotating Vinyl Sound Disc
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.DarkGray)
                    .border(BorderStroke(2.dp, Color.White.copy(alpha = 0.5f)), CircleShape)
                    .rotate(if (isPlaying) rotationAngle else 0f),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = short.uploaderAvatar.ifBlank { "https://img.youtube.com/vi/${short.id}/hqdefault.jpg" },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                )
            }
        }

        // Left-Bottom Metadata Overlay (Creator, Title, Music track)
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(0.78f)
                .padding(start = 14.dp, bottom = 28.dp, end = 8.dp)
        ) {
            // Channel Info & Subscribe Button
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = short.uploaderAvatar.ifBlank { "https://img.youtube.com/vi/${short.id}/hqdefault.jpg" },
                    contentDescription = short.uploaderName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(SurfaceDark)
                )

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = short.uploaderName,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.width(10.dp))

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (isSubscribed) SurfaceElevated else CrimsonRed,
                    border = if (isSubscribed) BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)) else null,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { onToggleSubscribe() }
                ) {
                    Text(
                        text = if (isSubscribed) "Subscribed" else "Subscribe",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Short Title
            Text(
                text = short.title,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Normal,
                lineHeight = 18.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Sound Track / Audio Banner
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Audiotrack,
                    contentDescription = "Original Sound",
                    tint = Color.White,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "${short.uploaderName} • Original Sound",
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Bottom Continuous Progress Line
        if (isCurrentPage && playbackState.durationMs > 0) {
            val progress = (playbackState.currentPositionMs.toFloat() / playbackState.durationMs.toFloat()).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { progress },
                color = CrimsonRed,
                trackColor = Color.White.copy(alpha = 0.2f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.5.dp)
                    .align(Alignment.BottomCenter)
            )
        }
    }
}

@Composable
fun ShortActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
    ) {
        Surface(
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.45f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
            modifier = Modifier.size(44.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = tint,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShortsCommentsSheet(
    comments: List<CommentItem>,
    isLoading: Boolean,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceDark,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(460.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Comments (${comments.size})",
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                }
            }

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = CrimsonRed, modifier = Modifier.size(32.dp))
                }
            } else if (comments.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No comments available for this Short",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(comments, key = { it.id }) { comment ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            AsyncImage(
                                model = comment.authorAvatar.ifBlank { "https://img.youtube.com/vi/${comment.id}/hqdefault.jpg" },
                                contentDescription = comment.author,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(SurfaceElevated)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = comment.author,
                                        color = TextPrimary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = comment.timeAgo,
                                        color = TextMuted,
                                        fontSize = 11.sp
                                    )
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = comment.content,
                                    color = TextSecondary,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Outlined.ThumbUp,
                                        contentDescription = null,
                                        tint = TextMuted,
                                        modifier = Modifier.size(12.dp)
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
                }
            }
        }
    }
}

private fun formatCount(count: Long): String {
    return when {
        count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
        count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
        count > 0 -> count.toString()
        else -> "Like"
    }
}
