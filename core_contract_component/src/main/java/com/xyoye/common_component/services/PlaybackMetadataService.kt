package com.xyoye.common_component.services

import com.alibaba.android.arouter.facade.template.IProvider
import com.xyoye.common_component.playback.handoff.PlaybackFileReference
import com.xyoye.common_component.playback.handoff.PlaybackSessionVersion
import com.xyoye.data_component.entity.CatalogSourceState
import com.xyoye.data_component.enums.MediaType

/** Captured during resolution, never reconstructed from a temporary playback URL. */
data class PlaybackMetadataFile(
    val source: CatalogSourceState,
    val mediaType: MediaType,
    val file: PlaybackFileReference,
    val parentPath: String?
)

interface PlaybackMetadataService : IProvider {
    /** Enqueue only; catalog/network failures must never escape into playback. */
    fun submit(
        version: PlaybackSessionVersion,
        file: PlaybackMetadataFile
    )
}
