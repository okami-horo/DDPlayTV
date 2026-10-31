package com.xyoye.common_component.storage.catalog

import com.xyoye.common_component.playback.handoff.PlaybackFileReference

/** No connection, authentication header, or playback URL crosses the catalog boundary. */
data class CatalogFileSnapshot(
    val libraryId: Int,
    val reference: PlaybackFileReference,
    val locatorKey: String,
    val fileName: String,
    val parentPath: String,
    val size: Long?,
    val modifiedAt: Long?
)

data class CatalogDirectorySnapshot(
    val path: String,
    val files: List<CatalogFileSnapshot>,
    val directories: List<String>,
    val complete: Boolean
)
