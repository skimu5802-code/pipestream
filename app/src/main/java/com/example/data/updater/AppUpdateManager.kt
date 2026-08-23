package com.example.data.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class GitHubRelease(
    val tagName: String,
    val versionName: String,
    val changelog: String,
    val downloadUrl: String,
    val releaseName: String
)

sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    data class UpdateAvailable(val release: GitHubRelease) : UpdateState()
    data class Downloading(val progress: Float, val downloadedMb: String, val totalMb: String) : UpdateState()
    data class ReadyToInstall(val apkFile: File) : UpdateState()
    object UpToDate : UpdateState()
    data class Error(val message: String) : UpdateState()
}

class AppUpdateManager(private val context: Context) {

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState = _updateState.asStateFlow()

    private val repoOwner = "skimu5802-code"
    private val repoName = "pipestream"

    suspend fun checkForUpdates(isManualCheck: Boolean = false) {
        if (isManualCheck) _updateState.value = UpdateState.Checking

        withContext(Dispatchers.IO) {
            try {
                val apiUrl = "https://api.github.com/repos/$repoOwner/$repoName/releases/latest"
                val connection = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/vnd.github.v3+json")
                    setRequestProperty("User-Agent", "PipeStream-App")
                    connectTimeout = 10000
                    readTimeout = 10000
                }

                if (connection.responseCode == 200) {
                    val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(responseText)
                    val tagName = json.getString("tag_name")
                    val releaseName = json.optString("name", tagName)
                    val changelog = json.optString("body", "No changelog provided.")

                    val assets = json.optJSONArray("assets")
                    var downloadUrl = ""
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            val name = asset.getString("name")
                            if (name.endsWith(".apk", ignoreCase = true)) {
                                downloadUrl = asset.getString("browser_download_url")
                                break
                            }
                        }
                    }

                    val currentVersion = getAppVersionName()
                    if (isNewerVersion(currentVersion, tagName) && downloadUrl.isNotEmpty()) {
                        _updateState.value = UpdateState.UpdateAvailable(
                            GitHubRelease(
                                tagName = tagName,
                                versionName = tagName.removePrefix("v"),
                                changelog = changelog,
                                downloadUrl = downloadUrl,
                                releaseName = releaseName
                            )
                        )
                    } else if (isManualCheck) {
                        _updateState.value = UpdateState.UpToDate
                    }
                } else if (isManualCheck) {
                    _updateState.value = UpdateState.Error("Failed to check updates (Code: ${connection.responseCode})")
                }
            } catch (e: Exception) {
                if (isManualCheck) {
                    _updateState.value = UpdateState.Error(e.localizedMessage ?: "Unknown network error")
                }
            }
        }
    }

    suspend fun downloadAndInstallApk(release: GitHubRelease) {
        withContext(Dispatchers.IO) {
            try {
                val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
                val apkFile = File(updateDir, "pipestream-${release.tagName}.apk")

                if (apkFile.exists()) apkFile.delete()

                val connection = (URL(release.downloadUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 15000
                    readTimeout = 15000
                }

                val totalLength = connection.contentLengthLong
                var downloadedBytes: Long = 0

                connection.inputStream.use { input ->
                    FileOutputStream(apkFile).use { output ->
                        val buffer = ByteArray(8 * 1024)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead

                            val progress = if (totalLength > 0) downloadedBytes.toFloat() / totalLength.toFloat() else 0f
                            val dlMb = String.format("%.1f", downloadedBytes / (1024f * 1024f))
                            val totMb = if (totalLength > 0) String.format("%.1f", totalLength / (1024f * 1024f)) else "??"

                            _updateState.value = UpdateState.Downloading(progress, dlMb, totMb)
                        }
                    }
                }

                _updateState.value = UpdateState.ReadyToInstall(apkFile)
                installApk(apkFile)
            } catch (e: Exception) {
                _updateState.value = UpdateState.Error("Download failed: ${e.localizedMessage}")
            }
        }
    }

    fun installApk(apkFile: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    return
                }
            }

            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
        } catch (e: Exception) {
            _updateState.value = UpdateState.Error("Install failed: ${e.localizedMessage}")
        }
    }

    fun dismissUpdate() {
        _updateState.value = UpdateState.Idle
    }

    private fun getAppVersionName(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
    }

    private fun isNewerVersion(current: String, latest: String): Boolean {
        val currParts = current.removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }
        val latestParts = latest.removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }

        val length = maxOf(currParts.size, latestParts.size)
        for (i in 0 until length) {
            val c = currParts.getOrElse(i) { 0 }
            val l = latestParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }
}
