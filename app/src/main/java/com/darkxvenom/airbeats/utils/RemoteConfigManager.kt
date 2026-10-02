package com.darkxvenom.airbeats.utils

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.darkxvenom.airbeats.innertube.models.YTItem
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

data class AppRemoteConfig(
    val playDomain: String,
    val statsBaseUrl: String,
    val listenTogetherUrl: String,
    val websiteUrl: String,
    val githubRepo: String,
    val updateApiUrl: String,
    val crashWebhookUrl: String,
    val telegramUrl: String,
)

object RemoteConfigManager {
    private const val PREFS_NAME = "airbeats_remote_config_cache"
    private const val KEY_PLAY_DOMAIN = "play_domain"
    private const val KEY_STATS_BASE_URL = "stats_base_url"
    private const val KEY_LISTEN_TOGETHER_URL = "listen_together_url"
    private const val KEY_WEBSITE_URL = "website_url"
    private const val KEY_GITHUB_REPO = "github_repo"
    private const val KEY_UPDATE_API_URL = "update_api_url"
    private const val KEY_CRASH_WEBHOOK_URL = "crash_webhook_url"
    private const val KEY_TELEGRAM_URL = "telegram_url"
    private const val KEY_APP_CONFIG_JSON = "app_config"
    private const val KEY_LAST_SYNC = "last_sync_timestamp"

    val DEFAULT_PLAY_DOMAIN: String = ""
    val DEFAULT_STATS_BASE_URL: String = ""
    val DEFAULT_LISTEN_TOGETHER_URL: String = ""
    val DEFAULT_WEBSITE_URL: String = ""
    val DEFAULT_GITHUB_REPO: String = "d0x-dev/AirBeats"
    val DEFAULT_UPDATE_API_URL: String = ""
    val DEFAULT_CRASH_WEBHOOK_URL: String = "https://airbeats-telegram-bot.darkxvenom44.workers.dev/crash"
    val DEFAULT_TELEGRAM_URL: String = "https://t.me/+vg3N1iU5x0ZmOTc9"

    @Volatile
    private var activeConfig: AppRemoteConfig = AppRemoteConfig(
        playDomain = normalizeUrl(DEFAULT_PLAY_DOMAIN),
        statsBaseUrl = normalizeUrl(DEFAULT_STATS_BASE_URL),
        listenTogetherUrl = normalizeUrl(DEFAULT_LISTEN_TOGETHER_URL),
        websiteUrl = normalizeUrl(DEFAULT_WEBSITE_URL),
        githubRepo = DEFAULT_GITHUB_REPO,
        updateApiUrl = DEFAULT_UPDATE_API_URL,
        crashWebhookUrl = normalizeWebhookUrl(DEFAULT_CRASH_WEBHOOK_URL),
        telegramUrl = normalizeUrl(DEFAULT_TELEGRAM_URL),
    )

    private var sharedPreferences: SharedPreferences? = null

    val playDomain: String
        get() = activeConfig.playDomain

    val statsBaseUrl: String
        get() = activeConfig.statsBaseUrl

    val listenTogetherUrl: String
        get() = activeConfig.listenTogetherUrl

    val websiteUrl: String
        get() = activeConfig.websiteUrl

    val githubRepo: String
        get() = activeConfig.githubRepo

    val updateApiUrl: String
        get() = activeConfig.updateApiUrl

    val crashWebhookUrl: String
        get() = activeConfig.crashWebhookUrl

    val telegramUrl: String
        get() = activeConfig.telegramUrl

    /**
     * Initializes the RemoteConfigManager:
     * 1. Synchronously reads the local cache so valid configuration is immediately available on app startup.
     * 2. Sets the innertube shareDomainProvider delegate.
     * 3. Asynchronously fetches the latest values from Firebase without blocking launch.
     */
    fun initialize(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sharedPreferences = prefs

        // 1. Read cached values synchronously
        loadFromCache(prefs)

        // 2. Wire domain provider to innertube
        YTItem.shareDomainProvider = { playDomain }

        // 3. Immediately fetch fresh config from Firebase
        refresh()
    }

    /**
     * Triggers a fresh background fetch of configuration from Firebase on app open / resume.
     */
    fun refresh() {
        CoroutineScope(Dispatchers.IO).launch {
            fetchFromFirebase()
        }
    }

