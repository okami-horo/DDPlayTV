package com.xyoye.anime_component.ui.fragment.search_anime

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.xyoye.data_component.data.AnimeData
import com.xyoye.data_component.data.SearchAnimeData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
class SearchAnimeFragmentViewModelTest {
    @Test
    fun frozenParametersAndLateOldCompletionCannotReplaceNewQueryOrLoading() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val old = CompletableDeferred<Result<SearchAnimeData>>()
            val current = CompletableDeferred<Result<SearchAnimeData>>()
            val calls = mutableListOf<Pair<String, String?>>()
            val recorded = mutableListOf<String>()
            val vm =
                SearchAnimeFragmentViewModel(
                    searchRequest = { keyword, type ->
                        calls.add(keyword to type)
                        withContext(NonCancellable) { if (keyword == "old") old.await() else current.await() }
                    },
                    recordSearch = { recorded.add(it) }
                )
            val store = ViewModelStore().apply { put("vm", vm) }
            try {
                vm.searchText.set("old")
                vm.search()
                vm.searchText.set("new")
                runCurrent()
                assertEquals(listOf("old"), recorded)
                vm.search()
                runCurrent()
                old.complete(Result.failure(IllegalStateException("old failure")))
                runCurrent()
                assertTrue(vm.queryState.value.loading)
                assertNull(vm.queryState.value.error)
                current.complete(Result.success(SearchAnimeData(mutableListOf(AnimeData(animeId = 2)))))
                runCurrent()
                assertEquals(listOf("old" to null, "new" to null), calls)
                assertEquals("new", vm.queryState.value.keyword)
                assertEquals(
                    2,
                    vm.queryState.value.items
                        .single()
                        .animeId
                )
                assertFalse(vm.queryState.value.loading)
            } finally {
                store.clear()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun clearInvalidatesNonCooperativeResultAndFailureCanBeRetried() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val late = CompletableDeferred<Result<SearchAnimeData>>()
            var calls = 0
            val vm =
                SearchAnimeFragmentViewModel(
                    searchRequest = { _, _ ->
                        when (++calls) {
                            1 -> withContext(NonCancellable) { late.await() }
                            2 -> Result.failure(IllegalStateException("offline"))
                            else -> Result.success(SearchAnimeData())
                        }
                    },
                    recordSearch = {}
                )
            val store = ViewModelStore().apply { put("vm", vm) }
            try {
                vm.searchText.set("query")
                vm.search()
                runCurrent()
                vm.clearSearch()
                late.complete(Result.success(SearchAnimeData(mutableListOf(AnimeData(animeId = 1)))))
                runCurrent()
                assertFalse(vm.queryState.value.loaded)
                assertTrue(
                    vm.queryState.value.items
                        .isEmpty()
                )
                vm.searchText.set("retry")
                vm.search()
                runCurrent()
                assertEquals("offline", vm.queryState.value.error)
                vm.search()
                runCurrent()
                assertTrue(vm.queryState.value.loaded)
                assertNull(vm.queryState.value.error)
                assertTrue(
                    vm.queryState.value.items
                        .isEmpty()
                )
            } finally {
                store.clear()
                Dispatchers.resetMain()
            }
        }
}
