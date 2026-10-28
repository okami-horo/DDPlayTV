package com.okamihoro.ddplaytv.ui.tv

import android.content.res.Configuration
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.okamihoro.ddplaytv.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvMainFocusSmokeTest {
    @Before
    fun useRemoteInputMode() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
    }

    @Test
    fun mediaLoadingDoesNotTakeFocusFromCurrentNavigationItem() {
        ActivityScenario.launch(TvMainActivity::class.java).use { scenario ->
            awaitMedia(scenario)
            scenario.onActivity { activity ->
                assertNavigationItem(activity, 0)
            }
        }
    }

    @Test
    fun tvShellKeepsVisibleFocusAcrossNavAndContentBoundary() {
        ActivityScenario.launch(TvMainActivity::class.java).use { scenario ->
            awaitMedia(scenario)
            scenario.onActivity { activity ->
                assertNavigationItem(activity, 0)
            }
            sendDpad(KeyEvent.KEYCODE_DPAD_RIGHT)
            waitForIdle()
            scenario.onActivity { activity ->
                assertVisibleFocus(activity.currentFocus)
                val media = activity.findViewById<RecyclerView>(com.xyoye.local_component.R.id.media_lib_rv)
                assertNotNull("RIGHT must enter a media item", media.findContainingViewHolder(activity.currentFocus!!))
            }
            sendDpad(KeyEvent.KEYCODE_DPAD_LEFT)
            waitForIdle()
            scenario.onActivity { activity ->
                assertNavigationItem(activity, 0)
            }
        }
    }

    private fun awaitMedia(scenario: ActivityScenario<TvMainActivity>) {
        var ready = false
        val deadline = SystemClock.uptimeMillis() + 5000
        while (!ready && SystemClock.uptimeMillis() < deadline) {
            waitForIdle()
            scenario.onActivity { activity ->
                assumeTrue(activity.isTvUiMode())
                val media = activity.findViewById<RecyclerView>(com.xyoye.local_component.R.id.media_lib_rv)
                ready = activity.hasWindowFocus() && (media?.adapter?.itemCount ?: 0) > 0
            }
            SystemClock.sleep(50)
        }
        assertTrue("Media data and the activity window must be ready", ready)
        waitForIdle()
    }

    private fun assertNavigationItem(
        activity: TvMainActivity,
        position: Int
    ) {
        val focus = activity.currentFocus
        assertVisibleFocus(focus)
        val navigation = activity.findViewById<RecyclerView>(R.id.tv_nav_rv)
        val holder = navigation.findContainingViewHolder(focus!!)
        assertNotNull("Expected focus in navigation, actual id=${focus.id}", holder)
        assertEquals("Expected current navigation item", position, holder!!.bindingAdapterPosition)
    }

    private fun sendDpad(keyCode: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(keyCode)
    }

    private fun waitForIdle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun TvMainActivity.isTvUiMode(): Boolean {
        val mask = resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        return mask == Configuration.UI_MODE_TYPE_TELEVISION
    }

    private fun assertVisibleFocus(focus: View?) {
        assertNotNull("Expected a focused view", focus)
        assertTrue("Expected focused view to be shown", focus!!.isShown)
    }
}
