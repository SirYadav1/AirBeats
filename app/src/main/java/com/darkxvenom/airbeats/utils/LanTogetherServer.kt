package com.darkxvenom.airbeats.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class LanTogetherServer(
    val hostIp: String,
    val port: Int,
    val sessionId: String = UUID.randomUUID().toString().take(8).uppercase(),
    val hostDisplayName: String,
) : NanoHTTPD(port) {

    private val participants = ConcurrentHashMap<String, ListenTogetherParticipant>()

    @Volatile
    var playbackState: ListenTogetherPlaybackState? = null

    @Volatile
    var stateVersion: Long = 1L

    @Volatile
    var controllerId: String = "host"

    init {
        participants["host"] = ListenTogetherParticipant(
            id = "host",
            name = hostDisplayName,
            isHost = true
        )
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        if (method == Method.OPTIONS) {
            return newFixedLengthResponse(Response.Status.OK, "text/plain", "")
                .apply { applyCors(this) }
        }

        return try {
            when {
                uri == "/together/status" || uri == "/together/ping" -> {
                    val json = JSONObject().apply {
                        put("status", "ok")
                        put("app", "AirBeats")
                        put("sessionId", sessionId)
                        put("hostName", hostDisplayName)
                        put("participants", participants.size)
                    }
                    jsonResponse(json)
                }

                uri == "/together/join" && method == Method.POST -> {
                    val body = parseBodyJson(session)
                    val guestName = body.optString("name").ifBlank { "AirBeats listener" }
                    val guestId = UUID.randomUUID().toString().take(8)
                    participants[guestId] = ListenTogetherParticipant(
                        id = guestId,
                        name = guestName,
                        isHost = false
                    )
                    stateVersion++

                    val currentSession = buildSessionSnapshot(participantId = guestId)
                    jsonResponse(currentSession.toJson())
                }

                uri == "/together/state" && method == Method.GET -> {
                    val params = session.parms
                    val reqParticipantId = params["participantId"] ?: "guest"
                    val currentSession = buildSessionSnapshot(participantId = reqParticipantId)
                    jsonResponse(currentSession.toJson())
                }

                uri == "/together/state" && method == Method.POST -> {
                    val body = parseBodyJson(session)
                    val stateObj = body.optJSONObject("state")
                    if (stateObj != null) {
                        playbackState = ListenTogetherPlaybackState.fromJson(stateObj)
                        stateVersion++
                    }
                    val reqParticipantId = body.optString("participantId").ifBlank { "host" }
                    val currentSession = buildSessionSnapshot(participantId = reqParticipantId)
                    jsonResponse(currentSession.toJson())
                }

                uri == "/together/leave" && method == Method.POST -> {
                    val body = parseBodyJson(session)
                    val pId = body.optString("participantId")
                    if (pId.isNotBlank() && pId != "host") {
                        participants.remove(pId)
                        stateVersion++
                    }
                    jsonResponse(JSONObject().put("status", "ok"))
                }

                else -> {
                    newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
                        .apply { applyCors(this) }
                }
            }
        } catch (e: Exception) {
            val err = JSONObject().put("error", e.message ?: "Unknown error")
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", err.toString())
                .apply { applyCors(this) }
        }
    }

    fun buildSessionSnapshot(participantId: String): ListenTogetherSession {
        return ListenTogetherSession(
            code = "$hostIp:$port",
            participantId = participantId,
            joinUrl = "airbeats://together?host=$hostIp&port=$port&sid=$sessionId",
            participants = participants.size,
            participantList = participants.values.sortedByDescending { it.isHost },
            hostName = hostDisplayName,
            controllerId = controllerId,
            controllerName = participants[controllerId]?.name ?: hostDisplayName,
            stateVersion = stateVersion,
            serverNow = System.currentTimeMillis(),
            state = playbackState
        )
    }

    private fun parseBodyJson(session: IHTTPSession): JSONObject {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val postData = files["postData"].orEmpty()
        return if (postData.isNotBlank()) JSONObject(postData) else JSONObject()
    }

    private fun jsonResponse(json: JSONObject): Response {
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString()).apply {
            applyCors(this)
        }
    }

    private fun applyCors(response: Response) {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Headers", "origin, accept, content-type")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
    }

    companion object {
        fun getLocalIpAddress(context: Context?): String? {
            // 1. Try ConnectivityManager for Wi-Fi / Ethernet
            if (context != null) {
                val ipFromCm = runCatching {
                    val manager = context.getSystemService(ConnectivityManager::class.java)
                    manager?.allNetworks?.asSequence()?.filter { network ->
                        val caps = manager.getNetworkCapabilities(network)
                        caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
                    }?.flatMap { manager.getLinkProperties(it)?.linkAddresses.orEmpty().asSequence() }
                        ?.map { it.address }?.filterIsInstance<Inet4Address>()?.firstOrNull { !it.isLoopbackAddress }
                        ?.hostAddress
                }.getOrNull()
                if (!ipFromCm.isNullOrBlank() && ipFromCm != "127.0.0.1") {
                    return ipFromCm
                }
            }

            // 2. Fallback: NetworkInterface for Wi-Fi / Mobile Hotspot (e.g. 192.168.43.1)
            return runCatching {
                NetworkInterface.getNetworkInterfaces().toList()
                    .asSequence()
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { it.inetAddresses.toList().asSequence() }
                    .filterIsInstance<Inet4Address>()
                    .map { it.hostAddress }
                    .firstOrNull { !it.isNullOrBlank() && it != "127.0.0.1" }
            }.getOrNull()
        }
    }
}
