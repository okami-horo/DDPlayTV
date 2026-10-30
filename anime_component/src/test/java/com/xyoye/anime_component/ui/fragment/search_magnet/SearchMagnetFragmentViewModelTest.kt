package com.xyoye.anime_component.ui.fragment.search_magnet

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.xyoye.data_component.data.MagnetData
import com.xyoye.data_component.data.MagnetResourceData
import com.xyoye.data_component.data.MagnetScreenData
import com.xyoye.data_component.data.MagnetSubgroupData
import com.xyoye.data_component.data.MagnetTypeData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SearchMagnetFragmentViewModelTest {
    @Test
    fun viewInitializationDoesNotRestartAnEntrySearchAfterItWasCleared() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            var calls = 0
            val vm =
                SearchMagnetFragmentViewModel(
                    searchRequest = { _, _, _, _ ->
                        calls++
                        Result.success(MagnetResourceData())
                    },
                    recordSearch = {},
                    domain = { "https://node" }
                )
            val store = ViewModelStore().apply { put("vm", vm) }
            try {
                assertTrue(vm.initializeSearch("entry"))
                runCurrent()
                vm.clearSearch()
                assertFalse(vm.initializeSearch("entry"))
                runCurrent()
                assertEquals(1, calls)
                assertEquals("", vm.queryState.value.keyword)
            } finally {
                store.clear()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun clearedQueryRejectsLateSuccessAndNewFilterOwnsLoadingAndErrors() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val search = CompletableDeferred<Result<MagnetResourceData>>()
            val oldFilter = CompletableDeferred<Result<MagnetTypeData>>()
            val currentFilter = CompletableDeferred<Result<MagnetSubgroupData>>()
            val vm =
                SearchMagnetFragmentViewModel(
                    searchRequest = { _, _, _, _ -> withContext(NonCancellable) { search.await() } },
                    typeRequest = { withContext(NonCancellable) { oldFilter.await() } },
                    subgroupRequest = { currentFilter.await() },
                    recordSearch = {},
                    domain = { "https://node" }
                )
            val store = ViewModelStore().apply { put("vm", vm) }
            try {
                vm.searchText.set("query")
                vm.search()
                runCurrent()
                vm.clearSearch()
                search.complete(Result.success(MagnetResourceData(Resources = mutableListOf(MagnetData(Title = "late")))))
                runCurrent()
                assertTrue(
                    vm.queryState.value.items
                        .isEmpty()
                )
                assertFalse(vm.queryState.value.loaded)
                vm.getMagnetType()
                runCurrent()
                vm.getMagnetSubgroup()
                runCurrent()
                oldFilter.complete(Result.failure(IllegalStateException("old filter failure")))
                runCurrent()
                assertTrue(vm.queryState.value.filterLoading)
                assertNull(vm.queryState.value.filterError)
                currentFilter.complete(Result.success(MagnetSubgroupData()))
                runCurrent()
                val command = vm.dialogRequests.first() as SearchMagnetFragmentViewModel.DialogRequest.Filter
                assertEquals(com.xyoye.data_component.enums.MagnetScreenType.SUBGROUP, command.type)
                assertFalse(vm.queryState.value.filterLoading)
            } finally {
                store.clear()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun snapshotsFiltersAndOldCompletionCannotStopCurrentLoading() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val old = CompletableDeferred<Result<MagnetResourceData>>()
            val latest = CompletableDeferred<Result<MagnetResourceData>>()
            val calls = mutableListOf<List<String>>()
            val vm =
                SearchMagnetFragmentViewModel(
                    searchRequest = { domain, keyword, type, subgroup ->
                        calls.add(listOf(domain, keyword, type, subgroup))
                        withContext(NonCancellable) { if (keyword == "old") old.await() else latest.await() }
                    },
                    recordSearch = {},
                    domain = { "https://node" }
                )
            val store = ViewModelStore().apply { put("vm", vm) }
            try {
                vm.searchText.set("old")
                vm.magnetTypeId.set(1)
                vm.search()
                vm.magnetTypeId.set(2)
                runCurrent()
                vm.searchText.set("new")
                vm.search()
                runCurrent()
                old.complete(Result.failure(IllegalStateException("old")))
                runCurrent()
                assertTrue(vm.queryState.value.loading)
                assertNull(vm.queryState.value.error)
                latest.complete(Result.success(MagnetResourceData(Resources = mutableListOf(MagnetData(Title = "new")))))
                runCurrent()
                assertEquals(listOf("https://node", "old", "1", ""), calls[0])
                assertEquals(listOf("https://node", "new", "2", ""), calls[1])
                assertEquals(
                    "new",
                    vm.queryState.value.items
                        .single()
                        .Title
                )
                vm.clearSearch()
                assertFalse(vm.queryState.value.loaded)
            } finally {
                store.clear()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun cachedFilterOnlyOpensOnActionAndConsumedCommandDoesNotReplay() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            var loads = 0
            var currentDomain: String? = "https://node"
            val vm =
                SearchMagnetFragmentViewModel(
                    typeRequest = {
                        loads++
                        Result.success(MagnetTypeData(mutableListOf(MagnetScreenData(1, "type"))))
                    },
                    domain = { currentDomain }
                )
            val store = ViewModelStore().apply { put("vm", vm) }
            try {
                vm.getMagnetType()
                runCurrent()
                val first = vm.dialogRequests.first()
                assertTrue(vm.isCurrentDialog(first))
                assertTrue(first is SearchMagnetFragmentViewModel.DialogRequest.Filter)
                val replay = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { vm.dialogRequests.first() }
                runCurrent()
                assertFalse(replay.isCompleted)
                replay.cancel()
                vm.getMagnetType()
                runCurrent()
                assertTrue(vm.dialogRequests.first() is SearchMagnetFragmentViewModel.DialogRequest.Filter)
                assertEquals(1, loads)
                currentDomain = null
                vm.getMagnetType()
                runCurrent()
                currentDomain = "https://fixed"
                vm.onDomainChanged()
                val stale = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { vm.dialogRequests.first() }
                runCurrent()
                assertFalse(stale.isCompleted)
                stale.cancel()
                assertFalse(vm.isCurrentDialog(first))
            } finally {
                store.clear()
                Dispatchers.resetMain()
            }
        }
}
