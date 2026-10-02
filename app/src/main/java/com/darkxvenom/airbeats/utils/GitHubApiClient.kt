package com.darkxvenom.airbeats.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.IOException

@Serializable
data class GitHubContributor(
    val login: String,
    val avatar_url: String,
    val html_url: String,
    val contributions: Int
)

@Serializable
data class GitHubProfile(
    val login: String,
    val name: String? = null,
    val avatar_url: String,
    val html_url: String,
    val bio: String? = null,
    val company: String? = null,
    val location: String? = null,
    val public_repos: Int = 0,
    val followers: Int = 0,
    val following: Int = 0
)

@Serializable
data class GitHubRepo(
    val name: String,
    val html_url: String,
    val description: String? = null,
    val language: String? = null,
    val stargazers_count: Int = 0,
    val forks_count: Int = 0,
    val pushed_at: String? = null
)

@Serializable
data class GitHubEvent(
    val type: String,
    val created_at: String,
    val repo: EventRepo
) {
    @Serializable
    data class EventRepo(val name: String)
}

@Serializable
data class GitHubCommitWrap(
    val commit: CommitDetails,
    val html_url: String
) {
    @Serializable
    data class CommitDetails(
        val message: String,
        val author: CommitAuthor
    ) {
        @Serializable
        data class CommitAuthor(val date: String)
    }
}

class GitHubApiClient {

    private val client = OkHttpClient.Builder().build()
    private val json = Json { ignoreUnknownKeys = true }
    private val baseUrl = "https://api.github.com"

    suspend fun getContributors(owner: String, repo: String): List<GitHubContributor> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/repos/$owner/$repo/contributors?per_page=100")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: "[]"
                    json.decodeFromString(body)
                } else {
                    emptyList()
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error fetching contributors")
            emptyList()
        }
    }

    suspend fun getUserProfile(username: String): GitHubProfile? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/users/$username")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@withContext null
                    json.decodeFromString<GitHubProfile>(body)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error fetching user profile")
            null
        }
    }

    suspend fun getUserRepos(username: String): List<GitHubRepo> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/users/$username/repos?sort=updated&per_page=10")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: "[]"
                    json.decodeFromString(body)
                } else {
                    emptyList()
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error fetching repos")
            emptyList()
        }
    }

    suspend fun getUserEvents(username: String): List<GitHubEvent> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/users/$username/events/public?per_page=10")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: "[]"
                    json.decodeFromString(body)
                } else {
                    emptyList()
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error fetching events")
            emptyList()
        }
    }

    suspend fun getProfileReadme(username: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://raw.githubusercontent.com/$username/$username/main/README.md")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.string()
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error fetching README")
            null
        }
    }

    suspend fun getRepoCommits(owner: String, repo: String, author: String): List<GitHubCommitWrap> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/repos/$owner/$repo/commits?author=$author&per_page=10")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: "[]"
                    json.decodeFromString(body)
                } else {
                    emptyList()
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error fetching commits")
            emptyList()
        }
    }

    suspend fun getRecentReleases(owner: String = "d0x-dev", repo: String = "AirBeats", perPage: Int = 10): List<GitHubReleaseItem> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/repos/$owner/$repo/releases?per_page=$perPage")
            .header("Accept", "application/vnd.github.v3+json")
            .header("User-Agent", "AirBeats-App")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: "[]"
                    json.decodeFromString(body)
                } else {
                    emptyList()
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error fetching releases")
            emptyList()
        }
    }

    suspend fun getRecentCommits(owner: String = "d0x-dev", repo: String = "AirBeats", perPage: Int = 10): List<GitHubCommitSummary> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/repos/$owner/$repo/commits?per_page=$perPage")
            .header("Accept", "application/vnd.github.v3+json")
            .header("User-Agent", "AirBeats-App")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: "[]"
                    json.decodeFromString(body)
                } else {
                    emptyList()
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error fetching repo commits")
            emptyList()
        }
    }
}

@Serializable
data class GitHubReleaseItem(
    val tag_name: String = "",
    val name: String? = null,
    val body: String? = null,
    val published_at: String? = null,
    val prerelease: Boolean = false,
    val html_url: String = "",
    val assets: List<GitHubReleaseAsset> = emptyList()
)

@Serializable
data class GitHubReleaseAsset(
    val name: String = "",
    val browser_download_url: String = "",
    val size: Long = 0L
)

@Serializable
data class GitHubCommitSummary(
    val sha: String = "",
    val commit: CommitSummaryDetails,
    val html_url: String = "",
    val author: CommitUser? = null
) {
    @Serializable
    data class CommitSummaryDetails(
        val message: String = "",
        val author: CommitSummaryAuthor
    )
    @Serializable
    data class CommitSummaryAuthor(
        val name: String = "",
        val date: String = ""
    )
    @Serializable
    data class CommitUser(
        val login: String = "",
        val avatar_url: String = ""
    )
}

