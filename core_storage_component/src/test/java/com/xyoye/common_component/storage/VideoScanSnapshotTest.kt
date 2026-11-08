package com.xyoye.common_component.storage

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.room.Room
import com.xyoye.common_component.database.DatabaseAccessProvider
import com.xyoye.common_component.database.DatabaseInfo
import com.xyoye.common_component.database.DatabaseProvider
import com.xyoye.common_component.source.handoff.PlaybackSourceResolverTest.DefaultPreferences
import com.xyoye.common_component.storage.impl.VideoStorage
import com.xyoye.common_component.utils.meida.VideoScan
import com.xyoye.data_component.entity.ExtendFolderEntity
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.entity.VideoEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, shadows = [DefaultPreferences::class])
class VideoScanSnapshotTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var database: DatabaseInfo
    private lateinit var provider: MediaProvider
    private val old = VideoEntity(fileId = 1, filePath = "/fixture/old.mkv", folderPath = "/fixture", isFilter = true)

    @Before fun setup() =
        runBlocking {
            database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), DatabaseInfo::class.java).build()
            DatabaseProvider.overrideProviderForTest(DatabaseAccessProvider { database })
            database.getVideoDao().insert(old)
            provider = MediaProvider()
            ShadowContentResolver.registerProviderInternal("media", provider)
        }

    @After fun close() {
        DatabaseProvider.overrideProviderForTest(null)
        database.close()
    }

    @Test fun unavailableScanRootPreservesExistingRows() =
        runBlocking {
            val absent = File(temporary.root, "unavailable")
            database.getExtendFolderDao().insert(ExtendFolderEntity(absent.path, 1))
            val storage = VideoStorage(MediaLibraryEntity.LOCAL)
            assertTrue(runCatching { storage.openDirectory(storage.getRootFile(), true) }.exceptionOrNull() is IOException)
            assertTrue(requireNotNull(database.getVideoDao().getVideo(old.filePath)).isFilter)
        }

    @Test fun nullMediaStoreCursorPreservesExistingRows() =
        runBlocking {
            provider.available = false
            val storage = VideoStorage(MediaLibraryEntity.LOCAL)
            assertTrue(runCatching { storage.openDirectory(storage.getRootFile(), true) }.exceptionOrNull() is IOException)
            assertNotNull(database.getVideoDao().getVideo(old.filePath))
        }

    @Test fun successfulEmptyScanStillRemovesMissingRows() =
        runBlocking {
            val empty = temporary.newFolder("empty")
            database.getExtendFolderDao().insert(ExtendFolderEntity(empty.path, 0))
            val storage = VideoStorage(MediaLibraryEntity.LOCAL)
            assertTrue(storage.openDirectory(storage.getRootFile(), true).isEmpty())
            assertNull(database.getVideoDao().getVideo(old.filePath))
        }

    @Test fun nullDirectoryListingIsAnErrorRatherThanEmptySuccess() {
        val directory =
            object : File("fixture") {
                override fun exists() = true

                override fun canRead() = true

                override fun isFile() = false

                override fun listFiles(): Array<File>? = null
            }
        val method = VideoScan::class.java.getDeclaredMethod("traverse", File::class.java).apply { isAccessible = true }
        val failure = runCatching { method.invoke(VideoScan, directory) }.exceptionOrNull()
        assertTrue((failure as? InvocationTargetException)?.cause is IOException)
    }

    private class MediaProvider : ContentProvider() {
        var available = true

        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            args: Array<out String>?,
            sortOrder: String?
        ): Cursor? = if (available) MatrixCursor(projection ?: emptyArray()) else null

        override fun getType(uri: Uri): String? = null

        override fun insert(
            uri: Uri,
            values: ContentValues?
        ): Uri? = null

        override fun delete(
            uri: Uri,
            selection: String?,
            args: Array<out String>?
        ) = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            args: Array<out String>?
        ) = 0
    }
}
