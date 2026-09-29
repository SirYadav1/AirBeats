package com.darkxvenom.airbeats.playback.queues

import androidx.media3.common.MediaItem
import com.darkxvenom.airbeats.innertube.YouTube
import com.darkxvenom.airbeats.innertube.models.SongItem
import com.darkxvenom.airbeats.innertube.models.WatchEndpoint
import com.darkxvenom.airbeats.extensions.toMediaItem
import com.darkxvenom.airbeats.models.MediaMetadata
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext

class YouTubeQueue(
    private var endpoint: WatchEndpoint,
    override val preloadItem: MediaMetadata? = null,
) : Queue {
    private var continuation: String? = null

    override suspend fun getInitialStatus(): Queue.Status {
        if (endpoint.videoId?.startsWith("JS:") == true || preloadItem?.id?.startsWith("JS:") == true) {
            val jioItem = preloadItem?.toMediaItem()
            val title = preloadItem?.title.orEmpty()
            val artist = preloadItem?.artists?.firstOrNull()?.name.orEmpty()
            val query = "$title $artist".trim()

            var ytMatchedEndpoint: WatchEndpoint? = null
            if (query.isNotEmpty()) {
                withContext(IO) {
                    runCatching {
                        val searchRes = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrNull()
                        val match = searchRes?.items?.firstOrNull() as? SongItem
                        if (match != null) {
                            ytMatchedEndpoint = WatchEndpoint(videoId = match.id)
                        }
                    }
                }
            }

            if (ytMatchedEndpoint != null) {
                val nextResult = withContext(IO) {
                    runCatching { YouTube.next(ytMatchedEndpoint!!).getOrNull() }.getOrNull()
                }
                if (nextResult != null) {
                    endpoint = nextResult.endpoint
                    continuation = nextResult.continuation
                    val recItems = nextResult.items.map { it.toMediaItem() }
                    val allItems = if (jioItem != null) {
                        listOf(jioItem) + recItems.filter { it.mediaId != jioItem.mediaId }
                    } else {
                        recItems
                    }
                    return Queue.Status(
                        title = nextResult.title ?: preloadItem?.title,
                        items = allItems,
                        mediaItemIndex = 0,
                    )
                }
            }

            // Fallback: If YouTube radio matching failed, play the JioSaavn item standalone
            if (jioItem != null) {
                return Queue.Status(
                    title = preloadItem?.title,
                    items = listOf(jioItem),
                    mediaItemIndex = 0,
                )
            }
        }

        val nextResult =
            withContext(IO) {
                runCatching {
                    YouTube.next(endpoint, continuation).getOrThrow()
                }.getOrNull()
            }

        if (nextResult == null) {
            val singleItem = preloadItem?.toMediaItem()
            val items = if (singleItem != null) listOf(singleItem) else emptyList()
            return Queue.Status(
                title = preloadItem?.title,
                items = items,
                mediaItemIndex = 0,
            )
        }

        endpoint = nextResult.endpoint
        continuation = nextResult.continuation

        val targetId = endpoint.videoId ?: preloadItem?.id
        val rawItems = nextResult.items.map { it.toMediaItem() }

        val targetIndex = if (targetId != null) {
            rawItems.indexOfFirst { it.mediaId == targetId }
        } else -1

        val (finalItems, finalIndex) = when {
            targetIndex != -1 -> {
                rawItems to targetIndex
            }
            preloadItem != null -> {
                val prepended = listOf(preloadItem.toMediaItem()) + rawItems.filter { it.mediaId != preloadItem.id }
                prepended to 0
            }
            else -> {
                rawItems to (nextResult.currentIndex ?: 0).coerceIn(0, (rawItems.size - 1).coerceAtLeast(0))
            }
        }

        return Queue.Status(
            title = nextResult.title ?: preloadItem?.title,
            items = finalItems,
            mediaItemIndex = finalIndex,
        )
    }

    override fun hasNextPage(): Boolean = continuation != null

    override suspend fun nextPage(): List<MediaItem> {
        val nextResult =
            withContext(IO) {
                runCatching {
                    YouTube.next(endpoint, continuation).getOrThrow()
                }.getOrNull()
            } ?: return emptyList()
        endpoint = nextResult.endpoint
        continuation = nextResult.continuation
        return nextResult.items.map { it.toMediaItem() }
    }

    companion object {
        fun radio(song: MediaMetadata) = YouTubeQueue(WatchEndpoint(song.id), song)
    }
}
