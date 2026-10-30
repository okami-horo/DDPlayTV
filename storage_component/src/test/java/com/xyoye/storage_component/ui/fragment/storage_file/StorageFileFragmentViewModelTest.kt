package com.xyoye.storage_component.ui.fragment.storage_file

import com.xyoye.common_component.storage.AbstractStorage
import com.xyoye.common_component.storage.AuthStorage
import com.xyoye.common_component.storage.PagedStorage
import com.xyoye.common_component.storage.baidupan.auth.BaiduPanReAuthRequiredException
import com.xyoye.common_component.storage.file.AbstractStorageFile
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.entity.PlayHistoryEntity
import com.xyoye.data_component.enums.MediaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.io.InputStream

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class StorageFileFragmentViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<StorageFileFragmentViewModel>()
    private var histories = emptyMap<String, PlayHistoryEntity>()
    private var ascending = true

    @Before fun prepareMain() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun releaseModels() {
        models.forEach { it.cancelRequests() }
        Dispatchers.resetMain()
    }

    private fun model(storage: FakeStorage) =
        StorageFileFragmentViewModel(
            ioDispatcher = dispatcher,
            hidePointFiles = { false },
            historyLookup = { histories[it.fileName()] },
            lastPlayLookup = { null },
            comparator = { if (ascending) compareBy { it.fileName() } else compareByDescending { it.fileName() } }
        ).also {
            it.storage = storage
            models.add(it)
        }

    private fun names(model: StorageFileFragmentViewModel) =
        model.state.value!!
            .items
            .filterIsInstance<StorageFile>()
            .map { it.fileName() }

    @Test fun shorteningAndClearingLocalQueryRestoresMatchesWithoutLosingRawFiles() =
        runTest(dispatcher) {
            val storage = FakeStorage()
            storage.list = { listOf(storage.file("abc.mkv"), storage.file("axy.mkv"), storage.file("font.ttf")) }
            val model = model(storage)
            model.listFile(null)
            advanceUntilIdle()
            model.searchByText("abc")
            assertEquals(listOf("abc.mkv"), names(model))
            model.searchByText("a")
            assertEquals(listOf("abc.mkv", "axy.mkv"), names(model))
            model.searchByText("")
            assertEquals(listOf("abc.mkv", "axy.mkv"), names(model))
            assertEquals(
                3,
                model.state.value!!
                    .directoryFiles.size
            )
        }

    @Test fun failureAndSuccessfulEmptyDirectoryHaveDifferentStatesAndCanRetry() =
        runTest(dispatcher) {
            val storage = FakeStorage()
            storage.list = { throw IOException("offline") }
            val model = model(storage)
            model.listFile(null)
            advanceUntilIdle()
            assertEquals("offline", model.state.value!!.error)
            assertFalse(model.state.value!!.hasDirectory)
            assertFalse(model.state.value!!.loading)
            storage.list = { emptyList() }
            model.listFile(null)
            advanceUntilIdle()
            assertTrue(model.state.value!!.hasDirectory)
            assertTrue(names(model).isEmpty())
            assertNull(model.state.value!!.error)
        }

    @Test fun refreshFailureKeepsOriginalListAndPaginationThenRetriesFromSameOffset() =
        runTest(dispatcher) {
            val storage = FakeStorage()
            storage.pagingEnabled = true
            storage.page = PagedStorage.Snapshot("/", 200, true, PagedStorage.State.IDLE)
            storage.list = { listOf(storage.file("old.mkv")) }
            val model = model(storage)
            model.listFile(null)
            advanceUntilIdle()
            storage.list = {
                storage.page = storage.page.copy(offset = 0)
                throw IOException("refresh failed")
            }
            model.listFile(null, refresh = true)
            advanceUntilIdle()
            assertEquals(listOf("old.mkv"), names(model))
            assertEquals(200, storage.page.offset)
            storage.more = {
                assertEquals(200, storage.page.offset)
                Result.success(listOf(storage.file("next.mkv")))
            }
            model.loadMore()
            advanceUntilIdle()
            assertEquals(listOf("next.mkv", "old.mkv"), names(model))
        }

    @Test fun queryChangesAfterInitialFailureKeepTheErrorUntilDirectoryRetrySucceeds() =
        runTest(dispatcher) {
            for (remoteSearch in listOf(false, true)) {
                val storage = FakeStorage(remoteSearch)
                storage.list = { throw IOException("offline") }
                var searches = 0
                storage.searcher = {
                    searches++
                    emptyList()
                }
                val model = model(storage)
                model.listFile(null)
                advanceUntilIdle()
                model.searchByText("abc")
                advanceUntilIdle()
                assertEquals("abc", model.state.value!!.query)
                assertEquals("offline", model.state.value!!.error)
                assertFalse(model.state.value!!.hasDirectory)
                assertFalse(model.state.value!!.loading)
                model.searchByText("")
                advanceUntilIdle()
                assertEquals("offline", model.state.value!!.error)
                assertFalse(model.state.value!!.hasDirectory)
                assertEquals(0, searches)
                storage.list = { emptyList() }
                model.listFile(null)
                advanceUntilIdle()
                assertTrue(model.state.value!!.hasDirectory)
                assertNull(model.state.value!!.error)
                assertTrue(names(model).isEmpty())
            }
        }

    @Test fun failedLoadMoreKeepsFilesAndCursorAndAllowsRetry() =
        runTest(dispatcher) {
            val storage = FakeStorage()
            storage.pagingEnabled = true
            storage.page = PagedStorage.Snapshot("/", 200, true, PagedStorage.State.IDLE)
            storage.list = { listOf(storage.file("first.mkv")) }
            val model = model(storage)
            model.listFile(null)
            advanceUntilIdle()
            storage.more = {
                storage.page = storage.page.copy(offset = 0)
                Result.failure(IOException("page failed"))
            }
            model.loadMore()
            advanceUntilIdle()
            assertEquals(listOf("first.mkv"), names(model))
            assertEquals(200, storage.page.offset)
            assertEquals(
                PagedStorage.State.ERROR,
                model.state.value!!
                    .items
                    .filterIsInstance<StoragePagingItem>()
                    .single()
                    .state
            )
            storage.more = {
                assertEquals(200, storage.page.offset)
                Result.success(listOf(storage.file("second.mkv")))
            }
            model.loadMore()
            advanceUntilIdle()
            assertEquals(listOf("first.mkv", "second.mkv"), names(model))
        }

    @Test fun loginFailureIsDistinctAndPendingRequestCanBeConsumed() =
        runTest(dispatcher) {
            val storage = FakeStorage()
            storage.list = { throw BaiduPanReAuthRequiredException("expired") }
            val model = model(storage)
            model.listFile(null)
            advanceUntilIdle()
            assertTrue(model.state.value!!.loginRequired)
            assertSame(storage.library, model.loginRequiredLiveData.value)
            model.consumeLoginRequest()
            assertNull(model.loginRequiredLiveData.value)
        }

    @Test fun cancelledRemoteQueryCannotOverwriteNewQueryOrFinishItsLoading() =
        runTest(dispatcher) {
            val storage = FakeStorage(remoteSearch = true)
            val old = CompletableDeferred<Unit>()
            val latest = CompletableDeferred<Unit>()
            storage.searcher = { query ->
                withContext(NonCancellable) { if (query == "old") old.await() else latest.await() }
                listOf(storage.file(query + ".mkv"))
            }
            val model = model(storage)
            model.listFile(null)
            advanceUntilIdle()
            model.searchByText("old")
            runCurrent()
            model.searchByText("new")
            runCurrent()
            old.complete(Unit)
            runCurrent()
            assertTrue(model.state.value!!.loading)
            assertEquals("new", model.state.value!!.query)
            latest.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf("new.mkv"), names(model))
            assertNull(model.state.value!!.error)
            assertFalse(model.state.value!!.loading)
        }

    @Test fun clearingRemoteQueryRejectsLateResponseAndRestoresDirectory() =
        runTest(dispatcher) {
            val storage = FakeStorage(remoteSearch = true)
            storage.list = { listOf(storage.file("directory.mkv")) }
            val gate = CompletableDeferred<Unit>()
            storage.searcher = {
                withContext(NonCancellable) { gate.await() }
                listOf(storage.file("stale.mkv"))
            }
            val model = model(storage)
            model.listFile(null)
            advanceUntilIdle()
            model.searchByText("pending")
            runCurrent()
            model.searchByText("")
            assertEquals(listOf("directory.mkv"), names(model))
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf("directory.mkv"), names(model))
            assertNull(model.state.value!!.error)
        }

    @Test fun lateSearchFailureDoesNotReplaceClearedState() =
        runTest(dispatcher) {
            val storage = FakeStorage(remoteSearch = true)
            storage.list = { listOf(storage.file("directory.mkv")) }
            val gate = CompletableDeferred<Unit>()
            storage.searcher = {
                withContext(NonCancellable) { gate.await() }
                throw IOException("obsolete")
            }
            val model = model(storage)
            model.listFile(null)
            advanceUntilIdle()
            model.searchByText("old")
            runCurrent()
            model.searchByText("")
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf("directory.mkv"), names(model))
            assertNull(model.state.value!!.error)
            assertFalse(model.state.value!!.loading)
        }

    @Test fun sharedStorageSerializesDirectoriesAndOldResultCannotCommitIntoNewRoute() =
        runTest(dispatcher) {
            val storage = FakeStorage()
            val gate = CompletableDeferred<Unit>()
            storage.list = { directory ->
                if (directory.filePath() == "/a") withContext(NonCancellable) { gate.await() }
                listOf(storage.file(directory.fileName() + ".mkv"))
            }
            val lock = Mutex()
            var active = "a"
            val first =
                model(storage).apply {
                    operationLock = lock
                    isPageActive = { active == "a" }
                }
            val second =
                model(storage).apply {
                    operationLock = lock
                    isPageActive = { active == "b" }
                }
            first.listFile(storage.file("/a", directory = true))
            runCurrent()
            active = "b"
            first.cancelRequests()
            second.listFile(storage.file("/b", directory = true))
            runCurrent()
            gate.complete(Unit)
            advanceUntilIdle()
            assertFalse(first.state.value!!.hasDirectory)
            assertEquals(
                "/b",
                second.state.value!!
                    .directory!!
                    .filePath()
            )
            assertEquals("/b", storage.directory!!.filePath())
            assertEquals(listOf("b.mkv"), names(second))
            assertEquals(
                listOf("b.mkv"),
                second.state.value!!
                    .directoryFiles
                    .map { it.fileName() }
            )
        }

    @Test fun historyAndSortingDuringFilteringNeverReplaceTheCompleteDirectory() =
        runTest(dispatcher) {
            val storage = FakeStorage()
            storage.list = { listOf(storage.file("abc.mkv"), storage.file("axy.mkv"), storage.file("other.mkv")) }
            val model = model(storage)
            model.listFile(null)
            advanceUntilIdle()
            model.searchByText("a")
            ascending = false
            model.changeSortOption()
            histories =
                mapOf(
                    "abc.mkv" to
                        PlayHistoryEntity(videoName = "abc", url = "source", mediaType = MediaType.LOCAL_STORAGE, videoPosition = 500)
                )
            model.updateHistory()
            advanceUntilIdle()
            assertEquals(listOf("axy.mkv", "abc.mkv"), names(model))
            assertEquals(
                500L,
                model.state.value!!
                    .items
                    .filterIsInstance<StorageFile>()
                    .last()
                    .playHistory!!
                    .videoPosition
            )
            model.searchByText("")
            assertEquals(listOf("other.mkv", "axy.mkv", "abc.mkv"), names(model))
            assertEquals(
                3,
                model.state.value!!
                    .directoryFiles.size
            )
        }

    @Test fun searchResultsCannotBecomePaginationInput() =
        runTest(dispatcher) {
            val storage = FakeStorage(remoteSearch = true)
            storage.pagingEnabled = true
            storage.page = PagedStorage.Snapshot("/", 200, true, PagedStorage.State.IDLE)
            storage.list = { listOf(storage.file("directory.mkv")) }
            storage.searcher = { listOf(storage.file("search.mkv")) }
            var loads = 0
            storage.more = {
                loads++
                Result.success(listOf(storage.file("next.mkv")))
            }
            val model = model(storage)
            model.listFile(null)
            advanceUntilIdle()
            model.searchByText("search")
            advanceUntilIdle()
            model.loadMore()
            advanceUntilIdle()
            assertEquals(0, loads)
            model.searchByText("")
            model.loadMore()
            advanceUntilIdle()
            assertEquals(listOf("directory.mkv", "next.mkv"), names(model))
            assertEquals(1, loads)
        }

    @Test fun activityRecreationDoesNotReuseFilesFromTheClosedStorage() =
        runTest(dispatcher) {
            val old = FakeStorage().apply { list = { listOf(file("old.mkv")) } }
            val model = model(old)
            model.listFile(null)
            advanceUntilIdle()
            val replacement = FakeStorage().apply { list = { listOf(file("new.mkv")) } }
            model.storage = replacement
            assertFalse(model.state.value!!.hasDirectory)
            model.listFile(null)
            advanceUntilIdle()
            assertEquals(listOf("new.mkv"), names(model))
            assertTrue(
                model.state.value!!
                    .directoryFiles
                    .all { it.storage === replacement }
            )
            assertSame(
                replacement,
                model.state.value!!
                    .directory!!
                    .storage
            )
        }

    @Test fun cancelledCurrentRequestDoesNotRemainLoadingOrBecomeAnError() =
        runTest(dispatcher) {
            val storage = FakeStorage().apply { list = { throw CancellationException("cancelled") } }
            val model = model(storage)
            model.listFile(null)
            advanceUntilIdle()
            assertNull(model.state.value!!.error)
            assertFalse(model.state.value!!.loading)
            assertFalse(model.state.value!!.hasDirectory)
        }

    @Test fun replacingStorageWhileOldRootLookupFinishesCannotAccessTheReplacement() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val old =
                FakeStorage().apply {
                    rootLoader = {
                        withContext(NonCancellable) { gate.await() }
                        file("/retired-root", directory = true)
                    }
                }
            val opened = mutableListOf<String>()
            val replacement =
                FakeStorage().apply {
                    list = { directory ->
                        opened.add(directory.filePath())
                        listOf(file("new.mkv"))
                    }
                }
            val model = model(old)
            model.listFile(null)
            runCurrent()
            model.storage = replacement
            gate.complete(Unit)
            advanceUntilIdle()
            assertTrue(opened.isEmpty())
            assertNull(replacement.directory)
            assertFalse(model.state.value!!.hasDirectory)
            model.listFile(null)
            advanceUntilIdle()
            assertEquals(listOf("/"), opened)
            assertEquals(listOf("new.mkv"), names(model))
            assertSame(
                replacement,
                model.state.value!!
                    .directory!!
                    .storage
            )
        }

    @Test fun localQueryDuringFirstDirectoryLoadKeepsTheLoadAndUsesTheLatestFilter() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val storage = FakeStorage()
            storage.list = {
                gate.await()
                listOf(storage.file("abc.mkv"), storage.file("axy.mkv"), storage.file("other.mkv"))
            }
            val model = model(storage)
            model.listFile(null)
            runCurrent()
            model.searchByText("abc")
            model.searchByText("")
            model.searchByText("a")
            assertTrue(model.state.value!!.loading)
            gate.complete(Unit)
            advanceUntilIdle()
            assertTrue(model.state.value!!.hasDirectory)
            assertEquals(listOf("abc.mkv", "axy.mkv"), names(model))
            assertEquals(
                3,
                model.state.value!!
                    .directoryFiles.size
            )
            assertNull(model.state.value!!.error)
            model.searchByText("")
            assertEquals(3, names(model).size)
        }

    @Test fun remoteQueryDuringFirstLoadWaitsForDirectoryAndRunsOnlyTheLatestQuery() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val storage = FakeStorage(remoteSearch = true)
            storage.list = {
                gate.await()
                listOf(storage.file("directory.mkv"))
            }
            val queries = mutableListOf<String>()
            storage.searcher = {
                assertEquals("/", storage.directory!!.filePath())
                queries.add(it)
                listOf(storage.file(it + ".mkv"))
            }
            val model = model(storage)
            model.listFile(null)
            runCurrent()
            model.searchByText("old")
            model.searchByText("")
            model.searchByText("latest")
            assertTrue(queries.isEmpty())
            assertTrue(model.state.value!!.loading)
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf("latest"), queries)
            assertEquals(listOf("latest.mkv"), names(model))
            assertEquals(
                listOf("directory.mkv"),
                model.state.value!!
                    .directoryFiles
                    .map { it.fileName() }
            )
            assertNull(model.state.value!!.error)
        }

    private class FakeStorage(
        private val remoteSearch: Boolean = false
    ) : AbstractStorage(
            MediaLibraryEntity(id = 10, displayName = "fixture", url = "http://fixture", mediaType = MediaType.LOCAL_STORAGE)
        ),
        PagedStorage,
        AuthStorage {
        var list: suspend (StorageFile) -> List<StorageFile> = { emptyList() }
        var rootLoader: suspend () -> StorageFile = { file("/", directory = true) }
        var searcher: suspend (String) -> List<StorageFile> = { emptyList() }
        var more: suspend () -> Result<List<StorageFile>> = { Result.success(emptyList()) }
        var pagingEnabled = false
        var page = PagedStorage.Snapshot("/", 0, false, PagedStorage.State.NO_MORE)
        override var state: PagedStorage.State
            get() = page.state
            set(value) {
                page = page.copy(state = value)
            }

        override fun snapshotPaging() = page

        override fun restorePaging(snapshot: PagedStorage.Snapshot) {
            page = snapshot
        }

        override fun shouldShowPagingItem(directory: StorageFile?) = pagingEnabled

        override fun hasMore() = page.hasMore

        override suspend fun reset() = Unit

        override suspend fun loadMore() = more()

        override fun isConnected() = true

        override fun requiresLogin(directory: StorageFile?) = false

        override fun loginActionText(directory: StorageFile?) = "login"

        override suspend fun listFiles(file: StorageFile) = list(file)

        override suspend fun getRootFile() = rootLoader()

        override suspend fun openFile(file: StorageFile): InputStream? = null

        override suspend fun pathFile(
            path: String,
            isDirectory: Boolean
        ) = file(path, isDirectory)

        override suspend fun historyFile(history: PlayHistoryEntity): StorageFile? = null

        override fun supportSearch() = remoteSearch

        override suspend fun search(keyword: String) = searcher(keyword)

        fun file(
            path: String,
            directory: Boolean = false
        ): StorageFile = Entry(this, path, directory)
    }

    private class Entry(
        storage: FakeStorage,
        private val path: String,
        private val directory: Boolean
    ) : AbstractStorageFile(storage) {
        override fun getRealFile(): Any = path

        override fun filePath() = path

        override fun fileUrl() = "http://fixture/" + path

        override fun isDirectory() = directory

        override fun isVideoFile() = !directory && path.endsWith(".mkv")

        override fun fileName() = path.substringAfterLast("/")

        override fun fileLength() = 1L

        override fun clone(): StorageFile = Entry(storage as FakeStorage, path, directory)
    }
}
