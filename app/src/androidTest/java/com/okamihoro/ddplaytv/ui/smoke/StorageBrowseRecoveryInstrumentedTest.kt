package com.okamihoro.ddplaytv.ui.smoke

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.MediaType
import com.xyoye.storage_component.ui.activities.storage_file.StorageFileActivity
import com.xyoye.storage_component.ui.fragment.storage_file.StorageFileFragment
import com.xyoye.storage_component.ui.fragment.storage_file.StorageFileFragmentViewModel
import fi.iki.elonen.NanoHTTPD
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class StorageBrowseRecoveryInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun failedDirectoryRetriesByRemoteAndFailedRefreshKeepsItsContent() {
        val fail = AtomicBoolean(true)
        val empty = AtomicBoolean(true)
        val server =
            object : NanoHTTPD(0) {
                override fun serve(session: IHTTPSession): Response {
                    session.parseBody(mutableMapOf())
                    return if (fail.get()) {
                        newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "fixture unavailable")
                    } else {
                        newFixedLengthResponse(
                            Response.Status.MULTI_STATUS,
                            "application/xml",
                            """
                            <d:multistatus xmlns:d="DAV:">
                              <d:response><d:href>/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
                              <d:response><d:href>/episode.mp4</d:href><d:propstat><d:prop><d:displayname>episode.mp4</d:displayname><d:getcontentlength>100</d:getcontentlength><d:resourcetype/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
                            </d:multistatus>
                            """.trimIndent().let { xml ->
                                if (empty.get()) xml.replace(Regex("<d:response><d:href>/episode.mp4.*?</d:response>"), "") else xml
                            }
                        )
                    }
                }
            }
        server.start()
        try {
            instrumentation.setInTouchMode(false)
            val library =
                MediaLibraryEntity(
                    displayName = "browse recovery fixture",
                    mediaType = MediaType.WEBDAV_SERVER,
                    url = "http://127.0.0.1:${server.listeningPort}/"
                )
            val intent = Intent(instrumentation.targetContext, StorageFileActivity::class.java).putExtra("storageLibrary", library)
            ActivityScenario.launch<StorageFileActivity>(intent).use { scenario ->
                lateinit var activity: StorageFileActivity
                lateinit var viewModel: StorageFileFragmentViewModel
                scenario.onActivity { activity = it }
                await("First failure is displayed") {
                    val fragment =
                        activity.supportFragmentManager.fragments
                            .filterIsInstance<StorageFileFragment>()
                            .firstOrNull()
                            ?: return@await false
                    viewModel = ViewModelProvider(fragment)[StorageFileFragmentViewModel::class.java]
                    viewModel.state.value?.error != null &&
                        activity.findViewById<TextView>(com.xyoye.core_ui_component.R.id.empty_action_tv)?.text == "重试加载"
                }
                var reached = false
                repeat(5) {
                    instrumentation.runOnMainSync {
                        reached = activity.currentFocus?.id == com.xyoye.core_ui_component.R.id.empty_action_tv
                    }
                    if (!reached) send(KeyEvent.KEYCODE_DPAD_DOWN)
                }
                instrumentation.runOnMainSync { assertEquals(com.xyoye.core_ui_component.R.id.empty_action_tv, activity.currentFocus?.id) }
                fail.set(false)
                send(KeyEvent.KEYCODE_DPAD_CENTER)
                await("Retry can return a real empty directory") {
                    viewModel.state.value?.let { it.hasDirectory && !it.loading && it.error == null && it.directoryFiles.isEmpty() } == true
                }
                instrumentation.runOnMainSync {
                    assertEquals(
                        "A successful empty response must replace the old error action",
                        "刷新",
                        activity.findViewById<TextView>(com.xyoye.core_ui_component.R.id.empty_action_tv).text.toString()
                    )
                }
                empty.set(false)
                focusList(activity)
                send(KeyEvent.KEYCODE_SETTINGS)
                await("Retry recovers the original directory") {
                    viewModel.state.value?.let { it.hasDirectory && !it.loading && it.error == null && it.directoryFiles.size == 1 } == true
                }
                focusList(activity)
                // SETTINGS refreshes; MENU now opens the selected file's actions.
                fail.set(true)
                send(KeyEvent.KEYCODE_SETTINGS)
                await("Failed refresh retains the visible directory") {
                    viewModel.state.value?.let { !it.loading && it.error != null && it.directoryFiles.size == 1 && it.items.size == 1 } ==
                        true
                }
                fail.set(false)
                focusList(activity)
                send(KeyEvent.KEYCODE_SETTINGS)
                await("A second retry clears the error") {
                    viewModel.state.value?.let {
                        !it.loading &&
                            it.error == null &&
                            it.items.size == 1
                    } ==
                        true
                }
                instrumentation.runOnMainSync { assertTrue(activity.currentFocus?.isShown == true) }
                send(KeyEvent.KEYCODE_BACK)
                await("BACK exits the root browser") { activity.isFinishing || activity.isDestroyed }
            }
        } finally {
            server.stop()
        }
    }

    private fun focusList(activity: StorageFileActivity) {
        repeat(5) {
            var focused = false
            instrumentation.runOnMainSync {
                focused =
                    activity
                        .findViewById<androidx.recyclerview.widget.RecyclerView>(
                            com.xyoye.storage_component.R.id.storage_file_rv
                        ).hasFocus() ||
                    activity.currentFocus?.id == com.xyoye.core_ui_component.R.id.empty_action_tv
            }
            if (focused) return
            send(KeyEvent.KEYCODE_DPAD_DOWN)
        }
        fail("The directory must remain reachable by DPAD after loading")
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
            if (!ready) SystemClock.sleep(25)
        }
        assertTrue(message, ready)
    }
}
