package com.xyoye.common_component.playback.handoff

import com.xyoye.data_component.enums.MediaType
import com.xyoye.data_component.enums.StorageSort

/** Stable inputs only. Persist the entire enclosing request encrypted; never log its fields. */
sealed class PlaybackDescriptor {
    final override fun toString(): String = javaClass.simpleName
}

data class StoredLibraryFileDescriptor(
    val libraryId: Int,
    val mediaType: MediaType,
    val file: PlaybackFileReference,
    val queueContext: PlaybackQueueContext? = null
) : PlaybackDescriptor()

data class PlayHistoryDescriptor(
    val historyId: Int,
    val expectedUniqueKey: String,
    val expectedLibraryId: Int? = null
) : PlaybackDescriptor()

data class ScreencastDescriptor(
    val senderAddress: String,
    val port: Int,
    val relatedVideos: List<PlaybackCastVideo>,
    val playUniqueKey: String,
    val httpHeaders: Map<String, String> = emptyMap()
) : PlaybackDescriptor()

/** Protocol references are opaque to feature callers and interpreted only by storage adapters. */
data class PlaybackFileReference(
    val uniqueKey: String,
    val storagePath: String,
    val filePathHint: String? = null,
    val playHistoryId: Int? = null,
    val protocolReference: String? = null,
    val displayTitle: String? = null
) {
    override fun toString(): String = "PlaybackFileReference"
}

data class PlaybackQueueContext(
    val directory: PlaybackFileReference?,
    val sort: StorageSort,
    val hideHiddenFiles: Boolean,
    val selectedUniqueKey: String,
    val fallbackItems: List<PlaybackFileReference>,
    val sortAscending: Boolean = true
) {
    companion object {
        const val MAX_FALLBACK_ITEMS = 200
    }

    init {
        require(fallbackItems.size <= MAX_FALLBACK_ITEMS) { "Playback queue exceeds its identity limit" }
        require(directory != null || fallbackItems.isNotEmpty()) { "Playback queue needs a resolvable identity" }
        require(fallbackItems.isEmpty() || fallbackItems.any { it.uniqueKey == selectedUniqueKey }) {
            "Playback queue must include the selected identity"
        }
    }

    override fun toString(): String = "PlaybackQueueContext(items=${fallbackItems.size})"
}

data class PlaybackCastVideo(
    val title: String,
    val uniqueKey: String,
    val episodeId: String? = null,
    val danmuFileName: String? = null,
    val subtitleFileName: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0
) {
    override fun toString(): String = "PlaybackCastVideo"
}
