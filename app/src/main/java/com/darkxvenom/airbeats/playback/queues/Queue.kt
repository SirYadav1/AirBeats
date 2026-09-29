package com.darkxvenom.airbeats.playback.queues

import androidx.media3.common.MediaItem
import com.darkxvenom.airbeats.extensions.metadata
import com.darkxvenom.airbeats.models.MediaMetadata

interface Queue {
    val preloadItem: MediaMetadata?

    suspend fun getInitialStatus(): Status

    fun hasNextPage(): Boolean

    suspend fun nextPage(): List<MediaItem>

    data class Status(
        val title: String?,
        val items: List<MediaItem>,
        val mediaItemIndex: Int,
        val position: Long = 0L,
    ) {
        fun filterExplicit(enabled: Boolean = true): Status {
            if (!enabled) return this
            val currentItem = items.getOrNull(mediaItemIndex)
            val filtered = items.filterExplicit()
            val newIndex = if (currentItem != null) {
                filtered.indexOfFirst { it.mediaId == currentItem.mediaId }.takeIf { it >= 0 } ?: 0
            } else {
                0
            }
            return copy(items = filtered, mediaItemIndex = newIndex)
        }
    }
}

fun List<MediaItem>.filterExplicit(enabled: Boolean = true) =
    if (enabled) {
        filterNot {
            it.metadata?.explicit == true
        }
    } else {
        this
    }

fun Queue.Status.filterExcluded(excludedSongIds: Set<String>): Queue.Status {
    if (excludedSongIds.isEmpty()) return this
    val currentItem = items.getOrNull(mediaItemIndex)
    val filtered = items.filterIndexed { index, mediaItem ->
        index == mediaItemIndex || mediaItem.mediaId !in excludedSongIds
    }
    val newIndex = if (currentItem != null) {
        filtered.indexOfFirst { it.mediaId == currentItem.mediaId }.takeIf { it >= 0 } ?: 0
    } else {
        0
    }
    return copy(items = filtered, mediaItemIndex = newIndex)
}

fun List<MediaItem>.filterExcluded(excludedSongIds: Set<String>): List<MediaItem> =
    if (excludedSongIds.isEmpty()) this else filterNot { it.mediaId in excludedSongIds }

