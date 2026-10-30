package com.xyoye.anime_component.ui.activities.anime_season

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.xyoye.data_component.data.AnimeData
import com.xyoye.data_component.data.BangumiAnimeData
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
class AnimeSeasonViewModelTest {
    @Test
    fun obsoleteFailureCannotEndCurrentLoadingAndCurrentFailureCanRetry() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val old = CompletableDeferred<Result<BangumiAnimeData>>()
            val latest = CompletableDeferred<Result<BangumiAnimeData>>()
            var currentCalls = 0
            val vm =
                AnimeSeasonViewModel { _, month ->
                    if (month == "1") {
                        withContext(NonCancellable) { old.await() }
                    } else if (++currentCalls == 1) {
                        latest.await()
                    } else {
                        Result.success(BangumiAnimeData())
                    }
                }
            val store = ViewModelStore().apply { put("vm", vm) }
            try {
                vm.checkSeason("1")
                runCurrent()
                vm.checkSeason("4")
                runCurrent()
                old.complete(Result.failure(IllegalStateException("old")))
                runCurrent()
                assertTrue(vm.queryState.value.loading)
                assertNull(vm.queryState.value.error)
                latest.complete(Result.failure(IllegalStateException("offline")))
                runCurrent()
                assertEquals("offline", vm.queryState.value.error)
                vm.retry()
                runCurrent()
                assertTrue(vm.queryState.value.loaded)
                assertTrue(
                    vm.queryState.value.items
                        .isEmpty()
                )
                assertNull(vm.queryState.value.error)
            } finally {
                store.clear()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun latestSeasonOwnsResultsAndRepeatedInitializationKeepsSelection() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val old = CompletableDeferred<Result<BangumiAnimeData>>()
            val latest = CompletableDeferred<Result<BangumiAnimeData>>()
            val calls = mutableListOf<Pair<String, String>>()
            val vm =
                AnimeSeasonViewModel { year, month ->
                    calls.add(year to month)
                    withContext(NonCancellable) { if (month == "1") old.await() else latest.await() }
                }
            val store = ViewModelStore().apply { put("vm", vm) }
            try {
                vm.checkSeason("1")
                runCurrent()
                vm.checkSeason("4")
                runCurrent()
                latest.complete(Result.success(BangumiAnimeData(mutableListOf(AnimeData(animeId = 4)))))
                runCurrent()
                old.complete(Result.success(BangumiAnimeData(mutableListOf(AnimeData(animeId = 1)))))
                runCurrent()
                assertEquals("4", vm.queryState.value.month)
                assertEquals(
                    4,
                    vm.queryState.value.items
                        .single()
                        .animeId
                )
                assertFalse(vm.queryState.value.loading)
                val before = calls.size
                vm.getYearsData()
                runCurrent()
                assertEquals(before, calls.size)
                assertEquals("4", vm.queryState.value.month)
            } finally {
                store.clear()
                Dispatchers.resetMain()
            }
        }
}
