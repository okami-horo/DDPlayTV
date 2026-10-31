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
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.PlayerType
import com.xyoye.player.DanDanVideoPlayer
import com.xyoye.player_component.ui.activities.player.PlayerActivity
import com.xyoye.storage_component.R
import com.xyoye.storage_component.ui.activities.storage_file.StorageFileActivity
import com.xyoye.storage_component.ui.fragment.storage_file.StorageFileFragment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FileBrowserPlaybackReturnInstrumentedTest {
    @Test fun actualPlaybackReturnsToTheSameFileAndPreview() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File("/sdcard/Download/file-browser-c.mp4")
        assertTrue("Push the generated file-browser fixture before running", file.isFile)
        val previousKernel = PlayerConfig.getUsePlayerType()
        PlayerConfig.putUsePlayerType(PlayerType.TYPE_EXO_PLAYER.value)
        instrumentation.setInTouchMode(false)

        fun await(condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 12000
            var ready = false
            while (!ready && SystemClock.uptimeMillis() < deadline) {
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync { ready = condition() }
                if (!ready) SystemClock.sleep(30)
            }
            assertTrue("Expected playback/browser state", ready)
        }

        fun send(key: Int) {
            instrumentation.sendKeyDownUpSync(key)
            instrumentation.waitForIdleSync()
        }
        try {
            withIndexedPlaybackFile(file) {
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
                    await { runCatching { items().any { it.filePath() == file.parent } }.getOrDefault(false) }
                    scenario.onActivity {
                        list().setSelectedPosition(items().indexOfFirst { it.filePath() == file.parent })
                        list().requestFocus()
                    }
                    send(KeyEvent.KEYCODE_DPAD_CENTER)
                    await { items().any { it.filePath() == file.path } && list().hasFocus() }
                    send(KeyEvent.KEYCODE_DPAD_CENTER)
                    await {
                        val player =
                            ActivityLifecycleMonitorRegistry
                                .getInstance()
                                .getActivitiesInStage(Stage.RESUMED)
                                .filterIsInstance<PlayerActivity>()
                                .firstOrNull() ?: return@await false
                        val view =
                            player
                                .findViewById<FrameLayout>(
                                    com.xyoye.player_component.R.id.player_container
                                ).getChildAt(0) as DanDanVideoPlayer
                        player.hasWindowFocus() && view.isPlaying() && view.getCurrentPosition() > 100
                    }
                    repeat(3) {
                        var isPlayer = false
                        instrumentation.runOnMainSync {
                            isPlayer =
                                ActivityLifecycleMonitorRegistry
                                    .getInstance()
                                    .getActivitiesInStage(
                                        Stage.RESUMED
                                    ).any { it is PlayerActivity }
                        }
                        if (isPlayer) send(KeyEvent.KEYCODE_BACK)
                    }
                    await {
                        activity.hasWindowFocus() &&
                            list().hasFocus() &&
                            fragment()
                                .requireView()
                                .findViewById<TextView>(R.id.preview_title)
                                .text
                                .toString() == file.name
                    }
                }
            }
        } finally {
            PlayerConfig.putUsePlayerType(previousKernel)
        }
    }
}
