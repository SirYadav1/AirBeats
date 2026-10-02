package com.darkxvenom.airbeats.utils

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import org.json.JSONArray
import org.json.JSONObject

data class UpdateInfo(
    val versionName: String,
    val releaseNotes: String = "",
    val releaseUrl: String = RemoteConfigManager.getLatestReleasePageUrl(),
    val apkDownloadUrl: String = ""
)

object Updater {
    private val client = HttpClient()
    var lastCheckTime = -1L
        private set

    private fun extractApkUrl(releaseObj: JSONObject, versionName: String, isNightly: Boolean): String {
        val assets = releaseObj.optJSONArray("assets")
        if (assets != null && assets.length() > 0) {
            var fallbackApkUrl = ""
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name", "")
                val url = asset.optString("browser_download_url", "")
                if (name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()) {
                    if (isNightly && name.contains("nightly", ignoreCase = true)) {
                        return url
                    }
                    if (!isNightly && (
                        name.contains("universal", ignoreCase = true) ||
                        name.contains("signed", ignoreCase = true) ||
                        name.contains("release", ignoreCase = true) ||
                        name.contains("airbeats", ignoreCase = true)
                    )) {
                        return url
                    }
                    if (fallbackApkUrl.isBlank()) {
                        fallbackApkUrl = url
                    }
                }
            }
            if (fallbackApkUrl.isNotBlank()) {
                return fallbackApkUrl
            }
        }

        // Direct download fallback URL based on build type & tag
        return RemoteConfigManager.getApkDownloadUrl(versionName, isNightly)
    }

    suspend fun getLatestUpdateInfo(isNightly: Boolean = com.darkxvenom.airbeats.BuildConfig.IS_NIGHTLY): Result<UpdateInfo> =
        runCatching {
            if (isNightly) {
                val response = client.get(RemoteConfigManager.getLatestReleaseApiUrl(isNightly = true)).bodyAsText()
                val jsonArray = JSONArray(response)
                var versionName = ""
                var releaseNotes = ""
                var releaseUrl = RemoteConfigManager.getReleasesPageUrl()
                var apkDownloadUrl = ""
                for (i in 0 until jsonArray.length()) {
                    val release = jsonArray.getJSONObject(i)
                    if (release.getBoolean("prerelease")) {
                        versionName = release.getString("tag_name").removePrefix("v").removeSuffix("-nightly").trim()
                        releaseNotes = release.optString("body", "").trim()
                        releaseUrl = release.optString("html_url", RemoteConfigManager.getReleasesPageUrl())
                        apkDownloadUrl = extractApkUrl(release, versionName, isNightly = true)
                        break
                    }
                }
                lastCheckTime = System.currentTimeMillis()
                UpdateInfo(versionName, releaseNotes, releaseUrl, apkDownloadUrl)
            } else {
                val response = client.get(RemoteConfigManager.getLatestReleaseApiUrl(isNightly = false)).bodyAsText()
                val json = JSONObject(response)
                val versionName = json.getString("tag_name").removePrefix("v").trim()
                val releaseNotes = json.optString("body", "").trim()
                val releaseUrl = json.optString("html_url", RemoteConfigManager.getLatestReleasePageUrl())
                val apkDownloadUrl = extractApkUrl(json, versionName, isNightly = false)
                lastCheckTime = System.currentTimeMillis()
                UpdateInfo(versionName, releaseNotes, releaseUrl, apkDownloadUrl)
            }
        }

    suspend fun getLatestVersionName(): Result<String> =
        getLatestUpdateInfo().map { it.versionName }
}
