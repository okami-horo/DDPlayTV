package com.okamihoro.ddplaytv.ui.smoke

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.okamihoro.ddplaytv.R
import com.okamihoro.ddplaytv.ui.tv.TvMainActivity
import com.rubensousa.dpadrecyclerview.DpadRecyclerView
import com.xyoye.anime_component.ui.activities.anime_season.AnimeSeasonActivity
import com.xyoye.anime_component.ui.activities.search.SearchActivity
import com.xyoye.data_component.enums.MediaType
import com.xyoye.local_component.ui.activities.play_history.PlayHistoryActivity
import com.xyoye.local_component.ui.activities.shooter_subtitle.ShooterSubtitleActivity
import com.xyoye.storage_component.ui.activities.screencast.receiver.ScreencastActivity
import com.xyoye.storage_component.ui.activities.storage_plus.StoragePlusActivity
import com.xyoye.user_component.ui.activities.about_us.AboutUsActivity
import com.xyoye.user_component.ui.activities.cache_manager.CacheManagerActivity
import com.xyoye.user_component.ui.activities.commonly_folder.CommonlyFolderActivity
import com.xyoye.user_component.ui.activities.license.LicenseActivity
import com.xyoye.user_component.ui.activities.login.LoginActivity
import com.xyoye.user_component.ui.activities.scan_manager.ScanManagerActivity
import com.xyoye.user_component.ui.activities.user_info.UserInfoActivity
import com.xyoye.user_component.ui.fragment.personal.PersonalFragment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 应用主要功能页烟测：验证入口页可打开、关键容器存在、核心 Fragment/Tab 正常挂载。
 */
