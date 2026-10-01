package com.darkxvenom.airbeats.utils

import android.content.Context
import com.darkxvenom.airbeats.constants.SpotifyCookieKey
import com.darkxvenom.airbeats.db.DatabaseDao
import com.darkxvenom.airbeats.db.entities.PlaylistEntity
import com.darkxvenom.airbeats.db.entities.PlaylistSongMap
import com.darkxvenom.airbeats.innertube.YouTube
import com.darkxvenom.airbeats.models.toMediaMetadata
import com.darkxvenom.airbeats.spotify.Spotify
import com.darkxvenom.airbeats.spotify.SpotifyAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import java.time.LocalDateTime
import java.util.UUID

object SpotifyImporter {

    suspend fun importPlaylist(
        url: String,
        dao: DatabaseDao,
        context: Context? = null,
        onProgress: (Int, Int) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val playlistId = extractPlaylistId(url)
            if (playlistId.isBlank()) throw IllegalArgumentException("Invalid Spotify Playlist URL or ID")

            val tracks = mutableListOf<Pair<String, String>>()
            var playlistName = "Imported Spotify Playlist"

            // 0. Ensure Spotify access token from dataStore if available
            if (Spotify.accessToken.isNullOrBlank() && context != null) {
                runCatching {
                    val spDc = context.dataStore.data.first()[SpotifyCookieKey]
                    if (!spDc.isNullOrBlank()) {
                        SpotifyAuth.fetchAccessToken(spDc).onSuccess { token ->
                            Spotify.accessToken = token.accessToken
                        }
                    }
                }
            }

            // 1. Try Spotify API if accessToken is available
            if (!Spotify.accessToken.isNullOrBlank()) {
                val spPlaylistResult = Spotify.playlist(playlistId)
                spPlaylistResult.onSuccess { spPlaylist ->
                    if (spPlaylist.name.isNotBlank()) {
                        playlistName = spPlaylist.name
                    }
                    var offset = 0
                    val limit = 100
                    while (true) {
                        val tracksResult = Spotify.playlistTracks(playlistId, limit = limit, offset = offset).getOrNull()
                        if (tracksResult == null || tracksResult.items.isEmpty()) break
                        tracksResult.items.forEach { item ->
                            val t = item.track
                            if (t != null && t.name.isNotBlank()) {
                                tracks.add(t.name to t.artists.joinToString(" ") { it.name })
                            }
                        }
                        offset += tracksResult.items.size
                        if (offset >= tracksResult.total || tracksResult.items.size < limit) break
                    }
                }
            }

            // 2. Fallback to Jsoup embed scraping if no tracks found via API
            if (tracks.isEmpty()) {
                val embedUrl = "https://open.spotify.com/embed/playlist/$playlistId"
                val doc = Jsoup.connect(embedUrl)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                    .get()

                val nextDataElement = doc.select("script#__NEXT_DATA__").first()
                if (nextDataElement != null) {
                    val json = JSONObject(nextDataElement.html())
                    val entity = json.optJSONObject("props")
                        ?.optJSONObject("pageProps")
                        ?.optJSONObject("state")
                        ?.optJSONObject("data")
                        ?.optJSONObject("entity")

                    if (entity != null) {
                        playlistName = entity.optString("name", playlistName)
                        val trackListArray = entity.optJSONArray("trackList")
                        if (trackListArray != null) {
                            for (i in 0 until trackListArray.length()) {
                                val trackObj = trackListArray.optJSONObject(i) ?: continue
                                val title = trackObj.optString("title")
                                val artist = trackObj.optString("subtitle")
                                if (title.isNotBlank()) {
                                    tracks.add(title to artist)
                                }
                            }
                        }
                    }
                }
            }

            if (tracks.isEmpty()) {
                throw IllegalStateException("Could not extract tracks from Spotify playlist. Please ensure the playlist is public or you are logged into Spotify.")
            }

            // 3. Create Playlist in DB
            val newPlaylistId = "LP" + UUID.randomUUID().toString().replace("-", "").take(8)
            val playlistEntity = PlaylistEntity(
                id = newPlaylistId,
                name = playlistName,
                browseId = "sp:$playlistId",
                bookmarkedAt = LocalDateTime.now(),
                remoteSongCount = tracks.size
            )
            dao.insert(playlistEntity)

            // 4. Match songs on YouTube and add to playlist
            val totalTracks = tracks.size
            val songIds = mutableListOf<String>()

            for (i in 0 until totalTracks) {
                onProgress(i + 1, totalTracks)
                val (title, artist) = tracks[i]
                val query = "$title $artist".trim()

                try {
                    val searchResult = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrNull()
                    val firstSong = searchResult?.items?.firstOrNull() as? com.darkxvenom.airbeats.innertube.models.SongItem
                    if (firstSong != null) {
                        songIds.add(firstSong.id)
                        dao.insert(firstSong.toMediaMetadata())
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // 5. Insert PlaylistSongMap entries
            songIds.forEachIndexed { index, songId ->
                dao.insert(
                    PlaylistSongMap(
                        songId = songId,
                        playlistId = newPlaylistId,
                        position = index
                    )
                )
            }

            playlistName
        }
    }

    suspend fun convertPlaylistToYouTube(
        spPlaylistId: String,
        targetPlaylistEntityId: String,
        dao: DatabaseDao,
        context: Context? = null,
        onProgress: (Int, Int) -> Unit
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val tracks = mutableListOf<Pair<String, String>>()

            // 0. Ensure Spotify access token from dataStore if available
            if (Spotify.accessToken.isNullOrBlank() && context != null) {
                runCatching {
                    val spDc = context.dataStore.data.first()[SpotifyCookieKey]
                    if (!spDc.isNullOrBlank()) {
                        SpotifyAuth.fetchAccessToken(spDc).onSuccess { token ->
                            Spotify.accessToken = token.accessToken
                        }
                    }
                }
            }

            // 1. Fetch tracks from Spotify API if accessToken is available
            if (!Spotify.accessToken.isNullOrBlank()) {
                var offset = 0
                val limit = 100
                while (true) {
                    val tracksResult = Spotify.playlistTracks(spPlaylistId, limit = limit, offset = offset).getOrNull()
                    if (tracksResult == null || tracksResult.items.isEmpty()) break
                    tracksResult.items.forEach { item ->
                        val t = item.track
                        if (t != null && t.name.isNotBlank()) {
                            tracks.add(t.name to t.artists.joinToString(" ") { it.name })
                        }
                    }
                    offset += tracksResult.items.size
                    if (offset >= tracksResult.total || tracksResult.items.size < limit) break
                }
            }

            // 2. Fallback to Jsoup embed scraping if no tracks found via API
            if (tracks.isEmpty()) {
                val embedUrl = "https://open.spotify.com/embed/playlist/$spPlaylistId"
                val doc = Jsoup.connect(embedUrl)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                    .get()

                val nextDataElement = doc.select("script#__NEXT_DATA__").first()
                if (nextDataElement != null) {
                    val json = JSONObject(nextDataElement.html())
                    val entity = json.optJSONObject("props")
                        ?.optJSONObject("pageProps")
                        ?.optJSONObject("state")
                        ?.optJSONObject("data")
                        ?.optJSONObject("entity")

                    val trackListArray = entity?.optJSONArray("trackList")
                    if (trackListArray != null) {
                        for (i in 0 until trackListArray.length()) {
                            val trackObj = trackListArray.optJSONObject(i) ?: continue
                            val title = trackObj.optString("title")
                            val artist = trackObj.optString("subtitle")
                            if (title.isNotBlank()) {
                                tracks.add(title to artist)
                            }
                        }
                    }
                }
            }

            if (tracks.isEmpty()) {
                throw IllegalStateException("No tracks found in Spotify playlist")
            }

            val totalTracks = tracks.size
            val matchedSongIds = mutableListOf<String>()

            for (i in 0 until totalTracks) {
                onProgress(i + 1, totalTracks)
                val (title, artist) = tracks[i]
                val query = "$title $artist".trim()

                try {
                    val searchResult = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrNull()
                    val firstSong = searchResult?.items?.firstOrNull() as? com.darkxvenom.airbeats.innertube.models.SongItem
                    if (firstSong != null) {
                        matchedSongIds.add(firstSong.id)
                        dao.insert(firstSong.toMediaMetadata())
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // Clear any existing maps and insert converted songs
            dao.clearPlaylist(targetPlaylistEntityId)
            matchedSongIds.forEachIndexed { index, songId ->
                dao.insert(
                    PlaylistSongMap(
                        songId = songId,
                        playlistId = targetPlaylistEntityId,
                        position = index
                    )
                )
            }

            matchedSongIds.size
        }
    }

    private fun extractPlaylistId(input: String): String {
        val trimmed = input.trim()
        return when {
            trimmed.contains("playlist/") -> trimmed.substringAfter("playlist/").substringBefore("?").substringBefore("/")
            trimmed.startsWith("spotify:playlist:") -> trimmed.substringAfter("spotify:playlist:")
            trimmed.startsWith("sp:") -> trimmed.removePrefix("sp:")
            else -> trimmed
        }
    }
}
