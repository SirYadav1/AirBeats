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
) : NanoHTTPD(port) {

    private val participants = ConcurrentHashMap<String, ListenTogetherParticipant>()

    @Volatile
    var playbackState: ListenTogetherPlaybackState? = null

    @Volatile
    var stateVersion: Long = 1L

    @Volatile
    var controllerId: String = "host"

    @Volatile
    private var cachedResolvedStream: Pair<String, ResolvedCastStream>? = null

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
  <style>
    :root {
      --bg: #090a10;
      --card: rgba(22, 24, 38, 0.85);
      --card-border: rgba(255, 255, 255, 0.08);
      --primary: #8b5cf6;
      --primary-hover: #7c3aed;
      --primary-grad: linear-gradient(135deg, #7c3aed 0%, #ec4899 100%);
      --text: #f9fafb;
      --text-dim: #9ca3af;
      --success: #10b981;
      --surface: rgba(255, 255, 255, 0.05);
      --surface-border: rgba(255, 255, 255, 0.08);
    }
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      background: var(--bg);
      background-image: 
        radial-gradient(circle at 10% 20%, rgba(124, 58, 237, 0.22) 0%, transparent 45%),
        radial-gradient(circle at 90% 80%, rgba(236, 72, 153, 0.16) 0%, transparent 45%);
      color: var(--text);
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
      min-height: 100vh;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      padding: 16px;
    }
    .player-card {
      background: var(--card);
      backdrop-filter: blur(32px);
      -webkit-backdrop-filter: blur(32px);
      border: 1px solid var(--card-border);
      border-radius: 28px;
      padding: 26px 22px;
      width: 100%;
      max-width: 440px;
      box-shadow: 0 25px 65px rgba(0, 0, 0, 0.65);
      text-align: center;
      position: relative;
    }
    .header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      margin-bottom: 20px;
    }
    .brand {
      display: flex;
      align-items: center;
      gap: 9px;
      font-weight: 800;
      font-size: 1.15rem;
      letter-spacing: -0.3px;
    }
    .brand-icon {
      width: 30px;
      height: 30px;
      border-radius: 9px;
      background: var(--primary-grad);
      display: flex;
      align-items: center;
      justify-content: center;
    }
    .brand-icon svg {
      width: 18px;
      height: 18px;
      fill: #fff;
    }
    .live-badge {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 4px 10px;
      background: rgba(16, 185, 129, 0.12);
      border: 1px solid rgba(16, 185, 129, 0.3);
      border-radius: 20px;
      font-size: 0.7rem;
      font-weight: 700;
      color: var(--success);
      letter-spacing: 0.5px;
    }
    .live-dot {
      width: 7px;
      height: 7px;
      background: var(--success);
      border-radius: 50%;
      box-shadow: 0 0 8px var(--success);
      animation: pulse 1.8s infinite;
    }
    @keyframes pulse {
      0% { transform: scale(0.9); opacity: 0.7; }
      50% { transform: scale(1.25); opacity: 1; }
      100% { transform: scale(0.9); opacity: 0.7; }
    }
    .art-container {
      position: relative;
      width: 220px;
      height: 220px;
      margin: 0 auto 18px;
      border-radius: 22px;
      overflow: hidden;
      box-shadow: 0 16px 36px rgba(0,0,0,0.6);
      background: #151622;
      display: flex;
      align-items: center;
      justify-content: center;
    }
    .art-img {
      width: 100%;
      height: 100%;
      object-fit: cover;
      display: none;
    }
    .art-placeholder {
      display: flex;
      align-items: center;
      justify-content: center;
      width: 100%;
      height: 100%;
      color: var(--text-dim);
    }
    .art-placeholder svg {
      width: 64px;
      height: 64px;
      fill: currentColor;
      opacity: 0.25;
    }
    .track-title {
      font-size: 1.25rem;
      font-weight: 700;
      margin-bottom: 5px;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
      padding: 0 4px;
    }
    .track-artist {
      font-size: 0.9rem;
      color: var(--text-dim);
      margin-bottom: 14px;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
      padding: 0 4px;
    }
    .status-tag {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 5px 12px;
      border-radius: 12px;
      background: var(--surface);
      border: 1px solid var(--surface-border);
      font-size: 0.78rem;
      font-weight: 600;
      color: var(--text-dim);
      margin-bottom: 16px;
    }
    .wave-anim {
      display: none;
      align-items: flex-end;
      gap: 2px;
      height: 12px;
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
    @keyframes wave {
      0% { height: 25%; }
      100% { height: 100%; }
    }

    /* Hero Audio Button */
    .hero-btn {
      width: 100%;
      padding: 14px 20px;
      border-radius: 16px;
      background: var(--primary-grad);
      color: #fff;
      font-size: 0.98rem;
      font-weight: 700;
      border: none;
      cursor: pointer;
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 10px;
      margin-bottom: 18px;
      box-shadow: 0 8px 24px rgba(124, 58, 237, 0.4);
      transition: all 0.2s ease;
    }
    .hero-btn:hover {
      transform: translateY(-1px);
      box-shadow: 0 10px 28px rgba(124, 58, 237, 0.5);
    }
    .hero-btn:active {
      transform: translateY(1px);
    }

    /* Player Controls Bar */
    .player-controls {
      background: rgba(0, 0, 0, 0.28);
      border: 1px solid var(--surface-border);
      border-radius: 18px;
      padding: 14px 16px;
      margin-bottom: 16px;
    }
    .seek-slider {
      width: 100%;
      height: 6px;
      -webkit-appearance: none;
      appearance: none;
      background: rgba(255, 255, 255, 0.1);
      border-radius: 6px;
      outline: none;
      cursor: pointer;
    }
    .seek-slider::-webkit-slider-thumb {
      -webkit-appearance: none;
      appearance: none;
      width: 14px;
      height: 14px;
      border-radius: 50%;
      background: #ec4899;
      cursor: pointer;
      box-shadow: 0 0 8px rgba(236, 72, 153, 0.7);
    }
    .seek-slider::-moz-range-thumb {
      width: 14px;
      height: 14px;
      border-radius: 50%;
      background: #ec4899;
      cursor: pointer;
      box-shadow: 0 0 8px rgba(236, 72, 153, 0.7);
    }
    .time-row {
      display: flex;
      justify-content: space-between;
      margin-top: 6px;
      font-size: 0.72rem;
      color: var(--text-dim);
      font-variant-numeric: tabular-nums;
    }
    .control-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      margin-top: 12px;
      gap: 12px;
    }
    .vol-wrap {
      display: flex;
      align-items: center;
      gap: 8px;
      flex: 1;
    }
    .vol-btn {
      background: transparent;
      border: none;
      color: var(--text-dim);
      font-size: 1.1rem;
      cursor: pointer;
      display: flex;
      align-items: center;
      justify-content: center;
    }
    .vol-slider {
      width: 100%;
      height: 5px;
      -webkit-appearance: none;
      appearance: none;
      background: rgba(255, 255, 255, 0.1);
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
    .btn-sync {
      padding: 6px 12px;
      border-radius: 10px;
      background: rgba(255, 255, 255, 0.08);
      border: 1px solid var(--surface-border);
      color: var(--text);
      font-size: 0.76rem;
      font-weight: 600;
      cursor: pointer;
      white-space: nowrap;
      transition: all 0.2s ease;
    }
    .btn-sync:hover {
      background: rgba(255, 255, 255, 0.15);
    }

    .session-box {
      background: rgba(0, 0, 0, 0.22);
      border: 1px solid var(--surface-border);
      border-radius: 16px;
      padding: 10px 14px;
      margin-bottom: 16px;
      display: flex;
      justify-content: space-around;
      font-size: 0.74rem;
      color: var(--text-dim);
    }
    .session-box strong {
      color: var(--text);
      display: block;
      margin-top: 2px;
      font-size: 0.82rem;
    }
    .actions {
      display: flex;
      flex-direction: column;
      gap: 9px;
    }
    .btn {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      gap: 8px;
      padding: 11px 16px;
      border-radius: 14px;
      font-size: 0.85rem;
      font-weight: 600;
      cursor: pointer;
      text-decoration: none;
      transition: all 0.2s ease;
      border: none;
    }
    .btn-app {
      background: rgba(124, 58, 237, 0.18);
      border: 1px solid rgba(124, 58, 237, 0.4);
      color: #c4b5fd;
    }
    .btn-app:hover {
      background: rgba(124, 58, 237, 0.28);
    }
    .btn-outline {
      background: transparent;
      color: var(--text-dim);
      border: 1px solid var(--surface-border);
    }
    .btn-outline:hover {
      color: var(--text);
      border-color: rgba(255, 255, 255, 0.22);
    }
    .toast {
      position: fixed;
      bottom: 24px;
      background: #10b981;
      color: white;
      padding: 8px 18px;
      border-radius: 20px;
      font-size: 0.8rem;
      font-weight: 600;
      opacity: 0;
      pointer-events: none;
      transition: opacity 0.3s ease;
      z-index: 100;
    }
    .toast.show { opacity: 1; }
  </style>
</head>
<body>
  <audio id="audioElement" preload="auto" playsinline></audio>

  <div class="player-card">
    <div class="header">
      <div class="brand">
        <div class="brand-icon">
          <svg viewBox="0 0 24 24"><path d="M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z"/></svg>
        </div>
        <span>AirBeats</span>
      </div>
      <div class="live-badge">
        <span class="live-dot"></span>
        <span>LAN LIVE</span>
      </div>
    </div>

    <div class="art-container">
      <img id="artImg" class="art-img" alt="Artwork" />
      <div id="artPlaceholder" class="art-placeholder">
        <svg viewBox="0 0 24 24"><path d="M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z"/></svg>
      </div>
    </div>

    <h1 id="trackTitle" class="track-title">AirBeats Session</h1>
    <p id="trackArtist" class="track-artist">Waiting for host to play music...</p>

    <div class="status-tag">
      <span id="waveAnim" class="wave-anim">
        <span></span><span></span><span></span>
      </span>
      <span id="statusText">Connecting...</span>
    </div>

    <!-- Hero Play / Listen Button -->
    <button id="heroPlayBtn" class="hero-btn" onclick="togglePlayback()">
      <span id="heroPlayIcon">▶</span>
      <span id="heroPlayText">Start Listening (Play Audio)</span>
    </button>

    <!-- Player Controls Bar -->
    <div class="player-controls">
      <input type="range" id="seekSlider" class="seek-slider" min="0" max="100" value="0" step="0.1" onchange="onSeekChange(this.value)" oninput="onSeekInput(this.value)" />
      <div class="time-row">
        <span id="curTime">00:00</span>
        <span id="durTime">--:--</span>
      </div>
      <div class="control-row">
        <div class="vol-wrap">
          <button class="vol-btn" onclick="toggleMute()" title="Mute/Unmute">
            <span id="volIcon">🔊</span>
          </button>
          <input type="range" id="volSlider" class="vol-slider" min="0" max="1" step="0.02" value="1" oninput="onVolumeChange(this.value)" title="Volume" />
        </div>
        <button class="btn-sync" onclick="syncWithHost()" title="Re-sync with Host">
          ⚡ Sync Live
        </button>
      </div>
    </div>

    <div class="session-box">
      <div>
        <span>Host</span>
        <strong id="hostName">$hostDisplayName</strong>
      </div>
      <div>
        <span>Session ID</span>
        <strong>$sessionId</strong>
      </div>
      <div>
        <span>Connected</span>
        <strong id="participantCount">1 Listener</strong>
      </div>
    </div>

    <div class="actions">
      <a id="appDeepLink" href="airbeats://together?host=$hostIp&port=$port&sid=$sessionId" class="btn btn-app">
        🎧 Open in AirBeats App
      </a>
      <button onclick="copyLink()" class="btn btn-outline">
        📋 Copy Browser Link
      </button>
      <button onclick="copyStreamLink()" class="btn btn-outline" style="font-size:0.78rem;">
        🎵 Copy Direct Stream URL (/stream)
      </button>
    </div>
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
    var volSlider = document.getElementById('volSlider');
    var volIcon = document.getElementById('volIcon');
    var waveAnim = document.getElementById('waveAnim');
    var statusText = document.getElementById('statusText');

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

    function togglePlayback() {
      if (!isAudioActivated) {
        activateAudio();
      } else {
        if (audio.paused) {
          isUserPaused = false;
          audio.play().catch(function(e) { console.warn('Play error:', e); });
        } else {
          isUserPaused = true;
          audio.pause();
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
        if (durTimeEl && audio.duration && !isNaN(audio.duration)) {
          durTimeEl.innerText = formatTime(audio.duration);
        }
      };

      if (!isUserPaused && state.isPlaying) {
        audio.play().catch(function(e) { console.warn('Audio play error:', e); });
      }
      updatePlayPauseUi();
    }

    function updatePlayPauseUi() {
      if (!isAudioActivated) {
        if (heroPlayIcon) heroPlayIcon.innerText = '▶';
        if (heroPlayText) heroPlayText.innerText = 'Start Listening (Play Audio)';
        if (waveAnim) waveAnim.style.display = 'none';
      } else if (audio.paused) {
        if (heroPlayIcon) heroPlayIcon.innerText = '▶';
        if (heroPlayText) heroPlayText.innerText = 'Resume Audio';
        if (waveAnim) waveAnim.style.display = 'none';
        if (statusText) statusText.innerText = isUserPaused ? 'Paused Locally' : 'Paused by Host';
      } else {
        if (heroPlayIcon) heroPlayIcon.innerText = '⏸';
        if (heroPlayText) heroPlayText.innerText = 'Listening Live (Tap to Pause)';
        if (waveAnim) waveAnim.style.display = 'inline-flex';
        if (statusText) statusText.innerText = 'Playing';
      }
    }

    function syncWithHost() {
      if (!lastServerState) return;
      var posMs = lastServerState.positionMs || 0;
      if (lastServerState.isPlaying && lastServerState.updatedAt) {
        posMs += Math.max(0, Date.now() - lastServerState.updatedAt);
      }
      var targetSec = Math.max(0, posMs / 1000);
      if (audio && audio.duration && targetSec < audio.duration) {
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
      if (volIcon) {
        volIcon.innerText = (audio.volume === 0 || audio.muted) ? '🔇' : (audio.volume < 0.5 ? '🔉' : '🔊');
      }
    }

    function toggleMute() {
      if (!audio) return;
      audio.muted = !audio.muted;
      if (volIcon) {
        volIcon.innerText = audio.muted ? '🔇' : (audio.volume < 0.5 ? '🔉' : '🔊');
      }
      if (volSlider && !audio.muted && audio.volume === 0) {
        audio.volume = 0.5;
        volSlider.value = 0.5;
      }
    }

    function onSeekInput(val) {
      isSeeking = true;
      if (audio && audio.duration) {
        var sec = (parseFloat(val) / 100) * audio.duration;
        if (curTimeEl) curTimeEl.innerText = formatTime(sec);
      }
    }

    function onSeekChange(val) {
      if (audio && audio.duration) {
        var sec = (parseFloat(val) / 100) * audio.duration;
        audio.currentTime = sec;
      }
      isSeeking = false;
    }

    if (audio) {
      audio.ontimeupdate = function() {
        if (!isSeeking && audio.duration && !isNaN(audio.duration)) {
          var pct = (audio.currentTime / audio.duration) * 100;
          if (seekSlider) seekSlider.value = pct;
          if (curTimeEl) curTimeEl.innerText = formatTime(audio.currentTime);
          if (durTimeEl) durTimeEl.innerText = formatTime(audio.duration);
        }
      };

      audio.onplay = function() { updatePlayPauseUi(); };
      audio.onpause = function() { updatePlayPauseUi(); };
      audio.onended = function() {
        if (waveAnim) waveAnim.style.display = 'none';
        if (statusText) statusText.innerText = 'Track Ended';
      };
    }

    function updateUi(data) {
      if (!data) return;
      var state = data.state;
      lastServerState = state;

      var count = data.participants || 1;
      var countEl = document.getElementById('participantCount');
      if (countEl) countEl.innerText = count + (count === 1 ? ' Listener' : ' Listeners');
      var hostEl = document.getElementById('hostName');
      if (hostEl) hostEl.innerText = data.hostName || 'AirBeats Host';

      var titleEl = document.getElementById('trackTitle');
      var artistEl = document.getElementById('trackArtist');
      var artImg = document.getElementById('artImg');
      var artPlaceholder = document.getElementById('artPlaceholder');

      if (!state || !state.title) {
        if (titleEl) titleEl.innerText = 'AirBeats Session';
        if (artistEl) artistEl.innerText = 'Waiting for host to play music...';
        if (statusText) statusText.innerText = 'Idle';
        if (waveAnim) waveAnim.style.display = 'none';
        if (artImg) artImg.style.display = 'none';
        if (artPlaceholder) artPlaceholder.style.display = 'flex';
        return;
      }

      if (titleEl) titleEl.innerText = state.title;
      var artistStr = (state.artists && state.artists.length) ? state.artists.join(', ') : 'AirBeats';
      if (artistEl) artistEl.innerText = artistStr;
      document.title = (state.isPlaying ? '\u25B6 ' : '\u23F8 ') + state.title + ' \u2022 AirBeats';

      if (state.thumbnailUrl) {
        if (artImg && artImg.src !== state.thumbnailUrl) artImg.src = state.thumbnailUrl;
        if (artImg) artImg.style.display = 'block';
        if (artPlaceholder) artPlaceholder.style.display = 'none';
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
          if (state.isPlaying && audio.paused) {
            audio.play().catch(function(){});
          } else if (!state.isPlaying && !audio.paused) {
            audio.pause();
          }

          if (state.isPlaying && !isSeeking) {
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
        if (statusText) statusText.innerText = 'Reconnecting...';
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
          showToast('Browser link copied!');
        }).catch(function() {
          showToast('Copied: ' + url);
        });
      } else {
        showToast('Link: ' + url);
      }
    }

    function copyStreamLink() {
      var streamUrl = window.location.origin + '/stream';
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
