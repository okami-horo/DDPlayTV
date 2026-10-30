package com.xyoye.app.quality

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

    @Test
    fun nineActionsHaveVisibleItemsAndDefaultFocus() {
        ActivityScenario.launch(TvMainActivity::class.java).use { scenario ->
            lateinit var dialog: BottomActionDialog
            scenario.onActivity { activity ->
                dialog = BottomActionDialog(activity, (1..9).map { SheetActionBean(it, "来源 $it", -1) }) { true }
                dialog.show()
            }
            awaitDialogFocus(scenario, dialog)
            scenario.onActivity {
                val list = dialog.findViewById<DpadRecyclerView>(com.xyoye.core_ui_component.R.id.content_rv)!!
                assertEquals(9, list.adapter!!.itemCount)
                assertTrue("Nine actions must have a measured viewport", list.height > 0)
                assertTrue("Nine actions must render actual children", list.childCount > 0)
                assertEquals(0, list.findContainingViewHolder(dialog.currentFocus!!)!!.bindingAdapterPosition)
                dialog.dismiss()
            }
        }
    }

    @Test
    fun longActionListScrollsToLastItemByRemote() {
        ActivityScenario.launch(TvMainActivity::class.java).use { scenario ->
            lateinit var dialog: BottomActionDialog
            scenario.onActivity { activity ->
                dialog = BottomActionDialog(activity, (1..30).map { SheetActionBean(it, "动作 $it", -1) }) { true }
                dialog.show()
            }
            awaitDialogFocus(scenario, dialog)
            repeat(29) { index ->
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                val deadline = SystemClock.uptimeMillis() + 3000
                var settled = false
                while (!settled && SystemClock.uptimeMillis() < deadline) {
                    waitForIdle()
                    scenario.onActivity {
                        val list = dialog.findViewById<DpadRecyclerView>(com.xyoye.core_ui_component.R.id.content_rv)!!
                        settled = list.scrollState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE &&
                            list.findContainingViewHolder(dialog.currentFocus!!)!!.bindingAdapterPosition == index + 1
                    }
                    if (!settled) SystemClock.sleep(25)
                }
                assertTrue("DPAD must reach and settle on item ${index + 1}", settled)
            }
            scenario.onActivity {
                val list = dialog.findViewById<DpadRecyclerView>(com.xyoye.core_ui_component.R.id.content_rv)!!
                assertEquals(29, list.findContainingViewHolder(dialog.currentFocus!!)!!.bindingAdapterPosition)
                assertTrue(dialog.currentFocus!!.isShown)
                dialog.dismiss()
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
                    "window=${dialog.window?.decorView?.hasWindowFocus()} focus=${focus?.javaClass?.simpleName}:${focus?.id} list=${list?.javaClass?.simpleName} count=${list?.adapter?.itemCount} measured=${list?.measuredWidth}x${list?.measuredHeight} size=${list?.width}x${list?.height} listFocus=${list?.hasFocus()} children=${list?.childCount}"
            }
            SystemClock.sleep(25)
        }
        assertTrue("Expected an actionable list item in the dialog window: $diagnostic", ready)
    }

    private fun assertVisibleFocus(focus: View?) {
        assertNotNull("Expected a focused view", focus)
        assertTrue("Expected focused view to be shown", focus!!.isShown)
    }
}
