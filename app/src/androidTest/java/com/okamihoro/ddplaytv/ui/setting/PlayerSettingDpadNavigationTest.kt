package com.okamihoro.ddplaytv.ui.setting

import android.content.res.Configuration
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager2.widget.ViewPager2
import com.xyoye.user_component.R
import com.xyoye.user_component.ui.activities.setting_player.SettingPlayerActivity
import com.xyoye.user_component.ui.fragment.PlayerSettingFragment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerSettingDpadNavigationTest {
    @Before
    fun useRemoteInputMode() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
    }

    @Test
    fun dpadDownAtBottomDoesNotSwitchPageInTvSettingsMode() {
        ActivityScenario.launch(SettingPlayerActivity::class.java).use { scenario ->
            waitForIdle()

            scenario.onActivity { activity ->
                assumeTrue("This test only applies to TV UI mode", activity.isTvUiMode())
                activity.supportFragmentManager.executePendingTransactions()

                val playerFragment = findFragment<PlayerSettingFragment>(activity)
                val listView = playerFragment.listView
                val lastPosition = (listView.adapter?.itemCount ?: 0) - 1
                assertTrue("Player settings list should contain at least one item", lastPosition >= 0)
                listView.scrollToPosition(lastPosition)
            }

            waitForIdle()

            scenario.onActivity { activity ->
                val playerFragment = findFragment<PlayerSettingFragment>(activity)
                val listView = playerFragment.listView
                val lastPosition = (listView.adapter?.itemCount ?: 0) - 1
                val holder = listView.findViewHolderForAdapterPosition(lastPosition)
                assertNotNull("Expected last preference row to be bound", holder)

                val focusTarget = findFocusableView(holder!!.itemView)
                assertNotNull("Expected last preference row to have a focusable target", focusTarget)
                assertTrue("Failed to focus the last preference row", focusTarget!!.requestFocus())
            }

            waitForIdle()

            repeat(3) {
                sendDpad(KeyEvent.KEYCODE_DPAD_DOWN)
                waitForIdle()
            }

            scenario.onActivity { activity ->
                val viewPager = activity.findViewById<ViewPager2>(R.id.viewpager)
                assertNotNull("Player setting ViewPager should be present", viewPager)
                assertEquals(
                    "Pressing DPAD_DOWN at the bottom should keep the current tab",
                    0,
                    viewPager.currentItem,
                )
            }

            sendDpad(KeyEvent.KEYCODE_DPAD_RIGHT)
            waitForIdle()

            scenario.onActivity { activity ->
                val viewPager = activity.findViewById<ViewPager2>(R.id.viewpager)
                assertEquals("DPAD_RIGHT should still switch to next tab", 1, viewPager.currentItem)
            }
        }
    }

    private fun sendDpad(keyCode: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(keyCode)
    }

    private fun waitForIdle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun SettingPlayerActivity.isTvUiMode(): Boolean {
        val mask = resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        return mask == Configuration.UI_MODE_TYPE_TELEVISION
    }

    private inline fun <reified T : Fragment> findFragment(activity: SettingPlayerActivity): T {
        val fragment =
            activity.supportFragmentManager.fragments.firstOrNull {
                it is T
            }
        assertTrue("Expected fragment ${T::class.java.simpleName} to be attached", fragment is T)
        @Suppress("UNCHECKED_CAST")
        return fragment as T
    }

    private fun findFocusableView(root: View): View? {
        if (root.isShown && root.isFocusable) {
            return root
        }

        val viewGroup = root as? ViewGroup ?: return null
        for (index in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(index)
            val found = findFocusableView(child)
            if (found != null) {
                return found
            }
        }
        return null
    }
}
