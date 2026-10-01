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

data class LanQueueItem(
    val index: Int,
    val id: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val thumbnailUrl: String?,
)

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

    var queueItemsProvider: (() -> List<LanQueueItem>)? = null
    var currentQueueIndexProvider: (() -> Int)? = null
    var lyricsResolver: (suspend (songId: String) -> String?)? = null

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

                uri == "/together/queue" && method == Method.GET -> {
                    val items = queueItemsProvider?.invoke().orEmpty()
                    val curIdx = currentQueueIndexProvider?.invoke() ?: 0
                    val jsonArr = org.json.JSONArray()
                    items.forEach { item ->
                        val obj = JSONObject().apply {
                            put("index", item.index)
                            put("id", item.id)
                            put("title", item.title)
                            put("artist", item.artist)
                            put("durationMs", item.durationMs)
                            put("thumbnailUrl", item.thumbnailUrl ?: "")
                            put("isCurrent", item.index == curIdx)
                            put("isPlayed", item.index < curIdx)
                        }
                        jsonArr.put(obj)
                    }
                    val res = JSONObject().apply {
                        put("currentIndex", curIdx)
                        put("queue", jsonArr)
                    }
                    jsonResponse(res)
                }

                uri == "/together/lyrics" && method == Method.GET -> {
                    val reqId = session.parms["id"]?.takeIf { it.isNotBlank() } ?: playbackState?.songId.orEmpty()
                    val lyrics = if (reqId.isNotBlank()) {
                        try {
                            runBlocking(Dispatchers.IO) {
                                lyricsResolver?.invoke(reqId)
                            }
                        } catch (e: Exception) {
                            null
                        }
                    } else null
                    val res = JSONObject().apply {
                        put("songId", reqId)
                        put("lyrics", lyrics ?: "")
                    }
                    jsonResponse(res)
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
                        val cmdPos = if (action == "play_index") {
                            body.optLong("index", positionMs ?: 0L)
                        } else {
                            positionMs
                        }
                        onPlaybackCommand?.invoke(action, cmdPos)
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
<html lang="en" data-theme="dark">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
  <title>AirBeats - Listen Together</title>
  <link rel="icon" href="https://raw.githubusercontent.com/drkvenom786/Airbeats/refs/heads/main/icon2.png" type="image/png">
  <style>
    :root {
      --bg: #090a12;
      --card-bg: rgba(18, 20, 32, 0.78);
      --card-solid: #121420;
      --text: #f8fafc;
      --text-muted: #94a3b8;
      --border: rgba(255, 255, 255, 0.1);
      --pill-bg: rgba(255, 255, 255, 0.08);
      --pill-active: #ffffff;
      --pill-active-text: #090a12;
      --btn-primary: #ffffff;
      --btn-text: #090a12;
      --accent: #6366f1;
      --accent-glow: rgba(99, 102, 241, 0.35);
      --track-bg: rgba(255, 255, 255, 0.12);
      --track-fill: #ffffff;
      --thumb-color: #ffffff;
      --success: #10b981;
      --circle-btn: rgba(255, 255, 255, 0.08);
      --circle-btn-border: rgba(255, 255, 255, 0.12);
    }

    [data-theme="light"] {
      --bg: #f1f3f7;
      --card-bg: rgba(255, 255, 255, 0.88);
      --card-solid: #ffffff;
      --text: #0f172a;
      --text-muted: #64748b;
      --border: rgba(0, 0, 0, 0.08);
      --pill-bg: rgba(0, 0, 0, 0.05);
      --pill-active: #0f172a;
      --pill-active-text: #ffffff;
      --btn-primary: #0f172a;
      --btn-text: #ffffff;
      --accent: #4f46e5;
      --accent-glow: rgba(79, 70, 229, 0.25);
      --track-bg: rgba(0, 0, 0, 0.08);
      --track-fill: #0f172a;
      --thumb-color: #0f172a;
      --success: #059669;
      --circle-btn: #ffffff;
      --circle-btn-border: rgba(0, 0, 0, 0.1);
    }

    * { box-sizing: border-box; margin: 0; padding: 0; -webkit-tap-highlight-color: transparent; }

    body {
      background: var(--bg);
      color: var(--text);
      font-family: -apple-system, BlinkMacSystemFont, "SF Pro Display", "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
      min-height: 100vh;
      display: flex;
      justify-content: center;
      padding: 0;
      overflow-x: hidden;
      transition: background-color 0.25s ease, color 0.25s ease;
    }

    /* Ambient Dynamic Blur Backdrop */
    .ambient-bg {
      position: fixed;
      inset: -40px;
      background-size: cover;
      background-position: center;
      filter: blur(80px) saturate(1.8) brightness(0.35);
      z-index: -2;
      transform: scale(1.15);
      transition: background-image 0.8s cubic-bezier(0.4, 0, 0.2, 1);
      opacity: 0.85;
      pointer-events: none;
    }

    [data-theme="light"] .ambient-bg {
      filter: blur(90px) saturate(1.4) brightness(0.9);
      opacity: 0.45;
    }

    .ambient-overlay {
      position: fixed;
      inset: 0;
      background: radial-gradient(circle at 50% 20%, transparent 20%, var(--bg) 90%);
      z-index: -1;
      pointer-events: none;
    }

    .app-container {
      width: 100%;
      max-width: 480px;
      min-height: 100vh;
      background: var(--card-bg);
      backdrop-filter: blur(28px);
      -webkit-backdrop-filter: blur(28px);
      border-left: 1px solid var(--border);
      border-right: 1px solid var(--border);
      padding: 16px 20px 28px;
      display: flex;
      flex-direction: column;
      position: relative;
      box-shadow: 0 20px 60px rgba(0, 0, 0, 0.25);
    }

    /* Top Bar Header */
    .top-bar {
      display: flex;
      align-items: center;
      justify-content: space-between;
      height: 48px;
      margin-bottom: 12px;
    }

    .brand-wrap {
      display: flex;
      align-items: center;
      gap: 10px;
    }

    .brand-icon {
      width: 36px;
      height: 36px;
      border-radius: 50%;
      overflow: hidden;
      background: #000;
      display: flex;
      align-items: center;
      justify-content: center;
      box-shadow: 0 4px 12px rgba(0, 0, 0, 0.2);
    }

    .brand-icon img {
      width: 100%;
      height: 100%;
      object-fit: cover;
    }

    .brand-name {
      font-size: 1.15rem;
      font-weight: 800;
      letter-spacing: 0.5px;
      color: var(--text);
    }

    .top-actions {
      display: flex;
      align-items: center;
      gap: 8px;
    }

    .circle-btn {
      width: 38px;
      height: 38px;
      border-radius: 50%;
      background: var(--circle-btn);
      border: 1px solid var(--circle-btn-border);
      display: flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
      color: var(--text);
      transition: transform 0.15s ease, background 0.15s ease;
      box-shadow: 0 2px 6px rgba(0, 0, 0, 0.06);
    }

    .circle-btn:active {
      transform: scale(0.92);
    }

    .circle-btn svg {
      width: 18px;
      height: 18px;
      fill: currentColor;
    }

    /* Segmented Navigation Tab Bar */
    .nav-tabs {
      display: flex;
      background: var(--pill-bg);
      border: 1px solid var(--border);
      border-radius: 20px;
      padding: 4px;
      margin-bottom: 16px;
      backdrop-filter: blur(12px);
      gap: 4px;
    }

    .tab-btn {
      flex: 1;
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 6px;
      padding: 8px 10px;
      border-radius: 16px;
      border: none;
      background: transparent;
      color: var(--text-muted);
      font-weight: 700;
      font-size: 0.84rem;
      cursor: pointer;
      transition: all 0.2s cubic-bezier(0.4, 0, 0.2, 1);
    }

    .tab-btn svg {
      width: 16px;
      height: 16px;
      fill: currentColor;
      transition: transform 0.2s;
    }

    .tab-btn.active {
      background: var(--pill-active);
      color: var(--pill-active-text);
      box-shadow: 0 3px 12px rgba(0, 0, 0, 0.14);
    }

    .tab-badge {
      font-size: 0.72rem;
      padding: 2px 6px;
      border-radius: 10px;
      background: var(--pill-bg);
      color: var(--text);
      font-weight: 800;
    }

    .tab-btn.active .tab-badge {
      background: rgba(0, 0, 0, 0.15);
      color: var(--pill-active-text);
    }

    /* Tab Views */
    .tab-view {
      display: none;
      flex-direction: column;
      animation: fadeIn 0.2s ease;
    }

    .tab-view.active {
      display: flex;
    }

    @keyframes fadeIn {
      from { opacity: 0; transform: translateY(4px); }
      to { opacity: 1; transform: translateY(0); }
    }

    /* Dropdown Menu */
    .menu-dropdown {
      position: absolute;
      top: 60px;
      right: 20px;
      background: var(--card-solid);
      border: 1px solid var(--border);
      border-radius: 18px;
      box-shadow: 0 16px 36px rgba(0,0,0,0.28);
      padding: 8px;
      display: none;
      flex-direction: column;
      gap: 4px;
      z-index: 100;
      min-width: 220px;
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

    /* ── TAB 1: PLAYER VIEW ── */
    .art-container {
      position: relative;
      width: 100%;
      aspect-ratio: 1 / 1;
      border-radius: 28px;
      overflow: hidden;
      box-shadow: 0 16px 36px rgba(0, 0, 0, 0.24);
      background: rgba(0, 0, 0, 0.3);
      margin-bottom: 18px;
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

    .artist-row {
      display: inline-flex;
      align-items: center;
      gap: 8px;
      margin-bottom: 6px;
      text-decoration: none;
      color: var(--text);
    }

    .artist-avatar {
      width: 26px;
      height: 26px;
      border-radius: 50%;
      background: var(--pill-bg);
      display: flex;
      align-items: center;
      justify-content: center;
      color: var(--text-muted);
    }

    .artist-avatar svg {
      width: 14px;
      height: 14px;
      fill: currentColor;
    }

    .artist-name {
      font-size: 1.05rem;
      font-weight: 700;
      color: var(--text);
    }

    .track-title {
      font-size: 1.6rem;
      font-weight: 800;
      line-height: 1.25;
      letter-spacing: -0.3px;
      color: var(--text);
      margin-bottom: 10px;
      display: -webkit-box;
      -webkit-line-clamp: 2;
      -webkit-box-orient: vertical;
      overflow: hidden;
      word-break: break-word;
    }

    .meta-row {
      display: flex;
      align-items: center;
      gap: 10px;
      margin-bottom: 16px;
      font-size: 0.82rem;
      color: var(--text-muted);
      font-weight: 600;
    }

    .listener-pill {
      display: inline-flex;
      align-items: center;
      gap: 5px;
      padding: 4px 10px;
      border-radius: 12px;
      background: var(--pill-bg);
      color: var(--text);
      font-weight: 700;
    }

    .listener-pill svg {
      width: 14px;
      height: 14px;
      fill: currentColor;
    }

    .quality-tag {
      padding: 4px 10px;
      border-radius: 12px;
      background: var(--pill-bg);
      font-weight: 700;
      color: var(--text-muted);
    }

    .wave-anim {
      display: none;
      align-items: flex-end;
      gap: 2px;
      height: 12px;
      margin-left: 4px;
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

    /* SEEK BAR */
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

    /* CONTROLS DECK (PREV / HERO PLAY / NEXT) */
    .controls-deck {
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 16px;
      margin-bottom: 16px;
    }

    .ctrl-circle-btn {
      width: 48px;
      height: 48px;
      border-radius: 50%;
      background: var(--circle-btn);
      border: 1px solid var(--circle-btn-border);
      display: flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
      color: var(--text);
      transition: transform 0.15s ease, background 0.15s ease;
    }

    .ctrl-circle-btn:active {
      transform: scale(0.92);
    }

    .ctrl-circle-btn svg {
      width: 22px;
      height: 22px;
      fill: currentColor;
    }

    .hero-btn {
      flex: 1;
      max-width: 220px;
      height: 54px;
      border-radius: 27px;
      background: var(--btn-primary);
      color: var(--btn-text);
      font-size: 1rem;
      font-weight: 700;
      border: none;
      cursor: pointer;
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 10px;
      box-shadow: 0 4px 16px rgba(0, 0, 0, 0.16);
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

    /* Volume & Live Sync Row */
    .controls-sub-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      margin-bottom: 16px;
      padding: 0 2px;
    }

    .vol-wrap {
      display: flex;
      align-items: center;
      gap: 8px;
      flex: 1;
      max-width: 210px;
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

    /* ── TAB 2: QUEUE VIEW ── */
    .queue-header-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      margin-bottom: 14px;
    }

    .queue-title-wrap {
      display: flex;
      align-items: center;
      gap: 8px;
    }

    .queue-title {
      font-size: 1.15rem;
      font-weight: 800;
      color: var(--text);
    }

    .btn-share-queue {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 7px 14px;
      border-radius: 20px;
      background: var(--pill-bg);
      border: 1px solid var(--border);
      color: var(--text);
      font-size: 0.8rem;
      font-weight: 700;
      cursor: pointer;
      transition: all 0.15s ease;
    }

    .btn-share-queue:hover {
      background: var(--accent);
      color: #ffffff;
      border-color: var(--accent);
    }

    .btn-share-queue svg {
      width: 14px;
      height: 14px;
      fill: currentColor;
    }

    .queue-list-container {
      display: flex;
      flex-direction: column;
      gap: 8px;
      max-height: 520px;
      overflow-y: auto;
      padding-right: 4px;
    }

    .queue-list-container::-webkit-scrollbar {
      width: 4px;
    }
    .queue-list-container::-webkit-scrollbar-track {
      background: transparent;
    }
    .queue-list-container::-webkit-scrollbar-thumb {
      background: var(--border);
      border-radius: 4px;
    }

    .queue-section-label {
      font-size: 0.78rem;
      font-weight: 800;
      letter-spacing: 0.6px;
      text-transform: uppercase;
      color: var(--text-muted);
      margin-top: 10px;
      margin-bottom: 4px;
      display: flex;
      align-items: center;
      gap: 6px;
    }

    .queue-item {
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 10px 12px;
      border-radius: 16px;
      background: var(--pill-bg);
      border: 1px solid transparent;
      transition: all 0.15s ease;
      cursor: pointer;
    }

    .queue-item:hover {
      border-color: var(--border);
      transform: translateX(2px);
    }

    .queue-item.current {
      background: rgba(99, 102, 241, 0.12);
      border-color: var(--accent);
    }

    .queue-item.played {
      opacity: 0.65;
    }

    .queue-thumb {
      width: 44px;
      height: 44px;
      border-radius: 10px;
      object-fit: cover;
      background: #181926;
      flex-shrink: 0;
    }

    .queue-item-info {
      flex: 1;
      min-width: 0;
    }

    .queue-item-title {
      font-size: 0.92rem;
      font-weight: 700;
      color: var(--text);
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }

    .queue-item-artist {
      font-size: 0.78rem;
      color: var(--text-muted);
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
      margin-top: 2px;
    }

    .queue-item-btn {
      width: 32px;
      height: 32px;
      border-radius: 50%;
      background: transparent;
      border: none;
      display: flex;
      align-items: center;
      justify-content: center;
      color: var(--text-muted);
      cursor: pointer;
      flex-shrink: 0;
      transition: color 0.15s;
    }

    .queue-item-btn:hover {
      color: var(--text);
    }

    .queue-item-btn svg {
      width: 16px;
      height: 16px;
      fill: currentColor;
    }

    /* ── TAB 3: LYRICS VIEW ── */
    .lyrics-card {
      display: flex;
      flex-direction: column;
      min-height: 480px;
      max-height: 540px;
      border-radius: 24px;
      background: var(--pill-bg);
      border: 1px solid var(--border);
      padding: 18px 16px;
      position: relative;
    }

    .lyrics-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      margin-bottom: 14px;
      padding-bottom: 10px;
      border-bottom: 1px solid var(--border);
    }

    .lyrics-header-title {
      font-size: 0.9rem;
      font-weight: 800;
      color: var(--text);
      display: flex;
      align-items: center;
      gap: 6px;
    }

    .lyrics-scroll-box {
      flex: 1;
      overflow-y: auto;
      padding: 20px 8px 120px;
      display: flex;
      flex-direction: column;
      gap: 16px;
      scroll-behavior: smooth;
    }

    .lyrics-scroll-box::-webkit-scrollbar {
      width: 4px;
    }
    .lyrics-scroll-box::-webkit-scrollbar-thumb {
      background: var(--border);
      border-radius: 4px;
    }

    .lyric-line {
      font-size: 1.18rem;
      line-height: 1.5;
      font-weight: 600;
      color: var(--text-muted);
      cursor: pointer;
      transition: all 0.25s cubic-bezier(0.4, 0, 0.2, 1);
      transform-origin: left center;
      padding: 4px 6px;
      border-radius: 10px;
    }

    .lyric-line:hover {
      color: var(--text);
      background: var(--pill-bg);
    }

    .lyric-line.active {
      font-size: 1.45rem;
      font-weight: 800;
      color: var(--text);
      transform: scale(1.03);
      text-shadow: 0 0 18px var(--accent-glow);
    }

    .lyrics-empty {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      height: 320px;
      gap: 14px;
      color: var(--text-muted);
      text-align: center;
    }

    .lyrics-empty svg {
      width: 48px;
      height: 48px;
      fill: currentColor;
      opacity: 0.4;
    }

    /* BOTTOM ACTIONS (OPEN IN APP & SHARE) */
    .bottom-actions {
      display: flex;
      align-items: center;
      gap: 10px;
      margin-top: auto;
      padding-top: 14px;
    }

    .btn-open-app {
      flex: 1;
      height: 50px;
      border-radius: 25px;
      background: var(--btn-primary);
      color: var(--btn-text);
      font-size: 0.95rem;
      font-weight: 700;
      border: none;
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      gap: 8px;
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
      width: 50px;
      height: 50px;
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
      width: 20px;
      height: 20px;
      fill: currentColor;
    }

    .footer-caption {
      text-align: center;
      font-size: 0.74rem;
      color: var(--text-muted);
      margin-top: 12px;
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
      padding: 9px 20px;
      border-radius: 24px;
      font-size: 0.84rem;
      font-weight: 700;
      opacity: 0;
      pointer-events: none;
      transition: opacity 0.25s ease, transform 0.25s ease;
      z-index: 200;
      box-shadow: 0 6px 18px rgba(0,0,0,0.22);
    }
    .toast.show {
      opacity: 1;
      transform: translateX(-50%) translateY(-6px);
    }
  </style>
</head>
<body>
  <!-- Dynamic Ambient Artwork Backdrop -->
  <div id="ambientBg" class="ambient-bg"></div>
  <div class="ambient-overlay"></div>

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

      <div class="top-actions">
        <button class="circle-btn" onclick="toggleTheme()" aria-label="Toggle Theme" title="Theme">
          <svg id="themeIcon" viewBox="0 0 24 24"><path d="M12 3c-4.97 0-9 4.03-9 9s4.03 9 9 9 9-4.03 9-9c0-.46-.04-.92-.1-1.36-.98 1.37-2.58 2.26-4.4 2.26-2.98 0-5.4-2.42-5.4-5.4 0-1.81.89-3.42 2.26-4.4-.44-.06-.9-.1-1.36-.1z"/></svg>
        </button>
        <button class="circle-btn" onclick="toggleMenu()" aria-label="Menu" title="More">
          <svg viewBox="0 0 24 24"><path d="M12 8c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm0 2c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z"/></svg>
        </button>
      </div>

      <!-- Dropdown Menu -->
      <div id="menuDropdown" class="menu-dropdown">
        <button class="menu-item" onclick="syncWithHost()">
          <svg viewBox="0 0 24 24"><path d="M12 4V1L8 5l4 4V6c3.31 0 6 2.69 6 6 0 1.01-.25 1.97-.7 2.8l1.46 1.46C19.54 15.03 20 13.57 20 12c0-4.42-3.58-8-8-8zm0 14c-3.31 0-6-2.69-6-6 0-1.01.25-1.97.7-2.8L5.24 7.74C4.46 8.97 4 10.43 4 12c0 4.42 3.58 8 8 8v3l4-4-4-4v3z"/></svg>
          <span>Sync with Host</span>
        </button>
        <button class="menu-item" onclick="copyStreamLink()">
          <svg viewBox="0 0 24 24"><path d="M3.9 12c0-1.71 1.39-3.1 3.1-3.1h4V7H7c-2.76 0-5 2.24-5 5s2.24 5 5 5h4v-1.9H7c-1.71 0-3.1-1.39-3.1-3.1zM8 13h8v-2H8v2zm9-6h-4v1.9h4c1.71 0 3.1 1.39 3.1 3.1s-1.39 3.1-3.1 3.1h-4V17h4c2.76 0 5-2.24 5-5s-2.24-5-5-5z"/></svg>
          <span>Copy Stream URL</span>
        </button>
        <button class="menu-item" onclick="shareQueue()">
          <svg viewBox="0 0 24 24"><path d="M18 16.08c-.76 0-1.44.3-1.96.77L8.91 12.7c.05-.23.09-.46.09-.7s-.04-.47-.09-.7l7.05-4.11c.54.5 1.25.81 2.04.81 1.66 0 3-1.34 3-3s-1.34-3-3-3-3 1.34-3 3c0 .24.04.47.09.7L8.04 9.81C7.5 9.31 6.79 9 6 9c-1.66 0-3 1.34-3 3s1.34 3 3 3c.79 0 1.5-.31 2.04-.81l7.12 4.16c-.05.21-.08.43-.08.65 0 1.61 1.31 2.92 2.92 2.92 1.61 0 2.92-1.31 2.92-2.92s-1.31-2.92-2.92-2.92z"/></svg>
          <span>Share Queue</span>
        </button>
        <a id="menuAppDeepLink" href="airbeats://together?host=$hostIp&port=$port&sid=$sessionId" class="menu-item">
          <svg viewBox="0 0 24 24"><path d="M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z"/></svg>
          <span>Open in AirBeats App</span>
        </a>
      </div>
    </div>

    <!-- Segmented Navigation Tabs -->
    <div class="nav-tabs">
      <button id="tabBtnPlayer" class="tab-btn active" onclick="switchTab('player')">
        <svg viewBox="0 0 24 24"><path d="M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z"/></svg>
        <span>Player</span>
      </button>
      <button id="tabBtnQueue" class="tab-btn" onclick="switchTab('queue')">
        <svg viewBox="0 0 24 24"><path d="M4 6H2v14c0 1.1.9 2 2 2h14v-2H4V6zm16-4H8c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-1 9H9V9h10v2zm-4 4H9v-2h6v2zm4-8H9V5h10v2z"/></svg>
        <span>Queue</span>
        <span id="queueBadge" class="tab-badge" style="display:none;">0</span>
      </button>
      <button id="tabBtnLyrics" class="tab-btn" onclick="switchTab('lyrics')">
        <svg viewBox="0 0 24 24"><path d="M12 14c1.66 0 3-1.34 3-3V5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3zm5.3-3c0 3-2.54 5.1-5.3 5.1S6.7 14 6.7 11H5c0 3.41 2.72 6.23 6 6.72V21h2v-3.28c3.28-.48 6-3.3 6-6.72h-1.7z"/></svg>
        <span>Lyrics</span>
      </button>
    </div>

    <!-- ── TAB 1: PLAYER ── -->
    <div id="viewPlayer" class="tab-view active">
      <!-- Artwork Container -->
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
      </div>

      <!-- Track Title -->
      <h1 id="trackTitle" class="track-title">AirBeats Session</h1>

      <!-- Meta / Badges Row -->
      <div class="meta-row">
        <div class="listener-pill">
          <svg viewBox="0 0 24 24"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-1 14H9V8h2v8zm4 0h-2V8h2v8z"/></svg>
          <span id="participantCount">1 listener</span>
        </div>
        <span class="quality-tag">Lossless LAN</span>
        <span id="statusTag">Connecting...</span>
        <div id="waveAnim" class="wave-anim">
          <span></span><span></span><span></span>
        </div>
      </div>

      <!-- Seek Bar -->
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

      <!-- Controls Deck (Previous, Main Play/Pause, Next) -->
      <div class="controls-deck">
        <button class="ctrl-circle-btn" onclick="prevTrack()" title="Previous Track">
          <svg viewBox="0 0 24 24"><path d="M6 6h2v12H6zm3.5 6l8.5 6V6z"/></svg>
        </button>

        <button id="heroPlayBtn" class="hero-btn" onclick="togglePlayback()">
          <span id="heroPlayIcon" class="btn-icon-wrap">
            <svg viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg>
          </span>
          <span id="heroPlayText">Start Listening</span>
        </button>

        <button class="ctrl-circle-btn" onclick="nextTrack()" title="Next Track">
          <svg viewBox="0 0 24 24"><path d="M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z"/></svg>
        </button>
      </div>

      <!-- Volume & Live Sync Row -->
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
    </div>

    <!-- ── TAB 2: QUEUE ── -->
    <div id="viewQueue" class="tab-view">
      <div class="queue-header-row">
        <div class="queue-title-wrap">
          <span class="queue-title">Playback Queue</span>
        </div>
        <button class="btn-share-queue" onclick="shareQueue()" title="Share complete queue">
          <svg viewBox="0 0 24 24"><path d="M18 16.08c-.76 0-1.44.3-1.96.77L8.91 12.7c.05-.23.09-.46.09-.7s-.04-.47-.09-.7l7.05-4.11c.54.5 1.25.81 2.04.81 1.66 0 3-1.34 3-3s-1.34-3-3-3-3 1.34-3 3c0 .24.04.47.09.7L8.04 9.81C7.5 9.31 6.79 9 6 9c-1.66 0-3 1.34-3 3s1.34 3 3 3c.79 0 1.5-.31 2.04-.81l7.12 4.16c-.05.21-.08.43-.08.65 0 1.61 1.31 2.92 2.92 2.92 1.61 0 2.92-1.31 2.92-2.92s-1.31-2.92-2.92-2.92z"/></svg>
          <span>Share Queue</span>
        </button>
      </div>

      <div id="queueListContainer" class="queue-list-container">
        <!-- Rendered dynamically by updateQueueUi() -->
        <div class="lyrics-empty">
          <svg viewBox="0 0 24 24"><path d="M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z"/></svg>
          <p>Loading queue...</p>
        </div>
      </div>
    </div>

    <!-- ── TAB 3: LYRICS ── -->
    <div id="viewLyrics" class="tab-view">
      <div class="lyrics-card">
        <div class="lyrics-header">
          <span class="lyrics-header-title">
            <svg style="width:16px;height:16px;fill:currentColor" viewBox="0 0 24 24"><path d="M12 14c1.66 0 3-1.34 3-3V5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3zm5.3-3c0 3-2.54 5.1-5.3 5.1S6.7 14 6.7 11H5c0 3.41 2.72 6.23 6 6.72V21h2v-3.28c3.28-.48 6-3.3 6-6.72h-1.7z"/></svg>
            <span>Live Synced Lyrics</span>
          </span>
          <button class="circle-btn" style="width:30px;height:30px" onclick="reloadLyrics()" title="Refresh Lyrics">
            <svg style="width:14px;height:14px" viewBox="0 0 24 24"><path d="M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08c-.82 2.33-3.04 4-5.65 4-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z"/></svg>
          </button>
        </div>

        <div id="lyricsScrollBox" class="lyrics-scroll-box">
          <div class="lyrics-empty">
            <svg viewBox="0 0 24 24"><path d="M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z"/></svg>
            <p>Fetching lyrics...</p>
          </div>
        </div>
      </div>
    </div>

    <!-- Bottom Actions Row -->
    <div class="bottom-actions">
      <a id="appDeepLink" href="airbeats://together?host=$hostIp&port=$port&sid=$sessionId" class="btn-open-app">
        <img src="https://raw.githubusercontent.com/drkvenom786/Airbeats/refs/heads/main/icon2.png" alt="Logo" />
        <span>Open in AirBeats</span>
      </a>

      <button class="bottom-circle-btn" onclick="copyLink()" title="Share Session Link">
        <svg viewBox="0 0 24 24"><path d="M18 16.08c-.76 0-1.44.3-1.96.77L8.91 12.7c.05-.23.09-.46.09-.7s-.04-.47-.09-.7l7.05-4.11c.54.5 1.25.81 2.04.81 1.66 0 3-1.34 3-3s-1.34-3-3-3-3 1.34-3 3c0 .24.04.47.09.7L8.04 9.81C7.5 9.31 6.79 9 6 9c-1.66 0-3 1.34-3 3s1.34 3 3 3c.79 0 1.5-.31 2.04-.81l7.12 4.16c-.05.21-.08.43-.08.65 0 1.61 1.31 2.92 2.92 2.92 1.61 0 2.92-1.31 2.92-2.92s-1.31-2.92-2.92-2.92z"/></svg>
      </button>
    </div>

    <p class="footer-caption">AirBeats Lossless Audio Streaming • Host: $hostDisplayName</p>
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
    var ambientBg = document.getElementById('ambientBg');
    var lyricsScrollBox = document.getElementById('lyricsScrollBox');
    var queueListContainer = document.getElementById('queueListContainer');
    var queueBadge = document.getElementById('queueBadge');

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
    var activeTab = 'player';
    var cachedQueue = [];
    var parsedLyrics = [];
    var lyricsSongId = null;
    var activeLyricIndex = -1;

    function formatTime(sec) {
      if (!sec || isNaN(sec) || sec < 0) sec = 0;
      sec = Math.floor(sec);
      var m = Math.floor(sec / 60);
      var s = sec % 60;
      return (m < 10 ? '0' : '') + m + ':' + (s < 10 ? '0' : '') + s;
    }

    /* Tab Switching */
    function switchTab(tab) {
      activeTab = tab;
      ['player', 'queue', 'lyrics'].forEach(function(t) {
        var view = document.getElementById('view' + t.charAt(0).toUpperCase() + t.slice(1));
        var btn = document.getElementById('tabBtn' + t.charAt(0).toUpperCase() + t.slice(1));
        if (view) view.classList.toggle('active', t === tab);
        if (btn) btn.classList.toggle('active', t === tab);
      });
      if (tab === 'queue') {
        fetchQueue();
      } else if (tab === 'lyrics') {
        if (currentSongId && currentSongId !== lyricsSongId) {
          fetchLyrics(currentSongId);
        }
      }
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
      var next = (current === 'light') ? 'dark' : 'light';
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

    function prevTrack() {
      sendPlaybackAction('prev');
      showToast('Previous track');
    }

    function nextTrack() {
      sendPlaybackAction('next');
      showToast('Next track');
    }

    function playQueueIndex(idx) {
      sendPlaybackAction('play_index', idx);
      showToast('Playing track #' + (idx + 1));
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
      fetchLyrics(songId);
      fetchQueue();
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

    /* Real-Time Timeline and Lyrics Synchronization Loop */
    function tickTimeline() {
      if (!lastServerState) return;

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

      if (!isSeeking) {
        if (curTimeEl) curTimeEl.innerText = formatTime(currentMs / 1000);
        if (durTimeEl) durTimeEl.innerText = (durationMs > 0) ? formatTime(durationMs / 1000) : '--:--';

        if (durationMs > 0) {
          var pct = Math.min(100, Math.max(0, (currentMs / durationMs) * 100));
          if (seekSlider) seekSlider.value = pct;
          if (progressFill) progressFill.style.width = pct + '%';
          if (progressThumb) progressThumb.style.left = pct + '%';
        }
      }

      // Sync active lyric line
      syncLyricsWithTime(currentMs / 1000);
    }

    setInterval(tickTimeline, 100);

    /* ── QUEUE MANAGEMENT & SHARE QUEUE ── */
    function fetchQueue() {
      var xhr = new XMLHttpRequest();
      xhr.open('GET', '/together/queue', true);
      xhr.onload = function() {
        if (xhr.status >= 200 && xhr.status < 300) {
          try {
            var data = JSON.parse(xhr.responseText);
            cachedQueue = data.queue || [];
            updateQueueUi(cachedQueue, data.currentIndex || 0);
          } catch (e) {}
        }
      };
      xhr.send();
    }

    function updateQueueUi(queue, currentIndex) {
      if (!queueListContainer) return;
      if (queueBadge) {
        if (queue.length > 0) {
          queueBadge.style.display = 'inline-block';
          queueBadge.innerText = queue.length;
        } else {
          queueBadge.style.display = 'none';
        }
      }

      if (!queue || queue.length === 0) {
        queueListContainer.innerHTML = '<div class="lyrics-empty"><p>Queue is empty</p></div>';
        return;
      }

      var html = '';
      var played = [];
      var current = null;
      var upcoming = [];

      queue.forEach(function(item) {
        if (item.index < currentIndex) {
          played.push(item);
        } else if (item.index === currentIndex) {
          current = item;
        } else {
          upcoming.push(item);
        }
      });

      // 1. Previously Played Section
      if (played.length > 0) {
        html += '<div class="queue-section-label">';
        html += '<svg style="width:14px;height:14px;fill:currentColor" viewBox="0 0 24 24"><path d="M13 3a9 9 0 0 0-9 9H1l3.89 3.89.07.14L9 12H6c0-3.87 3.13-7 7-7s7 3.13 7 7-3.13 7-7 7c-1.93 0-3.68-.79-4.94-2.06l-1.42 1.42A8.954 8.954 0 0 0 13 21a9 9 0 0 0 0-18zm-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z"/></svg>';
        html += '<span>Previously Played (' + played.length + ')</span></div>';
        played.forEach(function(item) {
          html += renderQueueRow(item, 'played');
        });
      }

      // 2. Now Playing Section
      if (current) {
        html += '<div class="queue-section-label" style="color:var(--accent)">';
        html += '<svg style="width:14px;height:14px;fill:currentColor" viewBox="0 0 24 24"><path d="M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z"/></svg>';
        html += '<span>Now Playing</span></div>';
        html += renderQueueRow(current, 'current');
      }

      // 3. Up Next Section
      html += '<div class="queue-section-label">';
      html += '<svg style="width:14px;height:14px;fill:currentColor" viewBox="0 0 24 24"><path d="M4 6H2v14c0 1.1.9 2 2 2h14v-2H4V6zm16-4H8c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-1 9H9V9h10v2zm-4 4H9v-2h6v2zm4-8H9V5h10v2z"/></svg>';
      html += '<span>Up Next (' + upcoming.length + ')</span></div>';
      if (upcoming.length > 0) {
        upcoming.forEach(function(item) {
          html += renderQueueRow(item, 'upcoming');
        });
      } else {
        html += '<p style="font-size:0.82rem;color:var(--text-muted);padding:8px 12px;">No more tracks up next</p>';
      }

      queueListContainer.innerHTML = html;
    }

    function renderQueueRow(item, type) {
      var thumb = item.thumbnailUrl ? '<img class="queue-thumb" src="' + item.thumbnailUrl + '" alt="" onerror="this.style.display=\'none\'" />' : '<div class="queue-thumb" style="display:flex;align-items:center;justify-content:center"><svg style="width:18px;height:18px;fill:var(--text-muted)" viewBox="0 0 24 24"><path d="M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z"/></svg></div>';
      var cls = 'queue-item ' + type;
      var btnIcon = (type === 'current') ? pauseSvg : playSvg;
      var clickAction = 'playQueueIndex(' + item.index + ')';

      return '<div class="' + cls + '" onclick="' + clickAction + '">' +
        thumb +
        '<div class="queue-item-info">' +
          '<div class="queue-item-title">' + escapeHtml(item.title) + '</div>' +
          '<div class="queue-item-artist">' + escapeHtml(item.artist) + '</div>' +
        '</div>' +
        '<button class="queue-item-btn" onclick="event.stopPropagation();' + clickAction + '">' +
          btnIcon +
        '</button>' +
      '</div>';
    }

    function shareQueue() {
      if (menuDropdown) menuDropdown.classList.remove('show');
      if (!cachedQueue || cachedQueue.length === 0) {
        showToast('Queue is currently empty');
        return;
      }

      var text = '🎶 AirBeats Queue (' + cachedQueue.length + ' tracks):
' +
        cachedQueue.map(function(t, i) {
          return (i + 1) + '. ' + t.title + ' - ' + t.artist;
        }).join('
') +
        '

Listen along: ' + window.location.href;

      if (navigator.share) {
        navigator.share({
          title: 'AirBeats Queue',
          text: text,
          url: window.location.href
        }).catch(function() {
          copyTextToClipboard(text, 'Queue copied to clipboard!');
        });
      } else {
        copyTextToClipboard(text, 'Queue copied to clipboard!');
      }
    }

    /* ── LYRICS MANAGEMENT (KARAOKE SYNCHRONIZED) ── */
    function fetchLyrics(songId) {
      if (!songId) return;
      lyricsSongId = songId;
      var xhr = new XMLHttpRequest();
      xhr.open('GET', '/together/lyrics?id=' + encodeURIComponent(songId), true);
      xhr.onload = function() {
        if (xhr.status >= 200 && xhr.status < 300) {
          try {
            var data = JSON.parse(xhr.responseText);
            parseAndRenderLyrics(data.lyrics || '');
          } catch (e) {
            parseAndRenderLyrics('');
          }
        }
      };
      xhr.onerror = function() {
        parseAndRenderLyrics('');
      };
      xhr.send();
    }

    function reloadLyrics() {
      if (currentSongId) {
        fetchLyrics(currentSongId);
        showToast('Refreshing lyrics...');
      }
    }

    function parseAndRenderLyrics(raw) {
      if (!lyricsScrollBox) return;
      parsedLyrics = [];
      activeLyricIndex = -1;

      if (!raw || !raw.trim()) {
        lyricsScrollBox.innerHTML = '<div class="lyrics-empty"><svg viewBox="0 0 24 24"><path d="M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z"/></svg><p>No lyrics found for this track</p></div>';
        return;
      }

      var lines = raw.split('\n');
      var lrcRegex = /^\[(\d{2}):(\d{2}(?:\.\d{1,3})?)\](.*)$/;
      var isLrc = false;

      for (var i = 0; i < lines.length; i++) {
        var match = lines[i].trim().match(lrcRegex);
        if (match) {
          isLrc = true;
          var min = parseInt(match[1], 10);
          var sec = parseFloat(match[2]);
          var text = match[3].trim();
          parsedLyrics.push({ time: (min * 60) + sec, text: text || '♪' });
        }
      }

      if (isLrc && parsedLyrics.length > 0) {
        parsedLyrics.sort(function(a, b) { return a.time - b.time; });
        var html = '';
        parsedLyrics.forEach(function(item, idx) {
          html += '<div id="lyric_' + idx + '" class="lyric-line" onclick="seekToLyric(' + item.time + ')">' + escapeHtml(item.text) + '</div>';
        });
        lyricsScrollBox.innerHTML = html;
      } else {
        // Plain lyrics
        var htmlPlain = '';
        lines.forEach(function(line) {
          var t = line.trim();
          if (t) {
            htmlPlain += '<div class="lyric-line" style="opacity:0.85">' + escapeHtml(t) + '</div>';
          } else {
            htmlPlain += '<div style="height:12px"></div>';
          }
        });
        lyricsScrollBox.innerHTML = htmlPlain;
      }
    }

    function seekToLyric(timeSec) {
      if (audio && audio.duration) {
        audio.currentTime = timeSec;
      }
      sendPlaybackAction('seek', timeSec * 1000);
      showToast('Seek to ' + formatTime(timeSec));
    }

    function syncLyricsWithTime(curSec) {
      if (!parsedLyrics || parsedLyrics.length === 0 || !lyricsScrollBox) return;

      var currentIdx = -1;
      for (var i = 0; i < parsedLyrics.length; i++) {
        if (curSec >= parsedLyrics[i].time) {
          currentIdx = i;
        } else {
          break;
        }
      }

      if (currentIdx !== activeLyricIndex) {
        if (activeLyricIndex >= 0) {
          var prevEl = document.getElementById('lyric_' + activeLyricIndex);
          if (prevEl) prevEl.classList.remove('active');
        }
        activeLyricIndex = currentIdx;
        if (activeLyricIndex >= 0) {
          var activeEl = document.getElementById('lyric_' + activeLyricIndex);
          if (activeEl) {
            activeEl.classList.add('active');
            if (activeTab === 'lyrics') {
              var topPos = activeEl.offsetTop - lyricsScrollBox.offsetTop - (lyricsScrollBox.clientHeight / 2) + 40;
              lyricsScrollBox.scrollTo({ top: Math.max(0, topPos), behavior: 'smooth' });
            }
          }
        }
      }
    }

    function escapeHtml(str) {
      if (!str) return '';
      return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#039;');
    }

    function copyTextToClipboard(text, successMsg) {
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text).then(function() {
          showToast(successMsg);
        }).catch(function() {
          showToast('Copied to clipboard');
        });
      } else {
        var input = document.createElement('textarea');
        input.value = text;
        document.body.appendChild(input);
        input.select();
        document.execCommand('copy');
        document.body.removeChild(input);
        showToast(successMsg);
      }
    }

    function copyLink() {
      var url = window.location.href;
      copyTextToClipboard(url, 'Session join link copied!');
    }

    function copyStreamLink() {
      var streamUrl = window.location.origin + '/stream';
      if (menuDropdown) menuDropdown.classList.remove('show');
      copyTextToClipboard(streamUrl, 'Direct stream URL copied!');
    }

    function showToast(msg) {
      var t = document.getElementById('toast');
      if (!t) return;
      t.innerText = msg;
      t.className = 'toast show';
      setTimeout(function() { t.className = 'toast'; }, 2200);
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
      document.title = (state.isPlaying ? '\u25B6 ' : '\u23F8 ') + state.title + ' \u2022 AirBeats';

      if (state.thumbnailUrl) {
        if (ambientBg) ambientBg.style.backgroundImage = 'url("' + state.thumbnailUrl + '")';
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
            fetchLyrics(state.songId);
            fetchQueue();
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
    setInterval(fetchQueue, 3500);
    fetchState();
    fetchQueue();
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
