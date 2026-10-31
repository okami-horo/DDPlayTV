package com.xyoye.common_component.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.xyoye.common_component.database.dao.AnimeSearchHistoryDao
import com.xyoye.common_component.database.dao.DanmuBlockDao
import com.xyoye.common_component.database.dao.ExtendFolderDao
import com.xyoye.common_component.database.dao.MagnetScreenDao
import com.xyoye.common_component.database.dao.MagnetSearchHistoryDao
import com.xyoye.common_component.database.dao.Media3Dao
import com.xyoye.common_component.database.dao.MediaLibraryDao
import com.xyoye.common_component.database.dao.MetadataDao
import com.xyoye.common_component.database.dao.PlayHistoryDao
import com.xyoye.common_component.database.dao.VideoDao
import com.xyoye.data_component.entity.AnimeSearchHistoryEntity
import com.xyoye.data_component.entity.CatalogSourceState
import com.xyoye.data_component.entity.DanmuBlockEntity
import com.xyoye.data_component.entity.ExtendFolderEntity
import com.xyoye.data_component.entity.MagnetScreenEntity
import com.xyoye.data_component.entity.MagnetSearchHistoryEntity
import com.xyoye.data_component.entity.MediaAsset
import com.xyoye.data_component.entity.MediaBinding
import com.xyoye.data_component.entity.MediaBindingEpisode
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.entity.MetadataEpisode
import com.xyoye.data_component.entity.MetadataScanDirectory
import com.xyoye.data_component.entity.MetadataScanRun
import com.xyoye.data_component.entity.MetadataSeason
import com.xyoye.data_component.entity.MetadataWork
import com.xyoye.data_component.entity.PlayHistoryEntity
import com.xyoye.data_component.entity.VideoEntity
import com.xyoye.data_component.media3.entity.DownloadAssetCheck
import com.xyoye.data_component.media3.entity.RolloutToggleSnapshot

/**
 * Created by xyoye on 2020/7/29.
 */

@Database(
    entities =
        [
            VideoEntity::class,
            MagnetSearchHistoryEntity::class,
            AnimeSearchHistoryEntity::class,
            MagnetScreenEntity::class,
            MediaLibraryEntity::class,
            PlayHistoryEntity::class,
            DanmuBlockEntity::class,
            ExtendFolderEntity::class,
            RolloutToggleSnapshot::class,
            DownloadAssetCheck::class,
            CatalogSourceState::class,
            MediaAsset::class,
            MediaBinding::class,
            MediaBindingEpisode::class,
            MetadataWork::class,
            MetadataSeason::class,
            MetadataEpisode::class,
            MetadataScanRun::class,
            MetadataScanDirectory::class,
        ],
    version = 19,
    exportSchema = true,
)
abstract class DatabaseInfo : RoomDatabase() {
    abstract fun getMetadataDao(): MetadataDao

    abstract fun getVideoDao(): VideoDao

    abstract fun getAnimeSearchHistoryDao(): AnimeSearchHistoryDao

    abstract fun getMagnetSearchHistoryDao(): MagnetSearchHistoryDao

    abstract fun getMagnetScreenDao(): MagnetScreenDao

    abstract fun getMediaLibraryDao(): MediaLibraryDao

    abstract fun getPlayHistoryDao(): PlayHistoryDao

    abstract fun getDanmuBlockDao(): DanmuBlockDao

    abstract fun getExtendFolderDao(): ExtendFolderDao

    abstract fun getMedia3Dao(): Media3Dao
}
