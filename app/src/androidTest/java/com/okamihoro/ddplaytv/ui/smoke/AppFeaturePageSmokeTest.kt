package com.okamihoro.ddplaytv.ui.smoke

import android.content.Intent
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.tabs.TabLayout
import com.okamihoro.ddplaytv.R
import com.okamihoro.ddplaytv.ui.main.MainActivity
import com.okamihoro.ddplaytv.ui.tv.TvMainActivity
import com.rubensousa.dpadrecyclerview.DpadRecyclerView
import com.xyoye.anime_component.ui.activities.anime_season.AnimeSeasonActivity
import com.xyoye.anime_component.ui.activities.search.SearchActivity
import com.xyoye.anime_component.ui.fragment.home.HomeFragment
import com.xyoye.common_component.extension.isTelevisionUiMode
import com.xyoye.data_component.enums.MediaType
import com.xyoye.local_component.ui.activities.play_history.PlayHistoryActivity
import com.xyoye.local_component.ui.activities.shooter_subtitle.ShooterSubtitleActivity
import com.xyoye.local_component.ui.fragment.media.MediaFragment
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
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 应用主要功能页烟测：验证入口页可打开、关键容器存在、核心 Fragment/Tab 正常挂载。
 */
@RunWith(AndroidJUnit4::class)
class AppFeaturePageSmokeTest {
    @Test
    fun mainPageCanSwitchCoreTabs() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForIdle()
            scenario.onActivity { activity ->
                val nav = activity.findViewById<BottomNavigationView>(R.id.navigation_view)
                assertNotNull("BottomNavigationView should be present", nav)

                activity.supportFragmentManager.executePendingTransactions()
                assertFragmentTagIs<MediaFragment>(activity, "tag_fragment_media")

                nav.selectedItemId = R.id.navigation_home
                activity.supportFragmentManager.executePendingTransactions()
                assertFragmentTagIs<HomeFragment>(activity, "tag_fragment_home")

                nav.selectedItemId = R.id.navigation_personal
                activity.supportFragmentManager.executePendingTransactions()
                assertFragmentTagIs<PersonalFragment>(activity, "tag_fragment_personal")
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
                assertEquals("TV nav should contain four items", 4, navRv.adapter?.itemCount)

                activity.supportFragmentManager.executePendingTransactions()
                assertFragmentTagIs<MediaFragment>(activity, "tag_fragment_tv_media")
            }
        }
    }

    @Test
    fun userCenterPagesLoad() {
        ActivityScenario.launch(ScanManagerActivity::class.java).use { scenario ->
            waitForIdle()
            scenario.onActivity { activity ->
                if (activity.isTelevisionUiMode()) {
                    assertEquals(2, activity.findViewById<DpadRecyclerView>(com.xyoye.user_component.R.id.tab_layout).adapter?.itemCount)
                    assertEquals(1, activity.supportFragmentManager.fragments.count { it.view?.isShown == true })
                    return@onActivity
                }
                val viewPager = activity.findViewById<ViewPager2>(com.xyoye.user_component.R.id.viewpager)
                val tabLayout = activity.findViewById<TabLayout>(com.xyoye.user_component.R.id.tab_layout)
                assertNotNull("Scan manager ViewPager should exist", viewPager)
                assertNotNull("Scan manager TabLayout should exist", tabLayout)
                assertEquals(2, viewPager.adapter?.itemCount)
                assertEquals(2, tabLayout.tabCount)
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
                if (activity.isTelevisionUiMode()) {
                    assertEquals(2, activity.findViewById<DpadRecyclerView>(com.xyoye.anime_component.R.id.tab_layout).adapter?.itemCount)
                    assertEquals(1, activity.supportFragmentManager.fragments.count { it.view?.isShown == true })
                    return@onActivity
                }
                val viewPager = activity.findViewById<ViewPager2>(com.xyoye.anime_component.R.id.viewpager)
                assertNotNull("Anime search ViewPager should exist", viewPager)
                assertEquals(2, viewPager.adapter?.itemCount)
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
}
