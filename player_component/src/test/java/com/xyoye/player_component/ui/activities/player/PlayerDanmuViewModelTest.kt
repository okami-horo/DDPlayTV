package com.xyoye.player_component.ui.activities.player

import androidx.lifecycle.ViewModelStore
import com.xyoye.common_component.playback.handoff.PlaybackSessionVersion
import com.xyoye.common_component.utils.danmu.DanmuFinder
import com.xyoye.common_component.utils.danmu.MatchOutcome
import com.xyoye.common_component.utils.danmu.source.DanmuSource
import com.xyoye.data_component.bean.DanmuTrackResource
import com.xyoye.data_component.bean.LocalDanmuBean
import com.xyoye.data_component.data.DanmuAnimeData
import com.xyoye.data_component.data.DanmuEpisodeData
import com.xyoye.data_component.data.MatchMethod
import com.xyoye.data_component.data.MatchResult
import com.xyoye.player.controller.setting.DanmuSearchState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
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
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlayerDanmuViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val finder = Finder()
    private val first = PlaybackSessionVersion("session", 1)
    private val next = first.copy(sourceRevision = 2)

    @Before fun before() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun after() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test fun lateDownloadCannotBindAnotherSourceOrLeaveItsLoadingState() =
        runTest {
            val gate = CompletableDeferred<LocalDanmuBean?>()
            finder.downloadResponse = { withContext(NonCancellable) { gate.await() } }
            val model = model()
            model.activateSource(first)
            model.downloadDanmu(episode("old"))
            runCurrent()
            assertTrue(model.panelState.value!!.downloading)
            model.activateSource(next)
            gate.complete(LocalDanmuBean("old.xml", "old"))
            runCurrent()
            assertNull(model.pending.value)
            assertFalse(model.panelState.value!!.downloading)
            assertNull(model.panelState.value!!.downloadError)
        }

    @Test fun manualSelectionWinsOverLateAutomaticMatchAndEarlierManualChoice() =
        runTest {
            val auto = CompletableDeferred<MatchOutcome>()
            val old = CompletableDeferred<LocalDanmuBean?>()
            finder.downloadResponse =
                { if (it.episodeId == "old") withContext(NonCancellable) { old.await() } else LocalDanmuBean("new.xml", "new") }
            val model = model { withContext(NonCancellable) { auto.await() } }
            model.activateSource(first)
            model.matchDanmu(first, PlayerUiTestSource("current"))
            runCurrent()
            model.downloadDanmu(episode("old"))
            runCurrent()
            model.downloadDanmu(episode("new"))
            runCurrent()
            val accepted = model.pending.value!!
            auto.complete(MatchOutcome.Success(DanmuTrackResource.LocalFile(LocalDanmuBean("auto.xml")), MatchMethod.HASH, 0.0))
            old.complete(LocalDanmuBean("old.xml", "old"))
            runCurrent()
            assertEquals(accepted, model.pending.value)
            assertTrue(accepted.manual)
            assertEquals("new", (accepted.resource as DanmuTrackResource.LocalFile).danmu.episodeId)
        }

    @Test fun completedWhileUnobservedSurvivesSameVersionAndCanOnlyBeTakenOnce() =
        runTest {
            val model = model()
            model.activateSource(first)
            model.downloadDanmu(episode("selected"))
            runCurrent()
            val pending = model.pending.value!!
            model.activateSource(first)
            assertEquals(pending, model.pending.value)
            assertNull(model.takePending(next, pending.requestId))
            assertNull(model.takePending(first, pending.requestId + 1))
            assertEquals(pending, model.takePending(first, pending.requestId))
            model.activateSource(first)
            assertNull(model.pending.value)
            assertNull(model.takePending(first, pending.requestId))
        }

    @Test fun downloadFailureEndsLoadingAndAllowsRetry() =
        runTest {
            finder.downloadResponse = { null }
            val model = model()
            model.activateSource(first)
            model.downloadDanmu(episode("selected"))
            runCurrent()
            assertFalse(model.panelState.value!!.downloading)
            assertNotNull(model.panelState.value!!.downloadError)
            finder.downloadResponse = { LocalDanmuBean("success.xml", it.episodeId) }
            model.downloadDanmu(episode("selected"))
            runCurrent()
            assertFalse(model.panelState.value!!.downloading)
            assertNull(model.panelState.value!!.downloadError)
            assertNotNull(model.pending.value)
        }

    @Test fun newestSearchAndClearRejectSlowOldResults() =
        runTest {
            val old = CompletableDeferred<Result<List<DanmuAnimeData>>>()
            finder.searchResponse =
                {
                    if (it ==
                        "old"
                    ) {
                        withContext(NonCancellable) { old.await() }
                    } else {
                        Result.success(listOf(DanmuAnimeData(episodes = listOf(episode(it)))))
                    }
                }
            val model = model()
            model.searchDanmu("old")
            runCurrent()
            model.searchDanmu("new")
            runCurrent()
            assertEquals("new", (model.panelState.value!!.search as DanmuSearchState.Content).query)
            model.searchDanmu("")
            old.complete(Result.success(listOf(DanmuAnimeData(episodes = listOf(episode("old"))))))
            runCurrent()
            assertSame(DanmuSearchState.Idle, model.panelState.value!!.search)
        }

    @Test fun emptySearchAndFailureRemainDistinctAndCancellationIsNotAnError() =
        runTest {
            val model = model()
            finder.searchResponse = { Result.success(emptyList()) }
            model.searchDanmu("empty")
            runCurrent()
            assertTrue(model.panelState.value!!.search is DanmuSearchState.Empty)
            finder.searchResponse = { Result.failure(IllegalStateException("offline")) }
            model.searchDanmu("failure")
            runCurrent()
            assertEquals("offline", (model.panelState.value!!.search as DanmuSearchState.Error).message)
            finder.searchResponse = { throw CancellationException("cancelled") }
            model.searchDanmu("cancelled")
            runCurrent()
            assertFalse(model.panelState.value!!.search is DanmuSearchState.Error)
        }

    private fun model(
        match: suspend (com.xyoye.common_component.source.base.BaseVideoSource) -> MatchOutcome = {
            MatchOutcome.NotFound
        }
    ): PlayerDanmuViewModel = PlayerDanmuViewModel(finder, match, dispatcher).also { store.put("model", it) }

    private fun episode(id: String) = DanmuEpisodeData(episodeId = id, episodeTitle = id)

    private class Finder : DanmuFinder {
        var searchResponse: suspend (String) -> Result<List<DanmuAnimeData>> = { Result.success(emptyList()) }
        var downloadResponse: suspend (DanmuEpisodeData) -> LocalDanmuBean? = { LocalDanmuBean("selected.xml", it.episodeId) }

        override suspend fun search(text: String): List<DanmuAnimeData> = searchResponse(text).getOrDefault(emptyList())

        override suspend fun searchResult(text: String): Result<List<DanmuAnimeData>> = searchResponse(text)

        override suspend fun downloadEpisode(
            episode: DanmuEpisodeData,
            withRelated: Boolean
        ) = downloadResponse(episode)

        override suspend fun getMatched(source: DanmuSource): MatchResult? = null

        override suspend fun downloadMatched(source: DanmuSource): LocalDanmuBean? = null

        override suspend fun saveStream(
            episode: DanmuEpisodeData,
            inputStream: InputStream
        ): LocalDanmuBean? = null
    }
}
