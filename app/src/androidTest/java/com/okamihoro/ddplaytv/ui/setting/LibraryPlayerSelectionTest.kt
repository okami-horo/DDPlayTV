package com.okamihoro.ddplaytv.ui.setting

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xyoye.common_component.extension.isTelevisionUiMode
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.MediaType
import com.xyoye.storage_component.R
import com.xyoye.storage_component.ui.activities.storage_plus.StoragePlusActivity
import com.xyoye.storage_component.ui.dialog.WebDavStorageEditDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryPlayerSelectionTest {
    @Test
    fun retiredOverrideFollowsGlobalAndRemainingChoicesAreReachableByRemote() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.setInTouchMode(false)
        // Incomplete credentials prevent this navigation fixture from saving a library.
        val library = MediaLibraryEntity(displayName = "", url = "", mediaType = MediaType.WEBDAV_SERVER, playerTypeOverride = 3)
        val intent =
            Intent(instrumentation.targetContext, StoragePlusActivity::class.java)
                .putExtra("mediaType", MediaType.WEBDAV_SERVER)
                .putExtra("editData", library)
        ActivityScenario.launch<StoragePlusActivity>(intent).use { scenario ->
            lateinit var dialog: WebDavStorageEditDialog
            scenario.onActivity {
                assumeTrue(it.isTelevisionUiMode())
                dialog =
                    StoragePlusActivity::class.java
                        .getDeclaredField("storageEditDialog")
                        .apply { isAccessible = true }
                        .get(it) as WebDavStorageEditDialog
                assertTrue(dialog.findViewById<View>(R.id.follow_global_tv)!!.isSelected)
            }
            val deadline = SystemClock.uptimeMillis() + 5000
            var focused = false
            while (!focused && SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity {
                    focused = dialog.window?.decorView?.hasWindowFocus() == true &&
                        dialog.findViewById<View>(R.id.follow_global_tv)!!.requestFocus()
                }
                if (!focused) SystemClock.sleep(25)
            }
            assertTrue(focused)
            for (choice in listOf(R.id.media3_tv, R.id.mpv_tv)) {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    assertEquals(choice, dialog.currentFocus?.id)
                    assertTrue(dialog.currentFocus!!.isShown)
                }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                instrumentation.waitForIdleSync()
                scenario.onActivity { assertTrue(dialog.findViewById<View>(choice)!!.isSelected) }
            }
            repeat(2) { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT) }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertTrue(dialog.findViewById<View>(R.id.follow_global_tv)!!.isSelected)
                assertFalse(dialog.findViewById<View>(R.id.mpv_tv)!!.isSelected)
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            instrumentation.waitForIdleSync()
            assertFalse(dialog.isShowing)
        }
    }
}
