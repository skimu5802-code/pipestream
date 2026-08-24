package com.example.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.updater.GitHubRelease
import com.example.data.updater.UpdateState
import com.example.ui.theme.AccentGreen
import com.example.ui.theme.CrimsonRed
import java.io.File

@Composable
fun UpdateDialog(
    updateState: UpdateState,
    onDismiss: () -> Unit,
    onUpdateClick: (GitHubRelease) -> Unit,
    onInstallClick: (File) -> Unit
) {
    val context = LocalContext.current

    when (updateState) {
        is UpdateState.UpdateAvailable -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                modifier = Modifier.testTag("update_dialog"),
                icon = {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(CrimsonRed.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.SystemUpdate,
                            contentDescription = "Update Available",
                            tint = CrimsonRed,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                },
                title = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "New Update Available!",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = CrimsonRed.copy(alpha = 0.12f)
                        ) {
                            Text(
                                text = updateState.release.tagName,
                                color = CrimsonRed,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)
                            )
                        }
                    }
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = "Release Notes:",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        ClickableChangelogText(
                            rawText = updateState.release.changelog.ifBlank { "Performance improvements and bug fixes." },
                            onUrlClick = { url ->
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    // Ignore browser launch failure
                                }
                            }
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { onUpdateClick(updateState.release) },
                        colors = ButtonDefaults.buttonColors(containerColor = CrimsonRed),
                        modifier = Modifier.testTag("update_now_button")
                    ) {
                        Text("Update Now", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("update_later_button")
                    ) {
                        Text("Later")
                    }
                }
            )
        }

        is UpdateState.Downloading -> {
            AlertDialog(
                onDismissRequest = {},
                icon = {
                    Icon(
                        imageVector = Icons.Default.CloudDownload,
                        contentDescription = "Downloading",
                        tint = CrimsonRed,
                        modifier = Modifier.size(36.dp)
                    )
                },
                title = {
                    Text(
                        text = "Downloading Update...",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        LinearProgressIndicator(
                            progress = { updateState.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = CrimsonRed,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${updateState.downloadedMb} MB / ${updateState.totalMb} MB",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${(updateState.progress * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = CrimsonRed
                            )
                        }
                    }
                },
                confirmButton = {}
            )
        }

        is UpdateState.ReadyToInstall -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Download Complete", fontWeight = FontWeight.Bold) },
                text = { Text("PipeStream update is downloaded and ready to install.") },
                confirmButton = {
                    Button(
                        onClick = { onInstallClick(updateState.apkFile) },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                        modifier = Modifier.testTag("install_now_button")
                    ) {
                        Text("Install Now", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text("Dismiss")
                    }
                }
            )
        }

        is UpdateState.Error -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                icon = {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = "Error",
                        tint = MaterialTheme.colorScheme.error
                    )
                },
                title = { Text("Update Failed") },
                text = { Text(updateState.message) },
                confirmButton = {
                    Button(onClick = onDismiss) {
                        Text("OK")
                    }
                }
            )
        }

        else -> {}
    }
}

/**
 * Parses markdown links `[Title](url)` and standard `http://` / `https://` URLs
 * into an annotated string with clickable spans and blue accent styling.
 */
@Composable
fun ClickableChangelogText(
    rawText: String,
    onUrlClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant

    val annotatedString = remember(rawText, linkColor, textColor) {
        buildAnnotatedString {
            // Regex to match markdown links: [label](url) OR raw URLs: https?://...
            val combinedRegex = Regex("""\[([^\]]+)\]\((https?://[^\s)]+)\)|(https?://[^\s)\]]+)""")
            var currentIndex = 0

            combinedRegex.findAll(rawText).forEach { matchResult ->
                val start = matchResult.range.first
                val end = matchResult.range.last + 1

                // Append leading plain text
                if (start > currentIndex) {
                    append(rawText.substring(currentIndex, start))
                }

                val markdownLabel = matchResult.groups[1]?.value
                val markdownUrl = matchResult.groups[2]?.value
                val rawUrl = matchResult.groups[3]?.value

                if (markdownLabel != null && markdownUrl != null) {
                    // Markdown Link [Label](URL)
                    val tagStart = length
                    append(markdownLabel)
                    val tagEnd = length

                    addStyle(
                        style = SpanStyle(
                            color = linkColor,
                            fontWeight = FontWeight.SemiBold,
                            textDecoration = TextDecoration.Underline
                        ),
                        start = tagStart,
                        end = tagEnd
                    )
                    addStringAnnotation(
                        tag = "URL",
                        annotation = markdownUrl,
                        start = tagStart,
                        end = tagEnd
                    )
                } else if (rawUrl != null) {
                    // Raw URL
                    val tagStart = length
                    append(rawUrl)
                    val tagEnd = length

                    addStyle(
                        style = SpanStyle(
                            color = linkColor,
                            fontWeight = FontWeight.Medium,
                            textDecoration = TextDecoration.Underline
                        ),
                        start = tagStart,
                        end = tagEnd
                    )
                    addStringAnnotation(
                        tag = "URL",
                        annotation = rawUrl,
                        start = tagStart,
                        end = tagEnd
                    )
                }

                currentIndex = end
            }

            // Append trailing plain text
            if (currentIndex < rawText.length) {
                append(rawText.substring(currentIndex))
            }
        }
    }

    ClickableText(
        text = annotatedString,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium.copy(
            color = textColor,
            lineHeight = 20.sp
        ),
        onClick = { offset ->
            annotatedString.getStringAnnotations(tag = "URL", start = offset, end = offset)
                .firstOrNull()?.let { annotation ->
                    onUrlClick(annotation.item)
                }
        }
    )
}
