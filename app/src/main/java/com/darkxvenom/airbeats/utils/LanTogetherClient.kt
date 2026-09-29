package com.darkxvenom.airbeats.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object LanTogetherClient {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val httpClient = OkHttpClient.Builder()
        .callTimeout(3, TimeUnit.SECONDS)
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    fun normalizeHostAddress(raw: String, defaultPort: Int = 8765): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("airbeats://together", ignoreCase = true)) {
            val uri = android.net.Uri.parse(trimmed)
            val host = uri.getQueryParameter("host")
            val port = uri.getQueryParameter("port") ?: defaultPort.toString()
            if (!host.isNullOrBlank()) return "$host:$port"
        }
        val clean = trimmed.removePrefix("http://")
            .removePrefix("https://")
            .removePrefix("ws://")
            .removePrefix("wss://")
            .substringBefore("/")
        return if (clean.contains(":")) {
            clean
        } else {
            "$clean:$defaultPort"
        }
    }

    suspend fun joinSession(
        hostAddress: String,
        displayName: String,
    ): ListenTogetherSession = withContext(Dispatchers.IO) {
        val normalized = normalizeHostAddress(hostAddress)
        val url = "http://$normalized/together/join"
        val body = JSONObject().put("name", displayName).toString().toRequestBody(jsonMediaType)
        val request = Request.Builder().url(url).post(body).build()
        httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Failed to join LAN host: HTTP ${response.code}")
            }
            ListenTogetherSession.fromJson(JSONObject(text))
        }
    }

    suspend fun getSession(
        hostAddress: String,
        participantId: String,
    ): ListenTogetherSession = withContext(Dispatchers.IO) {
        val normalized = normalizeHostAddress(hostAddress)
        val url = "http://$normalized/together/state?participantId=$participantId"
        val request = Request.Builder().url(url).get().build()
        httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("LAN host communication error: HTTP ${response.code}")
            }
            ListenTogetherSession.fromJson(JSONObject(text))
        }
    }

    suspend fun updateState(
        hostAddress: String,
        participantId: String,
        state: ListenTogetherPlaybackState,
    ): ListenTogetherSession = withContext(Dispatchers.IO) {
        val normalized = normalizeHostAddress(hostAddress)
        val url = "http://$normalized/together/state"
        val body = JSONObject()
            .put("participantId", participantId)
            .put("state", state.toJson())
            .toString()
            .toRequestBody(jsonMediaType)
        val request = Request.Builder().url(url).post(body).build()
        httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Failed to update LAN host state: HTTP ${response.code}")
            }
            ListenTogetherSession.fromJson(JSONObject(text))
        }
    }

    suspend fun leaveSession(
        hostAddress: String,
        participantId: String,
    ) = withContext(Dispatchers.IO) {
        runCatching {
            val normalized = normalizeHostAddress(hostAddress)
            val url = "http://$normalized/together/leave"
            val body = JSONObject()
                .put("participantId", participantId)
                .toString()
                .toRequestBody(jsonMediaType)
            val request = Request.Builder().url(url).post(body).build()
            httpClient.newCall(request).execute().close()
        }
    }

    suspend fun pingHost(hostAddress: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val normalized = normalizeHostAddress(hostAddress)
            val url = "http://$normalized/together/status"
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        }.getOrDefault(false)
    }

    suspend fun scanLocalNetwork(
        localIp: String,
        port: Int = 8765,
    ): List<String> = withContext(Dispatchers.IO) {
        val prefix = localIp.substringBeforeLast(".")
        val fastClient = OkHttpClient.Builder()
            .callTimeout(500, TimeUnit.MILLISECONDS)
            .connectTimeout(400, TimeUnit.MILLISECONDS)
            .readTimeout(400, TimeUnit.MILLISECONDS)
            .build()

        val candidates = (1..254).map { "$prefix.$it" }.filter { it != localIp }
        val deferredList = candidates.map { ip ->
            async {
                runCatching {
                    val url = "http://$ip:$port/together/status"
                    val request = Request.Builder().url(url).get().build()
                    fastClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val text = response.body?.string().orEmpty()
                            if (text.contains("\"AirBeats\"") || text.contains("\"sessionId\"")) {
                                "$ip:$port"
                            } else null
                        } else null
                    }
                }.getOrNull()
            }
        }
        deferredList.awaitAll().filterNotNull()
    }
}
