package com.darkxvenom.airbeats.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class DiscoveredLanHost(
    val address: String,
    val hostName: String,
    val participants: Int = 1,
    val sessionId: String = ""
)

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
        context: android.content.Context? = null,
    ): List<DiscoveredLanHost> = withContext(Dispatchers.IO) {
        val discovered = ConcurrentHashMap<String, DiscoveredLanHost>()
        val prefix = localIp.substringBeforeLast(".")

        var clientMulticastLock: android.net.wifi.WifiManager.MulticastLock? = null
        runCatching {
            val wifiManager = context?.applicationContext?.getSystemService(android.content.Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            clientMulticastLock = wifiManager?.createMulticastLock("airbeats_lan_client_discovery")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }

        try {
            // Strategy 1: Ultra-fast UDP Broadcast Discovery
            runCatching {
                val udpSocket = DatagramSocket().apply {
                    broadcast = true
                    soTimeout = 500
                }
                val msg = "AIRBEATS_DISCOVER"
                val data = msg.toByteArray(Charsets.UTF_8)
                val broadcastTargets = listOfNotNull(
                    runCatching { InetAddress.getByName("255.255.255.255") }.getOrNull(),
                    runCatching { InetAddress.getByName("$prefix.255") }.getOrNull(),
                    runCatching { InetAddress.getByName("$prefix.1") }.getOrNull(),
                )
                for (target in broadcastTargets) {
                    runCatching {
                        udpSocket.send(DatagramPacket(data, data.size, target, port))
                    }
                }

                val buf = ByteArray(512)
                val startTime = System.currentTimeMillis()
                while (System.currentTimeMillis() - startTime < 500) {
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        udpSocket.receive(packet)
                        val response = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                        if (response.startsWith("AIRBEATS_HOST")) {
                            val parts = response.split(":")
                            val hostPort = parts.getOrNull(1)?.toIntOrNull() ?: port
                            val hostName = parts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "AirBeats Host"
                            val sessionId = parts.getOrNull(3) ?: ""
                            val participantCount = parts.getOrNull(4)?.toIntOrNull() ?: 1
                            val senderIp = packet.address.hostAddress ?: ""
                            if (senderIp.isNotBlank()) {
                                val addr = "$senderIp:$hostPort"
                                discovered[addr] = DiscoveredLanHost(
                                    address = addr,
                                    hostName = hostName,
                                    participants = participantCount,
                                    sessionId = sessionId
                                )
                            }
                        }
                    } catch (_: SocketTimeoutException) {
                        break
                    } catch (_: Exception) {}
                }
                udpSocket.close()
            }

            // If UDP discovered hosts, return immediately
            if (discovered.isNotEmpty()) {
                return@withContext discovered.values.toList()
            }

            // Strategy 2: Fast batched TCP Socket probe across subnet
            val probeClient = OkHttpClient.Builder()
                .callTimeout(1500, TimeUnit.MILLISECONDS)
                .connectTimeout(1200, TimeUnit.MILLISECONDS)
                .readTimeout(1200, TimeUnit.MILLISECONDS)
                .build()

            val candidates = (1..254).map { "$prefix.$it" }
            coroutineScope {
                candidates.chunked(64).forEach { batch ->
                    if (discovered.isNotEmpty()) return@forEach
                    batch.map { ip ->
                        async {
                            var socketReachable = false
                            try {
                                Socket().use { s ->
                                    s.connect(InetSocketAddress(ip, port), 400)
                                    socketReachable = true
                                }
                            } catch (_: Exception) {}

                            if (socketReachable) {
                                runCatching {
                                    val url = "http://$ip:$port/together/status"
                                    val request = Request.Builder().url(url).get().build()
                                    probeClient.newCall(request).execute().use { response ->
                                        if (response.isSuccessful) {
                                            val text = response.body?.string().orEmpty()
                                            if (text.contains("AirBeats") || text.contains("sessionId") || text.contains("status")) {
                                                val json = JSONObject(text)
                                                val hostName = json.optString("hostName").ifBlank { "AirBeats Host" }
                                                val pCount = json.optInt("participants", 1)
                                                val sId = json.optString("sessionId", "")
                                                val addr = "$ip:$port"
                                                discovered[addr] = DiscoveredLanHost(
                                                    address = addr,
                                                    hostName = hostName,
                                                    participants = pCount,
                                                    sessionId = sId
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }.awaitAll()
                }
            }
        } finally {
            runCatching {
                if (clientMulticastLock?.isHeld == true) {
                    clientMulticastLock?.release()
                }
            }
        }

        discovered.values.toList()
    }

    suspend fun scanLocalNetworkAddresses(
        localIp: String,
        port: Int = 8765,
        context: android.content.Context? = null,
    ): List<String> = scanLocalNetwork(localIp, port, context).map { it.address }
}
