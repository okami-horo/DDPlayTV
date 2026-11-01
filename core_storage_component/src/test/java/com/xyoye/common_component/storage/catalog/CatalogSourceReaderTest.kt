package com.xyoye.common_component.storage.catalog

import com.xyoye.common_component.playback.handoff.PlaybackFileReference
import com.xyoye.common_component.storage.PagedStorage
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
@Config(manifest = Config.NONE, shadows = [com.xyoye.common_component.source.handoff.PlaybackSourceResolverTest.DefaultPreferences::class])
class CatalogSourceReaderTest {
    @get:org.junit.Rule val temporary = org.junit.rules.TemporaryFolder()

    @Test fun localDirectoryFindsUnindexedSiblingFilesWithoutRecursiveTraversal() =
        runBlocking {
            val folder = temporary.newFolder("series")
            java.io.File(folder, "Show S01E03.mkv").writeText("fixture")
            java.io.File(folder, "Show S01E04.mkv").writeText("fixture")
            java.io.File(folder, "notes.txt").writeText("not a video")
            val subfolder = java.io.File(folder, "other").apply { mkdir() }
            java.io.File(subfolder, "Other S01E01.mkv").writeText("fixture")
            val local = MediaLibraryEntity.LOCAL.copy()
            val snapshot =
                CatalogSourceReader {
                    com.xyoye.common_component.storage.impl
                        .VideoStorage(it)
                }.directory(local, folder.path)
            assertTrue(snapshot.complete)
            assertEquals(setOf("Show S01E03.mkv", "Show S01E04.mkv"), snapshot.files.map { it.fileName }.toSet())
            assertEquals(listOf(subfolder.path), snapshot.directories)
            assertTrue(snapshot.files.all { it.parentPath == folder.path && it.size == 7L })
        }

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

    @Test fun everyMaintainedLibraryCanReadMetadataWithoutOpeningMedia() =
        runBlocking {
            for (type in MediaType.entries.filter {
                it !in
                    setOf(
                        MediaType.REMOTE_STORAGE,
                        MediaType.OTHER_STORAGE,
                        MediaType.SCREEN_CAST
                    )
            }) {
                val library = library.copy(mediaType = type)
                val reader = CatalogSourceReader { source(it, {}) }
                assertEquals(type.name, "file.mkv", reader.file(library, PlaybackFileReference("history", "/file.mkv")).fileName)
            }
            for (type in listOf(MediaType.REMOTE_STORAGE, MediaType.OTHER_STORAGE, MediaType.SCREEN_CAST)) {
                assertFalse(CatalogSourceReader.supports(type))
            }
        }

    @Test fun pagedDirectoryReadsEveryPageAndRootFilesKeepTheirParent() =
        runBlocking {
            var moreCalls = 0
            var closed = false
            val reader =
                CatalogSourceReader {
                    source(it, { closed = true }, page = {
                        moreCalls++
                        emptyList()
                    })
                }
            val result = reader.directory(library, "/")
            assertTrue(result.complete)
            assertEquals(1, moreCalls)
            assertTrue(closed)
            assertEquals("/", reader.file(library, PlaybackFileReference("history", "/file.mkv")).parentPath)
        }

    @Test fun aLaterPageFailureCannotBecomeACompleteSnapshot() =
        runBlocking {
            var closed = false
            val reader = CatalogSourceReader { source(it, { closed = true }, page = { throw IOException("page failed") }) }
            val failure = runCatching { reader.directory(library, "/") }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(generateSequence(failure) { it.cause }.any { it.message == "page failed" })
            assertTrue(closed)
        }

    private val library = MediaLibraryEntity(10, "Fixture", "https://fixture.test", MediaType.WEBDAV_SERVER)

    private fun source(
        library: MediaLibraryEntity,
        close: () -> Unit,
        failure: Exception? = null,
        page: (() -> List<StorageFile>)? = null
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
        var hasMore = page != null
        storage =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Storage::class.java, PagedStorage::class.java)) { _, method, args ->
                when (method.name.substringBefore('-')) {
                    "getLibrary" -> library
                    "pathFile" -> file(args[1] as Boolean)
                    "getRootFile" -> file(true)
                    "openDirectory" -> {
                        if (failure != null) throw failure
                        listOf(file(false))
                    }
                    "hasMore" -> hasMore
                    "loadMore" -> page!!.invoke().also { hasMore = false }
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
