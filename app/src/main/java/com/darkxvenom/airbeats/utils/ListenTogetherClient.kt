package com.darkxvenom.airbeats.utils

import com.darkxvenom.airbeats.models.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object ListenTogetherClient {
    val BACKEND_URL: String
        get() = RemoteConfigManager.listenTogetherUrl

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val httpClient =
        OkHttpClient
            .Builder()
            .callTimeout(4, TimeUnit.SECONDS)
            .build()

    suspend fun createSession(
        displayName: String,
        state: ListenTogetherPlaybackState,
    ): ListenTogetherSession =
        post(
            "$BACKEND_URL/sessions",
            JSONObject()
                .put("name", displayName)
                .put("state", state.toJson())
        ) { body ->
            ListenTogetherSession.fromJson(body)
        }

    suspend fun joinSession(
        code: String,
        displayName: String,
    ): ListenTogetherSession =
        post(
            "$BACKEND_URL/sessions/${code.trim().uppercase()}/join",
            JSONObject().put("name", displayName)
        ) { body ->
            ListenTogetherSession.fromJson(body)
        }

    suspend fun getSession(code: String): ListenTogetherSession =
        get("$BACKEND_URL/sessions/${code.trim().uppercase()}") { body ->
            ListenTogetherSession.fromJson(body)
        }

    suspend fun updateState(
        code: String,
        participantId: String,
        state: ListenTogetherPlaybackState,
    ): ListenTogetherSession =
        post(
            "$BACKEND_URL/sessions/${code.trim().uppercase()}/state",
            JSONObject()
                .put("participantId", participantId)
                .put("state", state.toJson())
        ) { body ->
            ListenTogetherSession.fromJson(body)
        }

    suspend fun leaveSession(
        code: String,
        participantId: String,
    ) {
        post(
            "$BACKEND_URL/sessions/${code.trim().uppercase()}/leave",
            JSONObject().put("participantId", participantId)
        ) {}
    }

    private suspend fun <T> get(
        url: String,
        parser: (JSONObject) -> T,
    ): T =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw IllegalStateException(errorMessage(text, response.code))
                parser(JSONObject(text))
            }
        }

    private suspend fun <T> post(
        url: String,
        body: JSONObject,
        parser: (JSONObject) -> T,
    ): T =
        withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url(url)
                    .post(body.toString().toRequestBody(jsonMediaType))
                    .build()
            httpClient.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw IllegalStateException(errorMessage(text, response.code))
                parser(JSONObject(text))
            }
        }

    private fun errorMessage(
        text: String,
        code: Int,
    ): String =
        runCatching {
            JSONObject(text).optString("error")
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Listen Together request failed ($code)"
}

data class ListenTogetherSession(
    val code: String,
    val participantId: String,
    val joinUrl: String,
    val participants: Int,
    val participantList: List<ListenTogetherParticipant>,
    val hostName: String,
    val controllerId: String,
    val controllerName: String,
    val stateVersion: Long,
    val serverNow: Long,
    val state: ListenTogetherPlaybackState?,
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("code", code)
            .put("participantId", participantId)
            .put("joinUrl", joinUrl)
            .put("participants", participants)
            .put("participantList", JSONArray().apply { participantList.forEach { put(it.toJson()) } })
            .put("hostName", hostName)
            .put("controllerId", controllerId)
            .put("controllerName", controllerName)
            .put("stateVersion", stateVersion)
            .put("serverNow", serverNow)
            .put("state", state?.toJson())

    companion object {
        fun fromJson(json: JSONObject): ListenTogetherSession =
            ListenTogetherSession(
                code = json.optString("code"),
                participantId = json.optString("participantId"),
                joinUrl = json.optString("joinUrl"),
                participants = json.optInt("participants", 1),
                participantList =
                    json.optJSONArray("participantList")?.let { array ->
                        (0 until array.length()).mapNotNull { index ->
                            array.optJSONObject(index)?.let(ListenTogetherParticipant::fromJson)
                        }
                    }.orEmpty(),
                hostName = json.optString("hostName").ifBlank { "AirBeats listener" },
                controllerId = json.optString("controllerId"),
                controllerName = json.optString("controllerName").ifBlank { "AirBeats listener" },
                stateVersion = json.optLong("stateVersion", 0L),
                serverNow = json.optLong("serverNow", System.currentTimeMillis()),
                state = json.optJSONObject("state")?.let(ListenTogetherPlaybackState::fromJson),
            )
    }
}

data class ListenTogetherParticipant(
    val id: String,
    val name: String,
    val isHost: Boolean,
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("id", id)
            .put("name", name)
            .put("isHost", isHost)

    companion object {
        fun fromJson(json: JSONObject) =
            ListenTogetherParticipant(
                id = json.optString("id"),
                name = json.optString("name").ifBlank { "AirBeats listener" },
                isHost = json.optBoolean("isHost"),
            )
    }
}

data class ListenTogetherPlaybackState(
    val songId: String,
    val title: String,
    val artists: List<String>,
    val thumbnailUrl: String?,
    val positionMs: Long,
    val isPlaying: Boolean,
    val durationMs: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun toMediaMetadata() =
        MediaMetadata(
            id = songId,
            title = title,
            artists = artists.map { MediaMetadata.Artist(id = null, name = it) },
            duration = if (durationMs > 0) (durationMs / 1000).toInt() else -1,
            thumbnailUrl = thumbnailUrl,
        )

    fun toJson() =
        JSONObject()
            .put("songId", songId)
            .put("title", title)
            .put("artists", JSONArray(artists))
            .put("thumbnailUrl", thumbnailUrl)
            .put("positionMs", positionMs)
            .put("isPlaying", isPlaying)
            .put("durationMs", durationMs)
            .put("updatedAt", updatedAt)

    companion object {
        fun fromJson(json: JSONObject): ListenTogetherPlaybackState =
            ListenTogetherPlaybackState(
                songId = json.optString("songId"),
                title = json.optString("title"),
                artists =
                    json.optJSONArray("artists")?.let { array ->
                        (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
                    }.orEmpty(),
                thumbnailUrl = json.optString("thumbnailUrl").takeIf { it.isNotBlank() },
                positionMs = json.optLong("positionMs"),
                isPlaying = json.optBoolean("isPlaying"),
                durationMs = json.optLong("durationMs", 0L),
                updatedAt = json.optLong("updatedAt"),
            )
    }
}
