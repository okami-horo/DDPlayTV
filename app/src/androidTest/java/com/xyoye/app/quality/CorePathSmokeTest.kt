package com.xyoye.app.quality

import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.tabs.TabLayout
import com.okamihoro.ddplaytv.R
import com.okamihoro.ddplaytv.ui.main.MainActivity
import com.xyoye.anime_component.ui.activities.search.SearchActivity
import com.xyoye.player_component.ui.activities.player.PlayerActivity
import com.xyoye.user_component.ui.activities.scan_manager.ScanManagerActivity
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CorePathSmokeTest {
    @Test
    fun coreBrowseSearchAndSettingsPagesLaunch() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForIdle()
            scenario.onActivity { activity ->
                assertNotNull(activity.findViewById<BottomNavigationView>(R.id.navigation_view))
            }
        }

        ActivityScenario.launch(SearchActivity::class.java).use { scenario ->
            waitForIdle()
            scenario.onActivity { activity ->
                assertNotNull(activity.findViewById<androidx.viewpager2.widget.ViewPager2>(com.xyoye.anime_component.R.id.viewpager))
            }
        }

        ActivityScenario.launch(ScanManagerActivity::class.java).use { scenario ->
            waitForIdle()
            scenario.onActivity { activity ->
                assertNotNull(activity.findViewById<ViewPager2>(com.xyoye.user_component.R.id.viewpager))
                assertNotNull(activity.findViewById<TabLayout>(com.xyoye.user_component.R.id.tab_layout))
            }
        }
    }

    @Test
    fun corePlaybackEntryHandlesMissingSourceGracefully() {
        ActivityScenario.launch(PlayerActivity::class.java).use { scenario ->
            assertTrue(waitUntilDestroyed(scenario))
        }
    }

    private fun waitUntilDestroyed(
        scenario: ActivityScenario<*>,
        timeoutMs: Long = 5_000L
    ): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            waitForIdle()
            if (scenario.state == Lifecycle.State.DESTROYED) {
                return true
            }
            SystemClock.sleep(100)
        }
        return scenario.state == Lifecycle.State.DESTROYED
    }

    private fun waitForIdle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
}