@RunWith(AndroidJUnit4::class)
class AppFeaturePageSmokeTest {
    @Before
    fun useRemoteInputMode() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
    }

    @Test
    fun mainPageCanSwitchCoreSectionsWithDpad() {
        ActivityScenario.launch(TvMainActivity::class.java).use { scenario ->
            val deadline = SystemClock.uptimeMillis() + 5000
            var focused = false
            while (!focused && SystemClock.uptimeMillis() < deadline) {
                waitForIdle()
                scenario.onActivity { activity ->
                    val nav = activity.findViewById<DpadRecyclerView>(R.id.tv_nav_rv)
                    focused = activity.hasWindowFocus() && nav.hasFocus() && nav.childCount > 0
                }
                if (!focused) SystemClock.sleep(25)
            }
            assertTrue("Main navigation must receive default focus", focused)
            scenario.onActivity { activity ->
                activity.supportFragmentManager.executePendingTransactions()
                assertFragmentTagIs<com.xyoye.local_component.ui.metadata.CinemaFragment>(activity, "tag_fragment_tv_cinema")
            }
            repeat(3) { sendDpad(KeyEvent.KEYCODE_DPAD_RIGHT) }
            sendDpad(KeyEvent.KEYCODE_DPAD_CENTER)
            scenario.onActivity { activity ->
                activity.supportFragmentManager.executePendingTransactions()
                assertFragmentTagIs<PersonalFragment>(activity, "tag_fragment_tv_personal")
            }
            repeat(3) { sendDpad(KeyEvent.KEYCODE_DPAD_LEFT) }
            sendDpad(KeyEvent.KEYCODE_DPAD_CENTER)
            scenario.onActivity { activity ->
                activity.supportFragmentManager.executePendingTransactions()
                assertFragmentTagIs<com.xyoye.local_component.ui.metadata.CinemaFragment>(activity, "tag_fragment_tv_cinema")
            }
        }
    }

    @Test
    fun tvMainPageLoadsNavigationAndDefaultSection() {
        ActivityScenario.launch(TvMainActivity::class.java).use { scenario ->
            waitForIdle()
            scenario.onActivity { activity ->
                val navRv = activity.findViewById<RecyclerView>(R.id.tv_nav_rv)
                assertNotNull("TV nav RecyclerView should be present", navRv)
                assertEquals("TV nav should contain five items", 5, navRv.adapter?.itemCount)

                activity.supportFragmentManager.executePendingTransactions()
                assertFragmentTagIs<com.xyoye.local_component.ui.metadata.CinemaFragment>(activity, "tag_fragment_tv_cinema")
            }
        }
    }

    @Test
    fun userCenterPagesLoad() {
        ActivityScenario.launch(ScanManagerActivity::class.java).use { scenario ->
            waitForIdle()
            scenario.onActivity { activity ->
                assertEquals(2, activity.findViewById<DpadRecyclerView>(com.xyoye.user_component.R.id.tab_layout).adapter?.itemCount)
                assertEquals(1, activity.supportFragmentManager.fragments.count { it.view?.isShown == true })
            }
        }

        ActivityScenario.launch(CacheManagerActivity::class.java).use { scenario ->
            waitForIdle()
            scenario.onActivity { activity ->
                assertNotNull(
                    "Cache manager list should exist",
                    activity.findViewById<RecyclerView>(com.xyoye.user_component.R.id.rv_cache),
                )
            }
        }

        ActivityScenario.launch(CommonlyFolderActivity::class.java).use { scenario ->
            waitForIdle()
            scenario.onActivity { activity ->
                assertNotNull(
                    "Common folder switch should exist",
                    activity.findViewById<android.view.View>(com.xyoye.user_component.R.id.last_open_folder_sw),
                )
            }
        }

        ActivityScenario.launch(AboutUsActivity::class.java).use { scenario ->
            waitForIdle()
            scenario.onActivity { activity ->
                assertNotNull(
                    "About page version text should exist",
                    activity.findViewById<android.view.View>(com.xyoye.user_component.R.id.version_tv),
                )
            }
        }

        ActivityScenario.launch(LicenseActivity::class.java).use { scenario ->
            waitForIdle()
            scenario.onActivity { activity ->
                assertNotNull(
                    "License list should exist",
                    activity.findViewById<RecyclerView>(com.xyoye.user_component.R.id.license_rv),
                )
            }
        }

        ActivityScenario.launch(LoginActivity::class.java).use { scenario ->
            waitForIdle()
            scenario.onActivity { activity ->
                assertNotNull(
                    "Login account input should exist",
                    activity.findViewById<android.view.View>(com.xyoye.user_component.R.id.user_account_et),
                )
                assertNotNull(
                    "Login password input should exist",
                    activity.findViewById<android.view.View>(com.xyoye.user_component.R.id.user_password_et),
                )
            }
        }

        ActivityScenario.launch(UserInfoActivity::class.java).use { scenario ->
            waitForIdle()
            scenario.onActivity { activity ->
                assertNotNull(
                    "User info page root view should exist",
                    activity.findViewById<android.view.View>(com.xyoye.user_component.R.id.user_account_tips),
                )
            }
        }
    }

    @Test
    fun animeAndLocalPagesLoad() {
        ActivityScenario.launch(SearchActivity::class.java).use { scenario ->
            waitForIdle()
            scenario.onActivity { activity ->
                assertEquals(2, activity.findViewById<DpadRecyclerView>(com.xyoye.anime_component.R.id.tab_layout).adapter?.itemCount)
                assertEquals(1, activity.supportFragmentManager.fragments.count { it.view?.isShown == true })
            }
        }

        ActivityScenario.launch(AnimeSeasonActivity::class.java).use { scenario ->
            waitForIdle()
            scenario.onActivity { activity ->
                assertNotNull(
                    "Anime season list should exist",
                    activity.findViewById<RecyclerView>(com.xyoye.anime_component.R.id.anime_rv),
                )
            }
        }

        ActivityScenario.launch(PlayHistoryActivity::class.java).use { scenario ->
            waitForIdle()
            scenario.onActivity { activity ->
                assertNotNull(
                    "Play history list should exist",
                    activity.findViewById<RecyclerView>(com.xyoye.local_component.R.id.play_history_rv),
                )
            }
        }

        ActivityScenario.launch(ShooterSubtitleActivity::class.java).use { scenario ->
            waitForIdle()
            scenario.onActivity { activity ->
                assertNotNull(
                    "Shooter subtitle list should exist",
                    activity.findViewById<RecyclerView>(com.xyoye.local_component.R.id.subtitle_rv),
                )
            }
        }
    }

    @Test
    fun storagePagesLoad() {
        ActivityScenario.launch(ScreencastActivity::class.java).use { scenario ->
            waitForIdle()
            scenario.onActivity { activity ->
                assertNotNull(
                    "Screencast status text should exist",
                    activity.findViewById<android.view.View>(com.xyoye.storage_component.R.id.server_status_tv),
                )
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storagePlusIntent =
            Intent(context, StoragePlusActivity::class.java).apply {
                putExtra("mediaType", MediaType.REMOTE_STORAGE)
            }
        ActivityScenario.launch<StoragePlusActivity>(storagePlusIntent).use { scenario ->
            waitForIdle()
            scenario.onActivity { activity ->
                assertFalse("StoragePlus should not finish with a valid mediaType", activity.isFinishing)
            }
        }
    }

    private inline fun <reified T : Fragment> assertFragmentTagIs(
        activity: FragmentActivity,
        tag: String,
    ) {
        val fragment = activity.supportFragmentManager.findFragmentByTag(tag)
        assertTrue("Expected fragment '$tag' to be ${T::class.java.simpleName}", fragment is T)
    }

    private fun waitForIdle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun sendDpad(keyCode: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(keyCode)
        waitForIdle()
    }
}
