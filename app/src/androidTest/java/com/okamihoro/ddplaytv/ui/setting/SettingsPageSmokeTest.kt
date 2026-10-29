package com.okamihoro.ddplaytv.ui.setting

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.preference.Preference
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rubensousa.dpadrecyclerview.DpadRecyclerView
import com.xyoye.user_component.ui.activities.setting_app.SettingAppActivity
import com.xyoye.user_component.ui.activities.setting_developer.SettingDeveloperActivity
import com.xyoye.user_component.ui.activities.setting_player.SettingPlayerActivity
import com.xyoye.user_component.ui.fragment.AppSettingFragment
import com.xyoye.user_component.ui.fragment.DeveloperSettingFragment
import com.xyoye.user_component.ui.fragment.PlayerSettingFragment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 设置页烟测：验证页面可打开、关键 Fragment 可加载、PreferenceScreen 非空。
 */
@RunWith(AndroidJUnit4::class)
class SettingsPageSmokeTest {
    @Test
    fun developerSettingPageLoadsPreferences() {
        ActivityScenario.launch(SettingDeveloperActivity::class.java).use { scenario ->
            withFragmentByTag<DeveloperSettingFragment>(scenario, "DeveloperSettingFragment") { fragment ->
                val screen = fragment.preferenceScreen
                assertNotNull("Developer preferenceScreen should be initialized", screen)
                assertTrue("Developer preferenceScreen should contain items", screen.preferenceCount > 0)
                assertNotNull(
                    "Developer log level preference should exist",
                    fragment.findPreference<Preference>("developer_log_level"),
                )
                assertNotNull(
                    "Developer app log switch preference should exist",
                    fragment.findPreference<Preference>("app_log_enable"),
                )
            }
        }
    }

    @Test
    fun appSettingPageLoadsPreferences() {
        ActivityScenario.launch(SettingAppActivity::class.java).use { scenario ->
            withFragmentByTag<AppSettingFragment>(scenario, "AppSettingFragment") { fragment ->
                val screen = fragment.preferenceScreen
                assertNotNull("App setting preferenceScreen should be initialized", screen)
                assertTrue("App setting preferenceScreen should contain items", screen.preferenceCount > 0)
                assertNotNull(
                    "App dark mode preference should exist",
                    fragment.findPreference<Preference>("dark_mode"),
                )
            }
        }
    }

    @Test
    fun playerSettingPageLoadsRemoteCategoriesAndPreferences() {
        ActivityScenario.launch(SettingPlayerActivity::class.java).use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val categories = activity.findViewById<DpadRecyclerView>(com.xyoye.user_component.R.id.tab_layout)
                assertNotNull("Player setting categories should be present", categories)
                assertEquals("Player setting should contain three categories", 3, categories.adapter?.itemCount)
                val visible = activity.supportFragmentManager.fragments.filter { it.view?.isShown == true }
                assertEquals(1, visible.size)
                assertTrue(visible.single() is PlayerSettingFragment)
            }
        }
    }

    private inline fun <reified T : Fragment> withFragmentByTag(
        scenario: ActivityScenario<out FragmentActivity>,
        tag: String,
        crossinline assertion: (T) -> Unit,
    ) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        scenario.onActivity { activity ->
            activity.supportFragmentManager.executePendingTransactions()
            val fragment = activity.supportFragmentManager.findFragmentByTag(tag)
            assertTrue("Expected fragment '$tag' to be ${T::class.java.simpleName}", fragment is T)
            @Suppress("UNCHECKED_CAST")
            assertion(fragment as T)
        }
    }
}
