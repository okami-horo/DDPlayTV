package com.xyoye.app.quality

import android.content.res.Configuration
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.okamihoro.ddplaytv.ui.tv.TvMainActivity
import com.rubensousa.dpadrecyclerview.DpadRecyclerView
import com.xyoye.common_component.weight.BottomActionDialog
import com.xyoye.data_component.bean.SheetActionBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
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
        lateinit var host: View
        ActivityScenario.launch(TvMainActivity::class.java).use { scenario ->
            waitForIdle()
            scenario.onActivity { activity ->
                assumeTrue(activity.isTvUiMode())
                assertVisibleFocus(activity.currentFocus)
                host = activity.currentFocus!!
                dialog =
                    BottomActionDialog(
                        activity = activity,
                        actionData = listOf(SheetActionBean(actionId = 1, actionName = "测试动作", actionIconRes = -1)),
                        title = "测试",
                    ) {
                        true
                    }
            }
            repeat(30) {
                scenario.onActivity { dialog.show() }
                awaitDialogFocus(scenario, dialog)
                scenario.onActivity {
                    val list = dialog.findViewById<DpadRecyclerView>(com.xyoye.core_ui_component.R.id.content_rv)!!
                    val focus = dialog.currentFocus!!
                    assertEquals(0, list.findContainingViewHolder(focus)!!.bindingAdapterPosition)
                }
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                waitForIdle()
                scenario.onActivity { activity ->
                    assertFalse("Expected BACK to dismiss the dialog", dialog.isShowing)
                    assertSame("Dismiss must retain the initiating target", host, activity.currentFocus)
                }
            }
        }
    }

    private fun waitForIdle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun awaitDialogFocus(
        scenario: ActivityScenario<TvMainActivity>,
        dialog: BottomActionDialog
    ) {
        var ready = false
        var diagnostic = ""
        val deadline = SystemClock.uptimeMillis() + 5000
        while (!ready && SystemClock.uptimeMillis() < deadline) {
            waitForIdle()
            scenario.onActivity {
                val list = dialog.findViewById<DpadRecyclerView>(com.xyoye.core_ui_component.R.id.content_rv)
                val focus = dialog.currentFocus
                ready = dialog.window?.decorView?.hasWindowFocus() == true &&
                    focus != null &&
                    list?.findContainingViewHolder(focus) != null
                diagnostic =
                    "window=${dialog.window?.decorView?.hasWindowFocus()} focus=${focus?.javaClass?.simpleName}:${focus?.id} listFocus=${list?.hasFocus()} children=${list?.childCount}"
            }
            SystemClock.sleep(25)
        }
        assertTrue("Expected an actionable list item in the dialog window: $diagnostic", ready)
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
