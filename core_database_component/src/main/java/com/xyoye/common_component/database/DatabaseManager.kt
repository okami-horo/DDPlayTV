package com.xyoye.common_component.database

import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.xyoye.common_component.base.app.BaseApplication

/**
 * Created by xyoye on 2020/7/29.
 */

class DatabaseManager private constructor() {
    // "CREATE UNIQUE INDEX IF NOT EXISTS index_anime_search_history_search_text ON anime_search_history(search_text)"
    // "CREATE TABLE anime_search_history( id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, search_text TEXT NOT NULL UNIQUE, search_time INTEGER NOT NULL)"
    // "ALTER TABLE search_history RENAME TO magnet_search_history"
    // "ALTER TABLE magnet_screen ADD COLUMN screen_id INTEGER NOT NULL"

    companion object {
        private const val MEDIA_LIBRARY_SIDECAR_TABLE = "media_library_v16_ext"
        private const val MEDIA_LIBRARY_TEMP_TABLE = "media_library_temp"

        val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE media_library ADD COLUMN remote_secret TEXT")
                }
            }

        val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("DROP INDEX IF EXISTS 'index_media_library_url'")
                    database.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS index_media_library_url_media_type ON media_library(url, media_type)",
                    )
                }
            }

        val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    // 新建临时表
                    database.execSQL(
                        "CREATE TABLE play_history_temp(" +
                            "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                            "video_name TEXT NOT NULL," +
                            "url TEXT NOT NULL UNIQUE, " +
                            "media_type TEXT NOT NULL," +
                            "video_position INTEGER NOT NULL," +
                            "video_duration INTEGER NOT NULL," +
                            "play_time INTEGER NOT NULL," +
                            "danmu_path TEXT," +
                            "episode_id INTEGER NOT NULL," +
                            "subtitle_path TEXT," +
                            "extra TEXT)",
                    )
                    // 旧表数据迁移
                    database.execSQL(
                        "INSERT INTO play_history_temp(id, video_name, url, media_type, video_position, video_duration, play_time, danmu_path, episode_id,subtitle_path) " +
                            "SELECT id, video_name, url, media_type, video_position, video_duration, play_time, danmu_path, episode_id,subtitle_path FROM play_history",
                    )
                    // 移除旧表
                    database.execSQL("DROP TABLE play_history")
                    // 重命名为旧表
                    database.execSQL("ALTER TABLE play_history_temp RENAME TO play_history")
                    // 加上唯一约束
                    database.execSQL("DROP INDEX IF EXISTS 'index_play_history_url'")
                    database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_play_history_url ON play_history(url)")
                }
            }

        val MIGRATION_4_5 =
            object : Migration(4, 5) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE media_library ADD COLUMN web_dav_strict INTEGER NOT NULL DEFAULT 1")
                }
            }

        val MIGRATION_5_6 =
            object : Migration(5, 6) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE play_history ADD COLUMN torrent_path TEXT")
                    database.execSQL("ALTER TABLE play_history ADD COLUMN torrent_index INTEGER NOT NULL DEFAULT -1")
                    database.execSQL("ALTER TABLE play_history ADD COLUMN http_header TEXT")
                }
            }

        val MIGRATION_6_7 =
            object : Migration(6, 7) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE play_history ADD COLUMN unique_key TEXT NOT NULL DEFAULT ''")
                    // 更新新增字段默认值为随机值
                    database.execSQL("UPDATE play_history SET unique_key = hex(randomblob(16)) WHERE unique_key = ''")
                    // 移除旧的唯一约束
                    database.execSQL("DROP INDEX IF EXISTS 'index_play_history_url'")
                    // 设置unique_key和media_type的多列唯一约束
                    database.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS index_play_history_unique_key_media_type ON play_history(unique_key, media_type)",
                    )
                }
            }

        val MIGRATION_7_8 =
            object : Migration(7, 8) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE media_library ADD COLUMN screencast_address TEXT NOT NULL DEFAULT ''")
                }
            }

        val MIGRATION_8_9 =
            object : Migration(8, 9) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE media_library ADD COLUMN remote_anime_grouping INTEGER NOT NULL DEFAULT 0")
                }
            }

        val MIGRATION_9_10 =
            object : Migration(9, 10) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE play_history ADD COLUMN is_last_play INTEGER NOT NULL DEFAULT 0")
                }
            }

        val MIGRATION_10_11 =
            object : Migration(10, 11) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE play_history ADD COLUMN storage_path TEXT")
                    database.execSQL("ALTER TABLE play_history ADD COLUMN storage_id INTEGER")
                }
            }

        val MIGRATION_11_12 =
            object : Migration(11, 12) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    // 新建临时表
                    database.execSQL(
                        "CREATE TABLE play_history_temp(" +
                            "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT," +
                            "video_name TEXT NOT NULL," +
                            "url TEXT NOT NULL," +
                            "media_type TEXT NOT NULL," +
                            "video_position INTEGER NOT NULL," +
                            "video_duration INTEGER NOT NULL," +
                            "play_time INTEGER NOT NULL," +
                            "danmu_path TEXT," +
                            "episode_id TEXT," +
                            "subtitle_path TEXT," +
                            "torrent_path TEXT," +
                            "torrent_index INTEGER NOT NULL DEFAULT -1," +
                            "http_header TEXT," +
                            "unique_key TEXT NOT NULL DEFAULT ''," +
                            "storage_path TEXT," +
                            "storage_id INTEGER" +
                            ")",
                    )
                    // 旧表数据迁移
                    database.execSQL(
                        "INSERT INTO play_history_temp(" +
                            "id, video_name, url, media_type, video_position, " +
                            "video_duration, play_time, danmu_path, episode_id," +
                            "subtitle_path, torrent_path, torrent_index, http_header, " +
                            "unique_key, storage_path, storage_id" +
                            ") " +
                            "SELECT " +
                            "id, video_name, url, media_type, video_position," +
                            "video_duration, play_time, danmu_path, episode_id," +
                            "subtitle_path, torrent_path, torrent_index, http_header," +
                            "unique_key, storage_path, storage_id" +
                            " FROM play_history",
                    )
                    // 移除旧表
                    database.execSQL("DROP TABLE play_history")
                    // 重命名为旧表
                    database.execSQL("ALTER TABLE play_history_temp RENAME TO play_history")
                    // 移除旧的mediaType + uniqueKey唯一约束
                    database.execSQL("DROP INDEX IF EXISTS 'index_play_history_unique_key_media_type'")
                    // 创建新的storageId + uniqueKey的唯一约束
                    database.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS index_play_history_unique_key_storage_id ON play_history(unique_key, storage_id)",
                    )
                }
            }

        val MIGRATION_12_13 =
            object : Migration(12, 13) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE play_history ADD COLUMN audio_path TEXT")
                }
            }

        val MIGRATION_13_14 =
            object : Migration(13, 14) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        "CREATE TABLE IF NOT EXISTS media3_rollout_snapshot(" +
                            "snapshotId TEXT NOT NULL PRIMARY KEY," +
                            "flagName TEXT NOT NULL," +
                            "value INTEGER NOT NULL," +
                            "source TEXT NOT NULL," +
                            "evaluatedAt INTEGER NOT NULL," +
                            "appliesToSession TEXT" +
                            ")",
                    )
                    database.execSQL(
                        "CREATE TABLE IF NOT EXISTS media3_download_asset_check(" +
                            "downloadId TEXT NOT NULL PRIMARY KEY," +
                            "mediaId TEXT NOT NULL," +
                            "lastVerifiedAt INTEGER," +
                            "isCompatible INTEGER NOT NULL," +
                            "requiredAction TEXT NOT NULL," +
                            "verificationLogs TEXT NOT NULL DEFAULT ''" +
                            ")",
                    )
                }
            }

        val MIGRATION_14_15 =
            object : Migration(14, 15) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        "ALTER TABLE media_library ADD COLUMN player_type_override INTEGER NOT NULL DEFAULT 0",
                    )
                }
            }

        val MIGRATION_15_16 =
            object : Migration(15, 16) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        "ALTER TABLE media_library ADD COLUMN web_dav_allow_insecure_tls INTEGER NOT NULL DEFAULT 0",
                    )

                    val sidecarExists =
                        database
                            .query(
                                "SELECT 1 FROM sqlite_master WHERE type='table' AND name='$MEDIA_LIBRARY_SIDECAR_TABLE' LIMIT 1",
                            ).use { cursor ->
                                cursor.moveToFirst()
                            }
                    if (sidecarExists) {
                        database.execSQL(
                            "UPDATE media_library " +
                                "SET web_dav_allow_insecure_tls = COALESCE((" +
                                "SELECT web_dav_allow_insecure_tls FROM $MEDIA_LIBRARY_SIDECAR_TABLE " +
                                "WHERE $MEDIA_LIBRARY_SIDECAR_TABLE.id = media_library.id" +
                                "), web_dav_allow_insecure_tls)",
                        )
                        database.execSQL("DROP TABLE IF EXISTS $MEDIA_LIBRARY_SIDECAR_TABLE")
                    }
                }
            }

        val MIGRATION_16_17 =
            object : Migration(16, 17) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    // Remove retired sources before their enum values can fall back to OTHER_STORAGE.
                    database.execSQL(
                        "DELETE FROM play_history WHERE media_type = 'bilibili_storage' " +
                            "OR storage_id IN (SELECT id FROM media_library WHERE media_type = 'bilibili_storage')"
                    )
                    database.execSQL("DELETE FROM media_library WHERE media_type = 'bilibili_storage'")
                }
            }

        val MIGRATION_17_18 =
            object : Migration(17, 18) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    val sequence =
                        database.query("SELECT seq FROM sqlite_sequence WHERE name = 'media_library'").use { cursor ->
                            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
                        }
                    database.execSQL(
                        "CREATE TABLE $MEDIA_LIBRARY_TEMP_TABLE(" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL," +
                            "display_name TEXT NOT NULL," +
                            "url TEXT NOT NULL," +
                            "media_type TEXT NOT NULL," +
                            "account TEXT," +
                            "password TEXT," +
                            "is_anonymous INTEGER NOT NULL," +
                            "port INTEGER NOT NULL," +
                            "describe TEXT," +
                            "ftp_mode INTEGER NOT NULL," +
                            "ftp_address TEXT NOT NULL," +
                            "ftp_encoding TEXT NOT NULL," +
                            "smb_v2 INTEGER NOT NULL," +
                            "smb_share_path TEXT," +
                            "remote_secret TEXT," +
                            "web_dav_strict INTEGER NOT NULL," +
                            "web_dav_allow_insecure_tls INTEGER NOT NULL," +
                            "screencast_address TEXT NOT NULL" +
                            ")"
                    )
                    val columns =
                        "id, display_name, url, media_type, account, password, is_anonymous, port, describe, " +
                            "ftp_mode, ftp_address, ftp_encoding, smb_v2, smb_share_path, remote_secret, " +
                            "web_dav_strict, web_dav_allow_insecure_tls, screencast_address"
                    database.execSQL(
                        "INSERT INTO $MEDIA_LIBRARY_TEMP_TABLE($columns) SELECT $columns FROM media_library"
                    )
                    database.execSQL("DROP TABLE media_library")
                    database.execSQL("ALTER TABLE $MEDIA_LIBRARY_TEMP_TABLE RENAME TO media_library")
                    database.execSQL(
                        "CREATE UNIQUE INDEX index_media_library_url_media_type ON media_library(url, media_type)"
                    )
                    // Credentials and history reference library IDs, including IDs of deleted libraries.
                    database.execSQL("DELETE FROM sqlite_sequence WHERE name = 'media_library'")
                    database.execSQL(
                        "INSERT INTO sqlite_sequence(name, seq) VALUES ('media_library', ?)",
                        arrayOf(sequence)
                    )
                }
            }

        val MIGRATION_16_15 =
            object : Migration(16, 15) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        "CREATE TABLE IF NOT EXISTS $MEDIA_LIBRARY_SIDECAR_TABLE(" +
                            "id INTEGER NOT NULL PRIMARY KEY," +
                            "web_dav_allow_insecure_tls INTEGER NOT NULL" +
                            ")",
                    )
                    database.execSQL("DELETE FROM $MEDIA_LIBRARY_SIDECAR_TABLE")
                    database.execSQL(
                        "INSERT INTO $MEDIA_LIBRARY_SIDECAR_TABLE(id, web_dav_allow_insecure_tls) " +
                            "SELECT id, web_dav_allow_insecure_tls FROM media_library",
                    )

                    database.execSQL("DROP TABLE IF EXISTS $MEDIA_LIBRARY_TEMP_TABLE")
                    database.execSQL(
                        "CREATE TABLE $MEDIA_LIBRARY_TEMP_TABLE(" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL," +
                            "display_name TEXT NOT NULL," +
                            "url TEXT NOT NULL," +
                            "media_type TEXT NOT NULL," +
                            "account TEXT," +
                            "password TEXT," +
                            "is_anonymous INTEGER NOT NULL," +
                            "port INTEGER NOT NULL," +
                            "describe TEXT," +
                            "ftp_mode INTEGER NOT NULL," +
                            "ftp_address TEXT NOT NULL," +
                            "ftp_encoding TEXT NOT NULL," +
                            "smb_v2 INTEGER NOT NULL," +
                            "smb_share_path TEXT," +
                            "remote_secret TEXT," +
                            "web_dav_strict INTEGER NOT NULL," +
                            "screencast_address TEXT NOT NULL," +
                            "remote_anime_grouping INTEGER NOT NULL," +
                            "player_type_override INTEGER NOT NULL" +
                            ")",
                    )
                    database.execSQL(
                        "INSERT INTO $MEDIA_LIBRARY_TEMP_TABLE(" +
                            "id, display_name, url, media_type, account, password, is_anonymous, port, describe," +
                            "ftp_mode, ftp_address, ftp_encoding, smb_v2, smb_share_path, remote_secret," +
                            "web_dav_strict, screencast_address, remote_anime_grouping, player_type_override" +
                            ") " +
                            "SELECT " +
                            "id, display_name, url, media_type, account, password, is_anonymous, port, describe," +
                            "ftp_mode, ftp_address, ftp_encoding, smb_v2, smb_share_path, remote_secret," +
                            "web_dav_strict, screencast_address, remote_anime_grouping, player_type_override " +
                            "FROM media_library",
                    )
                    database.execSQL("DROP TABLE media_library")
                    database.execSQL("ALTER TABLE $MEDIA_LIBRARY_TEMP_TABLE RENAME TO media_library")
                    database.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS index_media_library_url_media_type ON media_library(url, media_type)",
                    )
                }
            }

        val instance = Holder.instance.database
    }

    private object Holder {
        val instance = DatabaseManager()
    }

    private var database =
        Room
            .databaseBuilder(
                BaseApplication.getAppContext(),
                DatabaseInfo::class.java,
                "rood_db",
            ).addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                MIGRATION_11_12,
                MIGRATION_12_13,
                MIGRATION_13_14,
                MIGRATION_14_15,
                MIGRATION_15_16,
                MIGRATION_16_17,
                MIGRATION_17_18,
                MetadataMigration,
                MIGRATION_16_15,
            ).build()
}
