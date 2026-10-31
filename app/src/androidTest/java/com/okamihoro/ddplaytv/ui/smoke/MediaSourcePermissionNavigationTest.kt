package com.okamihoro.ddplaytv.ui.smoke

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.okamihoro.ddplaytv.ui.tv.TvMainActivity
import com.xyoye.common_component.database.DatabaseProvider
import com.xyoye.common_component.utils.ActivityHelper
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.MediaType
import com.xyoye.local_component.ui.activities.play_history.PlayHistoryActivity
import com.xyoye.storage_component.ui.activities.storage_file.StorageFileActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class MediaSourcePermissionNavigationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    // The host revokes permissions before starting this runner, avoiding killing our own process.
    @Test
    fun deniedLocalPermissionStillAllowsRemoteAndHistoryAndReturnsFocus() {
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO))
        val library =
            MediaLibraryEntity(
                displayName = "permission navigation fixture",
                mediaType = MediaType.WEBDAV_SERVER,
                url = "http://127.0.0.1:9/permission-fixture"
            )
        val dao = DatabaseProvider.instance.getMediaLibraryDao()
        assertNull(runBlocking { dao.getByUrl(library.url, library.mediaType) })
        runBlocking { dao.insert(library) }
        try {
            withHome { activity ->
                selectLibrary(activity, library.displayName)
                val host = focused(activity)
                send(KeyEvent.KEYCODE_DPAD_CENTER)
                await("Remote browser without local permission") { ActivityHelper.instance.getTopActivity() is StorageFileActivity }
                send(KeyEvent.KEYCODE_BACK)
                await("Remote BACK restores entry") { activity.hasWindowFocus() && activity.currentFocus === host }
                selectLibrary(activity, MediaLibraryEntity.HISTORY.displayName)
                val historyHost = focused(activity)
                send(KeyEvent.KEYCODE_DPAD_CENTER)
                await("History without local permission") { ActivityHelper.instance.getTopActivity() is PlayHistoryActivity }
                send(KeyEvent.KEYCODE_BACK)
                await("History BACK restores entry") { activity.hasWindowFocus() && activity.currentFocus === historyHost }
                assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO))
            }
        } finally {
            runBlocking { dao.delete(library.url, library.mediaType) }
        }
    }

    @Test
    fun grantingLocalPermissionRestoresLocalLibraryAccess() {
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO))
        withHome { activity ->
            selectLibrary(activity, MediaLibraryEntity.LOCAL.displayName)
            val host = focused(activity)
            send(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Local browser after permission restored") { ActivityHelper.instance.getTopActivity() is StorageFileActivity }
            send(KeyEvent.KEYCODE_BACK)
            await("Local BACK restores entry") { activity.hasWindowFocus() && activity.currentFocus === host }
        }
    }

    private fun withHome(block: (TvMainActivity) -> Unit) {
        instrumentation.setInTouchMode(false)
        ActivityScenario.launch(TvMainActivity::class.java).use { scenario ->
            lateinit var activity: TvMainActivity
            scenario.onActivity { activity = it }
            await("Top navigation") { activity.hasWindowFocus() }
            send(KeyEvent.KEYCODE_DPAD_RIGHT)
            send(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Home media list") { activity.hasWindowFocus() && (list(activity).adapter?.itemCount ?: 0) >= 4 }
            send(KeyEvent.KEYCODE_DPAD_DOWN)
            block(activity)
        }
    }

    private fun list(activity: TvMainActivity) = activity.findViewById<RecyclerView>(com.xyoye.local_component.R.id.media_lib_rv)

    private fun selectLibrary(
        activity: TvMainActivity,
        name: String
    ) {
        var count = 0
        instrumentation.runOnMainSync { count = list(activity).adapter!!.itemCount }
        repeat(count) { send(KeyEvent.KEYCODE_DPAD_UP) }
        repeat(count) {
            var found = false
            instrumentation.runOnMainSync {
                found = list(activity)
                    .findContainingViewHolder(activity.currentFocus ?: return@runOnMainSync)
                    ?.itemView
                    ?.findViewById<TextView>(com.xyoye.local_component.R.id.library_name_tv)
                    ?.text == name
            }
            if (found) return
            send(KeyEvent.KEYCODE_DPAD_DOWN)
        }
        fail("Library not reachable by DPAD: $name")
    }

    private fun focused(activity: TvMainActivity): View {
        var view: View? = null
        instrumentation.runOnMainSync { view = activity.currentFocus }
        return requireNotNull(view)
    }

    private fun send(key: Int) {
        instrumentation.sendKeyDownUpSync(key)
        instrumentation.waitForIdleSync()
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
