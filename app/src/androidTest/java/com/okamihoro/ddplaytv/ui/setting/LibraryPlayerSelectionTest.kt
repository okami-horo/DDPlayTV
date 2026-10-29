package com.okamihoro.ddplaytv.ui.setting

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.MediaType
import com.xyoye.storage_component.R
import com.xyoye.storage_component.ui.activities.storage_plus.StoragePlusActivity
import com.xyoye.storage_component.ui.dialog.WebDavStorageEditDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

            fun backState(label: String): String {
                var state = ""
                instrumentation.runOnMainSync {
                    val decor = dialog.window?.decorView
                    val focus = dialog.currentFocus
                    val imeVisible =
                        decor?.let {
                            ViewCompat.getRootWindowInsets(it)?.isVisible(WindowInsetsCompat.Type.ime())
                        }
                    state = "$label: showing=${dialog.isShowing}, " +
                        "windowFocus=${decor?.hasWindowFocus()}, " +
                        "focus=${focus?.javaClass?.simpleName}:${focus?.id}, imeVisible=$imeVisible"
                }
                instrumentation.sendStatus(
                    0,
                    Bundle().apply {
                        putString(Instrumentation.REPORT_KEY_STREAMRESULT, "\nLibraryPlayerSelectionTest $state\n")
                    }
                )
                return state
            }
            val beforeBack = backState("before BACK")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            instrumentation.waitForIdleSync()
            val afterBack = backState("after BACK")
            assertFalse("$beforeBack; $afterBack", dialog.isShowing)
        }
    }
}
