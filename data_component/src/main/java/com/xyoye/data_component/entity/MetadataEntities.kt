package com.xyoye.data_component.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Content identity is independent of connection credentials and playback history. */
@Entity(tableName = "catalog_source")
data class CatalogSourceState(
    @PrimaryKey val libraryId: Int,
    val contentGeneration: Long = 1,
    val revision: Long = 1,
    val changedAt: Long = 0
)

@Entity(
    tableName = "media_asset",
    indices = [Index(value = ["libraryId", "contentGeneration", "locatorKey"], unique = true)]
)
data class MediaAsset(
    @PrimaryKey(autoGenerate = true) val assetId: Long = 0,
    val libraryId: Int,
    val contentGeneration: Long,
    val locatorKey: String,
    val uniqueKey: String,
    val storagePath: String,
    val protocolReference: String? = null,
    val fileName: String,
    val parentPath: String,
    val size: Long? = null,
    val modifiedAt: Long? = null,
    val discoveredAt: Long,
    val available: Boolean = true,
    val revision: Long = 1,
    val lastSeenRun: String? = null
)

@Entity(tableName = "metadata_work", primaryKeys = ["mediaKind", "tmdbId"])
data class MetadataWork(
    val mediaKind: String,
    val tmdbId: Long,
    val title: String,
    val originalTitle: String,
    val overview: String,
    val releaseDate: String,
    val genres: String,
    val rating: Double,
    val poster: String?,
    val backdrop: String?,
    val language: String,
    val fallbackFields: String,
    val fetchedAt: Long,
    val requestGeneration: Long
)

@Entity(tableName = "metadata_season", primaryKeys = ["seriesId", "seasonNumber"])
data class MetadataSeason(
    val seriesId: Long,
    val seasonNumber: Int,
    val title: String,
    val poster: String?,
    val airDate: String,
    val fetchedAt: Long = 0
)

@Entity(tableName = "metadata_episode", primaryKeys = ["seriesId", "seasonNumber", "episodeNumber"])
data class MetadataEpisode(
    val seriesId: Long,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val tmdbId: Long?,
    val title: String,
    val overview: String,
    val airDate: String,
    val still: String?
)

@Entity(tableName = "media_binding", indices = [Index(value = ["mediaKind", "tmdbId"])])
data class MediaBinding(
    @PrimaryKey val assetId: Long,
    val state: String = "UNMATCHED",
    val mediaKind: String? = null,
    val tmdbId: Long? = null,
    val origin: String = "AUTO",
    val locked: Boolean = false,
    val revision: Long = 0,
    val taskId: String? = null,
    val evidence: String = "",
    val ruleVersion: Int = 1,
    val failure: String? = null
)

@Entity(tableName = "media_binding_episode", primaryKeys = ["assetId", "seasonNumber", "episodeNumber"])
data class MediaBindingEpisode(
    val assetId: Long,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val ordinal: Int
)

@Entity(tableName = "metadata_scan", indices = [Index(value = ["libraryId"])])
data class MetadataScanRun(
    @PrimaryKey val runId: String,
    val libraryId: Int,
    val contentGeneration: Long,
    val sourceRevision: Long,
    val directory: String,
    val recursive: Boolean,
    val state: String,
    val discovered: Int = 0,
    val matched: Int = 0,
    val pending: Int = 0,
    val failed: Int = 0,
    val completedDirectories: Int = 0,
    val startedAt: Long,
    val failure: String? = null
)

@Entity(tableName = "metadata_scan_directory", primaryKeys = ["runId", "path"])
data class MetadataScanDirectory(
    val runId: String,
    val path: String,
    val complete: Boolean = false
)
