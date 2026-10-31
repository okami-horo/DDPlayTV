package com.xyoye.common_component.storage.catalog

import com.xyoye.common_component.playback.handoff.PlaybackFileReference
import com.xyoye.common_component.storage.Storage
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.MediaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class CatalogSourceReaderTest {
    @Test fun metadataReadsOnlyPropertiesAndClosesEveryIndependentConnection() =
        runBlocking {
            var created = 0
            var closed = 0
            val reader =
                CatalogSourceReader { library ->
                    created++
                    source(library, { closed++ })
                }
            repeat(2) {
                val file = reader.file(library, PlaybackFileReference("history", "/file.mkv"))
                assertEquals("path:/file.mkv", file.locatorKey)
                assertNull(file.size)
                assertNull(file.modifiedAt)
            }
            assertEquals(2, created)
            assertEquals(2, closed)
        }

    @Test fun failedDirectoryAndCancellationNeverBecomeCompleteEmptySnapshots() =
        runBlocking {
            for (error in listOf(IOException("second page failed"), CancellationException("cancel"))) {
                var closed = false
                val reader = CatalogSourceReader { source(it, { closed = true }, error) }
                try {
                    reader.directory(library, "/")
                    fail("Directory failure must propagate")
                } catch (
                    actual: Exception
                ) {
                    // Proxy and coroutine stack recovery may each wrap the original failure.
                    assertTrue(generateSequence(actual as Throwable) { it.cause }.any { it === error })
                    if (error is CancellationException) assertTrue(actual is CancellationException)
                }
                assertTrue(closed)
            }
        }

    @Test fun unverifiedProtocolsCannotClaimFullCatalogCoverage() {
        assertTrue(CatalogSourceReader.supports(MediaType.LOCAL_STORAGE))
        assertTrue(CatalogSourceReader.supports(MediaType.EXTERNAL_STORAGE))
        assertTrue(CatalogSourceReader.supports(MediaType.SMB_SERVER))
        assertTrue(CatalogSourceReader.supports(MediaType.WEBDAV_SERVER))
        assertFalse(CatalogSourceReader.supports(MediaType.REMOTE_STORAGE))
        assertFalse(CatalogSourceReader.supports(MediaType.STREAM_LINK))
    }

    private val library = MediaLibraryEntity(10, "Fixture", "https://fixture.test", MediaType.WEBDAV_SERVER)

    private fun source(
        library: MediaLibraryEntity,
        close: () -> Unit,
        failure: Exception? = null
    ): Storage {
        lateinit var storage: Storage

        fun file(directory: Boolean): StorageFile =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(StorageFile::class.java)) { _, method, _ ->
                when (method.name) {
                    "getStorage" -> storage
                    "getPlayHistory" -> null
                    "uniqueKey" -> "history"
                    "storagePath", "filePath" -> if (directory) "/" else "/file.mkv"
                    "fileName" -> if (directory) "root" else "file.mkv"
                    "fileLength" -> 0L
                    "isDirectory" -> directory
                    "isFile", "isVideoFile" -> !directory
                    "canRead" -> true
                    "close" -> null
                    else -> throw AssertionError("Unexpected property or media access: ${method.name}")
                }
            } as StorageFile
        storage =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Storage::class.java)) { _, method, args ->
                when (method.name) {
                    "getLibrary" -> library
                    "pathFile" -> file(args[1] as Boolean)
                    "getRootFile" -> file(true)
                    "openDirectory" -> {
                        if (failure != null) throw failure
                        listOf(file(false))
                    }
                    "close" -> {
                        close()
                        null
                    }
                    else -> throw AssertionError("Unexpected media/connection access: ${method.name}")
                }
            } as Storage
        return storage
    }
}
