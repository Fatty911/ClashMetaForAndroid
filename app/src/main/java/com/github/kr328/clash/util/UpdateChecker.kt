package com.github.kr328.clash.util

import android.content.Context
import android.content.pm.PackageManager
import com.github.kr328.clash.BuildConfig
import com.github.kr328.clash.common.log.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseInfo(
    val versionName: String,
    val versionCode: Long,
    val downloadUrl: String,
    val releaseNotes: String,
    val publishedAt: String
)

object UpdateChecker {
    private val GITHUB_API_URL: String
        get() = "https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest"
    private const val TAG = "UpdateChecker"

    suspend fun checkForUpdate(context: Context): Result<ReleaseInfo?> = withContext(Dispatchers.IO) {
        try {
            val currentVersionCode = getCurrentVersionCode(context)
            
            val connection = URL(GITHUB_API_URL).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext Result.failure(Exception("HTTP error: $responseCode"))
            }
            
            val response = connection.inputStream.bufferedReader().use(BufferedReader::readText)
            val json = JSONObject(response)
            
            val tagName = json.getString("tag_name")
            val versionName = tagName.removePrefix("v")
            val publishedAt = json.getString("published_at")
            val releaseNotes = json.optString("body", "")
            
            val assets = json.getJSONArray("assets")
            var downloadUrl = ""

            // prefer universal apk, then meta, then any apk
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.getString("name")
                if (name.endsWith(".apk") && name.contains("universal", ignoreCase = true)) {
                    downloadUrl = asset.getString("browser_download_url")
                    break
                }
            }
            if (downloadUrl.isEmpty()) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.getString("name")
                    if (name.endsWith(".apk") && name.contains("meta", ignoreCase = true)) {
                        downloadUrl = asset.getString("browser_download_url")
                        break
                    }
                }
            }
            if (downloadUrl.isEmpty()) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.getString("name")
                    if (name.endsWith(".apk")) {
                        downloadUrl = asset.getString("browser_download_url")
                        break
                    }
                }
            }

            val versionCode = parseVersionCode(versionName)

            Log.d("$TAG: repo=${BuildConfig.GITHUB_REPO} current=$currentVersionCode latest=$versionCode")
            
            if (versionCode > currentVersionCode) {
                Result.success(ReleaseInfo(
                    versionName = versionName,
                    versionCode = versionCode,
                    downloadUrl = downloadUrl,
                    releaseNotes = releaseNotes,
                    publishedAt = publishedAt
                ))
            } else {
                Result.success(null)
            }
        } catch (e: Exception) {
            Log.w("$TAG: Failed to check for update: ${e.message}")
            Result.failure(e)
        }
    }

    private fun getCurrentVersionCode(context: Context): Long {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
        } catch (e: PackageManager.NameNotFoundException) {
            0L
        }
    }

    private fun parseVersionCode(versionName: String): Long {
        return try {
            val parts = versionName.split(".")
            val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val patch = parts.getOrNull(2)?.split("-")?.firstOrNull()?.toIntOrNull() ?: 0
            (major * 100000 + minor * 1000 + patch).toLong()
        } catch (e: Exception) {
            0L
        }
    }
}
