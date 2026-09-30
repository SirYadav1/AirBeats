package com.darkxvenom.airbeats.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import com.darkxvenom.airbeats.playback.cast.ResolvedCastStream
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import timber.log.Timber
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@OptIn(UnstableApi::class)
class LanTogetherServer(
    val context: Context? = null,
    val hostIp: String,
    val port: Int,
    val sessionId: String = UUID.randomUUID().toString().take(8).uppercase(),
    val hostDisplayName: String,
    var audioStreamResolver: (suspend (songId: String) -> ResolvedCastStream?)? = null,
    var onPlaybackCommand: ((action: String, positionMs: Long?) -> Unit)? = null,
) : NanoHTTPD(port) {

    private val participants = ConcurrentHashMap<String, ListenTogetherParticipant>()

    @Volatile
    var playbackState: ListenTogetherPlaybackState? = null

    @Volatile
    var stateVersion: Long = 1L

    @Volatile
    var controllerId: String = "host"

    @Volatile
    var lastActionTime: Long = 0L

    @Volatile
    private var cachedResolvedStream: Pair<String, ResolvedCastStream>? = null

    init {
        participants["host"] = ListenTogetherParticipant(
            id = "host",
            name = hostDisplayName,
            isHost = true
        )
    }

    private var discoveryThread: Thread? = null
    private var discoverySocket: DatagramSocket? = null
    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null

    override fun start(timeout: Int, daemon: Boolean) {
        super.start(timeout, daemon)
        startUdpDiscoveryResponder()
    }

    override fun stop() {
        stopUdpDiscoveryResponder()
        super.stop()
    }

    private fun startUdpDiscoveryResponder() {
        stopUdpDiscoveryResponder()
        runCatching {
            val wifiManager = context?.applicationContext?.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            multicastLock = wifiManager?.createMulticastLock("airbeats_lan_host_discovery")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        discoveryThread = Thread {
            try {
                val socket = DatagramSocket(port)
                discoverySocket = socket
                socket.broadcast = true
                val buf = ByteArray(512)
                while (!socket.isClosed) {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val msg = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                    if (msg.startsWith("AIRBEATS_DISCOVER")) {
                        val replyMsg = "AIRBEATS_HOST:$port:$hostDisplayName:$sessionId:${participants.size}"
                        val replyData = replyMsg.toByteArray(Charsets.UTF_8)
                        val replyPacket = DatagramPacket(replyData, replyData.size, packet.address, packet.port)
                        socket.send(replyPacket)
                    }
                }
            } catch (_: Exception) {}
        }.apply {
            isDaemon = true
            name = "LanTogether-Discovery"
            start()
        }
    }

    private fun stopUdpDiscoveryResponder() {
        runCatching {
            discoverySocket?.close()
            discoverySocket = null
            discoveryThread?.interrupt()
            discoveryThread = null
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
            multicastLock = null
        }
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
                uri == "/" || uri == "/together" || uri == "/index.html" -> {
                    htmlResponse(renderWebPlayerHtml())
                }

                uri == "/stream" || uri == "/together/stream" || uri == "/audio/stream" -> {
                    serveAudioStream(session)
                }

                uri == "/favicon.ico" -> {
                    newFixedLengthResponse(Response.Status.NO_CONTENT, "image/x-icon", "")
                        .apply { applyCors(this) }
                }

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

                uri == "/together/action" && method == Method.POST -> {
                    val body = parseBodyJson(session)
                    val action = body.optString("action")
                    val positionMs = if (body.has("positionMs")) body.optLong("positionMs") else null
                    val reqParticipantId = body.optString("participantId").ifBlank { "guest" }

                    if (action.isNotBlank()) {
                        lastActionTime = System.currentTimeMillis()
                        playbackState?.let { current ->
                            when (action) {
                                "play", "resume" -> {
                                    playbackState = current.copy(
                                        isPlaying = true,
                                        positionMs = positionMs ?: current.positionMs,
                                        updatedAt = System.currentTimeMillis()
                                    )
                                    stateVersion++
                                }
                                "pause" -> {
                                    playbackState = current.copy(
                                        isPlaying = false,
                                        positionMs = positionMs ?: current.positionMs,
                                        updatedAt = System.currentTimeMillis()
                                    )
                                    stateVersion++
                                }
                                "seek" -> {
                                    if (positionMs != null) {
                                        playbackState = current.copy(
                                            positionMs = positionMs,
                                            updatedAt = System.currentTimeMillis()
                                        )
                                        stateVersion++
                                    }
                                }
                            }
                        }
                        onPlaybackCommand?.invoke(action, positionMs)
                    }

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

    private fun serveAudioStream(session: IHTTPSession): Response {
        if (session.method == Method.OPTIONS) {
            return cors(newFixedLengthResponse(Response.Status.OK, "text/plain", ""))
        }
        if (session.method != Method.GET && session.method != Method.HEAD) {
            return cors(newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "Method not allowed"))
        }

        val targetSongId = session.parms["id"]?.takeIf { it.isNotBlank() }
            ?: playbackState?.songId?.takeIf { it.isNotBlank() }

        if (targetSongId == null) {
            return cors(newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "No active track"))
        }

        val source = getOrResolveStream(targetSongId)
            ?: return cors(newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Stream unavailable"))

        val targetContext = context
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20000)
            .setReadTimeoutMs(20000)

        if (source.requestHeaders.isNotEmpty()) {
            httpFactory.setDefaultRequestProperties(source.requestHeaders)
        }

        val factory: androidx.media3.datasource.DataSource.Factory = if (targetContext != null) {
            DefaultDataSource.Factory(targetContext, httpFactory)
        } else {
            httpFactory
        }

        val dataSource = factory.createDataSource()
        var input: DataSourceInputStream? = null
        try {
            val spec = DataSpec.Builder().setUri(source.url).build()
            val total = dataSource.open(spec)
            dataSource.close()

            val mimeType = resolveMimeType(source)

            if (total == 0L) {
                return cors(newFixedLengthResponse(Response.Status.OK, mimeType, ""))
            }

            val range = session.headers["range"]
            val match = range?.let { Regex("bytes=(\\d*)-(\\d*)").matchEntire(it) }
            var start = 0L
            var end = if (total != C.LENGTH_UNSET.toLong()) total - 1 else Long.MAX_VALUE

            if (range != null) {
                if (match == null || total <= 0) return rangeError(total)
                val first = match.groupValues[1]
                val last = match.groupValues[2]
                if (first.isEmpty()) {
                    val suffix = last.toLongOrNull()?.takeIf { it > 0 } ?: return rangeError(total)
                    start = (total - suffix).coerceAtLeast(0)
                } else {
                    start = first.toLongOrNull() ?: return rangeError(total)
                    if (last.isNotEmpty()) end = minOf(last.toLongOrNull() ?: return rangeError(total), end)
                }
                if (start >= total || end < start) return rangeError(total)
            }

            val length = if (total >= 0) end - start + 1 else C.LENGTH_UNSET.toLong()
            input = DataSourceInputStream(
                factory.createDataSource(),
                spec.buildUpon().setPosition(start).setLength(length).build()
            )

            val status = if (range == null) Response.Status.OK else Response.Status.PARTIAL_CONTENT
            val response = if (length >= 0) {
                newFixedLengthResponse(status, mimeType, input, length)
            } else {
                newChunkedResponse(status, mimeType, input)
            }

            response.addHeader("Accept-Ranges", "bytes")
            if (range != null) {
                response.addHeader("Content-Range", "bytes $start-$end/$total")
            }
            return cors(response)
        } catch (error: Exception) {
            input?.close()
            Timber.e(error, "LanTogetherServer: error streaming audio for $targetSongId")
            return cors(newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Stream unavailable"))
        } finally {
            runCatching { dataSource.close() }
        }
    }

    private fun getOrResolveStream(songId: String): ResolvedCastStream? {
        val cached = cachedResolvedStream
        if (cached != null && cached.first == songId) {
            return cached.second
        }
        val resolver = audioStreamResolver ?: return null
        val resolved = runCatching {
            runBlocking(Dispatchers.IO) {
                resolver(songId)
            }
        }.getOrNull() ?: return null
        cachedResolvedStream = songId to resolved
        return resolved
    }

    private fun resolveMimeType(source: ResolvedCastStream): String {
        val rawMime = source.mimeType.split(";")[0].trim()
        val url = source.url.lowercase()
        return when {
            rawMime.isNotBlank() && rawMime != "application/octet-stream" && rawMime != "audio/mp4" -> rawMime
            url.contains(".mp3") -> "audio/mpeg"
            url.contains(".flac") -> "audio/flac"
            url.contains(".wav") -> "audio/wav"
            url.contains(".ogg") || url.contains(".opus") -> "audio/ogg"
            url.contains(".m4a") || url.contains(".aac") || url.contains(".mp4") -> "audio/mp4"
            rawMime == "audio/mp4" -> "audio/mp4"
            else -> "audio/mpeg"
        }
    }

    private fun rangeError(total: Long): Response = cors(
        newFixedLengthResponse(
            Response.Status.RANGE_NOT_SATISFIABLE,
            "text/plain",
            "Invalid range",
        )
    ).apply { if (total >= 0) addHeader("Content-Range", "bytes */$total") }

    private fun cors(response: Response): Response = response.apply {
        addHeader("Access-Control-Allow-Origin", "*")
        addHeader("Access-Control-Allow-Headers", "Range, Origin, Accept, Content-Type")
        addHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
        addHeader("Access-Control-Expose-Headers", "Content-Range, Accept-Ranges, Content-Length")
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

    private fun htmlResponse(html: String): Response {
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=UTF-8", html).apply {
            applyCors(this)
        }
    }

    private fun applyCors(response: Response) {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Headers", "Range, Origin, Accept, Content-Type")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS, HEAD")
        response.addHeader("Access-Control-Expose-Headers", "Content-Range, Accept-Ranges, Content-Length")
    }

    private fun renderWebPlayerHtml(): String {
        return """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
  <title>AirBeats - Listen Together</title>
  <link rel="icon" href="https://raw.githubusercontent.com/drkvenom786/Airbeats/refs/heads/main/icon2.png" type="image/png">
  <style>
    :root {
      --bg: #ffffff;
      --card-bg: #ffffff;
      --text: #050505;
      --text-muted: #6b7280;
      --border: #e5e7eb;
      --pill-bg: #f3f4f6;
      --btn-primary: #050505;
      --btn-text: #ffffff;
      --accent: #8b5cf6;
      --track-bg: #e5e7eb;
      --track-fill: #050505;
      --thumb-color: #050505;
      --success: #10b981;
      --circle-btn: #ffffff;
      --circle-btn-border: #e5e7eb;
    }

    @media (prefers-color-scheme: dark) {
      :root {
        --bg: #090a10;
        --card-bg: #12131c;
        --text: #f9fafb;
        --text-muted: #9ca3af;
        --border: rgba(255, 255, 255, 0.08);
        --pill-bg: rgba(255, 255, 255, 0.06);
        --btn-primary: #ffffff;
        --btn-text: #050505;
        --track-bg: rgba(255, 255, 255, 0.12);
        --track-fill: #ffffff;
        --thumb-color: #ffffff;
        --circle-btn: #181926;
        --circle-btn-border: rgba(255, 255, 255, 0.12);
      }
    }

    [data-theme="light"] {
      --bg: #ffffff;
      --card-bg: #ffffff;
      --text: #050505;
      --text-muted: #6b7280;
      --border: #e5e7eb;
      --pill-bg: #f3f4f6;
      --btn-primary: #050505;
      --btn-text: #ffffff;
      --track-bg: #e5e7eb;
      --track-fill: #050505;
      --thumb-color: #050505;
      --circle-btn: #ffffff;
      --circle-btn-border: #e5e7eb;
    }

    [data-theme="dark"] {
      --bg: #090a10;
      --card-bg: #12131c;
      --text: #f9fafb;
      --text-muted: #9ca3af;
      --border: rgba(255, 255, 255, 0.08);
      --pill-bg: rgba(255, 255, 255, 0.06);
      --btn-primary: #ffffff;
      --btn-text: #050505;
      --track-bg: rgba(255, 255, 255, 0.12);
      --track-fill: #ffffff;
      --thumb-color: #ffffff;
      --circle-btn: #181926;
      --circle-btn-border: rgba(255, 255, 255, 0.12);
    }

    * { box-sizing: border-box; margin: 0; padding: 0; -webkit-tap-highlight-color: transparent; }

    body {
      background: var(--bg);
      color: var(--text);
      font-family: -apple-system, BlinkMacSystemFont, "SF Pro Display", "Segoe UI", Roboto, sans-serif;
      min-height: 100vh;
      display: flex;
      justify-content: center;
      padding: 0;
      overflow-x: hidden;
      transition: background-color 0.2s ease, color 0.2s ease;
    }

    .app-container {
      width: 100%;
      max-width: 480px;
      min-height: 100vh;
      background: var(--card-bg);
      padding: 16px 20px 32px;
      display: flex;
      flex-direction: column;
      position: relative;
    }

    /* Top Bar Header */
    .top-bar {
      display: flex;
      align-items: center;
      justify-content: space-between;
      height: 52px;
      margin-bottom: 12px;
    }

    .brand-wrap {
      display: flex;
      align-items: center;
      gap: 12px;
    }

    .brand-icon {
      width: 38px;
      height: 38px;
      border-radius: 50%;
      overflow: hidden;
      background: #000;
      display: flex;
      align-items: center;
      justify-content: center;
      box-shadow: 0 4px 10px rgba(0, 0, 0, 0.12);
    }

    .brand-icon img {
      width: 100%;
      height: 100%;
      object-fit: cover;
    }

    .brand-name {
      font-size: 1.25rem;
      font-weight: 800;
      letter-spacing: 0.5px;
      color: var(--text);
    }

    .circle-btn {
      width: 42px;
      height: 42px;
      border-radius: 50%;
      background: var(--circle-btn);
      border: 1px solid var(--circle-btn-border);
      display: flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
      color: var(--text);
      transition: transform 0.15s ease, background 0.15s ease;
      box-shadow: 0 2px 6px rgba(0, 0, 0, 0.04);
    }

    .circle-btn:active {
      transform: scale(0.92);
    }

    .circle-btn svg {
      width: 20px;
      height: 20px;
      fill: currentColor;
    }

    /* Dropdown Menu */
    .menu-dropdown {
      position: absolute;
      top: 66px;
      right: 20px;
      background: var(--card-bg);
      border: 1px solid var(--border);
      border-radius: 18px;
      box-shadow: 0 16px 36px rgba(0,0,0,0.18);
      padding: 8px;
      display: none;
      flex-direction: column;
      gap: 4px;
      z-index: 100;
      min-width: 210px;
    }

    .menu-dropdown.show { display: flex; }

    .menu-item {
      display: flex;
      align-items: center;
      gap: 10px;
      padding: 10px 14px;
      border-radius: 12px;
      font-size: 0.88rem;
      font-weight: 600;
      color: var(--text);
      cursor: pointer;
      background: transparent;
      border: none;
      text-align: left;
      text-decoration: none;
      transition: background 0.15s;
    }

    .menu-item:hover {
      background: var(--pill-bg);
    }

    .menu-item svg {
      width: 18px;
      height: 18px;
      fill: currentColor;
      flex-shrink: 0;
    }

    /* Artwork Container */
    .art-container {
      position: relative;
      width: 100%;
      aspect-ratio: 1 / 1;
      border-radius: 28px;
      overflow: hidden;
      box-shadow: 0 12px 32px rgba(0, 0, 0, 0.16);
      background: #151622;
      margin-bottom: 20px;
    }

    .art-img {
      width: 100%;
      height: 100%;
      object-fit: cover;
      display: none;
    }

    .art-placeholder {
      width: 100%;
      height: 100%;
      display: flex;
      align-items: center;
      justify-content: center;
      color: var(--text-muted);
    }

    .art-placeholder svg {
      width: 72px;
      height: 72px;
      fill: currentColor;
      opacity: 0.3;
    }

    .live-badge-float {
      position: absolute;
      top: 14px;
      right: 14px;
      padding: 5px 12px;
      background: rgba(0, 0, 0, 0.65);
      backdrop-filter: blur(8px);
      -webkit-backdrop-filter: blur(8px);
      border-radius: 20px;
      font-size: 0.72rem;
      font-weight: 700;
      color: #10b981;
      letter-spacing: 0.5px;
      display: flex;
      align-items: center;
      gap: 6px;
    }

    .live-dot {
      width: 6px;
      height: 6px;
      background: #10b981;
      border-radius: 50%;
      box-shadow: 0 0 8px #10b981;
      animation: pulse 1.8s infinite;
    }

    @keyframes pulse {
      0% { transform: scale(0.9); opacity: 0.7; }
      50% { transform: scale(1.3); opacity: 1; }
      100% { transform: scale(0.9); opacity: 0.7; }
    }

    /* Artist Row */
    .artist-row {
      display: inline-flex;
      align-items: center;
      gap: 8px;
      margin-bottom: 8px;
      cursor: pointer;
      text-decoration: none;
      color: var(--text);
    }

    .artist-avatar {
      width: 28px;
      height: 28px;
      border-radius: 50%;
      background: var(--pill-bg);
      display: flex;
      align-items: center;
      justify-content: center;
      color: var(--text-muted);
    }

    .artist-avatar svg {
      width: 16px;
      height: 16px;
      fill: currentColor;
    }

    .artist-name {
      font-size: 1.05rem;
      font-weight: 700;
      color: var(--text);
    }

    .artist-chevron {
      font-size: 1.1rem;
      font-weight: 700;
      color: var(--text-muted);
      line-height: 1;
    }

    /* Song Title */
    .track-title {
      font-size: 1.7rem;
      font-weight: 800;
      line-height: 1.25;
      letter-spacing: -0.4px;
      color: var(--text);
      margin-bottom: 12px;
      display: -webkit-box;
      -webkit-line-clamp: 2;
      -webkit-box-orient: vertical;
      overflow: hidden;
      word-break: break-word;
    }

    /* Meta & Badges */
    .meta-row {
      display: flex;
      align-items: center;
      gap: 12px;
      margin-bottom: 12px;
      font-size: 0.85rem;
      color: var(--text-muted);
      font-weight: 600;
    }

    .listener-pill {
      display: inline-flex;
      align-items: center;
      gap: 6px;
    }

    .listener-pill svg {
      width: 16px;
      height: 16px;
      fill: currentColor;
    }

    .genre-tag {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 6px 12px;
      border-radius: 12px;
      background: var(--pill-bg);
      font-size: 0.82rem;
      font-weight: 700;
      color: var(--text);
      margin-bottom: 12px;
      width: fit-content;
    }

    .genre-tag svg {
      width: 14px;
      height: 14px;
      fill: currentColor;
    }

    .track-desc {
      font-size: 0.85rem;
      color: var(--text-muted);
      margin-bottom: 18px;
      line-height: 1.4;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }

    /* Visualizer wave */
    .wave-anim {
      display: none;
      align-items: flex-end;
      gap: 2px;
      height: 12px;
      margin-left: 6px;
    }

    .wave-anim span {
      display: block;
      width: 3px;
      background: var(--success);
      border-radius: 2px;
      animation: wave 0.8s ease-in-out infinite alternate;
    }
    .wave-anim span:nth-child(1) { height: 50%; animation-delay: 0.1s; }
    .wave-anim span:nth-child(2) { height: 100%; animation-delay: 0.3s; }
    .wave-anim span:nth-child(3) { height: 40%; animation-delay: 0.2s; }
    @keyframes wave { 0% { height: 25%; } 100% { height: 100%; } }

    /* DYNAMIC LIVE SEEK BAR */
    .progress-section {
      width: 100%;
      margin-bottom: 16px;
    }

    .progress-bar-wrap {
      position: relative;
      width: 100%;
      height: 24px;
      display: flex;
      align-items: center;
      cursor: pointer;
    }

    .progress-track {
      position: absolute;
      top: 50%;
      left: 0;
      right: 0;
      height: 6px;
      transform: translateY(-50%);
      background: var(--track-bg);
      border-radius: 99px;
      overflow: hidden;
      pointer-events: none;
    }

    .progress-fill {
      height: 100%;
      width: 0%;
      background: var(--track-fill);
      border-radius: 99px;
      transition: width 0.08s linear;
      pointer-events: none;
    }

    .progress-thumb {
      position: absolute;
      top: 50%;
      left: 0%;
      width: 14px;
      height: 14px;
      border-radius: 50%;
      background: var(--thumb-color);
      transform: translate(-50%, -50%);
      box-shadow: 0 2px 6px rgba(0, 0, 0, 0.25);
      pointer-events: none;
      transition: left 0.08s linear;
    }

    .seek-slider {
      position: absolute;
      left: 0;
      top: 0;
      width: 100%;
      height: 100%;
      opacity: 0;
      margin: 0;
      cursor: pointer;
      z-index: 5;
    }

    .time-row {
      display: flex;
      justify-content: space-between;
      margin-top: 2px;
      font-size: 0.76rem;
      font-weight: 600;
      color: var(--text-muted);
      font-variant-numeric: tabular-nums;
    }

    /* HERO AUDIO BUTTON (MATCHES THEME BLACK & WHITE) */
    .hero-btn {
      width: 100%;
      height: 54px;
      border-radius: 27px;
      background: var(--btn-primary);
      color: var(--btn-text);
      font-size: 1rem;
      font-weight: 700;
      border: 1px solid var(--border);
      cursor: pointer;
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 10px;
      margin-bottom: 14px;
      box-shadow: 0 4px 14px rgba(0, 0, 0, 0.12);
      transition: transform 0.15s ease, opacity 0.15s ease;
    }

    .hero-btn:active {
      transform: scale(0.98);
      opacity: 0.92;
    }

    .hero-btn svg {
      width: 22px;
      height: 22px;
      fill: currentColor;
    }

    .btn-icon-wrap {
      display: flex;
      align-items: center;
      justify-content: center;
    }

    /* Volume & Live Sync Row */
    .controls-sub-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      margin-bottom: 20px;
      padding: 0 2px;
    }

    .vol-wrap {
      display: flex;
      align-items: center;
      gap: 8px;
      flex: 1;
      max-width: 220px;
    }

    .vol-btn {
      background: transparent;
      border: none;
      color: var(--text);
      cursor: pointer;
      display: flex;
      align-items: center;
      justify-content: center;
      padding: 4px;
    }

    .vol-btn svg {
      width: 18px;
      height: 18px;
      fill: currentColor;
    }

    .vol-slider {
      width: 100%;
      height: 5px;
      -webkit-appearance: none;
      appearance: none;
      background: var(--track-bg);
      border-radius: 4px;
      outline: none;
      cursor: pointer;
    }

    .vol-slider::-webkit-slider-thumb {
      -webkit-appearance: none;
      appearance: none;
      width: 12px;
      height: 12px;
      border-radius: 50%;
      background: var(--text);
      cursor: pointer;
    }

    .btn-sync-live {
      padding: 7px 14px;
      border-radius: 14px;
      background: var(--pill-bg);
      border: 1px solid var(--border);
      color: var(--text);
      font-size: 0.8rem;
      font-weight: 700;
      cursor: pointer;
      white-space: nowrap;
      display: inline-flex;
      align-items: center;
      gap: 6px;
      transition: background 0.15s ease;
    }

    .btn-sync-live:hover {
      background: var(--border);
    }

    .btn-sync-live svg {
      width: 14px;
      height: 14px;
      fill: currentColor;
    }

    /* BOTTOM ACTION BAR (MATCHING SCREENSHOT) */
    .bottom-actions {
      display: flex;
      align-items: center;
      gap: 12px;
      margin-top: auto;
      padding-top: 12px;
    }

    .btn-open-app {
      flex: 1;
      height: 54px;
      border-radius: 27px;
      background: var(--btn-primary);
      color: var(--btn-text);
      font-size: 1rem;
      font-weight: 700;
      border: none;
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      gap: 10px;
      text-decoration: none;
      box-shadow: 0 4px 14px rgba(0, 0, 0, 0.16);
      transition: transform 0.15s ease, opacity 0.15s ease;
    }

    .btn-open-app:active {
      transform: scale(0.98);
      opacity: 0.92;
    }

    .btn-open-app img {
      width: 22px;
      height: 22px;
      border-radius: 50%;
    }

    .bottom-circle-btn {
      width: 54px;
      height: 54px;
      border-radius: 50%;
      background: var(--circle-btn);
      border: 1px solid var(--circle-btn-border);
      display: flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
      color: var(--text);
      box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
      transition: transform 0.15s ease;
      flex-shrink: 0;
    }

    .bottom-circle-btn:active {
      transform: scale(0.92);
    }

    .bottom-circle-btn svg {
      width: 22px;
      height: 22px;
      fill: currentColor;
    }

    .footer-caption {
      text-align: center;
      font-size: 0.75rem;
      color: var(--text-muted);
      margin-top: 14px;
      font-weight: 500;
    }

    /* Toast */
    .toast {
      position: fixed;
      bottom: 24px;
      left: 50%;
      transform: translateX(-50%);
      background: #10b981;
      color: white;
      padding: 8px 18px;
      border-radius: 20px;
      font-size: 0.82rem;
      font-weight: 700;
      opacity: 0;
      pointer-events: none;
      transition: opacity 0.25s ease;
      z-index: 200;
      box-shadow: 0 4px 12px rgba(0,0,0,0.18);
    }
    .toast.show { opacity: 1; }
  </style>
</head>
<body>
  <audio id="audioElement" preload="auto" playsinline></audio>

  <div class="app-container">
    <!-- Top Bar -->
    <div class="top-bar">
      <div class="brand-wrap">
        <div class="brand-icon">
          <img src="https://raw.githubusercontent.com/drkvenom786/Airbeats/refs/heads/main/icon2.png" alt="AirBeats" />
        </div>
        <span class="brand-name">AIRBEATS</span>
      </div>

      <button class="circle-btn" onclick="toggleMenu()" aria-label="Menu">
        <svg viewBox="0 0 24 24"><path d="M3 18h18v-2H3v2zm0-5h18v-2H3v2zm0-7v2h18V6H3z"/></svg>
      </button>

      <!-- Dropdown Menu -->
      <div id="menuDropdown" class="menu-dropdown">
        <button class="menu-item" onclick="syncWithHost()">
          <svg viewBox="0 0 24 24"><path d="M12 4V1L8 5l4 4V6c3.31 0 6 2.69 6 6 0 1.01-.25 1.97-.7 2.8l1.46 1.46C19.54 15.03 20 13.57 20 12c0-4.42-3.58-8-8-8zm0 14c-3.31 0-6-2.69-6-6 0-1.01.25-1.97.7-2.8L5.24 7.74C4.46 8.97 4 10.43 4 12c0 4.42 3.58 8 8 8v3l4-4-4-4v3z"/></svg>
          <span>Sync with Host</span>
        </button>
        <button class="menu-item" onclick="copyStreamLink()">
          <svg viewBox="0 0 24 24"><path d="M3.9 12c0-1.71 1.39-3.1 3.1-3.1h4V7H7c-2.76 0-5 2.24-5 5s2.24 5 5 5h4v-1.9H7c-1.71 0-3.1-1.39-3.1-3.1zM8 13h8v-2H8v2zm9-6h-4v1.9h4c1.71 0 3.1 1.39 3.1 3.1s-1.39 3.1-3.1 3.1h-4V17h4c2.76 0 5-2.24 5-5s-2.24-5-5-5z"/></svg>
          <span>Copy Direct Stream URL</span>
        </button>
        <button class="menu-item" onclick="toggleTheme()">
          <svg viewBox="0 0 24 24"><path d="M12 3c-4.97 0-9 4.03-9 9s4.03 9 9 9 9-4.03 9-9c0-.46-.04-.92-.1-1.36-.98 1.37-2.58 2.26-4.4 2.26-2.98 0-5.4-2.42-5.4-5.4 0-1.81.89-3.42 2.26-4.4-.44-.06-.9-.1-1.36-.1z"/></svg>
          <span>Toggle Light / Dark Mode</span>
        </button>
        <a id="menuAppDeepLink" href="airbeats://together?host=$hostIp&port=$port&sid=$sessionId" class="menu-item">
          <svg viewBox="0 0 24 24"><path d="M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z"/></svg>
          <span>Open in AirBeats App</span>
        </a>
      </div>
    </div>

    <!-- Album Artwork Card -->
    <div class="art-container">
      <img id="artImg" class="art-img" alt="Artwork" />
      <div id="artPlaceholder" class="art-placeholder">
        <svg viewBox="0 0 24 24"><path d="M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z"/></svg>
      </div>
      <div class="live-badge-float">
        <span class="live-dot"></span>
        <span>LAN LIVE</span>
      </div>
    </div>

    <!-- Artist Row -->
    <div class="artist-row">
      <div class="artist-avatar">
        <svg viewBox="0 0 24 24"><path d="M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z"/></svg>
      </div>
      <span id="trackArtist" class="artist-name">AirBeats</span>
      <span class="artist-chevron">›</span>
    </div>

    <!-- Track Title -->
    <h1 id="trackTitle" class="track-title">AirBeats Session</h1>

    <!-- Meta / Badges Row -->
    <div class="meta-row">
      <div class="listener-pill">
        <svg viewBox="0 0 24 24"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-1 14H9V8h2v8zm4 0h-2V8h2v8z"/></svg>
        <span id="participantCount">1 listener</span>
      </div>
      <span id="statusTag">Connecting...</span>
      <div id="waveAnim" class="wave-anim">
        <span></span><span></span><span></span>
      </div>
    </div>

    <!-- Genre / Source Badge -->
    <div class="genre-tag">
      <svg viewBox="0 0 24 24"><path d="M10 20h4V4h-4v16zm-6 0h4v-8H4v8zM16 9v11h4V9h-4z"/></svg>
      <span id="genreLabel">AirBeats LAN</span>
    </div>

    <!-- Track Description -->
    <p id="trackDesc" class="track-desc">Streaming live over LAN • Host: $hostDisplayName</p>

    <!-- LIVE SEEK BAR WITH CONTINUOUS FILL -->
    <div class="progress-section">
      <div class="progress-bar-wrap" id="progressBarWrap">
        <div class="progress-track">
          <div id="progressFill" class="progress-fill" style="width: 0%;"></div>
        </div>
        <div id="progressThumb" class="progress-thumb" style="left: 0%;"></div>
        <input type="range" id="seekSlider" class="seek-slider" min="0" max="100" value="0" step="0.1" />
      </div>
      <div class="time-row">
        <span id="curTime">00:00</span>
        <span id="durTime">--:--</span>
      </div>
    </div>

    <!-- Hero Play / Listen Button (Black & White Theme, SVG Icons) -->
    <button id="heroPlayBtn" class="hero-btn" onclick="togglePlayback()">
      <span id="heroPlayIcon" class="btn-icon-wrap">
        <svg viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg>
      </span>
      <span id="heroPlayText">Start Listening</span>
    </button>

    <!-- Volume & Live Sync Row (SVG Icons) -->
    <div class="controls-sub-row">
      <div class="vol-wrap">
        <button class="vol-btn" onclick="toggleMute()" title="Mute/Unmute">
          <span id="volIcon">
            <svg viewBox="0 0 24 24"><path d="M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z"/></svg>
          </span>
        </button>
        <input type="range" id="volSlider" class="vol-slider" min="0" max="1" step="0.02" value="1" oninput="onVolumeChange(this.value)" title="Volume" />
      </div>
      <button class="btn-sync-live" onclick="syncWithHost()" title="Re-sync with Host">
        <svg viewBox="0 0 24 24"><path d="M12 4V1L8 5l4 4V6c3.31 0 6 2.69 6 6 0 1.01-.25 1.97-.7 2.8l1.46 1.46C19.54 15.03 20 13.57 20 12c0-4.42-3.58-8-8-8zm0 14c-3.31 0-6-2.69-6-6 0-1.01.25-1.97.7-2.8L5.24 7.74C4.46 8.97 4 10.43 4 12c0 4.42 3.58 8 8 8v3l4-4-4-4v3z"/></svg>
        <span>Sync Live</span>
      </button>
    </div>

    <!-- Bottom Actions Row (Matching Reference Screenshot) -->
    <div class="bottom-actions">
      <a id="appDeepLink" href="airbeats://together?host=$hostIp&port=$port&sid=$sessionId" class="btn-open-app">
        <img src="https://raw.githubusercontent.com/drkvenom786/Airbeats/refs/heads/main/icon2.png" alt="Logo" />
        <span>Open in AirBeats</span>
      </a>

      <button class="bottom-circle-btn" onclick="copyLink()" title="Share Link">
        <svg viewBox="0 0 24 24"><path d="M18 16.08c-.76 0-1.44.3-1.96.77L8.91 12.7c.05-.23.09-.46.09-.7s-.04-.47-.09-.7l7.05-4.11c.54.5 1.25.81 2.04.81 1.66 0 3-1.34 3-3s-1.34-3-3-3-3 1.34-3 3c0 .24.04.47.09.7L8.04 9.81C7.5 9.31 6.79 9 6 9c-1.66 0-3 1.34-3 3s1.34 3 3 3c.79 0 1.5-.31 2.04-.81l7.12 4.16c-.05.21-.08.43-.08.65 0 1.61 1.31 2.92 2.92 2.92 1.61 0 2.92-1.31 2.92-2.92s-1.31-2.92-2.92-2.92z"/></svg>
      </button>

      <button class="bottom-circle-btn" onclick="toggleMenu()" title="More Options">
        <svg viewBox="0 0 24 24"><path d="M12 8c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm0 2c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z"/></svg>
      </button>
    </div>

    <p class="footer-caption">Free high-res audio streaming on AirBeats App</p>
  </div>

  <div id="toast" class="toast"></div>

  <script>
    var audio = document.getElementById('audioElement');
    var heroPlayBtn = document.getElementById('heroPlayBtn');
    var heroPlayIcon = document.getElementById('heroPlayIcon');
    var heroPlayText = document.getElementById('heroPlayText');
    var curTimeEl = document.getElementById('curTime');
    var durTimeEl = document.getElementById('durTime');
    var seekSlider = document.getElementById('seekSlider');
    var progressFill = document.getElementById('progressFill');
    var progressThumb = document.getElementById('progressThumb');
    var volSlider = document.getElementById('volSlider');
    var volIcon = document.getElementById('volIcon');
    var waveAnim = document.getElementById('waveAnim');
    var statusTag = document.getElementById('statusTag');
    var menuDropdown = document.getElementById('menuDropdown');

    var playSvg = '<svg viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg>';
    var pauseSvg = '<svg viewBox="0 0 24 24"><path d="M6 19h4V5H6v14zm8-14v14h4V5h-4z"/></svg>';
    var volHighSvg = '<svg viewBox="0 0 24 24"><path d="M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z"/></svg>';
    var volLowSvg = '<svg viewBox="0 0 24 24"><path d="M18.5 12c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM5 9v6h4l5 5V4L9 9H5z"/></svg>';
    var volMuteSvg = '<svg viewBox="0 0 24 24"><path d="M16.5 12c0-1.77-1.02-3.29-2.5-4.03v2.21l2.45 2.45c.03-.2.05-.41.05-.63zm2.5 0c0 .94-.2 1.82-.54 2.64l1.51 1.51C20.63 14.91 21 13.5 21 12c0-4.28-2.99-7.86-7-8.77v2.06c2.89.86 5 3.54 5 6.71zM4.27 3L3 4.27 7.73 9H3v6h4l5 5v-6.73l4.25 4.25c-.67.52-1.42.93-2.25 1.18v2.06c1.38-.31 2.63-.95 3.69-1.81L19.73 21 21 19.73l-9-9L4.27 3zM12 4L9.91 6.09 12 8.18V4z"/></svg>';

    var isAudioActivated = false;
    var isUserPaused = false;
    var currentSongId = null;
    var lastServerState = null;
    var isSeeking = false;
    var clientPid = 'web_' + Math.random().toString(36).substring(2, 8);

    function formatTime(sec) {
      if (!sec || isNaN(sec) || sec < 0) sec = 0;
      sec = Math.floor(sec);
      var m = Math.floor(sec / 60);
      var s = sec % 60;
      return (m < 10 ? '0' : '') + m + ':' + (s < 10 ? '0' : '') + s;
    }

    function toggleMenu() {
      if (!menuDropdown) return;
      menuDropdown.classList.toggle('show');
    }

    document.addEventListener('click', function(e) {
      if (menuDropdown && menuDropdown.classList.contains('show')) {
        if (!e.target.closest('.circle-btn') && !e.target.closest('.bottom-circle-btn') && !e.target.closest('.menu-dropdown')) {
          menuDropdown.classList.remove('show');
        }
      }
    });

    function toggleTheme() {
      var current = document.documentElement.getAttribute('data-theme');
      var next = (current === 'dark') ? 'light' : 'dark';
      document.documentElement.setAttribute('data-theme', next);
      localStorage.setItem('airbeats_theme', next);
      showToast('Switched to ' + next + ' theme');
      if (menuDropdown) menuDropdown.classList.remove('show');
    }

    var savedTheme = localStorage.getItem('airbeats_theme');
    if (savedTheme) {
      document.documentElement.setAttribute('data-theme', savedTheme);
    }

    var lastUserActionTime = 0;

    function sendPlaybackAction(action, positionMs) {
      lastUserActionTime = Date.now();
      var xhr = new XMLHttpRequest();
      xhr.open('POST', '/together/action', true);
      xhr.setRequestHeader('Content-Type', 'application/json');
      var payload = {
        action: action,
        participantId: clientPid
      };
      if (positionMs !== undefined && positionMs !== null) {
        payload.positionMs = Math.round(positionMs);
      }
      xhr.onload = function() {
        if (xhr.status >= 200 && xhr.status < 300) {
          try {
            var json = JSON.parse(xhr.responseText);
            updateUi(json);
          } catch (e) {}
        }
      };
      xhr.send(JSON.stringify(payload));
    }

    function togglePlayback() {
      if (!isAudioActivated) {
        activateAudio();
        if (lastServerState && !lastServerState.isPlaying) {
          sendPlaybackAction('play', Math.round((audio.currentTime || 0) * 1000));
        }
      } else {
        if (audio.paused) {
          isUserPaused = false;
          lastUserActionTime = Date.now();
          if (lastServerState) lastServerState.isPlaying = true;
          audio.play().catch(function(e) { console.warn('Play error:', e); });
          sendPlaybackAction('play', Math.round((audio.currentTime || 0) * 1000));
        } else {
          isUserPaused = true;
          lastUserActionTime = Date.now();
          if (lastServerState) lastServerState.isPlaying = false;
          audio.pause();
          sendPlaybackAction('pause', Math.round((audio.currentTime || 0) * 1000));
        }
        updatePlayPauseUi();
      }
    }

    function activateAudio() {
      isAudioActivated = true;
      isUserPaused = false;
      if (lastServerState && lastServerState.songId) {
        loadAndPlaySong(lastServerState.songId, lastServerState);
      } else {
        audio.src = '/stream?t=' + Date.now();
        audio.play().catch(function(e) { console.warn('Stream waiting...', e); });
      }
      updatePlayPauseUi();
    }

    function loadAndPlaySong(songId, state) {
      currentSongId = songId;
      var streamUrl = '/stream?id=' + encodeURIComponent(songId) + '&t=' + Date.now();
      audio.src = streamUrl;
      audio.load();

      var posMs = state.positionMs || 0;
      if (state.isPlaying && state.updatedAt) {
        posMs += Math.max(0, Date.now() - state.updatedAt);
      }
      var startSec = Math.max(0, posMs / 1000);

      audio.onloadedmetadata = function() {
        if (startSec > 0 && audio.duration && startSec < audio.duration) {
          audio.currentTime = startSec;
        }
        var totalDurMs = (state.durationMs > 0) ? state.durationMs : (audio.duration * 1000);
        if (durTimeEl && totalDurMs > 0) {
          durTimeEl.innerText = formatTime(totalDurMs / 1000);
        }
      };

      var shouldPlay = !isUserPaused && (state.isPlaying || (Date.now() - lastUserActionTime < 4000));
      if (shouldPlay) {
        audio.play().catch(function(e) { console.warn('Audio play error:', e); });
      }
      updatePlayPauseUi();
    }

    function updatePlayPauseUi() {
      if (!isAudioActivated) {
        if (heroPlayIcon) heroPlayIcon.innerHTML = playSvg;
        if (heroPlayText) heroPlayText.innerText = 'Start Listening';
        if (waveAnim) waveAnim.style.display = 'none';
      } else if (audio.paused) {
        if (heroPlayIcon) heroPlayIcon.innerHTML = playSvg;
        if (heroPlayText) heroPlayText.innerText = 'Resume Audio';
        if (waveAnim) waveAnim.style.display = 'none';
        if (statusTag) statusTag.innerText = isUserPaused ? 'Paused Locally' : 'Paused by Host';
      } else {
        if (heroPlayIcon) heroPlayIcon.innerHTML = pauseSvg;
        if (heroPlayText) heroPlayText.innerText = 'Listening Live';
        if (waveAnim) waveAnim.style.display = 'inline-flex';
        if (statusTag) statusTag.innerText = 'Playing';
      }
    }

    function syncWithHost() {
      if (menuDropdown) menuDropdown.classList.remove('show');
      if (!lastServerState) return;
      var posMs = lastServerState.positionMs || 0;
      if (lastServerState.isPlaying && lastServerState.updatedAt) {
        posMs += Math.max(0, Date.now() - lastServerState.updatedAt);
      }
      var targetSec = Math.max(0, posMs / 1000);
      if (audio && audio.duration && isFinite(audio.duration) && targetSec < audio.duration) {
        audio.currentTime = targetSec;
      }
      if (!isUserPaused && lastServerState.isPlaying && audio.paused) {
        audio.play().catch(function(){});
      }
      showToast('Synced to ' + formatTime(targetSec));
    }

    function onVolumeChange(val) {
      if (audio) {
        audio.volume = parseFloat(val);
        audio.muted = (audio.volume === 0);
      }
      updateVolumeUi();
    }

    function toggleMute() {
      if (!audio) return;
      audio.muted = !audio.muted;
      updateVolumeUi();
      if (volSlider && !audio.muted && audio.volume === 0) {
        audio.volume = 0.5;
        volSlider.value = 0.5;
      }
    }

    function updateVolumeUi() {
      if (!volIcon || !audio) return;
      if (audio.muted || audio.volume === 0) {
        volIcon.innerHTML = volMuteSvg;
      } else if (audio.volume < 0.5) {
        volIcon.innerHTML = volLowSvg;
      } else {
        volIcon.innerHTML = volHighSvg;
      }
    }

    /* SEEK BAR PROGRESS FILL & INTERACTION */
    if (seekSlider) {
      seekSlider.oninput = function() {
        isSeeking = true;
        var pct = parseFloat(this.value);
        if (progressFill) progressFill.style.width = pct + '%';
        if (progressThumb) progressThumb.style.left = pct + '%';
        var durSec = 0;
        if (lastServerState && lastServerState.durationMs > 0) {
          durSec = lastServerState.durationMs / 1000;
        } else if (audio && audio.duration && !isNaN(audio.duration)) {
          durSec = audio.duration;
        }
        if (durSec > 0 && curTimeEl) {
          curTimeEl.innerText = formatTime((pct / 100) * durSec);
        }
      };

      seekSlider.onchange = function() {
        var pct = parseFloat(this.value);
        if (progressFill) progressFill.style.width = pct + '%';
        if (progressThumb) progressThumb.style.left = pct + '%';
        var durSec = 0;
        if (lastServerState && lastServerState.durationMs > 0) {
          durSec = lastServerState.durationMs / 1000;
        } else if (audio && audio.duration && !isNaN(audio.duration)) {
          durSec = audio.duration;
        }
        if (audio && durSec > 0) {
          var targetSec = (pct / 100) * durSec;
          audio.currentTime = targetSec;
          sendPlaybackAction('seek', targetSec * 1000);
        }
        isSeeking = false;
      };
    }

    /* CONTINUOUS LIVE TIMELINE TICK LOOP (UPDATES AUTOMATICALLY IN REAL TIME) */
    function tickTimeline() {
      if (!lastServerState) return;
      if (isSeeking) return;

      var durationMs = 0;
      if (lastServerState.durationMs && lastServerState.durationMs > 0) {
        durationMs = lastServerState.durationMs;
      } else if (audio && audio.duration && !isNaN(audio.duration) && isFinite(audio.duration) && audio.duration > 0) {
        durationMs = audio.duration * 1000;
      }

      var currentMs = 0;
      if (isAudioActivated && audio && !audio.paused && !isNaN(audio.currentTime)) {
        currentMs = audio.currentTime * 1000;
      } else {
        var elapsed = (lastServerState.isPlaying && lastServerState.updatedAt)
          ? Math.max(0, Date.now() - lastServerState.updatedAt)
          : 0;
        currentMs = (lastServerState.positionMs || 0) + elapsed;
        if (durationMs > 0 && currentMs > durationMs) {
          currentMs = durationMs;
        }
      }

      if (curTimeEl) {
        curTimeEl.innerText = formatTime(currentMs / 1000);
      }
      if (durTimeEl) {
        durTimeEl.innerText = (durationMs > 0) ? formatTime(durationMs / 1000) : '--:--';
      }

      if (durationMs > 0) {
        var pct = Math.min(100, Math.max(0, (currentMs / durationMs) * 100));
        if (seekSlider) seekSlider.value = pct;
        if (progressFill) progressFill.style.width = pct + '%';
        if (progressThumb) progressThumb.style.left = pct + '%';
      }
    }

    setInterval(tickTimeline, 100);

    if (audio) {
      audio.onplay = function() { updatePlayPauseUi(); };
      audio.onpause = function() { updatePlayPauseUi(); };
      audio.onended = function() {
        if (waveAnim) waveAnim.style.display = 'none';
        if (statusTag) statusTag.innerText = 'Track Ended';
      };
    }

    function updateUi(data) {
      if (!data) return;
      var state = data.state;
      lastServerState = state;

      var count = data.participants || 1;
      var countEl = document.getElementById('participantCount');
      if (countEl) countEl.innerText = count + (count === 1 ? ' listener' : ' listeners');

      var titleEl = document.getElementById('trackTitle');
      var artistEl = document.getElementById('trackArtist');
      var artImg = document.getElementById('artImg');
      var artPlaceholder = document.getElementById('artPlaceholder');
      var descEl = document.getElementById('trackDesc');

      if (!state || !state.title) {
        if (titleEl) titleEl.innerText = 'AirBeats Session';
        if (artistEl) artistEl.innerText = 'AirBeats';
        if (statusTag) statusTag.innerText = 'Idle';
        if (waveAnim) waveAnim.style.display = 'none';
        if (artImg) artImg.style.display = 'none';
        if (artPlaceholder) artPlaceholder.style.display = 'flex';
        return;
      }

      if (titleEl) titleEl.innerText = state.title;
      var artistStr = (state.artists && state.artists.length) ? state.artists.join(', ') : 'AirBeats';
      if (artistEl) artistEl.innerText = artistStr;
      if (descEl) descEl.innerText = 'Streaming live over LAN • Host: ' + (data.hostName || 'AirBeats');
      document.title = (state.isPlaying ? '\u25B6 ' : '\u23F8 ') + state.title + ' \u2022 AirBeats';

      if (state.thumbnailUrl) {
        if (artImg) {
          if (artImg.src !== state.thumbnailUrl) artImg.src = state.thumbnailUrl;
          artImg.onerror = function() {
            artImg.style.display = 'none';
            if (artPlaceholder) artPlaceholder.style.display = 'flex';
          };
          artImg.onload = function() {
            artImg.style.display = 'block';
            if (artPlaceholder) artPlaceholder.style.display = 'none';
          };
        }
      } else {
        if (artImg) artImg.style.display = 'none';
        if (artPlaceholder) artPlaceholder.style.display = 'flex';
      }

      if (state.songId) {
        if (state.songId !== currentSongId) {
          if (isAudioActivated) {
            loadAndPlaySong(state.songId, state);
          } else {
            currentSongId = state.songId;
          }
        } else if (isAudioActivated && !isUserPaused) {
          var timeSinceUserAction = Date.now() - lastUserActionTime;
          if (timeSinceUserAction > 4000) {
            if (state.isPlaying && audio.paused) {
              audio.play().catch(function(){});
            } else if (!state.isPlaying && !audio.paused) {
              audio.pause();
            }
          }

          if (state.isPlaying && !isSeeking && timeSinceUserAction > 4000) {
            var elapsedMs = Math.max(0, Date.now() - (state.updatedAt || Date.now()));
            var expectedSec = ((state.positionMs || 0) + elapsedMs) / 1000;
            if (audio.duration && expectedSec < audio.duration) {
              var diff = Math.abs(audio.currentTime - expectedSec);
              if (diff > 3.5) {
                audio.currentTime = expectedSec;
              }
            }
          }
        }
      }
      updatePlayPauseUi();
      tickTimeline();
    }

    function fetchState() {
      var xhr = new XMLHttpRequest();
      xhr.open('GET', '/together/state?participantId=' + clientPid, true);
      xhr.timeout = 2500;
      xhr.onload = function() {
        if (xhr.status >= 200 && xhr.status < 300) {
          try {
            var json = JSON.parse(xhr.responseText);
            updateUi(json);
          } catch (e) {}
        }
      };
      xhr.onerror = function() {
        if (statusTag) statusTag.innerText = 'Reconnecting...';
        if (waveAnim) waveAnim.style.display = 'none';
      };
      xhr.send();
    }

    setInterval(fetchState, 1200);
    fetchState();

    function copyLink() {
      var url = window.location.href;
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(url).then(function() {
          showToast('Join link copied!');
        }).catch(function() {
          showToast('Copied: ' + url);
        });
      } else {
        showToast('Link: ' + url);
      }
    }

    function copyStreamLink() {
      var streamUrl = window.location.origin + '/stream';
      if (menuDropdown) menuDropdown.classList.remove('show');
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(streamUrl).then(function() {
          showToast('Stream URL copied: ' + streamUrl);
        }).catch(function() {
          showToast('Copied: ' + streamUrl);
        });
      } else {
        showToast('Stream: ' + streamUrl);
      }
    }

    function showToast(msg) {
      var t = document.getElementById('toast');
      if (!t) return;
      t.innerText = msg;
      t.className = 'toast show';
      setTimeout(function() { t.className = 'toast'; }, 2200);
    }
  </script>
</body>
</html>
        """.trimIndent()
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
