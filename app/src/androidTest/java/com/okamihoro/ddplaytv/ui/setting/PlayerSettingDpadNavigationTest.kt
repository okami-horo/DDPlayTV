package com.okamihoro.ddplaytv.ui.setting

import android.os.SystemClock
import android.view.KeyEvent
import androidx.fragment.app.Fragment
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.SwitchPreference
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rubensousa.dpadrecyclerview.DpadRecyclerView
import com.xyoye.common_component.config.PlayerConfig
import com.xyoye.user_component.R
import com.xyoye.user_component.ui.activities.setting_player.SettingPlayerActivity
import com.xyoye.user_component.ui.fragment.DanmuSettingFragment
import com.xyoye.user_component.ui.fragment.PlayerSettingFragment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerSettingDpadNavigationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before
    fun useRemoteInputMode() {
        instrumentation.setInTouchMode(false)
    }

    @Test
    fun retiredPlayerPreferenceIsNormalizedAndSurvivesRecreation() {
        val original = PlayerConfig.getUsePlayerType()
        PlayerConfig.putUsePlayerType(3)
        try {
            ActivityScenario.launch(SettingPlayerActivity::class.java).use { scenario ->
                repeat(2) { pass ->
                    if (pass > 0) scenario.recreate()
                    scenario.onActivity {
                        val preference = (active(it) as PlayerSettingFragment).findPreference<ListPreference>("player_type")!!
                        assertEquals(listOf("2", "4"), preference.entryValues.map(CharSequence::toString))
                        assertEquals("2", preference.value)
                        assertEquals(2, PlayerConfig.getUsePlayerType())
                    }
                }
            }
        } finally {
            PlayerConfig.putUsePlayerType(original)
        }
    }

    @Test
    fun categoriesRequireExplicitNavigationAndSurviveRecreation() {
        ActivityScenario.launch(SettingPlayerActivity::class.java).use { scenario ->
            await(scenario) { it.findViewById<DpadRecyclerView>(R.id.tab_layout).hasFocus() && it.hasWindowFocus() }
            send(KeyEvent.KEYCODE_DPAD_DOWN)
            await(scenario) { active(it) is PlayerSettingFragment && (active(it) as PlayerSettingFragment).listView.hasFocus() }
            repeat(100) { send(KeyEvent.KEYCODE_DPAD_DOWN) }
            repeat(100) { send(KeyEvent.KEYCODE_DPAD_UP) }
            scenario.onActivity {
                assertTrue(active(it) is PlayerSettingFragment)
                assertTrue(it.currentFocus?.isShown == true)
                it.findViewById<DpadRecyclerView>(R.id.tab_layout).requestFocus()
            }
            send(KeyEvent.KEYCODE_DPAD_RIGHT)
            scenario.onActivity { assertTrue("Browsing a category must not activate it", active(it) is PlayerSettingFragment) }
            send(KeyEvent.KEYCODE_DPAD_CENTER)
            await(scenario) { active(it) is DanmuSettingFragment }
            send(KeyEvent.KEYCODE_DPAD_DOWN)
            await(scenario) { (active(it) as DanmuSettingFragment).listView.hasFocus() }
            send(KeyEvent.KEYCODE_DPAD_RIGHT)
            send(KeyEvent.KEYCODE_DPAD_LEFT)
            scenario.onActivity { assertTrue("Content arrows must not change category", active(it) is DanmuSettingFragment) }
            scenario.recreate()
            await(scenario) { active(it) is DanmuSettingFragment && it.currentFocus?.isShown == true }
            scenario.onActivity {
                assertEquals(1, it.supportFragmentManager.fragments.count { fragment -> fragment.view?.isShown == true })
            }
        }
    }

    @Test
    fun switchExecutesOnceAndPersistsAcrossRecreation() {
        val originalKernel = PlayerConfig.getUsePlayerType()
        PlayerConfig.putUsePlayerType(2)
        try {
            verifySurfaceSwitchPersists()
        } finally {
            PlayerConfig.putUsePlayerType(originalKernel)
        }
    }

    @Test
    fun mpvOutputIsFixedAndSurfaceSettingOnlyAppliesToMedia3() {
        val originalKernel = PlayerConfig.getUsePlayerType()
        try {
            for (kernel in listOf(4, 2)) {
                PlayerConfig.putUsePlayerType(kernel)
                ActivityScenario.launch(SettingPlayerActivity::class.java).use { scenario ->
                    scenario.onActivity {
                        val fragment = active(it) as PlayerSettingFragment
                        assertNull(fragment.findPreference<Preference>("mpv_video_output"))
                        assertNull(fragment.findPreference<Preference>("mpv_hwdec_priority"))
                        assertEquals(kernel == 2, fragment.findPreference<Preference>("surface_renders")!!.isVisible)
                        assertEquals(kernel == 4, fragment.findPreference<Preference>("mpv_audio_output")!!.isVisible)
                    }
                }
            }
        } finally {
            PlayerConfig.putUsePlayerType(originalKernel)
        }
    }

    private fun verifySurfaceSwitchPersists() {
        ActivityScenario.launch(SettingPlayerActivity::class.java).use { scenario ->
            var original = false
            var position = -1
            scenario.onActivity { activity ->
                val fragment = active(activity) as PlayerSettingFragment
                original = fragment.findPreference<SwitchPreference>("surface_renders")!!.isChecked
                val list = fragment.listView as DpadRecyclerView
                position = (list.adapter as PreferenceGroupAdapter).getPreferenceAdapterPosition("surface_renders")
                list.setSelectedPosition(position)
                list.requestFocus()
            }
            await(scenario) { activity ->
                val list = (active(activity) as PlayerSettingFragment).listView
                activity.hasWindowFocus() &&
                    activity.currentFocus?.let { list.findContainingViewHolder(it)?.bindingAdapterPosition == position } == true
            }
            try {
                send(KeyEvent.KEYCODE_DPAD_CENTER)
                scenario.onActivity {
                    assertEquals(
                        !original,
                        (active(it) as PlayerSettingFragment).findPreference<SwitchPreference>("surface_renders")!!.isChecked
                    )
                }
                send(KeyEvent.KEYCODE_DPAD_LEFT)
                send(KeyEvent.KEYCODE_DPAD_RIGHT)
                scenario.onActivity { assertTrue(active(it) is PlayerSettingFragment) }
                scenario.recreate()
                await(scenario) { active(it) is PlayerSettingFragment }
                scenario.onActivity {
                    assertEquals(
                        !original,
                        (active(it) as PlayerSettingFragment).findPreference<SwitchPreference>("surface_renders")!!.isChecked
                    )
                }
            } finally {
                scenario.onActivity {
                    (active(it) as PlayerSettingFragment).findPreference<SwitchPreference>("surface_renders")!!.isChecked =
                        original
                }
            }
        }
    }

    private fun active(activity: SettingPlayerActivity): Fragment? =
        activity.supportFragmentManager.fragments.firstOrNull { !it.isHidden && it.view != null }

    private fun send(key: Int) {
        instrumentation.sendKeyDownUpSync(key)
        instrumentation.waitForIdleSync()
    }

    private fun await(
        scenario: ActivityScenario<SettingPlayerActivity>,
        condition: (SettingPlayerActivity) -> Boolean
    ) {
        val deadline = SystemClock.uptimeMillis() + 5000
        var ready = false
        var diagnostic = ""
        while (!ready && SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                ready = condition(it)
                diagnostic = "focus=${it.currentFocus?.javaClass?.simpleName}:${it.currentFocus?.id} window=${it.hasWindowFocus()}"
            }
            if (!ready) SystemClock.sleep(25)
        }
        assertTrue("Expected category and actual focus target: $diagnostic", ready)
    }
}
