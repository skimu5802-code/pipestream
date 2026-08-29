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
    val isLoadingMore by viewModel.isLoadingMoreFeed.collectAsState()
    val feedErrorMessage by viewModel.feedErrorMessage.collectAsState()
    val contentRegion by viewModel.contentRegion.collectAsState()

    // Map history for fast watch progress lookup
    val historyMap = remember(historyItems) {
        historyItems.associateBy { it.streamId }
    }

    // Dynamic Time-of-Day contextual category tag
    val currentHour = remember { java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) }
    val timeContextPill = when (currentHour) {
        in 5..11 -> "☀️ Morning Mix"
        in 12..16 -> "🌤️ Afternoon"
        in 17..21 -> "🌆 Evening"
        else -> "🌙 Night Chill"
    }

    val categories = if (contentRegion.equals("BD", ignoreCase = true)) {
        listOf("All", "For You", timeContextPill, "Natok & Drama", "Music", "Tech", "Islamic", "News", "Gaming", "Podcasts")
    } else {
        listOf("All", "For You", timeContextPill, "Music", "Gaming", "News", "Tech", "Podcasts", "Live")
    }

    // Lazy list state for infinite scroll pagination
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    // Blend feeds: Subscriptions + Personalized (70%) + Trending (30%)
    val displayStreams = remember(selectedCategory, personalizedStreams, trendingStreams, subscriptionStreams) {
        when {
            selectedCategory == "For You" -> {
                val combined = mutableListOf<StreamItem>()
                // Interleave subscription recent videos, personalized recommendations, and trending
                val seen = mutableSetOf<String>()
                
                // Add up to 2 fresh subscription videos at top if available
                subscriptionStreams.take(2).forEach { item ->
                    if (seen.add(item.id)) combined.add(item)
                }
                
                // Add personalized recommendations
                personalizedStreams.forEach { item ->
                    if (seen.add(item.id)) combined.add(item)
                }

                // Add remaining subscription videos
                subscriptionStreams.forEach { item ->
                    if (seen.add(item.id)) combined.add(item)
                }

                // Add trending videos
                trendingStreams.forEach { item ->
                    if (seen.add(item.id)) combined.add(item)
                }
                combined
            }
            selectedCategory == "All" -> {
                val combined = mutableListOf<StreamItem>()
                val seen = mutableSetOf<String>()
                // Add top 1 subscription video if available
                subscriptionStreams.firstOrNull()?.let {
                    if (seen.add(it.id)) combined.add(it)
                }
                trendingStreams.forEach { item ->
                    if (seen.add(item.id)) combined.add(item)
                }
                combined
            }
            else -> trendingStreams
        }
    }

    // Infinite Dynamic Scroll Trigger
    androidx.compose.runtime.LaunchedEffect(listState, displayStreams.size, isLoading, isLoadingMore) {
        androidx.compose.runtime.snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastVisibleIndex ->
                if (lastVisibleIndex != null && lastVisibleIndex >= displayStreams.size - 4 && displayStreams.isNotEmpty() && !isLoading && !isLoadingMore) {
                    viewModel.loadMoreFeed()
                }
            }
    }

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
            onCategorySelected = { cat ->
                val targetCategory = if (cat.startsWith("☀️") || cat.startsWith("🌤️") || cat.startsWith("🌆") || cat.startsWith("🌙")) {
                    when (currentHour) {
                        in 5..11 -> "Music"
                        in 12..16 -> "All"
                        in 17..21 -> "Natok & Drama"
                        else -> "Podcasts"
                    }
                } else cat
                viewModel.loadCategoryFeed(targetCategory)
            },
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Pull to refresh stream list
        PullToRefreshBox(
            isRefreshing = isLoading,
            onRefresh = { viewModel.loadCategoryFeed(selectedCategory, forceRefresh = true) },
            modifier = Modifier.fillMaxSize()
        ) {
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
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Main YouTube-style video cards feed
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
                                viewModel.openDownloadSheetForStream(it)
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

                    // Infinite Scroll Loading Indicator at Bottom
                    if (isLoadingMore) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 20.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    androidx.compose.material3.CircularProgressIndicator(
                                        modifier = Modifier.size(22.dp),
                                        strokeWidth = 2.5.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "Loading more videos...",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
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


