package com.darkxvenom.airbeats.utils

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class GlobalStatsUser(val id: String, val user: String? = null, val name: String, val profileUrl: String?, val email: String? = null, val totalListenMs: Long, val weeklyListenMs: Long, val lastUpdatedAt: Long, val rank: Int = 0, val fcmToken: String? = null)
data class GlobalStatsBoard(val users: List<GlobalStatsUser> = emptyList(), val updatedAt: Long = 0L, val userNumber: String? = null)
data class LocalStatsUpload(val userId: String, val user: String? = null, val name: String, val profileUrl: String?, val email: String? = null, val totalListenMs: Long, val weeklyListenMs: Long, val fcmToken: String? = null)

/** Firebase-authenticated stats client. No stats credential is shipped with the app. */
class AirBeatsStatsCloudClient {
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).writeTimeout(10, TimeUnit.SECONDS).build()

    suspend fun readBoard(fileName: String = GLOBAL_STATS_FILE): Result<GlobalStatsBoard> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url("${workerUrl()}/leaderboard?limit=100&_t=${System.currentTimeMillis()}").header("Cache-Control", "no-cache").get().build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) error(parseError(text, response.code))
                parseBoard(JSONObject(text))
            }
        }
    }

    suspend fun uploadDaily(upload: LocalStatsUpload): Result<GlobalStatsBoard> = withContext(Dispatchers.IO) {
        runCatching {
            val auth = FirebaseAuth.getInstance()
            val user = auth.currentUser ?: auth.signInAnonymously().await().user ?: error("Unable to establish a Firebase identity")
            val token = user.getIdToken(true).await().token ?: error("Unable to obtain a Firebase identity token")
            val payload = JSONObject()
                .put("userId", upload.userId)
                .put("name", upload.name.ifBlank { "AirBeats User" })
                .put("profileUrl", upload.profileUrl ?: JSONObject.NULL)
                .put("totalListenMs", upload.totalListenMs.coerceAtLeast(0L))
                .put("weeklyListenMs", upload.weeklyListenMs.coerceAtLeast(0L))
            if (!upload.user.isNullOrBlank()) {
                payload.put("user", upload.user)
            }
            if (!upload.email.isNullOrBlank()) {
                payload.put("email", upload.email)
            }
            if (!upload.fcmToken.isNullOrBlank()) {
                payload.put("fcmToken", upload.fcmToken)
            }
            val request = Request.Builder().url("${workerUrl()}/stats").header("Authorization", "Bearer $token").post(payload.toString().toRequestBody(JSON_MEDIA_TYPE)).build()
            var returnedUserNumber: String? = null
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful && response.code != 429) error(parseError(text, response.code))
                if (text.isNotBlank()) {
                    runCatching {
                        val resJson = JSONObject(text)
                        returnedUserNumber = resJson.optString("user").takeIf(String::isNotBlank)
                    }
                }
            }
            val board = readBoard().getOrThrow()
            if (!returnedUserNumber.isNullOrBlank()) {
                board.copy(userNumber = returnedUserNumber)
            } else {
                board
            }
        }
    }

    private fun workerUrl(): String = RemoteConfigManager.statsBaseUrl.trimEnd('/').also { require(it.startsWith("https://")) { "Stats service is not configured yet" } }

    private fun parseBoard(json: JSONObject): GlobalStatsBoard {
        val usersJson = json.optJSONArray("users") ?: JSONArray()
        val users = List(usersJson.length()) { usersJson.optJSONObject(it) }.mapNotNull { user -> user?.let {
            val id = it.optString("id")
            val userNum = it.optString("user").takeIf(String::isNotBlank)
            if (id.isBlank()) null else GlobalStatsUser(id = id, user = userNum, name = it.optString("name", "AirBeats User"), profileUrl = it.optString("profileUrl").takeIf(String::isNotBlank), totalListenMs = it.optLong("totalListenMs"), weeklyListenMs = it.optLong("weeklyListenMs"), lastUpdatedAt = it.optLong("lastUpdatedAt"), rank = it.optInt("rank"))
        }}.take(100)
        return GlobalStatsBoard(users, json.optLong("updatedAt"))
    }

    private fun parseError(text: String, code: Int): String = runCatching { JSONObject(text).optString("error").ifBlank { "HTTP $code" } }.getOrDefault("HTTP $code")

    private companion object {
        const val GLOBAL_STATS_FILE = "leaderboard"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
