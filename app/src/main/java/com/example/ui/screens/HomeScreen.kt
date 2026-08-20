package com.example.ui.screens

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.StreamItem
import com.example.ui.components.CategoryPillRow
import com.example.ui.components.CompactStreamItem
import com.example.ui.components.ContinueWatchingRow
import com.example.ui.components.HeroFeaturedCard
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
    val trendingStreams by viewModel.trendingStreams.collectAsState()
    val personalizedStreams by viewModel.personalizedStreams.collectAsState()
    val subscriptionStreams by viewModel.subscriptionStreams.collectAsState()
    val historyItems by viewModel.historyFlow.collectAsState()
    val hasEnoughActivity by viewModel.hasEnoughActivity.collectAsState()
    val selectedCategory by viewModel.selectedCategory.collectAsState()
    val isLoading by viewModel.isTrendingLoading.collectAsState()
    val feedErrorMessage by viewModel.feedErrorMessage.collectAsState()

    val contentRegion by viewModel.contentRegion.collectAsState()

    val categories = if (contentRegion.equals("BD", ignoreCase = true)) {
        listOf("For You", "All", "Natok & Drama", "Music", "Tech", "Islamic", "News", "Gaming", "Podcasts")
    } else {
        listOf("For You", "All", "Music", "Gaming", "News", "Tech", "Podcasts")
    }

    // Filter continue watching items (e.g. items with lastPosition > 0 or recent 5 items)
    val continueWatchingItems = historyItems.take(6)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("home_screen")
    ) {
        // Top App Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // App Logo & Brand with subtle glow
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primary,
                    shadowElevation = 4.dp,
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.PlayCircle,
                            contentDescription = "PipeStream Logo",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "PipeStream",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Black,
                            fontSize = 20.sp,
                            color = MaterialTheme.colorScheme.onBackground,
                            letterSpacing = (-0.5).sp
                        )
                    )
                    Text(
                        text = "Stream Player",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            letterSpacing = 0.6.sp
                        )
                    )
                }
            }

            // Search & Settings Action Buttons
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.size(40.dp)
                ) {
                    IconButton(
                        onClick = onNavigateToSearch,
                        modifier = Modifier.testTag("home_search_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.size(40.dp)
                ) {
                    IconButton(
                        onClick = onNavigateToSettings,
                        modifier = Modifier.testTag("home_settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        // Category pills
        CategoryPillRow(
            categories = categories,
            selectedCategory = selectedCategory,
            onCategorySelected = { viewModel.loadCategoryFeed(it) },
            modifier = Modifier.padding(bottom = 6.dp)
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
                            text = if (feedErrorMessage != null) "Extraction Error" else "No Streams Found",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = feedErrorMessage ?: "No live streams returned via NewPipeExtractor.",
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
                    contentPadding = PaddingValues(top = 4.dp, bottom = 90.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 1. Continue Watching Section (if user has active watch history)
                    if (selectedCategory == "For You" && continueWatchingItems.isNotEmpty()) {
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

                    // 2. Featured Hero Stream
                    val heroStream = displayStreams.firstOrNull()
                    if (heroStream != null) {
                        item {
                            HeroFeaturedCard(
                                stream = heroStream,
                                onStreamClick = { viewModel.selectAndPlayStream(it) }
                            )
                        }
                    }

                    // 3. Personalized Recommendation Carousel (For You)
                    if (selectedCategory == "For You" && personalizedStreams.isNotEmpty()) {
                        item {
                            val headerTitle = if (hasEnoughActivity) {
                                "Recommended For You"
                            } else {
                                "Trending & Popular"
                            }
                            val headerSubtitle = if (hasEnoughActivity) {
                                if (historyItems.isNotEmpty()) "Based on your watch activity and subscriptions" else "Tailored discoveries for you"
                            } else {
                                "Trending, popular, and recent mixed videos"
                            }

                            PersonalizedSectionHeader(
                                title = headerTitle,
                                subtitle = headerSubtitle
                            )
                        }

                        item {
                            LazyRow(
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(personalizedStreams.take(8), key = { "rec_${it.id}" }) { stream ->
                                    StreamCard(
                                        stream = stream,
                                        onStreamClick = { viewModel.selectAndPlayStream(it) },
                                        onDownloadClick = {
                                            viewModel.selectAndPlayStream(it)
                                            viewModel.setShowDownloadSheet(true)
                                        },
                                        onBookmarkClick = {
                                            viewModel.toggleBookmark()
                                        },
                                        modifier = Modifier.width(280.dp)
                                    )
                                }
                            }
                        }
                    }

                    // 4. Subscribed Channels Stream Carousel (if available)
                    if (subscriptionStreams.isNotEmpty()) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Subscriptions,
                                    contentDescription = null,
                                    tint = CrimsonRed,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "From Your Subscriptions",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 17.sp,
                                        color = TextPrimary
                                    )
                                )
                            }
                        }

                        item {
                            LazyRow(
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(subscriptionStreams, key = { "sub_${it.id}" }) { stream ->
                                    StreamCard(
                                        stream = stream,
                                        onStreamClick = { viewModel.selectAndPlayStream(it) },
                                        modifier = Modifier.width(260.dp)
                                    )
                                }
                            }
                        }
                    }

                    // 5. Main Feed Section Title
                    item {
                        Text(
                            text = when (selectedCategory) {
                                "For You" -> if (contentRegion.equals("BD", ignoreCase = true)) "Trending in Bangladesh" else "Trending in ${viewModel.userCountryName}"
                                "All" -> if (contentRegion.equals("BD", ignoreCase = true)) "Popular in Bangladesh" else "Trending Now"
                                else -> "$selectedCategory Highlights"
                            },
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = TextPrimary
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }

                    // 6. Remaining Streams List
                    val restStreams = if (displayStreams.size > 1) displayStreams.drop(1) else displayStreams
                    items(restStreams, key = { it.id }) { stream ->
                        StreamCard(
                            stream = stream,
                            onStreamClick = { viewModel.selectAndPlayStream(it) },
                            onDownloadClick = {
                                viewModel.selectAndPlayStream(it)
                                viewModel.setShowDownloadSheet(true)
                            },
                            onBookmarkClick = {
                                viewModel.toggleBookmark()
                            }
                        )
                    }
                }
            }
        }
    }
}

