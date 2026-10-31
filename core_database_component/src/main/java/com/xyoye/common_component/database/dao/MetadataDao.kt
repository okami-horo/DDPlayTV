package com.xyoye.common_component.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.xyoye.data_component.entity.CatalogSourceState
import com.xyoye.data_component.entity.MediaAsset
import com.xyoye.data_component.entity.MediaBinding
import com.xyoye.data_component.entity.MediaBindingEpisode
import com.xyoye.data_component.entity.MetadataEpisode
import com.xyoye.data_component.entity.MetadataScanDirectory
import com.xyoye.data_component.entity.MetadataScanRun
import com.xyoye.data_component.entity.MetadataSeason
import com.xyoye.data_component.entity.MetadataWork
import kotlinx.coroutines.flow.Flow

@Dao
interface MetadataDao {
    @Query("SELECT * FROM catalog_source WHERE libraryId = :id")
    suspend fun source(id: Int): CatalogSourceState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSource(source: CatalogSourceState)

    @Query("SELECT * FROM media_asset WHERE assetId = :id")
    suspend fun asset(id: Long): MediaAsset?

    @Query("SELECT * FROM media_asset WHERE libraryId = :libraryId AND contentGeneration = :generation AND locatorKey = :locator")
    suspend fun asset(
        libraryId: Int,
        generation: Long,
        locator: String
    ): MediaAsset?

    @Query(
        "SELECT * FROM media_asset WHERE libraryId = :libraryId AND contentGeneration = :generation AND parentPath = :parent ORDER BY fileName"
    )
    suspend fun directoryAssets(
        libraryId: Int,
        generation: Long,
        parent: String
    ): List<MediaAsset>

    @Query("SELECT * FROM catalog_source WHERE libraryId = :id")
    fun observeSource(id: Int): Flow<CatalogSourceState?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAsset(asset: MediaAsset): Long

    @Query("SELECT * FROM media_binding WHERE assetId = :id")
    suspend fun binding(id: Long): MediaBinding?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putBinding(binding: MediaBinding)

    @Query("DELETE FROM media_binding_episode WHERE assetId = :id")
    suspend fun clearEpisodes(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putBindingEpisodes(episodes: List<MediaBindingEpisode>)

    @Query("SELECT * FROM media_binding_episode WHERE assetId = :id ORDER BY ordinal")
    suspend fun bindingEpisodes(id: Long): List<MediaBindingEpisode>

    @Query("SELECT * FROM metadata_work WHERE mediaKind = :kind AND tmdbId = :id")
    suspend fun work(
        kind: String,
        id: Long
    ): MetadataWork?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putWork(work: MetadataWork)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSeasons(seasons: List<MetadataSeason>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putEpisodes(episodes: List<MetadataEpisode>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun putNewEpisodes(episodes: List<MetadataEpisode>)

    @Query("SELECT * FROM metadata_season WHERE seriesId = :id ORDER BY seasonNumber")
    suspend fun seasons(id: Long): List<MetadataSeason>

    @Query("SELECT * FROM metadata_episode WHERE seriesId = :id AND seasonNumber = :season ORDER BY episodeNumber")
    suspend fun episodes(
        id: Long,
        season: Int
    ): List<MetadataEpisode>

    @Query(
        """
        SELECT w.* FROM metadata_work w WHERE EXISTS (
            SELECT 1 FROM media_binding b JOIN media_asset a ON a.assetId = b.assetId
            JOIN catalog_source s ON s.libraryId = a.libraryId AND s.contentGeneration = a.contentGeneration
            JOIN media_library l ON l.id = a.libraryId
            WHERE b.mediaKind = w.mediaKind AND b.tmdbId = w.tmdbId
        ) ORDER BY w.title
    """
    )
    fun observeWorks(): Flow<List<MetadataWork>>

    @Query(
        """
        SELECT a.* FROM media_asset a
        JOIN catalog_source s ON s.libraryId = a.libraryId AND s.contentGeneration = a.contentGeneration
        JOIN media_library l ON l.id = a.libraryId
        ORDER BY a.discoveredAt DESC, a.assetId
    """
    )
    fun observeAssets(): Flow<List<MediaAsset>>

    @Query("SELECT * FROM media_binding")
    fun observeBindings(): Flow<List<MediaBinding>>

    @Query("SELECT * FROM media_binding_episode ORDER BY ordinal")
    fun observeBindingEpisodes(): Flow<List<MediaBindingEpisode>>

    @Query(
        """
        UPDATE media_asset SET available = 0, revision = revision + 1
        WHERE libraryId = :libraryId AND contentGeneration = :generation AND parentPath = :parent
        AND (lastSeenRun IS NULL OR lastSeenRun != :runId)
    """
    )
    suspend fun markMissing(
        libraryId: Int,
        generation: Long,
        parent: String,
        runId: String
    )

    @Query("DELETE FROM media_binding_episode WHERE assetId IN (SELECT assetId FROM media_asset WHERE libraryId = :id)")
    suspend fun deleteSourceEpisodes(id: Int)

    @Query("DELETE FROM media_binding WHERE assetId IN (SELECT assetId FROM media_asset WHERE libraryId = :id)")
    suspend fun deleteSourceBindings(id: Int)

    @Query("DELETE FROM media_asset WHERE libraryId = :id")
    suspend fun deleteSourceAssets(id: Int)

    @Query("DELETE FROM catalog_source WHERE libraryId = :id")
    suspend fun deleteSource(id: Int)

    @Query("UPDATE metadata_scan SET state = 'CANCELLED' WHERE libraryId = :id AND state IN ('QUEUED', 'RUNNING')")
    suspend fun cancelSourceScans(id: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putScan(scan: MetadataScanRun)

    @Query("SELECT * FROM metadata_scan WHERE runId = :id")
    suspend fun scan(id: String): MetadataScanRun?

    @Query("SELECT * FROM metadata_scan ORDER BY startedAt DESC LIMIT 50")
    fun observeScans(): Flow<List<MetadataScanRun>>

    @Query("UPDATE metadata_scan SET state = 'CANCELLED', failure = 'INTERRUPTED' WHERE state IN ('QUEUED', 'RUNNING', 'WAITING_NETWORK')")
    suspend fun interruptScans()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putDirectory(directory: MetadataScanDirectory)
}
