package com.xyoye.common_component.database.migration

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.xyoye.common_component.database.DatabaseInfo
import com.xyoye.common_component.database.DatabaseManager
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class RoomSchemaMigrationGateTest {
    @Test
    fun migration18To19AddsMetadataWithoutChangingExistingRows() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        deleteDatabaseFiles(context, TEST_DB_NAME)
        val file = context.getDatabasePath(TEST_DB_NAME).also { it.parentFile?.mkdirs() }
        createDatabaseFromRoomSchemaJson(file, loadSchemaJson(18), 18)
        val before: List<List<String?>>
        openHistoricalDatabase(context, 18).use { helper ->
            insertCurrentLibrary(helper.writableDatabase)
            before = rows(helper.writableDatabase.query("SELECT * FROM media_library"))
        }
        val database = openCurrentDatabase(context)
        try {
            assertEquals(before, rows(database.openHelper.writableDatabase.query("SELECT * FROM media_library")))
            assertTrue(tableExists(database.openHelper.writableDatabase, "metadata_work"))
            assertTrue(tableExists(database.openHelper.writableDatabase, "media_binding_episode"))
            assertEquals(19, database.openHelper.writableDatabase.version)
        } finally {
            database.close()
            deleteDatabaseFiles(context, TEST_DB_NAME)
        }
    }

    @Test
    fun migration16To17RemovesRetiredSourcesAndPreservesOtherRows() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        deleteDatabaseFiles(context, TEST_DB_NAME)
        val dbFile = context.getDatabasePath(TEST_DB_NAME).also { it.parentFile?.mkdirs() }
        createDatabaseFromRoomSchemaJson(dbFile, loadSchemaJson(16), 16)
        loadSchemaJson(17)
        val expectedLibraries: List<List<String?>>
        val expectedHistory: List<List<String?>>
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
            for ((id, type) in listOf(1 to "webdav_server", 2 to "bilibili_storage")) {
                database.execSQL(
                    "INSERT INTO media_library(" +
                        "id, display_name, url, media_type, account, password, is_anonymous, port, " +
                        "ftp_mode, ftp_address, ftp_encoding, smb_v2, web_dav_strict, " +
                        "web_dav_allow_insecure_tls, screencast_address, remote_anime_grouping, player_type_override" +
                        ") VALUES (?, 'Library', ?, ?, 'account', 'password', 0, 443, 0, '', 'UTF-8', 1, 1, 1, '', 0, 2)",
                    arrayOf(id, "https://example.test/$id", type)
                )
            }
            for ((id, type, storageId) in listOf(
                Triple(1, "webdav_server", 1),
                Triple(2, "bilibili_storage", 2),
                Triple(3, "other_storage", 2),
                Triple(4, "bilibili_storage", null)
            )) {
                database.execSQL(
                    "INSERT INTO play_history(" +
                        "id, video_name, url, media_type, video_position, video_duration, play_time, " +
                        "danmu_path, episode_id, subtitle_path, torrent_index, unique_key, storage_id, audio_path" +
                        ") VALUES (?, 'Video', 'play://video', ?, 12345, 90000, 123, " +
                        "'/local/bilibili_downloaded.xml', 'episode', '/local/subtitle.ass', -1, ?, ?, '/local/audio.flac')",
                    arrayOf(id, type, "key-$id", storageId)
                )
            }
            expectedLibraries = rows(database.rawQuery("SELECT * FROM media_library WHERE id = 1", null))
            expectedHistory = rows(database.rawQuery("SELECT * FROM play_history WHERE id = 1", null))
        }
        val roomDb = openHistoricalDatabase(context, 17, DatabaseManager.MIGRATION_16_17)
        try {
            val database = roomDb.writableDatabase
            assertEquals(17, database.version)
            assertEquals(expectedLibraries, rows(database.query("SELECT * FROM media_library")))
            assertEquals(expectedHistory, rows(database.query("SELECT * FROM play_history")))
        } finally {
            roomDb.close()
            deleteDatabaseFiles(context, TEST_DB_NAME)
        }
    }

    private fun rows(cursor: android.database.Cursor): List<List<String?>> =
        cursor.use {
            buildList {
                while (it.moveToNext()) {
                    add((0 until it.columnCount).map { column -> if (it.isNull(column)) null else it.getString(column) })
                }
            }
        }

    @Test
    fun migration17To18PreservesConnectionRowsHistoryIndexAndSequence() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        deleteDatabaseFiles(context, TEST_DB_NAME)
        val dbFile = context.getDatabasePath(TEST_DB_NAME).also { it.parentFile?.mkdirs() }
        createDatabaseFromRoomSchemaJson(dbFile, loadSchemaJson(17), 17)
        loadSchemaJson(CURRENT_VERSION)
        val expectedLibraries: List<List<String?>>
        val expectedHistory: List<List<String?>>
        openHistoricalDatabase(context, 17).use { helper ->
            val database = helper.writableDatabase
            seedMediaLibraryRows(database)
            seedPlayHistoryRows(database)
            database.execSQL(
                "UPDATE media_library SET player_type_override = 4, account = NULL, password = NULL, " +
                    "describe = NULL, smb_share_path = NULL, remote_secret = NULL WHERE id = 2"
            )
            reserveDeletedLibraryId(database)
            expectedLibraries = rows(database.query("SELECT $RETAINED_COLUMNS FROM media_library ORDER BY id"))
            expectedHistory = rows(database.query("SELECT * FROM play_history ORDER BY id"))
        }

        val roomDb = openCurrentDatabase(context)
        try {
            val database = roomDb.openHelper.writableDatabase
            assertCurrentLibrarySchema(database)
            assertEquals(expectedLibraries, rows(database.query("SELECT $RETAINED_COLUMNS FROM media_library ORDER BY id")))
            assertEquals(expectedHistory, rows(database.query("SELECT * FROM play_history ORDER BY id")))
            insertCurrentLibrary(database)
            assertEquals(100L, lastLibraryId(database))
            val duplicateFailure = runCatching { insertCurrentLibrary(database) }.exceptionOrNull()
            assertTrue("The source URL/type unique index must remain enforced", duplicateFailure is SQLiteConstraintException)
        } finally {
            roomDb.close()
            deleteDatabaseFiles(context, TEST_DB_NAME)
        }
    }

    @Test
    fun migration17To18HandlesLibraryWithoutAnyRows() {
        migrateEmptyLibrary(hadDeletedLibrary = false)
    }

    @Test
    fun migration17To18PreservesDeletedHighIdWhenLibraryIsEmpty() {
        migrateEmptyLibrary(hadDeletedLibrary = true)
    }

    private fun migrateEmptyLibrary(hadDeletedLibrary: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        deleteDatabaseFiles(context, TEST_DB_NAME)
        val dbFile = context.getDatabasePath(TEST_DB_NAME).also { it.parentFile?.mkdirs() }
        createDatabaseFromRoomSchemaJson(dbFile, loadSchemaJson(17), 17)
        if (hadDeletedLibrary) {
            openHistoricalDatabase(context, 17).use { helper ->
                val database = helper.writableDatabase
                seedMediaLibraryRows(database)
                reserveDeletedLibraryId(database)
                database.execSQL("DELETE FROM media_library")
            }
        }
        val roomDb = openCurrentDatabase(context)
        try {
            val database = roomDb.openHelper.writableDatabase
            assertCurrentLibrarySchema(database)
            assertTrue(rows(database.query("SELECT * FROM media_library")).isEmpty())
            insertCurrentLibrary(database)
            assertEquals(if (hadDeletedLibrary) 100L else 1L, lastLibraryId(database))
        } finally {
            roomDb.close()
            deleteDatabaseFiles(context, TEST_DB_NAME)
        }
    }

    @Test
    fun migration15And16To18PreservesConnectionDataAndTlsValues() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (version in listOf(15, 16)) {
            deleteDatabaseFiles(context, TEST_DB_NAME)
            val dbFile = context.getDatabasePath(TEST_DB_NAME).also { it.parentFile?.mkdirs() }
            createDatabaseFromRoomSchemaJson(dbFile, loadSchemaJson(version), version)
            val expectedLibraries: List<List<String?>>
            val expectedHistory: List<List<String?>>
            openHistoricalDatabase(context, version).use { helper ->
                val database = helper.writableDatabase
                seedMediaLibraryRows(database, includeTls = version >= 16)
                seedPlayHistoryRows(database)
                val projection =
                    if (version == 15) {
                        RETAINED_COLUMNS.replace(COLUMN_WEB_DAV_ALLOW_INSECURE_TLS, "0 AS $COLUMN_WEB_DAV_ALLOW_INSECURE_TLS")
                    } else {
                        RETAINED_COLUMNS
                    }
                expectedLibraries = rows(database.query("SELECT $projection FROM media_library ORDER BY id"))
                expectedHistory = rows(database.query("SELECT * FROM play_history ORDER BY id"))
            }
            val roomDb = openCurrentDatabase(context)
            try {
                val database = roomDb.openHelper.writableDatabase
                assertCurrentLibrarySchema(database)
                assertEquals(expectedLibraries, rows(database.query("SELECT $RETAINED_COLUMNS FROM media_library ORDER BY id")))
                assertEquals(expectedHistory, rows(database.query("SELECT * FROM play_history ORDER BY id")))
            } finally {
                roomDb.close()
                deleteDatabaseFiles(context, TEST_DB_NAME)
            }
        }
    }

    @Test
    fun newDatabaseContainsOnlySourceConnectionColumns() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        deleteDatabaseFiles(context, TEST_DB_NAME)
        val roomDb = openCurrentDatabase(context)
        try {
            val database = roomDb.openHelper.writableDatabase
            assertCurrentLibrarySchema(database)
            insertCurrentLibrary(database)
            assertEquals(1L, lastLibraryId(database))
        } finally {
            roomDb.close()
            deleteDatabaseFiles(context, TEST_DB_NAME)
        }
    }

    @Test
    fun schemaExportEnabledAndMigration15To16Works() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        deleteDatabaseFiles(context, TEST_DB_NAME)

        val dbFile =
            context.getDatabasePath(TEST_DB_NAME).also { file ->
                file.parentFile?.mkdirs()
            }

        val schemaJson = loadSchemaJson(version = OLD_VERSION)
        loadSchemaJson(version = NEW_VERSION)
        createDatabaseFromRoomSchemaJson(dbFile, schemaJson, version = OLD_VERSION)

        val roomDb = openHistoricalDatabase(context, NEW_VERSION, DatabaseManager.MIGRATION_15_16)

        try {
            val supportDb = roomDb.writableDatabase
            val columnNames = queryColumnNames(supportDb, tableName = TABLE_MEDIA_LIBRARY)

            assertTrue(
                "Migration 15->16 must add column web_dav_allow_insecure_tls to media_library",
                columnNames.contains(COLUMN_WEB_DAV_ALLOW_INSECURE_TLS),
            )
        } finally {
            roomDb.close()
            deleteDatabaseFiles(context, TEST_DB_NAME)
        }
    }

    @Test
    fun migration16To15KeepsMediaLibraryRowsAndSidecar() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        deleteDatabaseFiles(context, TEST_DB_NAME)

        val dbFile =
            context.getDatabasePath(TEST_DB_NAME).also { file ->
                file.parentFile?.mkdirs()
            }

        val schemaJson = loadSchemaJson(version = NEW_VERSION)
        createDatabaseFromRoomSchemaJson(dbFile, schemaJson, version = NEW_VERSION)

        val roomDb = openHistoricalDatabase(context, NEW_VERSION)

        try {
            val supportDb = roomDb.writableDatabase
            seedMediaLibraryRows(supportDb)

            DatabaseManager.MIGRATION_16_15.migrate(supportDb)
            supportDb.version = OLD_VERSION

            val columnNames = queryColumnNames(supportDb, tableName = TABLE_MEDIA_LIBRARY)
            assertFalse(
                "Migration 16->15 must remove column web_dav_allow_insecure_tls from media_library",
                columnNames.contains(COLUMN_WEB_DAV_ALLOW_INSECURE_TLS),
            )

            val mediaRows =
                queryMediaLibraryRowsWithoutTls(supportDb)
                    .associateBy { it.id }
            assertEquals(2, mediaRows.size)
            assertEquals("LibraryA", mediaRows.getValue(1L).displayName)
            assertEquals("LibraryB", mediaRows.getValue(2L).displayName)
            assertEquals(2, mediaRows.getValue(1L).playerTypeOverride)
            assertEquals(0, mediaRows.getValue(2L).playerTypeOverride)

            val sidecarMap = queryTlsSidecar(supportDb)
            assertEquals(mapOf(1L to 1, 2L to 0), sidecarMap)
        } finally {
            roomDb.close()
            deleteDatabaseFiles(context, TEST_DB_NAME)
        }
    }

    @Test
    fun migration16To15Then15To16RestoresTlsValues() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        deleteDatabaseFiles(context, TEST_DB_NAME)

        val dbFile =
            context.getDatabasePath(TEST_DB_NAME).also { file ->
                file.parentFile?.mkdirs()
            }

        val schemaJson = loadSchemaJson(version = NEW_VERSION)
        createDatabaseFromRoomSchemaJson(dbFile, schemaJson, version = NEW_VERSION)

        val roomDb = openHistoricalDatabase(context, NEW_VERSION)

        try {
            val supportDb = roomDb.writableDatabase
            seedMediaLibraryRows(supportDb)

            DatabaseManager.MIGRATION_16_15.migrate(supportDb)
            supportDb.version = OLD_VERSION

            DatabaseManager.MIGRATION_15_16.migrate(supportDb)
            supportDb.version = NEW_VERSION

            val columnNames = queryColumnNames(supportDb, tableName = TABLE_MEDIA_LIBRARY)
            assertTrue(
                "Migration 15->16 must re-add column web_dav_allow_insecure_tls",
                columnNames.contains(COLUMN_WEB_DAV_ALLOW_INSECURE_TLS),
            )

            val restoredTlsMap = queryMediaLibraryTlsMap(supportDb)
            assertEquals(mapOf(1L to 1, 2L to 0), restoredTlsMap)
            assertFalse(tableExists(supportDb, TABLE_MEDIA_LIBRARY_SIDECAR))
        } finally {
            roomDb.close()
            deleteDatabaseFiles(context, TEST_DB_NAME)
        }
    }

    @Test
    fun migration16To15KeepsPlayHistoryRowsUntouched() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        deleteDatabaseFiles(context, TEST_DB_NAME)

        val dbFile =
            context.getDatabasePath(TEST_DB_NAME).also { file ->
                file.parentFile?.mkdirs()
            }

        val schemaJson = loadSchemaJson(version = NEW_VERSION)
        createDatabaseFromRoomSchemaJson(dbFile, schemaJson, version = NEW_VERSION)

        val roomDb = openHistoricalDatabase(context, NEW_VERSION)

        try {
            val supportDb = roomDb.writableDatabase
            seedMediaLibraryRows(supportDb)
            seedPlayHistoryRows(supportDb)

            val before = queryPlayHistoryRows(supportDb)

            DatabaseManager.MIGRATION_16_15.migrate(supportDb)
            supportDb.version = OLD_VERSION

            val after = queryPlayHistoryRows(supportDb)
            assertEquals(before, after)
        } finally {
            roomDb.close()
            deleteDatabaseFiles(context, TEST_DB_NAME)
        }
    }

    private fun openCurrentDatabase(context: Context): DatabaseInfo =
        Room
            .databaseBuilder(context, DatabaseInfo::class.java, TEST_DB_NAME)
            .addMigrations(
                DatabaseManager.MIGRATION_15_16,
                DatabaseManager.MIGRATION_16_17,
                DatabaseManager.MIGRATION_17_18,
                com.xyoye.common_component.database.MetadataMigration
            ).build()

    private fun assertCurrentLibrarySchema(database: SupportSQLiteDatabase) {
        assertEquals(CURRENT_VERSION, database.version)
        assertEquals(RETAINED_COLUMNS.split(", ").toSet(), queryColumnNames(database, TABLE_MEDIA_LIBRARY))
        database.query("PRAGMA index_list(media_library)").use { cursor ->
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "index_media_library_url_media_type") {
                    found = true
                    assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("unique")))
                }
            }
            assertTrue("The source URL/type unique index must remain present", found)
        }
        database.query("PRAGMA index_info(index_media_library_url_media_type)").use { cursor ->
            val columns = mutableListOf<String>()
            while (cursor.moveToNext()) columns.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            assertEquals(listOf("url", "media_type"), columns)
        }
    }

    private fun insertCurrentLibrary(database: SupportSQLiteDatabase) {
        database.execSQL(
            "INSERT INTO media_library(${RETAINED_COLUMNS.removePrefix("id, ")}) VALUES(" +
                "'New source', 'https://example.com/new', 'webdav_server', NULL, NULL, 1, 443, NULL, " +
                "0, '', 'UTF-8', 1, NULL, NULL, 1, 0, '')"
        )
    }

    private fun reserveDeletedLibraryId(database: SupportSQLiteDatabase) {
        database.execSQL(
            "INSERT INTO media_library SELECT 99, 'Deleted', 'https://example.com/deleted', " +
                "media_type, account, password, is_anonymous, port, describe, ftp_mode, ftp_address, " +
                "ftp_encoding, smb_v2, smb_share_path, remote_secret, web_dav_strict, " +
                "web_dav_allow_insecure_tls, screencast_address, remote_anime_grouping, player_type_override " +
                "FROM media_library WHERE id = 1"
        )
        database.execSQL("DELETE FROM media_library WHERE id = 99")
    }

    private fun lastLibraryId(database: SupportSQLiteDatabase): Long =
        database.query("SELECT MAX(id) FROM media_library").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun openHistoricalDatabase(
        context: Context,
        version: Int,
        migration: Migration? = null
    ): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration
                .builder(context)
                .name(TEST_DB_NAME)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(version) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            error("Historical fixture is missing")
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int
                        ) {
                            val required = checkNotNull(migration)
                            check(required.startVersion == oldVersion && required.endVersion == newVersion)
                            required.migrate(db)
                        }
                    }
                ).build()
        )

    private fun loadSchemaJson(version: Int): JSONObject {
        val workingDir = checkNotNull(System.getProperty("user.dir")) { "System property user.dir is null" }
        val root =
            findProjectRoot(File(workingDir))
                ?: error("Cannot locate project root from user.dir=$workingDir")
        val canonicalName =
            checkNotNull(DatabaseInfo::class.java.canonicalName) { "DatabaseInfo canonicalName is null" }
        val schemaFile = File(root, "core_database_component/schemas/$canonicalName/$version.json")
        require(schemaFile.exists()) {
            "Missing Room schema file: ${schemaFile.absolutePath}. " +
                "Run :core_database_component:kaptDebugKotlin (or :core_database_component:testDebugUnitTest) " +
                "and commit generated schemas."
        }
        return JSONObject(schemaFile.readText())
    }

    private fun createDatabaseFromRoomSchemaJson(
        dbFile: File,
        schemaJson: JSONObject,
        version: Int
    ) {
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { database ->
            database.beginTransaction()
            try {
                val db = schemaJson.getJSONObject("database")

                val entities = db.getJSONArray("entities")
                forEachObject(entities) { entity ->
                    val tableName = entity.getString("tableName")
                    val tableSql =
                        entity
                            .getString("createSql")
                            .replace(ROOM_TABLE_NAME_PLACEHOLDER, tableName)
                    database.execSQL(tableSql)

                    val indices = entity.optJSONArray("indices")
                    if (indices != null) {
                        forEachObject(indices) { index ->
                            val createIndexSql =
                                index
                                    .optString("createSql")
                                    .replace(ROOM_TABLE_NAME_PLACEHOLDER, tableName)
                            if (createIndexSql.isNullOrBlank().not()) {
                                database.execSQL(createIndexSql)
                            }
                        }
                    }
                }

                val views = db.optJSONArray("views")
                if (views != null) {
                    forEachObject(views) { view ->
                        val viewName = view.optString("viewName")
                        val createViewSql = view.optString("createSql")
                        if (createViewSql.isNullOrBlank().not()) {
                            database.execSQL(createViewSql.replace(ROOM_VIEW_NAME_PLACEHOLDER, viewName))
                        }
                    }
                }

                val setupQueries = db.getJSONArray("setupQueries")
                for (i in 0 until setupQueries.length()) {
                    database.execSQL(setupQueries.getString(i))
                }

                database.version = version
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        }
    }

    private fun seedMediaLibraryRows(
        database: SupportSQLiteDatabase,
        includeTls: Boolean = true
    ) {
        val tlsColumn = if (includeTls) "web_dav_allow_insecure_tls, " else ""
        database.execSQL(
            "INSERT INTO media_library(" +
                "id, display_name, url, media_type, account, password, is_anonymous, port, describe," +
                "ftp_mode, ftp_address, ftp_encoding, smb_v2, smb_share_path, remote_secret," +
                "web_dav_strict, ${tlsColumn}screencast_address, remote_anime_grouping, player_type_override" +
                ") VALUES(" +
                "1, 'LibraryA', 'https://example.com/a', 'webdav', 'a', 'pa', 0, 443, 'desc-a'," +
                "0, '', 'UTF-8', 1, '/a', 'secret-a'," +
                "1, ${if (includeTls) "1, " else ""}'cast-a', 0, 2" +
                ")",
        )
        database.execSQL(
            "INSERT INTO media_library(" +
                "id, display_name, url, media_type, account, password, is_anonymous, port, describe," +
                "ftp_mode, ftp_address, ftp_encoding, smb_v2, smb_share_path, remote_secret," +
                "web_dav_strict, ${tlsColumn}screencast_address, remote_anime_grouping, player_type_override" +
                ") VALUES(" +
                "2, 'LibraryB', 'https://example.com/b', 'webdav', 'b', 'pb', 1, 80, 'desc-b'," +
                "0, '', 'UTF-8', 0, '/b', 'secret-b'," +
                "1, ${if (includeTls) "0, " else ""}'cast-b', 1, 0" +
                ")",
        )
    }

    private fun seedPlayHistoryRows(database: SupportSQLiteDatabase) {
        database.execSQL(
            "INSERT INTO play_history(" +
                "id, video_name, url, media_type, video_position, video_duration, play_time, danmu_path, episode_id," +
                "subtitle_path, torrent_path, torrent_index, http_header, unique_key, storage_path, storage_id, audio_path" +
                ") VALUES(" +
                "1, 'VideoA', 'play://a', 'webdav', 120, 3600, 1, NULL, 'ep-a'," +
                "NULL, NULL, -1, NULL, 'unique-a', NULL, 1, NULL" +
                ")",
        )
    }

    private fun queryColumnNames(
        database: SupportSQLiteDatabase,
        tableName: String
    ): Set<String> {
        val cursor = database.query("PRAGMA table_info(`$tableName`)")
        val columnNames = mutableSetOf<String>()
        cursor.use {
            val nameIndex = it.getColumnIndex("name")
            while (it.moveToNext()) {
                if (nameIndex >= 0) {
                    columnNames.add(it.getString(nameIndex))
                }
            }
        }
        return columnNames
    }

    private fun queryMediaLibraryRowsWithoutTls(database: SupportSQLiteDatabase): List<MediaLibraryRow> {
        val cursor =
            database.query(
                "SELECT id, display_name, player_type_override FROM media_library ORDER BY id",
            )
        return cursor.use {
            val idIndex = it.getColumnIndex("id")
            val displayNameIndex = it.getColumnIndex("display_name")
            val playerTypeOverrideIndex = it.getColumnIndex("player_type_override")
            val rows = mutableListOf<MediaLibraryRow>()
            while (it.moveToNext()) {
                rows.add(
                    MediaLibraryRow(
                        id = it.getLong(idIndex),
                        displayName = it.getString(displayNameIndex),
                        playerTypeOverride = it.getInt(playerTypeOverrideIndex),
                    ),
                )
            }
            rows
        }
    }

    private fun queryTlsSidecar(database: SupportSQLiteDatabase): Map<Long, Int> {
        val cursor =
            database.query(
                "SELECT id, web_dav_allow_insecure_tls FROM $TABLE_MEDIA_LIBRARY_SIDECAR ORDER BY id",
            )
        return cursor.use {
            val idIndex = it.getColumnIndex("id")
            val valueIndex = it.getColumnIndex(COLUMN_WEB_DAV_ALLOW_INSECURE_TLS)
            val map = linkedMapOf<Long, Int>()
            while (it.moveToNext()) {
                map[it.getLong(idIndex)] = it.getInt(valueIndex)
            }
            map
        }
    }

    private fun queryMediaLibraryTlsMap(database: SupportSQLiteDatabase): Map<Long, Int> {
        val cursor =
            database.query(
                "SELECT id, web_dav_allow_insecure_tls FROM media_library ORDER BY id",
            )
        return cursor.use {
            val idIndex = it.getColumnIndex("id")
            val tlsIndex = it.getColumnIndex(COLUMN_WEB_DAV_ALLOW_INSECURE_TLS)
            val map = linkedMapOf<Long, Int>()
            while (it.moveToNext()) {
                map[it.getLong(idIndex)] = it.getInt(tlsIndex)
            }
            map
        }
    }

    private fun queryPlayHistoryRows(database: SupportSQLiteDatabase): List<PlayHistoryRow> {
        val cursor =
            database.query(
                "SELECT id, video_name, unique_key, storage_id FROM play_history ORDER BY id",
            )
        return cursor.use {
            val idIndex = it.getColumnIndex("id")
            val videoNameIndex = it.getColumnIndex("video_name")
            val uniqueKeyIndex = it.getColumnIndex("unique_key")
            val storageIdIndex = it.getColumnIndex("storage_id")
            val rows = mutableListOf<PlayHistoryRow>()
            while (it.moveToNext()) {
                val storageId =
                    if (it.isNull(storageIdIndex)) {
                        null
                    } else {
                        it.getLong(storageIdIndex)
                    }
                rows.add(
                    PlayHistoryRow(
                        id = it.getLong(idIndex),
                        videoName = it.getString(videoNameIndex),
                        uniqueKey = it.getString(uniqueKeyIndex),
                        storageId = storageId,
                    ),
                )
            }
            rows
        }
    }

    private fun tableExists(
        database: SupportSQLiteDatabase,
        tableName: String
    ): Boolean {
        val cursor =
            database.query(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name='$tableName' LIMIT 1",
            )
        return cursor.use { it.moveToFirst() }
    }

    private fun forEachObject(
        array: JSONArray,
        block: (JSONObject) -> Unit
    ) {
        for (i in 0 until array.length()) {
            block(array.getJSONObject(i))
        }
    }

    private fun findProjectRoot(startDir: File): File? {
        var dir: File? = startDir
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile
        }
        return null
    }

    private fun deleteDatabaseFiles(
        context: Context,
        name: String
    ) {
        val dbFile = context.getDatabasePath(name)
        deleteFileIfExists(dbFile)
        deleteFileIfExists(File(dbFile.absolutePath + "-shm"))
        deleteFileIfExists(File(dbFile.absolutePath + "-wal"))
    }

    private fun deleteFileIfExists(file: File) {
        if (file.exists() && !file.delete()) {
            throw IllegalStateException("Failed to delete file: ${file.absolutePath}")
        }
    }

    private data class MediaLibraryRow(
        val id: Long,
        val displayName: String,
        val playerTypeOverride: Int
    )

    private data class PlayHistoryRow(
        val id: Long,
        val videoName: String,
        val uniqueKey: String,
        val storageId: Long?
    )

    private companion object {
        const val OLD_VERSION = 15
        const val NEW_VERSION = 16
        const val CURRENT_VERSION = 19
        const val TEST_DB_NAME = "room_migration_gate_test.db"
        private const val ROOM_TABLE_NAME_PLACEHOLDER = "${'$'}{TABLE_NAME}"
        private const val ROOM_VIEW_NAME_PLACEHOLDER = "${'$'}{VIEW_NAME}"
        private const val TABLE_MEDIA_LIBRARY = "media_library"
        private const val TABLE_MEDIA_LIBRARY_SIDECAR = "media_library_v16_ext"
        private const val COLUMN_WEB_DAV_ALLOW_INSECURE_TLS = "web_dav_allow_insecure_tls"
        private const val RETAINED_COLUMNS =
            "id, display_name, url, media_type, account, password, is_anonymous, port, describe, " +
                "ftp_mode, ftp_address, ftp_encoding, smb_v2, smb_share_path, remote_secret, " +
                "web_dav_strict, web_dav_allow_insecure_tls, screencast_address"
    }
}
