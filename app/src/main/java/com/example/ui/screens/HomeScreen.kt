package com.example.ui.screens

import android.content.Intent
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import com.example.ui.components.HomeFeedSkeletonList
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.StreamItem
import com.example.ui.components.CategoryPillRow
import com.example.ui.components.ContinueWatchingRow
import com.example.ui.components.PersonalizedSectionHeader
import com.example.ui.components.StreamCard
import com.example.ui.theme.AccentAmber
import com.example.ui.theme.BackgroundDark
import com.example.ui.theme.CrimsonRed
import com.example.ui.theme.SurfaceBorder
import com.example.ui.theme.SurfaceElevated
import com.example.ui.theme.SurfaceHighlight
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onNavigateToSearch: () -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val trendingStreams by viewModel.trendingStreams.collectAsState()
    val personalizedStreams by viewModel.personalizedStreams.collectAsState()
    val subscriptionStreams by viewModel.subscriptionStreams.collectAsState()
    val historyItems by viewModel.historyFlow.collectAsState()
    val hasEnoughActivity by viewModel.hasEnoughActivity.collectAsState()
    val selectedCategory by viewModel.selectedCategory.collectAsState()
    val isLoading by viewModel.isTrendingLoading.collectAsState()
    val feedErrorMessage by viewModel.feedErrorMessage.collectAsState()
    val contentRegion by viewModel.contentRegion.collectAsState()

    // Map history for fast watch progress lookup
    val historyMap = remember(historyItems) {
        historyItems.associateBy { it.streamId }
    }

    val categories = if (contentRegion.equals("BD", ignoreCase = true)) {
        listOf("All", "For You", "Natok & Drama", "Music", "Tech", "Islamic", "News", "Gaming", "Podcasts")
    } else {
        listOf("All", "For You", "Music", "Gaming", "News", "Tech", "Podcasts", "Live")
    }

    // Filter continue watching items (items with lastPosition > 0 or recent items)
    val continueWatchingItems = historyItems.take(6)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("home_screen")
    ) {
        // YouTube-Style Top App Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // App Logo & Brand
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = CrimsonRed,
                    shadowElevation = 3.dp,
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.PlayCircle,
                            contentDescription = "PipeStream Logo",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "PipeStream",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Black,
                            fontSize = 20.sp,
                            color = MaterialTheme.colorScheme.onBackground,
                            letterSpacing = (-0.5).sp
                        )
                    )
                }
            }

            // Search & Settings Action Buttons
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                IconButton(
                    onClick = onNavigateToSearch,
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("home_search_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(22.dp)
                    )
                }
                IconButton(
                    onClick = onNavigateToSettings,
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("home_settings_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        // YouTube Filter Topics Chip Row
        CategoryPillRow(
            categories = categories,
            selectedCategory = selectedCategory,
            onCategorySelected = { viewModel.loadCategoryFeed(it) },
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Pull to refresh stream list
        PullToRefreshBox(
            isRefreshing = isLoading,
            onRefresh = { viewModel.loadCategoryFeed(selectedCategory, forceRefresh = true) },
            modifier = Modifier.fillMaxSize()
        ) {
            val displayStreams = if (selectedCategory == "For You" && personalizedStreams.isNotEmpty()) {
                (personalizedStreams + trendingStreams).distinctBy { it.id }
            } else {
                trendingStreams
            }

            if (displayStreams.isEmpty() && isLoading) {
                HomeFeedSkeletonList()
            } else if (displayStreams.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.size(64.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Retry",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (feedErrorMessage != null) "Feed Connection Notice" else "No Videos Found",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = feedErrorMessage ?: "Please check network connection or tap retry.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = { viewModel.loadCategoryFeed(selectedCategory, forceRefresh = true) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Retry", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 1. Continue Watching Section (if user has active watch history)
                    if ((selectedCategory == "For You" || selectedCategory == "All") && continueWatchingItems.isNotEmpty()) {
                        item {
                            ContinueWatchingRow(
                                items = continueWatchingItems,
                                onItemClick = { h ->
                                    viewModel.selectAndPlayStream(
                                        StreamItem(
                                            id = h.streamId,
                                            title = h.title,
                                            uploaderName = h.uploaderName,
                                            uploaderAvatar = h.uploaderAvatar,
                                            thumbnailUrl = h.thumbnailUrl,
                                            durationSeconds = h.durationSeconds
                                        )
                                    )
                                }
                            )
                        }
                    }

                    // 2. Main YouTube-style video cards feed
                    items(displayStreams, key = { it.id }) { stream ->
                        val historyEntry = historyMap[stream.id]
                        val progress = if (historyEntry != null && historyEntry.durationSeconds > 0) {
                            (historyEntry.lastPositionMs / 1000f) / historyEntry.durationSeconds
                        } else null

                        StreamCard(
                            stream = stream,
                            watchProgress = progress,
                            onStreamClick = { viewModel.selectAndPlayStream(it) },
                            onPlayBackgroundClick = {
                                viewModel.selectAndPlayStream(it, audioOnly = true)
                            },
                            onDownloadClick = {
                                viewModel.selectAndPlayStream(it)
                                viewModel.setShowDownloadSheet(true)
                            },
                            onBookmarkClick = {
                                viewModel.toggleBookmarkForStream(it)
                            },
                            onShareClick = { item ->
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    putExtra(Intent.EXTRA_TEXT, "Watch ${item.title} on PipeStream: https://youtu.be/${item.id}")
                                    type = "text/plain"
                                }
                                val shareIntent = Intent.createChooser(sendIntent, "Share Video")
                                context.startActivity(shareIntent)
                            }
                        )
                    }
                }
            }
        }
    }
}


