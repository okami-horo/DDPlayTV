package com.okamihoro.ddplaytv.ui.tv

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.RadioButton
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xyoye.common_component.utils.danmu.DanmuFinder
import com.xyoye.local_component.ui.dialog.DanmuDownloadDialog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DanmuDownloadInstrumentedTest {
    @Test
    fun configuredAppCanSearchAndDownloadOfficialDanmu() =
        runBlocking<Unit> {
            assumeTrue(InstrumentationRegistry.getArguments().getString("dandanLive") == "true")

            val episode =
                DanmuFinder.instance
                    .searchResult("葬送的芙莉莲")
                    .getOrThrow()
                    .flatMap { it.episodes }
                    .first { it.episodeId == "176170001" }
            val downloaded = checkNotNull(DanmuFinder.instance.downloadEpisode(episode))
            val xml = File(downloaded.danmuPath).readText()

            assertEquals(episode.episodeId, downloaded.episodeId)
            assertTrue("The configured application must save real comments", xml.contains("<d p="))
        }

    @Test
    fun remoteCanDownloadAllOrOfficialCommentsAndCancelWithoutDownloading() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.setInTouchMode(false)
        val requests = mutableListOf<Boolean>()
        lateinit var dialog: DanmuDownloadDialog

        ActivityScenario.launch(TvMainActivity::class.java).use { scenario ->
            fun showDialog() {
                scenario.onActivity { activity ->
                    dialog =
                        DanmuDownloadDialog(activity) { related ->
                            requests.add(related)
                            dialog.dismiss()
                        }
                    dialog.show()
                }
                val deadline = SystemClock.uptimeMillis() + 5000
                var ready = false
                while (!ready && SystemClock.uptimeMillis() < deadline) {
                    instrumentation.waitForIdleSync()
                    scenario.onActivity {
                        ready = dialog.window?.decorView?.hasWindowFocus() == true &&
                            dialog.currentFocus?.id == com.xyoye.local_component.R.id.rb_all_source
                    }
                    if (!ready) SystemClock.sleep(25)
                }
                assertTrue("Expected visible focus on the default all-comments option", ready)
                scenario.onActivity {
                    assertTrue(dialog.currentFocus!!.isShown)
                    assertTrue(dialog.findViewById<RadioButton>(com.xyoye.local_component.R.id.rb_all_source)!!.isChecked)
                }
            }

            fun key(code: Int) {
                instrumentation.sendKeyDownUpSync(code)
                instrumentation.waitForIdleSync()
            }

            showDialog()
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            File(instrumentation.targetContext.getExternalFilesDir(null), "dandan-download-dialog.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            scenario.onActivity { assertEquals(com.xyoye.core_ui_component.R.id.positive_bt, dialog.currentFocus?.id) }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            assertEquals(listOf(true), requests)

            showDialog()
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            scenario.onActivity { assertEquals(com.xyoye.local_component.R.id.rb_official_source, dialog.currentFocus?.id) }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            scenario.onActivity {
                assertTrue(dialog.findViewById<RadioButton>(com.xyoye.local_component.R.id.rb_official_source)!!.isChecked)
            }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            scenario.onActivity { assertEquals(com.xyoye.core_ui_component.R.id.positive_bt, dialog.currentFocus?.id) }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            assertEquals(listOf(true, false), requests)

            showDialog()
            key(KeyEvent.KEYCODE_BACK)
            scenario.onActivity { activity ->
                assertFalse(dialog.isShowing)
                assertTrue("Returning from the dialog must retain visible host focus", activity.currentFocus?.isShown == true)
            }
            assertEquals(listOf(true, false), requests)
        }
    }
}
