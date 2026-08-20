package com.example.ui.components

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.util.Log
import android.util.Rational
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.example.data.model.StreamDetails
import com.example.player.MediaPlaybackManager
import com.example.player.PlaybackState
import com.example.ui.theme.AccentAmber
import com.example.ui.theme.BackgroundDark
import com.example.ui.theme.CrimsonRed
import com.example.ui.theme.SurfaceBorder
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.SurfaceElevated
import com.example.ui.theme.SurfaceHighlight
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.delay


@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerView(
    playbackManager: MediaPlaybackManager,
    playbackState: PlaybackState,
    onQualityClick: () -> Unit,
    onSpeedClick: () -> Unit,
    onSleepTimerClick: () -> Unit,
    onCollapse: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showControls by remember { mutableStateOf(false) }
    var isDraggingSlider by remember { mutableStateOf(false) }
    var sliderPosition by remember { mutableStateOf(0f) }

    // Resize Mode state: Fit -> Zoom/Crop -> Stretch
    var resizeModeIndex by remember { mutableStateOf(0) }
    val resizeModes = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT to "Fit",
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Fill",
        AspectRatioFrameLayout.RESIZE_MODE_FILL to "Stretch"
    )
    val currentResizeMode = resizeModes[resizeModeIndex].first
    val currentResizeLabel = resizeModes[resizeModeIndex].second

    // Double tap feedback state
    var doubleTapFeedback by remember { mutableStateOf<String?>(null) }
    var doubleTapIcon by remember { mutableStateOf(Icons.Default.PlayArrow) }
    var doubleTapAlignment by remember { mutableStateOf(Alignment.Center) }

    LaunchedEffect(doubleTapFeedback) {
        if (doubleTapFeedback != null) {
            delay(700)
            doubleTapFeedback = null
        }
    }

    val isActivelyPlayingOrReady = playbackState.hasFirstFrameRendered ||
            playbackState.isPlaying ||
            playbackState.currentPositionMs > 0L ||
            playbackState.isAudioOnly

    val isInitialLoading = !isActivelyPlayingOrReady && playbackState.isBuffering && playbackState.errorMessage == null
    val isRebuffering = isActivelyPlayingOrReady && playbackState.isBuffering && playbackState.errorMessage == null
    val showLoadingSpinner = (isInitialLoading || isRebuffering) && playbackState.errorMessage == null

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Auto-hide controls after 3.5 seconds of inactivity during active playback
    LaunchedEffect(showControls, playbackState.isPlaying, isInitialLoading) {
        if (showControls && playbackState.isPlaying && !isDraggingSlider && !isInitialLoading) {
            delay(3500)
            showControls = false
        }
    }

    val viewportModifier = if (isLandscape) {
        modifier
            .fillMaxSize()
            .background(Color.Black)
    } else {
        modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(Color.Black)
    }

    Box(
        modifier = viewportModifier
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
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { offset ->
                        when {
                            offset.x < size.width * 0.35f -> {
                                playbackManager.seekRelative(-10000)
                                doubleTapFeedback = "-10s"
                                doubleTapIcon = Icons.Default.Replay10
                                doubleTapAlignment = Alignment.CenterStart
                            }
                            offset.x > size.width * 0.65f -> {
                                playbackManager.seekRelative(10000)
                                doubleTapFeedback = "+10s"
                                doubleTapIcon = Icons.Default.Forward10
                                doubleTapAlignment = Alignment.CenterEnd
                            }
                            else -> {
                                val willPlay = !playbackState.isPlaying
                                playbackManager.togglePlayPause()
                                doubleTapFeedback = if (willPlay) "Playing" else "Paused"
                                doubleTapIcon = if (willPlay) Icons.Default.PlayArrow else Icons.Default.Pause
                                doubleTapAlignment = Alignment.Center
                            }
                        }
                    },
                    onTap = {
                        showControls = !showControls
                    }
                )
            }
            .testTag("video_player_viewport")
    ) {
        // Video Surface View
        if (!playbackState.isAudioOnly) {
            val currentStream = playbackState.currentStream
            if (currentStream != null || playbackState.isLocalFile) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = playbackManager.player
                            useController = false
                            resizeMode = currentResizeMode
                            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                            keepScreenOn = true
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        }
                    },
                    update = { view ->
                        if (view.player != playbackManager.player) {
                            view.player = playbackManager.player
                        }
                        view.resizeMode = currentResizeMode
                        view.keepScreenOn = playbackState.isPlaying
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else {
            // Sleek Glossy Audio Mode Display
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(CrimsonRed.copy(alpha = 0.25f), BackgroundDark, Color.Black)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(16.dp)
                ) {
                    // Glossy Album Disc / Art
                    val streamId = playbackState.currentStream?.id
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .clip(CircleShape)
                            .background(SurfaceElevated)
                            .border(BorderStroke(2.dp, CrimsonRed.copy(alpha = 0.6f)), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!streamId.isNullOrBlank()) {
                            AsyncImage(
                                model = "https://i.ytimg.com/vi/$streamId/hqdefault.jpg",
                                contentDescription = "Audio Art",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Audiotrack,
                                contentDescription = null,
                                tint = CrimsonRed,
                                modifier = Modifier.size(44.dp)
                            )
                        }

                        // Center Vinyl Hole Ring
                        Surface(
                            shape = CircleShape,
                            color = BackgroundDark,
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
                            modifier = Modifier.size(24.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(CrimsonRed)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Small Glossy Equalizer Pill
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = SurfaceElevated.copy(alpha = 0.85f),
                        border = BorderStroke(0.5.dp, SurfaceBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            GlossyEqualizerIndicator(
                                isPlaying = playbackState.isPlaying,
                                barCount = 4,
                                color = CrimsonRed,
                                maxBarHeight = 11.dp,
                                barWidth = 2.5.dp,
                                spacing = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (playbackState.isLocalFile) "OFFLINE AUDIO" else "AUDIO ONLY STREAM",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                }
            }
        }

        // Double Tap Animated Ripple Overlay
        if (doubleTapFeedback != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                contentAlignment = doubleTapAlignment
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.Black.copy(alpha = 0.7f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = doubleTapIcon,
                            contentDescription = doubleTapFeedback,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = doubleTapFeedback!!,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Persistent Bottom Seekbar (Smoothly transitions when controls hide during playback)
        AnimatedVisibility(
            visible = !showControls && !isInitialLoading && playbackState.errorMessage == null,
            enter = fadeIn(animationSpec = tween(260, easing = FastOutSlowInEasing)),
            exit = fadeOut(animationSpec = tween(200, easing = FastOutLinearInEasing)),
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
        ) {
            val streamDurationSec = playbackState.currentStream?.durationSeconds ?: 0L
            val fallbackDurationMs = if (streamDurationSec > 0) streamDurationSec * 1000L else 0L
            val totalDuration = if (playbackState.durationMs > 0) playbackState.durationMs else fallbackDurationMs
            if (totalDuration > 0) {
                val progressFraction = (playbackState.currentPositionMs.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)
                val bufferFraction = (playbackState.bufferedPositionMs.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.Black.copy(alpha = 0.45f))
                ) {
                    // Buffered track
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(bufferFraction)
                            .background(Color.White.copy(alpha = 0.45f))
                    )
                    // Played track with glowing tip
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progressFraction)
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        Color(0xFFFF4D67),
                                        CrimsonRed
                                    )
                                )
                            )
                    )
                }
            }
        }

        // Controls Overlay (Only visible when ready and toggled on with smooth, polished enter/exit transitions)
        AnimatedVisibility(
            visible = showControls && !isInitialLoading,
            enter = fadeIn(animationSpec = tween(260, easing = FastOutSlowInEasing)),
            exit = fadeOut(animationSpec = tween(220, easing = FastOutLinearInEasing)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
            ) {
                // Top Action Bar inside player (Slides down with smooth fade)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .animateEnterExit(
                            enter = slideInVertically(
                                initialOffsetY = { -it / 2 },
                                animationSpec = tween(260, easing = FastOutSlowInEasing)
                            ) + fadeIn(tween(260)),
                            exit = slideOutVertically(
                                targetOffsetY = { -it / 2 },
                                animationSpec = tween(200, easing = FastOutLinearInEasing)
                            ) + fadeOut(tween(200))
                        )
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)
                            )
                        )
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDrag = { change, dragAmount ->
                                    if (dragAmount.y > 15f) {
                                        change.consume()
                                        onCollapse()
                                    }
                                }
                            )
                        }
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left items: Collapse arrow + Quality indicator badge
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        IconButton(
                            onClick = onCollapse,
                            modifier = Modifier.size(36.dp).testTag("collapse_player_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "Collapse Player",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onQualityClick() },
                            color = SurfaceElevated.copy(alpha = 0.85f),
                            border = BorderStroke(0.5.dp, SurfaceBorder)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.HighQuality,
                                    contentDescription = "Quality",
                                    tint = CrimsonRed,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = playbackState.selectedQuality,
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // Center Top Drag Indicator Handle (YouTube style)
                    Box(
                        modifier = Modifier
                            .width(36.dp)
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.6f))
                            .clickable { onCollapse() }
                    )

                    // Right items: Resize mode, Audio toggle, Speed, Sleep timer, PiP
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        // Aspect Ratio Toggle
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    resizeModeIndex = (resizeModeIndex + 1) % resizeModes.size
                                },
                            color = SurfaceElevated.copy(alpha = 0.85f),
                            border = BorderStroke(0.5.dp, SurfaceBorder)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AspectRatio,
                                    contentDescription = "Resize mode",
                                    tint = Color.White,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = currentResizeLabel,
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        // Audio Only Toggle
                        IconButton(
                            onClick = { playbackManager.toggleAudioOnly() },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = if (playbackState.isAudioOnly) Icons.Default.VideoLibrary else Icons.Default.Audiotrack,
                                contentDescription = "Toggle Audio Mode",
                                tint = if (playbackState.isAudioOnly) CrimsonRed else Color.White,
                                modifier = Modifier.size(17.dp)
                            )
                        }

                        // Speed Picker
                        IconButton(
                            onClick = onSpeedClick,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Speed,
                                contentDescription = "Playback Speed",
                                tint = if (playbackState.playbackSpeed != 1.0f) CrimsonRed else Color.White,
                                modifier = Modifier.size(17.dp)
                            )
                        }

                        // Sleep Timer
                        IconButton(
                            onClick = onSleepTimerClick,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.LockClock,
                                contentDescription = "Sleep Timer",
                                tint = if (playbackState.sleepTimerMinutesRemaining != null) AccentAmber else Color.White,
                                modifier = Modifier.size(17.dp)
                            )
                        }

                        // Picture in Picture (PiP)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && context is Activity) {
                            IconButton(
                                onClick = {
                                    try {
                                        val params = PictureInPictureParams.Builder()
                                            .setAspectRatio(Rational(16, 9))
                                            .build()
                                        context.enterPictureInPictureMode(params)
                                    } catch (e: Exception) {
                                        // Ignore if PiP fails
                                    }
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PictureInPictureAlt,
                                    contentDescription = "Picture in Picture",
                                    tint = Color.White,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }
                    }
                }

                // Center Big Play / Pause Button (Scales and fades in/out gracefully)
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .animateEnterExit(
                            enter = scaleIn(
                                initialScale = 0.72f,
                                animationSpec = tween(260, easing = FastOutSlowInEasing)
                            ) + fadeIn(tween(260)),
                            exit = scaleOut(
                                targetScale = 0.72f,
                                animationSpec = tween(200, easing = FastOutLinearInEasing)
                            ) + fadeOut(tween(200))
                        )
                        .size(68.dp)
                        .clip(CircleShape)
                        .background(Color.Transparent)
                        .clickable { playbackManager.togglePlayPause() }
                        .testTag("play_pause_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playbackState.isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(54.dp)
                    )
                }

                // Modern Clean Bottom Seekbar & Controls (Slides up from bottom with smooth fade)
                val streamDurationSec = playbackState.currentStream?.durationSeconds ?: 0L
                val fallbackDurationMs = if (streamDurationSec > 0) streamDurationSec * 1000L else 0L
                val totalDuration = if (playbackState.durationMs > 0) playbackState.durationMs else fallbackDurationMs
                val currentPos = playbackState.currentPositionMs

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .animateEnterExit(
                            enter = slideInVertically(
                                initialOffsetY = { it / 2 },
                                animationSpec = tween(260, easing = FastOutSlowInEasing)
                            ) + fadeIn(tween(260)),
                            exit = slideOutVertically(
                                targetOffsetY = { it / 2 },
                                animationSpec = tween(200, easing = FastOutLinearInEasing)
                            ) + fadeOut(tween(200))
                        )
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                            )
                        )
                        .padding(horizontal = 14.dp, vertical = 4.dp)
                ) {
                    // Glossy, Clean, Low-Height Modern Player Seekbar
                    GlossyModernPlayerSeekBar(
                        currentPositionMs = currentPos,
                        bufferedPositionMs = playbackState.bufferedPositionMs,
                        totalDurationMs = totalDuration,
                        onSeekTo = { seekPosMs ->
                            playbackManager.seekTo(seekPosMs)
                        }
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${formatMs(currentPos)} / ${formatMs(totalDuration)}",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.3.sp
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Loop / Repeat Button
                            IconButton(
                                onClick = { playbackManager.toggleRepeatMode() },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = when (playbackState.repeatMode) {
                                        Player.REPEAT_MODE_ONE -> Icons.Default.RepeatOne
                                        else -> Icons.Default.Repeat
                                    },
                                    contentDescription = "Repeat",
                                    tint = if (playbackState.repeatMode != Player.REPEAT_MODE_OFF) CrimsonRed else Color.White.copy(alpha = 0.8f),
                                    modifier = Modifier.size(15.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            // Full Screen Button (Toggles landscape/fullscreen mode)
                            IconButton(
                                onClick = {
                                    val activity = context as? Activity
                                    activity?.let { act ->
                                        val isCurrentLandscape = act.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                                        if (isCurrentLandscape) {
                                            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                            WindowInsetsControllerCompat(act.window, act.window.decorView).apply {
                                                show(WindowInsetsCompat.Type.systemBars())
                                            }
                                        } else {
                                            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                            WindowInsetsControllerCompat(act.window, act.window.decorView).apply {
                                                hide(WindowInsetsCompat.Type.systemBars())
                                                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                val isCurrentLandscape = (context as? Activity)?.resources?.configuration?.orientation == Configuration.ORIENTATION_LANDSCAPE
                                Icon(
                                    imageVector = if (isCurrentLandscape) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                    contentDescription = if (isCurrentLandscape) "Exit Fullscreen" else "Full Screen",
                                    tint = Color.White.copy(alpha = 0.9f),
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Loading & Buffering Indicator Overlay (Clean, small, modern design)
        AnimatedVisibility(
            visible = showLoadingSpinner,
            enter = fadeIn(animationSpec = tween(150)),
            exit = fadeOut(animationSpec = tween(200)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (isInitialLoading) Color.Black else Color.Black.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center
            ) {
                if (isInitialLoading) {
                    val streamId = playbackState.currentStream?.id
                    if (!streamId.isNullOrBlank()) {
                        AsyncImage(
                            model = "https://i.ytimg.com/vi/$streamId/hqdefault.jpg",
                            contentDescription = "Video Thumbnail Preview",
                            contentScale = ContentScale.Crop,
                            alpha = 0.25f,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.6f))
                        )
                    }
                }

                // Compact Frosted Glass Spinner Badge
                Surface(
                    shape = CircleShape,
                    color = SurfaceDark.copy(alpha = 0.85f),
                    border = BorderStroke(1.dp, SurfaceBorder.copy(alpha = 0.6f)),
                    shadowElevation = 4.dp,
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = CrimsonRed,
                            trackColor = Color.White.copy(alpha = 0.15f),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }
        }

        // Error State Overlay (Top layer if error occurs)
        if (playbackState.errorMessage != null && !playbackState.isBuffering) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f))
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Playback Issue",
                        tint = AccentAmber,
                        modifier = Modifier.size(38.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Unable to play stream directly",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = playbackState.errorMessage ?: "",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { playbackManager.retryPlayback() },
                            colors = ButtonDefaults.buttonColors(containerColor = CrimsonRed),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Retry",
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Retry", fontSize = 12.sp)
                        }

                        Button(
                            onClick = { playbackManager.toggleAudioOnly() },
                            colors = ButtonDefaults.buttonColors(containerColor = SurfaceElevated),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Audiotrack,
                                contentDescription = "Audio Mode",
                                tint = CrimsonRed,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Audio Mode", fontSize = 12.sp, color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MiniPlayerBar(
    playbackState: PlaybackState,
    playbackManager: MediaPlaybackManager,
    onExpand: () -> Unit,
    onClose: () -> Unit = { playbackManager.closePlayback() },
    modifier: Modifier = Modifier
) {
    val stream = playbackState.currentStream ?: return

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, dragAmount ->
                        if (dragAmount.y < -15f) {
                            change.consume()
                            onExpand()
                        }
                    }
                )
            }
            .clickable { onExpand() }
            .testTag("mini_player_bar"),
        color = SurfaceElevated,
        shadowElevation = 16.dp,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        border = BorderStroke(1.dp, SurfaceBorder)
    ) {
        Column {
            // Sleek Glossy Dual-track Scrubber line (Buffered + Played progress)
            val progress = if (playbackState.durationMs > 0) {
                (playbackState.currentPositionMs.toFloat() / playbackState.durationMs.toFloat()).coerceIn(0f, 1f)
            } else 0f
            val bufferedProgress = if (playbackState.durationMs > 0) {
                (playbackState.bufferedPositionMs.toFloat() / playbackState.durationMs.toFloat()).coerceIn(0f, 1f)
            } else 0f

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(Color.White.copy(alpha = 0.12f))
            ) {
                // Buffered track
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(bufferedProgress)
                        .background(Color.White.copy(alpha = 0.35f))
                )
                // Played track with glossy gradient
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progress)
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(Color(0xFFFF334B), CrimsonRed)
                            )
                        )
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mini Thumbnail with rounded corners and clean badge
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(SurfaceDark),
                    contentAlignment = Alignment.Center
                ) {
                    if (stream.id.isNotBlank() && !playbackState.isLocalFile) {
                        AsyncImage(
                            model = "https://img.youtube.com/vi/${stream.id}/hqdefault.jpg",
                            contentDescription = stream.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = if (playbackState.isAudioOnly) Icons.Default.Audiotrack else Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = CrimsonRed,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    // Clean Glassy Equalizer Indicator Overlay
                    if (playbackState.isPlaying) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(3.dp),
                            shape = RoundedCornerShape(4.dp),
                            color = Color.Black.copy(alpha = 0.75f),
                            border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.25f))
                        ) {
                            GlossyEqualizerIndicator(
                                isPlaying = true,
                                modifier = Modifier.padding(horizontal = 3.5.dp, vertical = 2.5.dp),
                                barCount = 3,
                                maxBarHeight = 8.dp,
                                barWidth = 2.dp,
                                spacing = 1.dp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Title and Channel info
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stream.title,
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${stream.uploaderName} • ${if (playbackState.isLocalFile) "Offline Media" else if (playbackState.isAudioOnly) "Audio Mode" else playbackState.selectedQuality}",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Rewind 10s Button
                IconButton(
                    onClick = { playbackManager.seekRelative(-10000) },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Replay10,
                        contentDescription = "Rewind 10s",
                        tint = TextSecondary,
                        modifier = Modifier.size(19.dp)
                    )
                }

                // Play / Pause / Buffering Button
                IconButton(
                    onClick = { playbackManager.togglePlayPause() },
                    modifier = Modifier.size(38.dp)
                ) {
                    if (playbackState.isBuffering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = CrimsonRed,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (playbackState.isPlaying) "Pause" else "Play",
                            tint = CrimsonRed,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }

                // Forward 10s Button
                IconButton(
                    onClick = { playbackManager.seekRelative(10000) },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Forward10,
                        contentDescription = "Forward 10s",
                        tint = TextSecondary,
                        modifier = Modifier.size(19.dp)
                    )
                }

                // Close / Dismiss stream
                IconButton(
                    onClick = {
                        onClose()
                    },
                    modifier = Modifier.size(32.dp).testTag("mini_player_close_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss Player",
                        tint = TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadBottomSheet(
    stream: StreamDetails,
    onDismiss: () -> Unit,
    onDownloadSelected: (quality: String, isAudioOnly: Boolean) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun calculateExactMb(qualityKey: String, isAudio: Boolean): String {
        val exactBytes = com.example.player.DownloadHelper.resolveExactFormatSize(
            qualityKey = qualityKey,
            isAudio = isAudio,
            durationSeconds = stream.durationSeconds,
            videoStreams = stream.videoStreams,
            audioStreams = stream.audioStreams
        )
        return com.example.player.DownloadHelper.formatBytesToMb(exactBytes)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceDark,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Download Stream",
                    style = MaterialTheme.typography.titleLarge.copy(
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                }
            }

            Text(
                text = stream.title,
                color = TextSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "VIDEO FORMATS (MP4)",
                color = CrimsonRed,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            listOf(
                Triple("1080p Full HD", "1080p", calculateExactMb("1080p", false)),
                Triple("720p HD", "720p", calculateExactMb("720p", false)),
                Triple("480p SD", "480p", calculateExactMb("480p", false)),
                Triple("360p Standard", "360p", calculateExactMb("360p", false))
            ).forEach { (label, quality, size) ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { onDownloadSelected(quality, false) },
                    colors = CardDefaults.cardColors(containerColor = SurfaceElevated),
                    border = BorderStroke(1.dp, SurfaceBorder),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(text = label, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(text = "High definition MP4 video", color = TextSecondary, fontSize = 11.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = size, color = CrimsonRed, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(Icons.Default.Download, contentDescription = null, tint = CrimsonRed, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "AUDIO ONLY FORMATS (M4A)",
                color = AccentAmber,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            listOf(
                Triple("HQ Audio (256 kbps)", "256kbps", calculateExactMb("256kbps", true)),
                Triple("Standard Audio (128 kbps)", "128kbps", calculateExactMb("128kbps", true))
            ).forEach { (label, quality, size) ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { onDownloadSelected(quality, true) },
                    colors = CardDefaults.cardColors(containerColor = SurfaceElevated),
                    border = BorderStroke(1.dp, SurfaceBorder),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(text = label, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(text = "Crisp M4A/AAC Audio Only", color = TextSecondary, fontSize = 11.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = size, color = AccentAmber, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(Icons.Default.Download, contentDescription = null, tint = AccentAmber, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun QualityPickerDialog(
    currentQuality: String,
    onQualitySelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf("1080p", "720p", "480p", "360p", "240p", "Audio Only")

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text("Stream Resolution", color = TextPrimary, fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                options.forEach { quality ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                onQualitySelected(quality)
                                onDismiss()
                            }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentQuality.contains(quality, ignoreCase = true),
                            onClick = {
                                onQualitySelected(quality)
                                onDismiss()
                            },
                            colors = RadioButtonDefaults.colors(selectedColor = CrimsonRed)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = quality,
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = if (currentQuality.contains(quality, ignoreCase = true)) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = CrimsonRed, fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
fun PlaybackSpeedDialog(
    currentSpeed: Float,
    onSpeedSelected: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text("Playback Speed", color = TextPrimary, fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                speeds.forEach { speed ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                onSpeedSelected(speed)
                                onDismiss()
                            }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentSpeed == speed,
                            onClick = {
                                onSpeedSelected(speed)
                                onDismiss()
                            },
                            colors = RadioButtonDefaults.colors(selectedColor = CrimsonRed)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (speed == 1.0f) "1.0x (Normal)" else "${speed}x",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = if (currentSpeed == speed) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = CrimsonRed, fontWeight = FontWeight.Bold)
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerDialog(
    playbackState: com.example.player.PlaybackState,
    onSetTimer: (minutes: Int?, optionLabel: String?, endOfTrack: Boolean) -> Unit,
    onExtendTimer: (extraMinutes: Int) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val presets = listOf(10, 15, 20, 30, 45, 60, 90)
    var customMinutes by remember { mutableFloatStateOf(20f) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceDark,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .navigationBarsPadding()
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .background(AccentAmber.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.LockClock,
                            contentDescription = null,
                            tint = AccentAmber,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Sleep Timer",
                            style = MaterialTheme.typography.titleLarge.copy(
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                        )
                        Text(
                            text = "Pauses playback automatically before you sleep",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Active Timer Status Card (if running)
            val isTimerActive = playbackState.sleepTimerSecondsRemaining != null ||
                    playbackState.sleepTimerMinutesRemaining != null ||
                    playbackState.stopAtEndOfTrack

            if (isTimerActive) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = AccentAmber.copy(alpha = 0.12f)),
                    border = BorderStroke(1.dp, AccentAmber.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "ACTIVE TIMER",
                                    color = AccentAmber,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                val displayTime = if (playbackState.stopAtEndOfTrack) {
                                    "Stops at end of track"
                                } else {
                                    val sec = playbackState.sleepTimerSecondsRemaining
                                        ?: (playbackState.sleepTimerMinutesRemaining?.times(60) ?: 0)
                                    val mins = sec / 60
                                    val secs = sec % 60
                                    "${String.format("%02d:%02d", mins, secs)} remaining"
                                }
                                Text(
                                    text = displayTime,
                                    color = TextPrimary,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }

                            // Turn Off button
                            OutlinedButton(
                                onClick = {
                                    onSetTimer(null, null, false)
                                    onDismiss()
                                },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = CrimsonRed),
                                border = BorderStroke(1.dp, CrimsonRed.copy(alpha = 0.6f)),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("Turn Off", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }

                        if (!playbackState.stopAtEndOfTrack) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Quick extend duration:",
                                color = TextMuted,
                                fontSize = 11.sp
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(5, 10, 15, 30).forEach { extra ->
                                    Surface(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { onExtendTimer(extra) },
                                        color = SurfaceElevated,
                                        border = BorderStroke(1.dp, SurfaceBorder)
                                    ) {
                                        Text(
                                            text = "+$extra min",
                                            color = AccentAmber,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(vertical = 8.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            Text(
                text = "SELECT SLEEP DURATION",
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Presets List
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // End of track option
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onSetTimer(null, "End of current stream", true)
                            onDismiss()
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = if (playbackState.stopAtEndOfTrack) AccentAmber.copy(alpha = 0.15f) else SurfaceElevated
                    ),
                    border = BorderStroke(
                        1.dp,
                        if (playbackState.stopAtEndOfTrack) AccentAmber else SurfaceBorder
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "End of this stream",
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Text(
                                text = "Playback stops when the currently playing stream ends",
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                        if (playbackState.stopAtEndOfTrack) {
                            Icon(Icons.Default.LockClock, contentDescription = null, tint = AccentAmber, modifier = Modifier.size(18.dp))
                        }
                    }
                }

                // Preset minute cards in rows of 2
                presets.chunked(2).forEach { rowPresets ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowPresets.forEach { mins ->
                            val isSelected = playbackState.sleepTimerMinutesRemaining == mins && !playbackState.stopAtEndOfTrack
                            Card(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        onSetTimer(mins, "$mins min", false)
                                        onDismiss()
                                    },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) AccentAmber.copy(alpha = 0.15f) else SurfaceElevated
                                ),
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) AccentAmber else SurfaceBorder
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "$mins minutes",
                                        color = TextPrimary,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        fontSize = 13.sp
                                    )
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.LockClock,
                                            contentDescription = null,
                                            tint = AccentAmber,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                        if (rowPresets.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Custom Slider Duration
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceElevated),
                border = BorderStroke(1.dp, SurfaceBorder),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Custom duration",
                            color = TextPrimary,
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "${customMinutes.toInt()} minutes",
                            color = AccentAmber,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }

                    Slider(
                        value = customMinutes,
                        onValueChange = {
                            customMinutes = it
                        },
                        valueRange = 5f..120f,
                        steps = 22,
                        colors = SliderDefaults.colors(
                            thumbColor = AccentAmber,
                            activeTrackColor = AccentAmber,
                            inactiveTrackColor = SurfaceHighlight
                        )
                    )

                    Button(
                        onClick = {
                            val mins = customMinutes.toInt()
                            onSetTimer(mins, "$mins min", false)
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentAmber),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Start ${customMinutes.toInt()} min timer",
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}

@Composable
fun GlossyEqualizerIndicator(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    barCount: Int = 3,
    color: Color = CrimsonRed,
    maxBarHeight: androidx.compose.ui.unit.Dp = 10.dp,
    barWidth: androidx.compose.ui.unit.Dp = 2.dp,
    spacing: androidx.compose.ui.unit.Dp = 1.5.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "glossy_eq")
    val b1 by infiniteTransition.animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(380), RepeatMode.Reverse), label = "eq_b1"
    )
    val b2 by infiniteTransition.animateFloat(
        initialValue = 0.9f, targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(480), RepeatMode.Reverse), label = "eq_b2"
    )
    val b3 by infiniteTransition.animateFloat(
        initialValue = 0.4f, targetValue = 0.85f,
        animationSpec = infiniteRepeatable(tween(320), RepeatMode.Reverse), label = "eq_b3"
    )
    val b4 by infiniteTransition.animateFloat(
        initialValue = 0.75f, targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(420), RepeatMode.Reverse), label = "eq_b4"
    )

    val factors = listOf(b1, b2, b3, b4)

    Row(
        modifier = modifier.height(maxBarHeight),
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.Bottom
    ) {
        for (i in 0 until barCount.coerceIn(2, 4)) {
            val scale = if (isPlaying) factors[i % factors.size] else 0.25f
            Box(
                modifier = Modifier
                    .width(barWidth)
                    .fillMaxHeight(scale)
                    .clip(RoundedCornerShape(barWidth / 2))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.9f),
                                color
                            )
                        )
                    )
            )
        }
    }
}

@Composable
fun GlossyModernPlayerSeekBar(
    currentPositionMs: Long,
    bufferedPositionMs: Long,
    totalDurationMs: Long,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }

    val validDuration = if (totalDurationMs > 0) totalDurationMs else 1L
    val naturalProgress = (currentPositionMs.toFloat() / validDuration.toFloat()).coerceIn(0f, 1f)
    val activeProgress = if (isDragging) dragProgress else naturalProgress
    val bufferedProgress = (bufferedPositionMs.toFloat() / validDuration.toFloat()).coerceIn(0f, 1f)

    val scrubTimestamp = (activeProgress * validDuration).toLong()

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(24.dp)
            .pointerInput(totalDurationMs) {
                detectTapGestures { offset ->
                    if (size.width > 0 && totalDurationMs > 0) {
                        val frac = (offset.x / size.width).coerceIn(0f, 1f)
                        onSeekTo((frac * totalDurationMs).toLong())
                    }
                }
            }
            .pointerInput(totalDurationMs) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        if (size.width > 0) {
                            dragProgress = (offset.x / size.width).coerceIn(0f, 1f)
                        }
                    },
                    onDragEnd = {
                        isDragging = false
                        if (totalDurationMs > 0) {
                            onSeekTo((dragProgress * totalDurationMs).toLong())
                        }
                    },
                    onDragCancel = {
                        isDragging = false
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        if (size.width > 0) {
                            dragProgress = (change.position.x / size.width).coerceIn(0f, 1f)
                        }
                    }
                )
            },
        contentAlignment = Alignment.CenterStart
    ) {
        val totalWidth = maxWidth
        val trackHeight = if (isDragging) 4.5.dp else 3.dp
        val thumbCoreSize = if (isDragging) 12.dp else 8.dp
        val glowOuterSize = if (isDragging) 26.dp else 18.dp
        val glowMidSize = if (isDragging) 18.dp else 13.dp

        // Track Container
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .clip(RoundedCornerShape(trackHeight / 2))
                .background(Color.White.copy(alpha = 0.22f))
        ) {
            // Buffered track
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(bufferedProgress)
                    .background(Color.White.copy(alpha = 0.45f))
            )

            // Active played progress with glowing glossy gradient
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(activeProgress)
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color(0xFFFF4D67),
                                CrimsonRed
                            )
                        )
                    )
            )
        }

        // Glowing Thumb / Playing Indicator (Multi-layer radiant glow effect)
        val thumbOffset = ((totalWidth - glowOuterSize) * activeProgress).coerceAtLeast(0.dp)
        Box(
            modifier = Modifier
                .padding(start = thumbOffset)
                .size(glowOuterSize),
            contentAlignment = Alignment.Center
        ) {
            // Layer 1: Ambient soft crimson glow halo
            Box(
                modifier = Modifier
                    .size(glowOuterSize)
                    .clip(CircleShape)
                    .background(CrimsonRed.copy(alpha = 0.38f))
            )

            // Layer 2: Radiant mid glow ring
            Box(
                modifier = Modifier
                    .size(glowMidSize)
                    .clip(CircleShape)
                    .background(Color(0xFFFF334B).copy(alpha = 0.72f))
            )

            // Layer 3: Solid white-core thumb with crimson border
            Box(
                modifier = Modifier
                    .size(thumbCoreSize)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(BorderStroke(if (isDragging) 2.dp else 1.5.dp, CrimsonRed), CircleShape)
            )
        }

        // Floating Timestamp Tooltip during scrub
        if (isDragging) {
            val tooltipOffset = ((totalWidth * activeProgress) - 24.dp).coerceIn(0.dp, totalWidth - 48.dp)
            Surface(
                modifier = Modifier
                    .padding(start = tooltipOffset)
                    .align(Alignment.TopStart)
                    .offset(y = (-20).dp),
                shape = RoundedCornerShape(6.dp),
                color = Color.Black.copy(alpha = 0.88f),
                border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.3f))
            ) {
                Text(
                    text = formatMs(scrubTimestamp),
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

