package com.okamihoro.ddplaytv.ui.tv

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.okamihoro.ddplaytv.playback.withIndexedPlaybackFile
import com.rubensousa.dpadrecyclerview.DpadRecyclerView
import com.xyoye.common_component.adapter.BaseAdapter
import com.xyoye.common_component.config.PlayerConfig
import com.xyoye.common_component.database.DatabaseProvider
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.PlayerType
import com.xyoye.player.DanDanVideoPlayer
import com.xyoye.player_component.ui.activities.player.PlayerActivity
import com.xyoye.storage_component.R
import com.xyoye.storage_component.ui.activities.storage_file.StorageFileActivity
import com.xyoye.storage_component.ui.fragment.storage_file.StorageFileFragment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FileBrowserPlaybackReturnInstrumentedTest {
    @Test fun actualPlaybackReturnsToTheSameFileAndPreview() = verifyResume(PlayerType.TYPE_EXO_PLAYER, "Media3VideoPlayer")

    @Test fun mpvPlaybackReturnsAndResumesTheSameFile() = verifyResume(PlayerType.TYPE_MPV_PLAYER, "MpvVideoPlayer")

    private fun verifyResume(
        kernel: PlayerType,
        kernelClass: String
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File("/sdcard/Download/file-browser-c.mp4")
        assertTrue("Push the generated file-browser fixture before running", file.isFile)
        val previousKernel = PlayerConfig.getUsePlayerType()
        PlayerConfig.putUsePlayerType(kernel.value)
        instrumentation.setInTouchMode(false)
        var describeState: () -> String = { "" }

        fun await(
            message: String = "Expected playback/browser state",
            condition: () -> Boolean
        ) {
            val deadline = SystemClock.uptimeMillis() + 12000
            var ready = false
            while (!ready && SystemClock.uptimeMillis() < deadline) {
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync { ready = condition() }
                if (!ready) SystemClock.sleep(30)
            }
            var details = ""
            if (!ready) instrumentation.runOnMainSync { details = describeState() }
            assertTrue("$message $details", ready)
        }

        fun send(key: Int) {
            instrumentation.sendKeyDownUpSync(key)
            instrumentation.waitForIdleSync()
        }
        try {
            withIndexedPlaybackFile(file) { descriptor ->
                val historyDao = DatabaseProvider.instance.getPlayHistoryDao()
                runBlocking {
                    historyDao.getPlayHistory(descriptor.file.uniqueKey, descriptor.libraryId)?.let { historyDao.delete(it.id) }
                }
                val intent = Intent(context, StorageFileActivity::class.java).putExtra("storageLibrary", MediaLibraryEntity.LOCAL)
                ActivityScenario.launch<StorageFileActivity>(intent).use { scenario ->
                    lateinit var activity: StorageFileActivity
                    scenario.onActivity { activity = it }

                    fun fragment() =
                        activity.supportFragmentManager.fragments
                            .filterIsInstance<StorageFileFragment>()
                            .last { !it.isHidden }

                    fun list() = fragment().requireView().findViewById<DpadRecyclerView>(R.id.storage_file_rv)

                    fun items() = (list().adapter as BaseAdapter).items.filterIsInstance<StorageFile>()

                    fun activePlayer(): Pair<PlayerActivity, DanDanVideoPlayer>? {
                        val player =
                            ActivityLifecycleMonitorRegistry
                                .getInstance()
                                .getActivitiesInStage(Stage.RESUMED)
                                .filterIsInstance<PlayerActivity>()
                                .firstOrNull() ?: return null
                        val view =
                            player
                                .findViewById<FrameLayout>(
                                    com.xyoye.player_component.R.id.player_container
                                ).getChildAt(0) as DanDanVideoPlayer
                        return player to view
                    }

                    describeState = {
                        "browserWindow=${activity.hasWindowFocus()}, listFocus=${list().hasFocus()}, " +
                            "preview=${fragment().requireView().findViewById<TextView>(R.id.preview_title).text}, " +
                            "player=${activePlayer()?.second?.getPlayState()}, focus=${activity.currentFocus}"
                    }

                    fun exitToBrowser() {
                        // Keep confirmation presses together; waiting for toast/UI idleness between them can expire the exit window.
                        repeat(3) {
                            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                        }
                        instrumentation.waitForIdleSync()
                        await("BACK must return to the selected file with its preview and focus") {
                            activity.hasWindowFocus() &&
                                list().hasFocus() &&
                                fragment()
                                    .requireView()
                                    .findViewById<TextView>(R.id.preview_title)
                                    .text
                                    .toString() == file.name
                        }
                    }
                    await { runCatching { items().any { it.filePath() == file.parent } }.getOrDefault(false) }
                    scenario.onActivity {
                        list().setSelectedPosition(items().indexOfFirst { it.filePath() == file.parent })
                        list().requestFocus()
                    }
                    send(KeyEvent.KEYCODE_DPAD_CENTER)
                    await { items().any { it.filePath() == file.path } && list().hasFocus() }
                    send(KeyEvent.KEYCODE_DPAD_CENTER)
                    await {
                        val (player, view) = activePlayer() ?: return@await false
                        player.hasWindowFocus() && view.isPlaying() && view.getCurrentPosition() > 100
                    }
                    instrumentation.runOnMainSync {
                        val view = activePlayer()!!.second
                        val actualKernel =
                            DanDanVideoPlayer::class.java
                                .getDeclaredField("mVideoPlayer")
                                .apply { isAccessible = true }
                                .get(view)
                        assertEquals(kernelClass, actualKernel.javaClass.simpleName)
                        view.seekTo(8_000)
                    }
                    await {
                        val view = activePlayer()?.second ?: return@await false
                        view.getCurrentPosition() in 7_500L..12_000L
                    }
                    instrumentation.runOnMainSync { activePlayer()!!.second.pause() }
                    exitToBrowser()

                    // Read after exit/onPause/onDestroy, when a released backend used to overwrite with zero.
                    val saved = runBlocking { historyDao.getPlayHistory(descriptor.file.uniqueKey, descriptor.libraryId) }
                    assertNotNull("Leaving playback must create source-scoped history", saved)
                    assertTrue("Exit overwrote progress: ${saved!!.videoPosition}", saved.videoPosition in 7_500L..12_000L)
                    assertTrue("Exit overwrote duration", saved.videoDuration > saved.videoPosition)

                    send(KeyEvent.KEYCODE_DPAD_CENTER)
                    await("Selecting the same browser item must resume at the saved position") {
                        val (player, view) = activePlayer() ?: return@await false
                        player.hasWindowFocus() &&
                            view.isPlaying() &&
                            view.getCurrentPosition() in
                            (saved.videoPosition - 500)..(saved.videoPosition + 3_000)
                    }
                    assertEquals(kernel.value, PlayerConfig.getUsePlayerType())
                    exitToBrowser()
                }
            }
        } finally {
            PlayerConfig.putUsePlayerType(previousKernel)
        }
    }
}
