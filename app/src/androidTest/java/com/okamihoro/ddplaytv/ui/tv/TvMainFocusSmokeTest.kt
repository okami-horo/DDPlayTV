package com.okamihoro.ddplaytv.ui.tv

import android.content.res.Configuration
import android.view.KeyEvent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.okamihoro.ddplaytv.R
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
    fun tvShellKeepsVisibleFocusAcrossNavAndContentBoundary() {
        ActivityScenario.launch(TvMainActivity::class.java).use { scenario ->
            waitForIdle()
            scenario.onActivity { activity ->
                assumeTrue(activity.isTvUiMode())
                assertVisibleFocus(activity.currentFocus)
            }
            sendDpad(KeyEvent.KEYCODE_DPAD_RIGHT)
            waitForIdle()
            scenario.onActivity { activity ->
                assertVisibleFocus(activity.currentFocus)
            }
            sendDpad(KeyEvent.KEYCODE_DPAD_LEFT)
            waitForIdle()
            scenario.onActivity { activity ->
                assertVisibleFocus(activity.currentFocus)
                assertNotNull(activity.findViewById<View>(R.id.tv_nav_rv))
            }
        }
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
