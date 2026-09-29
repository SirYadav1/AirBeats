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
                uri == "/" || uri == "/together" || uri == "/index.html" -> {
                    htmlResponse(renderWebPlayerHtml())
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
        response.addHeader("Access-Control-Allow-Headers", "origin, accept, content-type")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
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
      --bg: #0b0c13;
      --card: rgba(25, 27, 40, 0.78);
      --card-border: rgba(255, 255, 255, 0.08);
      --primary: #8b5cf6;
      --primary-hover: #7c3aed;
      --primary-grad: linear-gradient(135deg, #7c3aed 0%, #ec4899 100%);
      --text: #f9fafb;
      --text-dim: #9ca3af;
      --success: #10b981;
      --surface: rgba(255, 255, 255, 0.04);
    }
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      background: var(--bg);
      background-image: 
        radial-gradient(circle at 10% 20%, rgba(124, 58, 237, 0.18) 0%, transparent 45%),
        radial-gradient(circle at 90% 80%, rgba(236, 72, 153, 0.14) 0%, transparent 45%);
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
      backdrop-filter: blur(28px);
      -webkit-backdrop-filter: blur(28px);
      border: 1px solid var(--card-border);
      border-radius: 28px;
      padding: 28px 24px;
      width: 100%;
      max-width: 420px;
      box-shadow: 0 25px 60px rgba(0, 0, 0, 0.6);
      text-align: center;
      position: relative;
    }
    .header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      margin-bottom: 22px;
    }
    .brand {
      display: flex;
      align-items: center;
      gap: 8px;
      font-weight: 800;
      font-size: 1.15rem;
      letter-spacing: -0.3px;
    }
    .brand-icon {
      width: 28px;
      height: 28px;
      border-radius: 8px;
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
      margin: 0 auto 20px;
      border-radius: 22px;
      overflow: hidden;
      box-shadow: 0 16px 36px rgba(0,0,0,0.55);
      background: #181926;
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
      opacity: 0.3;
    }
    .track-title {
      font-size: 1.25rem;
      font-weight: 700;
      margin-bottom: 6px;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .track-artist {
      font-size: 0.9rem;
      color: var(--text-dim);
      margin-bottom: 16px;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .status-tag {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 5px 12px;
      border-radius: 12px;
      background: var(--surface);
      border: 1px solid var(--card-border);
      font-size: 0.78rem;
      font-weight: 600;
      color: var(--text-dim);
      margin-bottom: 20px;
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
    .progress-wrap {
      margin-bottom: 20px;
    }
    .progress-track {
      width: 100%;
      height: 6px;
      background: rgba(255, 255, 255, 0.08);
      border-radius: 6px;
      overflow: hidden;
    }
    .progress-fill {
      height: 100%;
      width: 0%;
      background: var(--primary-grad);
      border-radius: 6px;
      transition: width 0.25s linear;
    }
    .time-row {
      display: flex;
      justify-content: space-between;
      margin-top: 6px;
      font-size: 0.72rem;
      color: var(--text-dim);
      font-variant-numeric: tabular-nums;
    }
    .session-box {
      background: rgba(0, 0, 0, 0.25);
      border: 1px solid var(--card-border);
      border-radius: 18px;
      padding: 12px 16px;
      margin-bottom: 20px;
      display: flex;
      justify-content: space-around;
      font-size: 0.75rem;
      color: var(--text-dim);
    }
    .session-box strong {
      color: var(--text);
      display: block;
      margin-top: 2px;
      font-size: 0.85rem;
    }
    .actions {
      display: flex;
      flex-direction: column;
      gap: 10px;
    }
    .btn {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      gap: 8px;
      padding: 12px 18px;
      border-radius: 14px;
      font-size: 0.88rem;
      font-weight: 600;
      cursor: pointer;
      text-decoration: none;
      transition: all 0.2s ease;
      border: none;
    }
    .btn-app {
      background: var(--primary-grad);
      color: #fff;
      box-shadow: 0 4px 18px rgba(124, 58, 237, 0.35);
    }
    .btn-app:hover {
      opacity: 0.95;
      transform: translateY(-1px);
    }
    .btn-secondary {
      background: rgba(255, 255, 255, 0.08);
      color: var(--text);
      border: 1px solid var(--card-border);
    }
    .btn-secondary:hover {
      background: rgba(255, 255, 255, 0.12);
    }
    .btn-outline {
      background: transparent;
      color: var(--text-dim);
      border: 1px solid var(--card-border);
    }
    .btn-outline:hover {
      color: var(--text);
      border-color: rgba(255, 255, 255, 0.2);
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

    <div class="progress-wrap">
      <div class="progress-track">
        <div id="progressBar" class="progress-fill"></div>
      </div>
      <div class="time-row">
        <span id="currentTime">00:00</span>
        <span id="networkCode">$hostIp:$port</span>
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
      <a id="listenWebBtn" href="#" target="_blank" class="btn btn-secondary" style="display:none;">
        ▶ Open Track on Web
      </a>
      <button onclick="copyLink()" class="btn btn-outline">
        📋 Copy Browser Link
      </button>
    </div>
  </div>

  <div id="toast" class="toast"></div>

  <script>
    var currentSongId = null;
    var lastServerPosition = 0;
    var lastServerTime = Date.now();
    var isPlaying = false;
    var clientPid = 'web_' + Math.random().toString(36).substring(2, 8);

    function formatTime(ms) {
      if (ms < 0 || isNaN(ms)) ms = 0;
      var totalSeconds = Math.floor(ms / 1000);
      var minutes = Math.floor(totalSeconds / 60);
      var seconds = totalSeconds % 60;
      return (minutes < 10 ? '0' : '') + minutes + ':' + (seconds < 10 ? '0' : '') + seconds;
    }

    function updateUi(data) {
      if (!data) return;
      var state = data.state;
      var count = data.participants || 1;
      var countEl = document.getElementById('participantCount');
      if (countEl) countEl.innerText = count + (count === 1 ? ' Listener' : ' Listeners');
      var hostEl = document.getElementById('hostName');
      if (hostEl) hostEl.innerText = data.hostName || 'AirBeats Host';

      var titleEl = document.getElementById('trackTitle');
      var artistEl = document.getElementById('trackArtist');
      var artImg = document.getElementById('artImg');
      var artPlaceholder = document.getElementById('artPlaceholder');
      var statusText = document.getElementById('statusText');
      var waveAnim = document.getElementById('waveAnim');
      var listenBtn = document.getElementById('listenWebBtn');

      if (!state || !state.title) {
        if (titleEl) titleEl.innerText = 'AirBeats Session';
        if (artistEl) artistEl.innerText = 'Waiting for host to play music...';
        if (statusText) statusText.innerText = 'Idle';
        if (waveAnim) waveAnim.style.display = 'none';
        if (artImg) artImg.style.display = 'none';
        if (artPlaceholder) artPlaceholder.style.display = 'flex';
        if (listenBtn) listenBtn.style.display = 'none';
        var curEl = document.getElementById('currentTime');
        if (curEl) curEl.innerText = '00:00';
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

      currentSongId = state.songId;
      lastServerPosition = state.positionMs || 0;
      lastServerTime = Date.now();
      isPlaying = Boolean(state.isPlaying);

      if (isPlaying) {
        if (waveAnim) waveAnim.style.display = 'inline-flex';
        if (statusText) statusText.innerText = 'Playing';
      } else {
        if (waveAnim) waveAnim.style.display = 'none';
        if (statusText) statusText.innerText = 'Paused';
      }

      if (listenBtn) {
        if (state.songId && !state.songId.startsWith('JS:')) {
          listenBtn.href = 'https://music.youtube.com/watch?v=' + encodeURIComponent(state.songId);
          listenBtn.innerText = '▶ Open on YouTube Music';
          listenBtn.style.display = 'inline-flex';
        } else if (state.songId && state.songId.startsWith('JS:')) {
          listenBtn.href = 'https://www.jiosaavn.com/search/' + encodeURIComponent(state.title);
          listenBtn.innerText = '▶ Search on JioSaavn';
          listenBtn.style.display = 'inline-flex';
        } else {
          listenBtn.style.display = 'none';
        }
      }
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
        var statusText = document.getElementById('statusText');
        if (statusText) statusText.innerText = 'Reconnecting...';
        var waveAnim = document.getElementById('waveAnim');
        if (waveAnim) waveAnim.style.display = 'none';
      };
      xhr.send();
    }

    setInterval(function() {
      var curEl = document.getElementById('currentTime');
      if (!curEl) return;
      if (!isPlaying) {
        curEl.innerText = formatTime(lastServerPosition);
        return;
      }
      var elapsed = Date.now() - lastServerTime;
      var pos = Math.max(0, lastServerPosition + elapsed);
      curEl.innerText = formatTime(pos);
    }, 250);

    setInterval(fetchState, 1500);
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