    private fun loadFromCache(prefs: SharedPreferences) {
        val cachedPlayDomain = prefs.getString(KEY_PLAY_DOMAIN, null)
        val cachedStatsBaseUrl = prefs.getString(KEY_STATS_BASE_URL, null)
        val cachedListenTogetherUrl = prefs.getString(KEY_LISTEN_TOGETHER_URL, null)
        val cachedWebsiteUrl = prefs.getString(KEY_WEBSITE_URL, null)
        val cachedGithubRepo = prefs.getString(KEY_GITHUB_REPO, null)
        val cachedUpdateApiUrl = prefs.getString(KEY_UPDATE_API_URL, null)
        val cachedCrashWebhookUrl = prefs.getString(KEY_CRASH_WEBHOOK_URL, null)
        val cachedTelegramUrl = prefs.getString(KEY_TELEGRAM_URL, null)

        activeConfig = AppRemoteConfig(
            playDomain = normalizeUrl(cachedPlayDomain ?: DEFAULT_PLAY_DOMAIN),
            statsBaseUrl = normalizeUrl(cachedStatsBaseUrl ?: DEFAULT_STATS_BASE_URL),
            listenTogetherUrl = normalizeUrl(cachedListenTogetherUrl ?: DEFAULT_LISTEN_TOGETHER_URL),
            websiteUrl = normalizeUrl(cachedWebsiteUrl ?: DEFAULT_WEBSITE_URL),
            githubRepo = cachedGithubRepo ?: DEFAULT_GITHUB_REPO,
            updateApiUrl = cachedUpdateApiUrl ?: DEFAULT_UPDATE_API_URL,
            crashWebhookUrl = normalizeWebhookUrl(cachedCrashWebhookUrl ?: DEFAULT_CRASH_WEBHOOK_URL),
            telegramUrl = normalizeUrl(cachedTelegramUrl ?: DEFAULT_TELEGRAM_URL),
        )

        Timber.d("RemoteConfigManager: Loaded cached config -> playDomain=$playDomain, websiteUrl=$websiteUrl, githubRepo=$githubRepo, crashWebhookUrl=$crashWebhookUrl, telegramUrl=$telegramUrl")
    }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    private suspend fun fetchFromFirebase() {
        // 1. Fetch from Firebase Realtime Database (direct from google-services.json config)
        fetchFromRealtimeDatabase()

        // 2. Fetch from Firebase Remote Config (companion/fallback)
        fetchFromRemoteConfig()
    }

