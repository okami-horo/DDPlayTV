package com.okamihoro.ddplaytv.ui.anime

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xyoye.anime_component.ui.activities.anime_detail.AnimeDetailViewModel
import com.xyoye.anime_component.ui.fragment.anime_episode.AnimeEpisodeFragment
import com.xyoye.anime_component.ui.fragment.anime_episode.AnimeEpisodeFragmentViewModel
import com.xyoye.common_component.adapter.BaseAdapter
import com.xyoye.common_component.config.UserConfig
import com.xyoye.common_component.session.UserSessionManager
import com.xyoye.data_component.data.BangumiData
import com.xyoye.data_component.data.EpisodeData
import com.xyoye.data_component.data.LoginData
import com.xyoye.data_component.entity.EpisodeHistoryEntity
import com.xyoye.user_component.ui.activities.about_us.AboutUsActivity
import com.xyoye.user_component.ui.fragment.personal.PersonalFragment
import com.xyoye.user_component.ui.fragment.personal.PersonalFragmentViewModel
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnimeViewLifecycleInstrumentedTest {
    @Test
    fun episodeParentObserverIsRemovedUntilTheSameFragmentCreatesANewView() {
        val queries = mutableListOf<List<String>>()
        val viewModel =
            AnimeEpisodeFragmentViewModel { ids ->
                queries.add(ids)
                flowOf<List<EpisodeHistoryEntity>>(emptyList())
            }
        val fragment = AnimeEpisodeFragment()
        ActivityScenario.launch(AboutUsActivity::class.java).use { scenario ->
            lateinit var parent: AnimeDetailViewModel
            lateinit var firstView: View
            scenario.onActivity { activity ->
                parent = ViewModelProvider(activity)[AnimeDetailViewModel::class.java]
                parent.animeDetailLiveData.value = bangumi("A")
                FragmentViewLifecycleFixture.mount(activity, fragment, viewModel)
                firstView = fragment.requireView()
            }
            FragmentViewLifecycleFixture.await("Initial episode is rendered") {
                episodeIds(fragment) == listOf("A") && parent.animeDetailLiveData.hasActiveObservers()
            }
            scenario.onActivity { activity ->
                val firstOwner = fragment.viewLifecycleOwner
                activity.supportFragmentManager
                    .beginTransaction()
                    .detach(fragment)
                    .commitNow()
                assertNull(fragment.view)
                assertEquals(Lifecycle.State.DESTROYED, firstOwner.lifecycle.currentState)
                assertFalse("The destroyed View must leave no parent observer", parent.animeDetailLiveData.hasObservers())
                parent.animeDetailLiveData.value = bangumi("B")
                assertEquals(listOf("A"), viewModel.displayEpisodesFlow.value.map { it.episodeId })
                activity.supportFragmentManager
                    .beginTransaction()
                    .attach(fragment)
                    .commitNow()
                assertNotSame(firstView, fragment.requireView())
                assertSame(viewModel, ViewModelProvider(fragment)[AnimeEpisodeFragmentViewModel::class.java])
            }
            FragmentViewLifecycleFixture.await("The replacement View renders the current parent value") {
                episodeIds(fragment) == listOf("B") && parent.animeDetailLiveData.hasActiveObservers()
            }
            scenario.onActivity {
                assertEquals(1, queries.count { it == listOf("A") })
                assertEquals(1, queries.count { it == listOf("B") })
            }
        }
    }

    @Test
    fun personalRelationObserverBelongsToTheViewAndRecreationDoesNotReloadTheAccount() {
        val previousLogin = UserSessionManager.currentLoginData()
        val previousCoverIndex = UserConfig.getUserCoverIndex()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var requests = 0
        val viewModel =
            PersonalFragmentViewModel {
                requests++
                3 to 7
            }
        val fragment = PersonalFragment()
        try {
            instrumentation.runOnMainSync {
                UserSessionManager.updateLoginData(LoginData("lifecycle-fixture", "fixture-token", "Fixture", null))
            }
            instrumentation.waitForIdleSync()
            ActivityScenario.launch(AboutUsActivity::class.java).use { scenario ->
                lateinit var firstView: View
                scenario.onActivity {
                    FragmentViewLifecycleFixture.mount(it, fragment, viewModel)
                    firstView = fragment.requireView()
                }
                FragmentViewLifecycleFixture.await("Local account relation data is rendered") {
                    relationCounts(fragment) == ("3" to "7") && viewModel.relationLiveData.hasActiveObservers()
                }
                scenario.onActivity { activity ->
                    val firstOwner = fragment.viewLifecycleOwner
                    activity.supportFragmentManager
                        .beginTransaction()
                        .detach(fragment)
                        .commitNow()
                    assertNull(fragment.view)
                    assertEquals(Lifecycle.State.DESTROYED, firstOwner.lifecycle.currentState)
                    assertFalse("The destroyed View must leave no relation observer", viewModel.relationLiveData.hasObservers())
                    viewModel.relationLiveData.value = 8 to 11
                    activity.supportFragmentManager
                        .beginTransaction()
                        .attach(fragment)
                        .commitNow()
                    assertNotSame(firstView, fragment.requireView())
                    assertSame(viewModel, ViewModelProvider(fragment)[PersonalFragmentViewModel::class.java])
                }
                FragmentViewLifecycleFixture.await("The new View renders the latest relation without reloading") {
                    relationCounts(fragment) == ("8" to "11") && viewModel.relationLiveData.hasActiveObservers()
                }
                scenario.onActivity { assertEquals(1, requests) }
            }
        } finally {
            instrumentation.runOnMainSync { UserSessionManager.updateLoginData(previousLogin) }
            instrumentation.waitForIdleSync()
            UserConfig.putUserCoverIndex(previousCoverIndex)
        }
    }

    private fun episodeIds(fragment: Fragment): List<String> {
        val list = fragment.requireView().findViewById<RecyclerView>(com.xyoye.anime_component.R.id.episode_rv)
        return (list.adapter as BaseAdapter).items.filterIsInstance<EpisodeData>().map { it.episodeId }
    }

    private fun relationCounts(fragment: Fragment): Pair<String, String> {
        val root = fragment.requireView()
        return root.findViewById<TextView>(com.xyoye.user_component.R.id.follow_anime_tv).text.toString() to
            root.findViewById<TextView>(com.xyoye.user_component.R.id.cloud_history_tv).text.toString()
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

internal object FragmentViewLifecycleFixture {
    fun <T : ViewModel> mount(
        activity: AppCompatActivity,
        fragment: Fragment,
        viewModel: T
    ) {
        val container = FrameLayout(activity).apply { id = View.generateViewId() }
        activity.findViewById<ViewGroup>(android.R.id.content).addView(
            container,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        activity.supportFragmentManager
            .beginTransaction()
            .replace(container.id, fragment)
            .setMaxLifecycle(fragment, Lifecycle.State.CREATED)
            .commitNow()
        assertNull("Inject the local model before the real Fragment creates its View", fragment.view)
        val factory =
            object : ViewModelProvider.Factory {
                override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = modelClass.cast(viewModel)!!
            }
        ViewModelProvider(fragment, factory)[viewModel.javaClass]
        activity.supportFragmentManager
            .beginTransaction()
            .setMaxLifecycle(fragment, Lifecycle.State.RESUMED)
            .commitNow()
    }

    fun await(
        message: String,
        condition: () -> Boolean
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + 5000
        var ready = false
        while (!ready && SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { ready = condition() }
            if (!ready) SystemClock.sleep(25)
        }
        assertTrue(message, ready)
    }
}
