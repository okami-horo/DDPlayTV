package com.xyoye.anime_component.ui.fragment.anime_episode

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.xyoye.data_component.data.BangumiData
import com.xyoye.data_component.data.EpisodeData
import com.xyoye.data_component.entity.EpisodeHistoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
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
class AnimeEpisodeFragmentViewModelTest {
    @Test
    fun newEpisodeIdsCancelContinuousHistoryQueryWithoutAdditionalUiCollectors() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val subscribed = mutableListOf<List<String>>()
            val cancelled = mutableListOf<List<String>>()
            val vm =
                AnimeEpisodeFragmentViewModel { ids ->
                    flow<List<EpisodeHistoryEntity>> {
                        subscribed.add(ids)
                        try {
                            emit(emptyList())
                            awaitCancellation()
                        } finally {
                            cancelled.add(ids)
                        }
                    }
                }
            val store = ViewModelStore().apply { put("vm", vm) }
            try {
                val first = backgroundScope.launch { vm.displayEpisodesFlow.collect() }
                runCurrent()
                vm.setBangumiData(bangumi("A"))
                runCurrent()
                assertEquals(
                    "A",
                    vm.displayEpisodesFlow.value
                        .single()
                        .episodeId
                )
                first.cancel()
                backgroundScope.launch { vm.displayEpisodesFlow.collect() }
                runCurrent()
                assertEquals(1, subscribed.count { it == listOf("A") })
                vm.setBangumiData(bangumi("B"))
                runCurrent()
                assertTrue(cancelled.contains(listOf("A")))
                assertEquals(listOf("B"), subscribed.last())
                assertEquals(
                    "B",
                    vm.displayEpisodesFlow.value
                        .single()
                        .episodeId
                )
            } finally {
                store.clear()
                Dispatchers.resetMain()
            }
        }

    private fun bangumi(id: String) =
        BangumiData(
            userRating = 0,
            animeId = 1,
            isOnAir = false,
            airDay = 0,
            isFavorited = false,
            isRestricted = false,
            rating = 0.0,
            episodes = mutableListOf(EpisodeData(episodeId = id))
        )
}