    private suspend fun fetchFromRealtimeDatabase() {
        try {
            val app = runCatching { FirebaseApp.getInstance() }.getOrNull() ?: return
            val options = app.options
            val baseUrl = (options.databaseUrl?.takeIf { it.isNotBlank() } ?: "https://${options.projectId}-default-rtdb.firebaseio.com").trimEnd('/')

            var token: String? = null
            try {
                val auth = FirebaseAuth.getInstance()
                val user = auth.currentUser ?: auth.signInAnonymously().await().user
                token = user?.getIdToken(false)?.await()?.token
            } catch (e: Exception) {
                Timber.d("RemoteConfigManager: Firebase anonymous auth skipped: ${e.message}")
            }

            val requestUrl = if (!token.isNullOrBlank()) {
                "$baseUrl/app_config.json?auth=$token"
            } else {
                "$baseUrl/app_config.json"
            }

            val request = Request.Builder()
                .url(requestUrl)
                .header("Cache-Control", "no-cache")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    if (body.isNotBlank() && !body.contains("\"error\"")) {
                        Timber.d("RemoteConfigManager: RTDB fetch succeeded: $body")
                        parseAndApplyJson(body)
                    } else {
                        Timber.d("RemoteConfigManager: RTDB returned error or empty body: $body")
                    }
                } else {
                    Timber.d("RemoteConfigManager: RTDB HTTP status code: ${response.code}")
                }
            }
        } catch (e: Exception) {
            Timber.d("RemoteConfigManager: RTDB fetch exception: ${e.message}")
        }
    }

    private suspend fun fetchFromRemoteConfig() {
        // Firebase Remote Config only contains client-safe values such as service URLs.
        try {
            val remoteConfig = FirebaseRemoteConfig.getInstance()
            val configSettings = FirebaseRemoteConfigSettings.Builder()
                .setMinimumFetchIntervalInSeconds(0) // Zero throttle so changes take effect immediately on open
                .setFetchTimeoutInSeconds(5)
                .build()

            remoteConfig.setConfigSettingsAsync(configSettings)
            remoteConfig.fetchAndActivate()
                .addOnSuccessListener {
                    parseAndUpdateConfig(remoteConfig)
                }
                .addOnFailureListener { e ->
                    Timber.d("RemoteConfigManager: Firebase Remote Config fetch skipped/failed: ${e.message}")
                }
        } catch (e: Exception) {
            Timber.d("RemoteConfigManager: Firebase Remote Config exception: ${e.message}")
        }
    }

    private fun parseAndUpdateConfig(remoteConfig: FirebaseRemoteConfig) {
        fun getRemoteStringOrNull(key: String): String? {
            val v = remoteConfig.getValue(key)
            return if (v.source == FirebaseRemoteConfig.VALUE_SOURCE_REMOTE) remoteConfig.getString(key).trim().ifBlank { null } else null
        }

        val jsonConfigString = getRemoteStringOrNull(KEY_APP_CONFIG_JSON)
        val individualPlayDomain = getRemoteStringOrNull(KEY_PLAY_DOMAIN)
        val individualStatsBaseUrl = getRemoteStringOrNull(KEY_STATS_BASE_URL)
        val individualListenTogetherUrl = getRemoteStringOrNull(KEY_LISTEN_TOGETHER_URL)
        val individualWebsiteUrl = getRemoteStringOrNull(KEY_WEBSITE_URL)
        val individualGithubRepo = getRemoteStringOrNull(KEY_GITHUB_REPO)
        val individualUpdateApiUrl = getRemoteStringOrNull(KEY_UPDATE_API_URL)
        val individualCrashWebhookUrl = getRemoteStringOrNull(KEY_CRASH_WEBHOOK_URL)
        val individualTelegramUrl = getRemoteStringOrNull(KEY_TELEGRAM_URL)

        if (jsonConfigString != null || individualPlayDomain != null || individualStatsBaseUrl != null ||
            individualListenTogetherUrl != null ||
            individualWebsiteUrl != null || individualGithubRepo != null || individualUpdateApiUrl != null ||
            individualCrashWebhookUrl != null || individualTelegramUrl != null) {
            parseAndApplyJson(
                jsonString = jsonConfigString,
                overridePlayDomain = individualPlayDomain,
                overrideStatsBaseUrl = individualStatsBaseUrl,
                overrideListenTogetherUrl = individualListenTogetherUrl,
                overrideWebsiteUrl = individualWebsiteUrl,
                overrideGithubRepo = individualGithubRepo,
                overrideUpdateApiUrl = individualUpdateApiUrl,
                overrideCrashWebhookUrl = individualCrashWebhookUrl,
                overrideTelegramUrl = individualTelegramUrl,
            )
        }
    }

    private fun parseAndApplyJson(
        jsonString: String?,
        overridePlayDomain: String? = null,
        overrideStatsBaseUrl: String? = null,
        overrideListenTogetherUrl: String? = null,
        overrideWebsiteUrl: String? = null,
        overrideGithubRepo: String? = null,
        overrideUpdateApiUrl: String? = null,
        overrideCrashWebhookUrl: String? = null,
        overrideTelegramUrl: String? = null,
    ) {
        var remotePlayDomain: String? = overridePlayDomain
        var remoteStatsBaseUrl: String? = overrideStatsBaseUrl
        var remoteListenTogetherUrl: String? = overrideListenTogetherUrl
        var remoteWebsiteUrl: String? = overrideWebsiteUrl
        var remoteGithubRepo: String? = overrideGithubRepo
        var remoteUpdateApiUrl: String? = overrideUpdateApiUrl
        var remoteCrashWebhookUrl: String? = overrideCrashWebhookUrl
        var remoteTelegramUrl: String? = overrideTelegramUrl

        if (!jsonString.isNullOrBlank() && jsonString.startsWith("{")) {
            try {
                val rootJson = JSONObject(jsonString)
                val json = if (rootJson.has("app_config") && rootJson.optJSONObject("app_config") != null) {
                    rootJson.getJSONObject("app_config")
                } else {
                    rootJson
                }

                fun findString(vararg keys: String): String? {
                    for (k in keys) {
                        if (json.has(k)) {
                            val v = json.optString(k).trim()
                            if (v.isNotBlank() && v != "null") return v
                        }
                    }
                    return null
                }

                if (remotePlayDomain == null) remotePlayDomain = findString("play_domain", "playDomain", "play_url", "playUrl")
                if (remoteStatsBaseUrl == null) remoteStatsBaseUrl = findString("stats_worker_url", "stats_base_url", "statsBaseUrl", "stats_url", "statsUrl")
                if (remoteListenTogetherUrl == null) remoteListenTogetherUrl = findString("listen_together_url", "listenTogetherUrl")
                if (remoteWebsiteUrl == null) remoteWebsiteUrl = findString("website_url", "websiteUrl", "website", "official_website", "officialWebsite")
                if (remoteGithubRepo == null) remoteGithubRepo = findString("github_repo", "githubRepo", "repo")
                if (remoteUpdateApiUrl == null) remoteUpdateApiUrl = findString("update_api_url", "updateApiUrl")
                if (remoteCrashWebhookUrl == null) remoteCrashWebhookUrl = findString("crash_webhook_url", "crashWebhookUrl", "crash_url", "crashUrl")
                if (remoteTelegramUrl == null) remoteTelegramUrl = findString("telegram_url", "telegramUrl", "telegram_link", "telegramLink", "telegram", "telegram_community", "telegramCommunity")
            } catch (e: Exception) {
                Timber.w(e, "RemoteConfigManager: Failed to parse JSON configuration")
            }
        }

        val newPlayDomain = if (!remotePlayDomain.isNullOrBlank()) normalizeUrl(remotePlayDomain) else activeConfig.playDomain
        val newStatsBaseUrl = if (!remoteStatsBaseUrl.isNullOrBlank()) normalizeUrl(remoteStatsBaseUrl) else activeConfig.statsBaseUrl
        val newListenTogetherUrl = if (!remoteListenTogetherUrl.isNullOrBlank()) normalizeUrl(remoteListenTogetherUrl) else activeConfig.listenTogetherUrl
        val newWebsiteUrl = if (!remoteWebsiteUrl.isNullOrBlank()) normalizeUrl(remoteWebsiteUrl) else activeConfig.websiteUrl
        val newGithubRepo = if (!remoteGithubRepo.isNullOrBlank()) remoteGithubRepo.trim().removePrefix("https://github.com/").trimEnd('/') else activeConfig.githubRepo
        val newUpdateApiUrl = if (!remoteUpdateApiUrl.isNullOrBlank()) normalizeUrl(remoteUpdateApiUrl) else activeConfig.updateApiUrl
        val newCrashWebhookUrl = if (!remoteCrashWebhookUrl.isNullOrBlank()) normalizeWebhookUrl(remoteCrashWebhookUrl) else activeConfig.crashWebhookUrl
        val newTelegramUrl = if (!remoteTelegramUrl.isNullOrBlank()) normalizeUrl(remoteTelegramUrl) else activeConfig.telegramUrl

        val newConfig = AppRemoteConfig(
            playDomain = newPlayDomain,
            statsBaseUrl = newStatsBaseUrl,
            listenTogetherUrl = newListenTogetherUrl,
            websiteUrl = newWebsiteUrl,
            githubRepo = newGithubRepo,
            updateApiUrl = newUpdateApiUrl,
            crashWebhookUrl = newCrashWebhookUrl,
            telegramUrl = newTelegramUrl,
        )

        // Compare against activeConfig
        if (newConfig != activeConfig) {
            Timber.i("RemoteConfigManager: Detected updated remote configuration! crashWebhookUrl=${newConfig.crashWebhookUrl}, websiteUrl=${newConfig.websiteUrl}, telegramUrl=${newConfig.telegramUrl}")
            activeConfig = newConfig
            sharedPreferences?.edit()?.apply {
                putString(KEY_PLAY_DOMAIN, newConfig.playDomain)
                putString(KEY_STATS_BASE_URL, newConfig.statsBaseUrl)
                putString(KEY_LISTEN_TOGETHER_URL, newConfig.listenTogetherUrl)
                putString(KEY_WEBSITE_URL, newConfig.websiteUrl)
                putString(KEY_GITHUB_REPO, newConfig.githubRepo)
                putString(KEY_UPDATE_API_URL, newConfig.updateApiUrl)
                putString(KEY_CRASH_WEBHOOK_URL, newConfig.crashWebhookUrl)
                putString(KEY_TELEGRAM_URL, newConfig.telegramUrl)
                putLong(KEY_LAST_SYNC, System.currentTimeMillis())
                apply()
            }
        } else {
            Timber.d("RemoteConfigManager: Remote config is identical to cached values. No update needed.")
        }
    }

    private fun normalizeWebhookUrl(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return ""
        return if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
            "https://$trimmed"
        } else {
            trimmed
        }
    }

    private fun normalizeUrl(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        if (trimmed.isBlank()) return ""
        return if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
            "https://$trimmed"
        } else {
            trimmed
        }
    }

    // Helper functions for share URLs
    fun getShareUrl(path: String, id: String): String = "$playDomain/$path?id=$id"
    fun getSongShareUrl(id: String): String = "$playDomain/song?id=$id"
    fun getPlaylistShareUrl(id: String): String = "$playDomain/playlist?id=$id"
    fun getAlbumShareUrl(id: String): String = "$playDomain/album?id=$id"
    fun getArtistShareUrl(id: String): String = "$playDomain/artist?id=$id"

    // Helper functions for releases and updates
    fun getLatestReleaseApiUrl(isNightly: Boolean): String {
        val customUrl = updateApiUrl.trim()
        if (customUrl.isNotBlank()) {
            return if (isNightly) {
                if (customUrl.endsWith("/latest")) customUrl.removeSuffix("/latest") else customUrl
            } else {
                if (customUrl.endsWith("/latest")) customUrl else "$customUrl/latest"
            }
        }
        val repo = githubRepo.ifBlank { "d0x-dev/AirBeats" }.trim()
        if (repo.isBlank()) return ""
        return if (isNightly) "https://api.github.com/repos/$repo/releases" else "https://api.github.com/repos/$repo/releases/latest"
    }

    fun getReleasesPageUrl(): String {
        val repo = githubRepo.ifBlank { "d0x-dev/AirBeats" }.trim()
        return if (repo.isNotBlank()) "https://github.com/$repo/releases" else "https://github.com/d0x-dev/AirBeats/releases"
    }

    fun getLatestReleasePageUrl(): String {
        val repo = githubRepo.ifBlank { "d0x-dev/AirBeats" }.trim()
        return if (repo.isNotBlank()) "https://github.com/$repo/releases/latest" else "https://github.com/d0x-dev/AirBeats/releases/latest"
    }

    fun getApkDownloadUrl(versionName: String, isNightly: Boolean): String {
        val repo = githubRepo.ifBlank { "d0x-dev/AirBeats" }.trim()
        if (repo.isBlank()) return ""
        return if (isNightly) {
            "https://github.com/$repo/releases/download/v${versionName}-nightly/Airbeats-v${versionName}-Nightly.apk"
        } else {
            "https://github.com/$repo/releases/download/v$versionName/AirBeats_v${versionName}_signed.apk"
        }
    }

    // Helper functions for deep link matching (matches dynamically against active Firebase configuration)
    fun isMatchingPlayDomain(host: String?): Boolean {
        if (host.isNullOrBlank() || playDomain.isBlank()) return false
        val currentHost = runCatching { Uri.parse(playDomain).host }.getOrNull()
        return !currentHost.isNullOrBlank() && host.equals(currentHost, ignoreCase = true)
    }

    fun isMatchingListenTogetherDomain(host: String?): Boolean {
        if (host.isNullOrBlank() || listenTogetherUrl.isBlank()) return false
        val currentHost = runCatching { Uri.parse(listenTogetherUrl).host }.getOrNull()
        return !currentHost.isNullOrBlank() && host.equals(currentHost, ignoreCase = true)
    }
}
