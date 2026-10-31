package com.xyoye.common_component.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object MetadataMigration : Migration(18, 19) {
    override fun migrate(database: SupportSQLiteDatabase) {
        statements.forEach(database::execSQL)
    }

    private val statements =
        listOf(
            "CREATE TABLE IF NOT EXISTS catalog_source (libraryId INTEGER NOT NULL PRIMARY KEY, contentGeneration INTEGER NOT NULL, revision INTEGER NOT NULL, changedAt INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS media_asset (assetId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, libraryId INTEGER NOT NULL, contentGeneration INTEGER NOT NULL, locatorKey TEXT NOT NULL, uniqueKey TEXT NOT NULL, storagePath TEXT NOT NULL, protocolReference TEXT, fileName TEXT NOT NULL, parentPath TEXT NOT NULL, size INTEGER, modifiedAt INTEGER, discoveredAt INTEGER NOT NULL, available INTEGER NOT NULL, revision INTEGER NOT NULL, lastSeenRun TEXT)",
            "CREATE UNIQUE INDEX IF NOT EXISTS index_media_asset_libraryId_contentGeneration_locatorKey ON media_asset(libraryId, contentGeneration, locatorKey)",
            "CREATE TABLE IF NOT EXISTS metadata_work (mediaKind TEXT NOT NULL, tmdbId INTEGER NOT NULL, title TEXT NOT NULL, originalTitle TEXT NOT NULL, overview TEXT NOT NULL, releaseDate TEXT NOT NULL, genres TEXT NOT NULL, rating REAL NOT NULL, poster TEXT, backdrop TEXT, language TEXT NOT NULL, fallbackFields TEXT NOT NULL, fetchedAt INTEGER NOT NULL, requestGeneration INTEGER NOT NULL, PRIMARY KEY(mediaKind, tmdbId))",
            "CREATE TABLE IF NOT EXISTS metadata_season (seriesId INTEGER NOT NULL, seasonNumber INTEGER NOT NULL, title TEXT NOT NULL, poster TEXT, airDate TEXT NOT NULL, fetchedAt INTEGER NOT NULL, PRIMARY KEY(seriesId, seasonNumber))",
            "CREATE TABLE IF NOT EXISTS metadata_episode (seriesId INTEGER NOT NULL, seasonNumber INTEGER NOT NULL, episodeNumber INTEGER NOT NULL, tmdbId INTEGER, title TEXT NOT NULL, overview TEXT NOT NULL, airDate TEXT NOT NULL, still TEXT, PRIMARY KEY(seriesId, seasonNumber, episodeNumber))",
            "CREATE TABLE IF NOT EXISTS media_binding (assetId INTEGER NOT NULL PRIMARY KEY, state TEXT NOT NULL, mediaKind TEXT, tmdbId INTEGER, origin TEXT NOT NULL, locked INTEGER NOT NULL, revision INTEGER NOT NULL, taskId TEXT, evidence TEXT NOT NULL, ruleVersion INTEGER NOT NULL, failure TEXT)",
            "CREATE INDEX IF NOT EXISTS index_media_binding_mediaKind_tmdbId ON media_binding(mediaKind, tmdbId)",
            "CREATE TABLE IF NOT EXISTS media_binding_episode (assetId INTEGER NOT NULL, seasonNumber INTEGER NOT NULL, episodeNumber INTEGER NOT NULL, ordinal INTEGER NOT NULL, PRIMARY KEY(assetId, seasonNumber, episodeNumber))",
            "CREATE TABLE IF NOT EXISTS metadata_scan (runId TEXT NOT NULL PRIMARY KEY, libraryId INTEGER NOT NULL, contentGeneration INTEGER NOT NULL, sourceRevision INTEGER NOT NULL, directory TEXT NOT NULL, recursive INTEGER NOT NULL, state TEXT NOT NULL, discovered INTEGER NOT NULL, matched INTEGER NOT NULL, pending INTEGER NOT NULL, failed INTEGER NOT NULL, completedDirectories INTEGER NOT NULL, startedAt INTEGER NOT NULL, failure TEXT)",
            "CREATE INDEX IF NOT EXISTS index_metadata_scan_libraryId ON metadata_scan(libraryId)",
            "CREATE TABLE IF NOT EXISTS metadata_scan_directory (runId TEXT NOT NULL, path TEXT NOT NULL, complete INTEGER NOT NULL, PRIMARY KEY(runId, path))"
        )
}
