package com.xyoye.anime_component.ui.activities

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.xyoye.anime_component.ui.activities.anime_follow.AnimeFollowViewModel
import com.xyoye.anime_component.ui.activities.anime_history.AnimeHistoryViewModel
import com.xyoye.data_component.data.AnimeData
import com.xyoye.data_component.data.CloudHistoryListData
import com.xyoye.data_component.data.FollowAnimeData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AnimeCollectionViewModelTest {
    @Test fun injectedFollowSurvivesReentryAndFailedRefreshThenRetryReplacesIt() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val pending = CompletableDeferred<Result<FollowAnimeData>>()
            var calls = 0
            val original = AnimeData(animeId = 1, animeTitle = "Original")
            val replacement = AnimeData(animeId = 2, animeTitle = "New")
            val vm =
                AnimeFollowViewModel {
                    calls++
                    if (calls == 1) pending.await() else Result.success(FollowAnimeData(mutableListOf(replacement)))
                }
            val store = ViewModelStore().apply { put("vm", vm) }
            try {
                vm.initialize(listOf(original))
                vm.searchAnime("orig")
                vm.initialize(emptyList())
                assertEquals(listOf(original), vm.state.value.visibleItems)
                assertEquals(0, calls)
                vm.getUserFollow()
                vm.getUserFollow()
                runCurrent()
                assertEquals(1, calls)
                assertTrue(vm.state.value.loading)
                assertEquals(listOf(original), vm.state.value.visibleItems)
                pending.complete(Result.failure(IllegalStateException("offline")))
                runCurrent()
                assertFalse(vm.state.value.loading)
                assertEquals("offline", vm.state.value.error)
                assertEquals(listOf(original), vm.state.value.items)
                vm.getUserFollow()
                runCurrent()
                assertNull(vm.state.value.error)
                assertEquals(listOf(replacement), vm.state.value.items)
                assertTrue(
                    vm.state.value.visibleItems
                        .isEmpty()
                )
                vm.searchAnime("")
                assertEquals(listOf(replacement), vm.state.value.visibleItems)
            } finally {
                store.clear()
                Dispatchers.resetMain()
            }
        }

    @Test fun historyDistinguishesLoadingFailureEmptyAndFilteringWithoutLosingInjectedData() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            var calls = 0
            val vm =
                AnimeHistoryViewModel {
                    calls++
                    if (calls == 1) Result.failure(IllegalStateException("offline")) else Result.success(CloudHistoryListData())
                }
            val store = ViewModelStore().apply { put("vm", vm) }
            try {
                val anime = AnimeData(animeId = 3, animeTitle = "History")
                vm.initialize(listOf(anime))
                vm.initialize(null)
                assertEquals(listOf(anime), vm.state.value.items)
                assertEquals(0, calls)
                vm.searchAnime("missing")
                assertTrue(
                    vm.state.value.emptyMessage
                        .contains("匹配")
                )
                vm.getCloudHistory()
                assertTrue(vm.state.value.loading)
                runCurrent()
                assertEquals(listOf(anime), vm.state.value.items)
                assertTrue(
                    vm.state.value.emptyMessage
                        .contains("失败")
                )
                vm.searchAnime("")
                vm.getCloudHistory()
                runCurrent()
                assertNull(vm.state.value.error)
                assertTrue(
                    vm.state.value.items
                        .isEmpty()
                )
                assertTrue(
                    vm.state.value.emptyMessage
                        .contains("暂无内容")
                )
            } finally {
                store.clear()
                Dispatchers.resetMain()
            }
        }
}
