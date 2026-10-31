package com.okamihoro.ddplaytv.ui.tv

import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.inspector.WindowInspector
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.rubensousa.dpadrecyclerview.DpadRecyclerView
import com.xyoye.common_component.utils.ActivityHelper
import com.xyoye.storage_component.ui.activities.storage_plus.StoragePlusActivity
import com.xyoye.storage_component.ui.dialog.StorageEditDialog
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class MediaSourceEntryInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun addButtonAndMenuOpenNineSourcesAndReturnFromFtpAndWebDavByRemote() {
        instrumentation.setInTouchMode(false)
        ActivityScenario.launch(TvMainActivity::class.java).use { scenario ->
            lateinit var activity: TvMainActivity
            scenario.onActivity { activity = it }
            await("Top navigation") { activity.hasWindowFocus() }
            send(KeyEvent.KEYCODE_DPAD_RIGHT)
            send(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Home media list") {
                activity.hasWindowFocus() &&
                    (activity.findViewById<RecyclerView>(com.xyoye.local_component.R.id.media_lib_rv).adapter?.itemCount ?: 0) > 0
            }
            await("First source description remains fully visible") {
                val description =
                    activity.findViewById<android.widget.TextView>(com.xyoye.local_component.R.id.library_url_tv)
                        ?: return@await false
                val bounds = android.graphics.Rect()
                description.height > 0 && description.getGlobalVisibleRect(bounds) && bounds.height() >= description.height
            }
            send(KeyEvent.KEYCODE_DPAD_DOWN)
            repeat(12) {
                if (activity.currentFocus?.id !=
                    com.xyoye.local_component.R.id.add_media_storage_bt
                ) {
                    send(KeyEvent.KEYCODE_DPAD_RIGHT)
                }
            }
            scenario.onActivity {
                assertEquals(
                    "The add button must be reachable from the list",
                    com.xyoye.local_component.R.id.add_media_storage_bt,
                    activity.currentFocus?.id
                )
            }
            send(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitChooser()
            screenshot("source-chooser")
            // First two actions are FTP and WebDAV; selection is entirely remote driven.
            send(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitEditor("FTP")
            screenshot("source-ftp")
            send(KeyEvent.KEYCODE_BACK)
            await("Return to add button") {
                activity.hasWindowFocus() &&
                    activity.currentFocus?.id == com.xyoye.local_component.R.id.add_media_storage_bt
            }
            send(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitChooser()
            send(KeyEvent.KEYCODE_DPAD_DOWN)
            send(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitEditor("WebDav")
            screenshot("source-webdav")
            send(KeyEvent.KEYCODE_BACK)
            await("Return from WebDAV") { activity.hasWindowFocus() }
            send(KeyEvent.KEYCODE_DPAD_LEFT)
            lateinit var initiatingView: View
            scenario.onActivity {
                initiatingView = requireNotNull(activity.currentFocus)
                assertNotNull(
                    activity
                        .findViewById<RecyclerView>(
                            com.xyoye.local_component.R.id.media_lib_rv
                        ).findContainingViewHolder(initiatingView)
                )
            }
            repeat(2) {
                send(KeyEvent.KEYCODE_MENU)
                awaitChooser()
                for (position in 1..8) {
                    send(KeyEvent.KEYCODE_DPAD_DOWN)
                    await("Chooser scroll to item $position") {
                        val list = chooser() ?: return@await false
                        list.scrollState == RecyclerView.SCROLL_STATE_IDLE &&
                            list.findContainingViewHolder(list.rootView.findFocus() ?: return@await false)?.bindingAdapterPosition ==
                            position
                    }
                }
                instrumentation.runOnMainSync {
                    val list = chooser()!!
                    assertEquals(8, list.findContainingViewHolder(list.rootView.findFocus()!!)!!.bindingAdapterPosition)
                }
                send(KeyEvent.KEYCODE_DPAD_DOWN)
                instrumentation.runOnMainSync {
                    assertEquals(
                        com.xyoye.core_ui_component.R.id.negative_bt,
                        WindowInspector
                            .getGlobalWindowViews()
                            .first {
                                it.hasWindowFocus()
                            }.findFocus()
                            ?.id
                    )
                }
                send(KeyEvent.KEYCODE_DPAD_CENTER)
                await("Cancel restores the initiating item") { activity.hasWindowFocus() && activity.currentFocus === initiatingView }
            }
        }
    }

    private fun chooser(): DpadRecyclerView? =
        WindowInspector
            .getGlobalWindowViews()
            .firstOrNull { it.hasWindowFocus() }
            ?.findViewById(com.xyoye.core_ui_component.R.id.content_rv)

    private fun awaitChooser() {
        await("Nine source actions and default item focus") {
            val list = chooser()
            list?.adapter?.itemCount == 9 &&
                list.height > 0 &&
                list.scrollState == RecyclerView.SCROLL_STATE_IDLE &&
                list.findContainingViewHolder(list.rootView.findFocus() ?: return@await false)?.bindingAdapterPosition == 0
        }
    }

    private fun awaitEditor(name: String) {
        await("$name editor") {
            val editor = ActivityHelper.instance.getTopActivity() as? StoragePlusActivity ?: return@await false
            val dialog =
                StoragePlusActivity::class.java
                    .getDeclaredField("storageEditDialog")
                    .apply {
                        isAccessible = true
                    }.get(editor) as? StorageEditDialog<*>
            dialog?.window?.decorView?.hasWindowFocus() == true &&
                dialog.currentFocus?.isShown == true &&
                editor.intent
                    .getSerializableExtra("mediaType")
                    .toString()
                    .contains(if (name == "FTP") "FTP" else "WEBDAV")
        }
    }

    private fun send(key: Int) {
        instrumentation.sendKeyDownUpSync(key)
        instrumentation.waitForIdleSync()
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(350)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        java.io.File(instrumentation.targetContext.getExternalFilesDir("cinema-reference"), "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun await(
        message: String,
        condition: () -> Boolean
    ) {
        var ready = false
        val deadline = SystemClock.uptimeMillis() + 7000
        while (!ready && SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { ready = condition() }
            if (!ready) SystemClock.sleep(30)
        }
        assertTrue(message, ready)
    }
}
