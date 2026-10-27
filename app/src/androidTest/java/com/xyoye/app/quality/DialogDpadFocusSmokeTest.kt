package com.xyoye.app.quality

import android.content.res.Configuration
import android.view.KeyEvent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.okamihoro.ddplaytv.ui.tv.TvMainActivity
import com.xyoye.common_component.weight.BottomActionDialog
import com.xyoye.data_component.bean.SheetActionBean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DialogDpadFocusSmokeTest {
    @Before
    fun useRemoteInputMode() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
    }

    @Test
    fun bottomActionDialogRestoresHostFocusAfterDismiss() {
        lateinit var dialog: BottomActionDialog
        ActivityScenario.launch(TvMainActivity::class.java).use { scenario ->
            waitForIdle()
            scenario.onActivity { activity ->
                assumeTrue(activity.isTvUiMode())
                assertVisibleFocus(activity.currentFocus)
                dialog = BottomActionDialog(
                    activity = activity,
                    actionData = listOf(SheetActionBean(actionId = 1, actionName = "测试动作", actionIconRes = -1)),
                    title = "测试",
                ) {
                    true
                }
                dialog.show()
            }
            waitForIdle()
            scenario.onActivity { activity ->
                assertVisibleFocus(activity.currentFocus)
            }
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitForIdle()
            scenario.onActivity { activity ->
                assertFalse("Expected BACK to dismiss the dialog", dialog.isShowing)
                assertVisibleFocus(activity.currentFocus)
            }
        }
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
