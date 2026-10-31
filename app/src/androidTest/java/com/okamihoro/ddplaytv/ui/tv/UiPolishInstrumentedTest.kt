package com.okamihoro.ddplaytv.ui.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alibaba.android.arouter.launcher.ARouter
import com.okamihoro.ddplaytv.playback.withIndexedPlaybackFile
import com.rubensousa.dpadrecyclerview.DpadRecyclerView
import com.xyoye.common_component.adapter.BaseAdapter
import com.xyoye.common_component.config.PlayerConfig
import com.xyoye.common_component.playback.handoff.PlaybackNavigation
import com.xyoye.common_component.playback.handoff.PlaybackRequestInput
import com.xyoye.common_component.playback.handoff.PlaybackRequestOrigin
import com.xyoye.common_component.playback.handoff.PlaybackSubmissionResult
import com.xyoye.common_component.services.PlaybackRequestService
import com.xyoye.common_component.weight.dialog.FileManagerDialog
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.FileManagerAction
import com.xyoye.data_component.enums.MediaType
import com.xyoye.data_component.enums.PlayerType
import com.xyoye.local_component.ui.activities.bind_source.BindExtraSourceActivity
import com.xyoye.local_component.ui.activities.play_history.PlayHistoryActivity
import com.xyoye.player.DanDanVideoPlayer
import com.xyoye.player.info.PlayerInitializer
import com.xyoye.player.info.SettingAction
import com.xyoye.player.info.SettingItem
import com.xyoye.player_component.ui.activities.player.PlayerActivity
import com.xyoye.storage_component.ui.activities.storage_file.StorageFileActivity
import com.xyoye.storage_component.ui.activities.storage_plus.StoragePlusActivity
import com.xyoye.storage_component.ui.dialog.StorageEditDialog
import com.xyoye.user_component.ui.activities.about_us.AboutUsActivity
import com.xyoye.user_component.ui.activities.cache_manager.CacheManagerActivity
import com.xyoye.user_component.ui.activities.commonly_folder.CommonlyFolderActivity
import com.xyoye.user_component.ui.activities.license.LicenseActivity
import com.xyoye.user_component.ui.activities.login.LoginActivity
import com.xyoye.user_component.ui.activities.scan_manager.ScanManagerActivity
import com.xyoye.user_component.ui.activities.switch_themes.SwitchThemesActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import com.xyoye.player_component.R as PlayerR

@RunWith(AndroidJUnit4::class)
class UiPolishInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Before fun useRemote() {
        instrumentation.setInTouchMode(false)
    }

    @Test fun deepPagesHaveVisibleFocusAndReturnWithRemote() {
        val pages =
            listOf(
                CacheManagerActivity::class.java,
                CommonlyFolderActivity::class.java,
                ScanManagerActivity::class.java,
                LoginActivity::class.java,
                AboutUsActivity::class.java,
                LicenseActivity::class.java,
                SwitchThemesActivity::class.java,
                PlayHistoryActivity::class.java
            )
        pages.forEach { type ->
            ActivityScenario.launch<Activity>(Intent(context, type)).use { scenario ->
                await(scenario) { it.hasWindowFocus() }
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                await(scenario) { it.currentFocus?.isShown == true }
                scenario.onActivity { assertFullyVisible(requireNotNull(it.currentFocus)) }
                screenshot(type.simpleName)
                key(KeyEvent.KEYCODE_BACK)
            }
        }
    }

    @Test fun bindingPageKeepsVideoContextAndLocalSelectionReachable() {
        val directory = File(context.cacheDir, "ui-polish-binding").apply { mkdirs() }
        val file = File(directory, "binding-fixture.mp4")
        File("/sdcard/Download/ddplaytv-lease-playback-long.mp4").copyTo(file, overwrite = true)
        val library = MediaLibraryEntity.LOCAL
        try {
            withIndexedPlaybackFile(file) {
                ActivityScenario
                    .launch<StorageFileActivity>(
                        Intent(context, StorageFileActivity::class.java)
                            .putExtra("storageLibrary", library)
                    ).use { browser ->
                        await(browser) { it.hasWindowFocus() }
                        lateinit var host: StorageFileActivity
                        browser.onActivity { host = it }
                        val selected = runBlocking { requireNotNull(host.storage.pathFile(file.path, false)) }
                        browser.onActivity { it.shareStorageFile = selected }
                        val monitor = instrumentation.addMonitor(BindExtraSourceActivity::class.java.name, null, false)
                        browser.onActivity { it.startActivity(Intent(it, BindExtraSourceActivity::class.java)) }
                        val binding = instrumentation.waitForMonitorWithTimeout(monitor, 6000) as BindExtraSourceActivity
                        try {
                            await(browser) {
                                binding.hasWindowFocus() && binding.findViewById<View>(com.xyoye.local_component.R.id.tab_layout).isShown
                            }
                            screenshot("binding-empty")
                            browser.onActivity {
                                binding.findViewById<View>(com.xyoye.local_component.R.id.search_cl).requestFocus()
                            }
                            key(KeyEvent.KEYCODE_DPAD_DOWN)
                            browser.onActivity {
                                assertEquals(com.xyoye.local_component.R.id.video_layout, binding.currentFocus?.id)
                            }
                            screenshot("binding-video-focus")
                            key(KeyEvent.KEYCODE_DPAD_RIGHT)
                            browser.onActivity {
                                assertEquals(com.xyoye.local_component.R.id.more_action_iv, binding.currentFocus?.id)
                            }
                            key(KeyEvent.KEYCODE_DPAD_LEFT)
                            browser.onActivity {
                                assertEquals(com.xyoye.local_component.R.id.video_layout, binding.currentFocus?.id)
                            }
                            browser.onActivity {
                                binding
                                    .findViewById<View>(
                                        com.xyoye.local_component.R.id.tv_select_local_danmu
                                    ).requestFocus()
                            }
                            key(KeyEvent.KEYCODE_DPAD_CENTER)
                            screenshot("binding-file-picker")
                            key(KeyEvent.KEYCODE_BACK)
                            await(browser) { binding.hasWindowFocus() }
                            key(KeyEvent.KEYCODE_BACK)
                            await(browser) { it.hasWindowFocus() }
                        } finally {
                            instrumentation.removeMonitor(monitor)
                            instrumentation.runOnMainSync { if (!binding.isFinishing) binding.finish() }
                        }
                    }
            }
        } finally {
            file.delete()
            directory.delete()
        }
    }

    @Test fun filePickerKeepsActionsVisibleAndHandlesEmptyDirectory() {
        val empty = File(context.getExternalFilesDir("ui-polish-fixtures"), "empty").apply { mkdirs() }
        ActivityScenario.launch(TvMainActivity::class.java).use { scenario ->
            lateinit var dialog: FileManagerDialog
            scenario.onActivity {
                dialog = FileManagerDialog(it, FileManagerAction.ACTION_SELECT_SUBTITLE, empty.path) { fail("No subtitle selected") }
                dialog.show()
            }
            await(scenario) { dialog.window?.decorView?.hasWindowFocus() == true }
            instrumentation.runOnMainSync {
                assertTrue(dialog.findViewById<View>(com.xyoye.core_ui_component.R.id.empty_tv)!!.isShown)
                assertFullyVisible(dialog.findViewById(com.xyoye.core_ui_component.R.id.negative_bt)!!)
            }
            screenshot("file-picker-empty")
            key(KeyEvent.KEYCODE_BACK)
            await(scenario) { it.hasWindowFocus() && !dialog.isShowing }
        }
    }

    @Test fun sourceFormsKeepReturnVisibleAndScrollToConnectionStatus() {
        val types =
            listOf(
                MediaType.FTP_SERVER,
                MediaType.WEBDAV_SERVER,
                MediaType.SMB_SERVER,
                MediaType.REMOTE_STORAGE,
                MediaType.ALSIT_STORAGE,
                MediaType.EXTERNAL_STORAGE,
                MediaType.OPEN_115_STORAGE,
                MediaType.CLOUD_115_STORAGE,
                MediaType.BAIDU_PAN_STORAGE
            )
        types.forEach { type ->
            val intent = Intent(context, StoragePlusActivity::class.java).putExtra("mediaType", type)
            ActivityScenario.launch<StoragePlusActivity>(intent).use { scenario ->
                lateinit var dialog: StorageEditDialog<*>
                scenario.onActivity {
                    dialog =
                        StoragePlusActivity::class.java
                            .getDeclaredField("storageEditDialog")
                            .apply { isAccessible = true }
                            .get(it) as StorageEditDialog<*>
                }
                await(scenario) { dialog.window?.decorView?.hasWindowFocus() == true }
                instrumentation.runOnMainSync {
                    assertFullyVisible(dialog.findViewById(com.xyoye.core_ui_component.R.id.negative_bt)!!)
                    assertFullyVisible(requireNotNull(dialog.currentFocus))
                }
                screenshot("source-$type")
                if (type == MediaType.FTP_SERVER || type == MediaType.WEBDAV_SERVER) {
                    repeat(14) {
                        if (dialog.currentFocus?.id !=
                            com.xyoye.storage_component.R.id.server_test_connect_tv
                        ) {
                            key(KeyEvent.KEYCODE_DPAD_DOWN)
                        }
                    }
                    instrumentation.runOnMainSync {
                        assertEquals(com.xyoye.storage_component.R.id.server_test_connect_tv, dialog.currentFocus?.id)
                        dialog.onTestResult(false)
                    }
                    screenshot("source-$type-error")
                    instrumentation.runOnMainSync { assertFullyVisible(requireNotNull(dialog.currentFocus)) }
                }
                key(KeyEvent.KEYCODE_BACK)
                instrumentation.runOnMainSync { assertFalse(dialog.isShowing) }
            }
        }
    }

    @Test fun firstEpisodeSkipsDisabledPreviousAndPanelBackRestoresCaller() {
        val fixture = File("/sdcard/Download/ddplaytv-lease-playback-long.mp4")
        assertTrue(fixture.isFile)
        val directory = requireNotNull(context.getExternalFilesDir("ui-polish-fixtures"))
        val first = File(directory, "01-first.mp4")
        val second = File(directory, "02-middle.mp4")
        val last = File(directory, "03-long-episode-name-to-check-two-lines-and-focus-restoration.mp4")
        listOf(first, second, last).forEach { fixture.copyTo(it, overwrite = true) }
        val oldKernel = PlayerConfig.getUsePlayerType()
        val oldOffset = PlayerInitializer.Subtitle.offsetPosition
        PlayerConfig.putUsePlayerType(PlayerType.TYPE_EXO_PLAYER.value)
        try {
            withIndexedPlaybackFile(last) { lastDescriptor ->
                withIndexedPlaybackFile(second) { secondDescriptor ->
                    withIndexedPlaybackFile(first) { descriptor ->
                        val grouped =
                            descriptor.copy(
                                queueContext =
                                    requireNotNull(descriptor.queueContext).copy(
                                        fallbackItems = listOf(descriptor.file, secondDescriptor.file, lastDescriptor.file)
                                    )
                            )
                        val provider = ARouter.getInstance().navigation(PlaybackRequestService::class.java)
                        val request =
                            runBlocking {
                                provider.submit(PlaybackRequestInput(grouped, null, PlaybackRequestOrigin.STORAGE_FILE))
                            } as PlaybackSubmissionResult.Accepted
                        val intent = Intent(context, PlayerActivity::class.java).putExtra(PlaybackNavigation.REQUEST_ID, request.requestId)
                        ActivityScenario.launch<PlayerActivity>(intent).use { scenario ->
                            await(scenario, 20000) { player(it).isPlaying() && it.hasWindowFocus() }
                            key(KeyEvent.KEYCODE_DPAD_DOWN)
                            await(
                                scenario
                            ) { it.findViewById<View>(PlayerR.id.play_iv).isShown && it.currentFocus?.id == PlayerR.id.play_iv }
                            scenario.onActivity {
                                val previous = it.findViewById<View>(PlayerR.id.iv_previous_source)
                                assertTrue(previous.isShown)
                                assertFalse(previous.isEnabled)
                                assertFalse(previous.isFocusable)
                                assertEquals(it.findViewById<View>(PlayerR.id.iv_next_source).width, previous.width)
                            }
                            screenshot("player-first")
                            key(KeyEvent.KEYCODE_DPAD_RIGHT)
                            key(KeyEvent.KEYCODE_DPAD_RIGHT)
                            scenario.onActivity { assertEquals(PlayerR.id.iv_next_source, it.currentFocus?.id) }
                            key(KeyEvent.KEYCODE_DPAD_RIGHT)
                            key(KeyEvent.KEYCODE_DPAD_CENTER)
                            await(scenario) { it.findViewById<DpadRecyclerView>(PlayerR.id.source_rv).hasFocus() }
                            screenshot("player-episodes")
                            key(KeyEvent.KEYCODE_DPAD_DOWN)
                            key(KeyEvent.KEYCODE_DPAD_DOWN)
                            screenshot("player-long-title")
                            key(KeyEvent.KEYCODE_BACK)
                            await(scenario) { it.currentFocus?.id == PlayerR.id.video_list_iv }
                            key(KeyEvent.KEYCODE_MENU)
                            await(scenario) { it.findViewById<DpadRecyclerView>(PlayerR.id.setting_rv).hasFocus() }
                            scenario.onActivity {
                                val list = it.findViewById<DpadRecyclerView>(PlayerR.id.setting_rv)
                                val target =
                                    (list.adapter as BaseAdapter).items.indexOfFirst { item ->
                                        item is SettingItem &&
                                            item.action == SettingAction.SUBTITLE_TIME
                                    }
                                assertTrue(target >= 0)
                                list.setSelectedPosition(target)
                            }
                            instrumentation.waitForIdleSync()
                            SystemClock.sleep(300)
                            key(KeyEvent.KEYCODE_DPAD_CENTER)
                            await(scenario) {
                                val title = it.findViewById<View>(PlayerR.id.offset_title)
                                title.isShown && (title.parent.parent as View).translationX == 0f
                            }
                            scenario.onActivity { it.findViewById<View>(PlayerR.id.tv_add_half).requestFocus() }
                            key(KeyEvent.KEYCODE_DPAD_CENTER)
                            assertEquals(oldOffset + 500, PlayerInitializer.Subtitle.offsetPosition)
                            screenshot("player-offset")
                            key(KeyEvent.KEYCODE_BACK)
                            await(scenario) { it.findViewById<DpadRecyclerView>(PlayerR.id.setting_rv).hasFocus() }
                            scenario.onActivity {
                                val list = it.findViewById<DpadRecyclerView>(PlayerR.id.setting_rv)
                                list.setSelectedPosition(
                                    (list.adapter as BaseAdapter).items.indexOfFirst { item ->
                                        item is SettingItem && item.action == SettingAction.SUBTITLE_STYLE
                                    }
                                )
                            }
                            key(KeyEvent.KEYCODE_DPAD_CENTER)
                            await(scenario) { it.findViewById<View>(PlayerR.id.subtitle_setting_nsv).hasFocus() }
                            screenshot("player-subtitle-style")
                            key(KeyEvent.KEYCODE_BACK)
                            await(scenario) { it.findViewById<DpadRecyclerView>(PlayerR.id.setting_rv).hasFocus() }
                            key(KeyEvent.KEYCODE_BACK)
                            await(scenario) { it.currentFocus?.id == PlayerR.id.video_list_iv }
                            key(KeyEvent.KEYCODE_DPAD_CENTER)
                            await(scenario) { it.findViewById<DpadRecyclerView>(PlayerR.id.source_rv).hasFocus() }
                            key(KeyEvent.KEYCODE_DPAD_DOWN)
                            key(KeyEvent.KEYCODE_DPAD_DOWN)
                            key(KeyEvent.KEYCODE_DPAD_CENTER)
                            await(scenario, 15000) { player(it).getVideoSource().getGroupIndex() == 2 && player(it).isPlaying() }
                            key(KeyEvent.KEYCODE_DPAD_DOWN)
                            await(scenario) { it.findViewById<View>(PlayerR.id.video_list_iv).isShown }
                            scenario.onActivity {
                                assertFalse(it.findViewById<View>(PlayerR.id.iv_next_source).isFocusable)
                                assertFalse(it.findViewById<View>(PlayerR.id.iv_next_source).isEnabled)
                                it.findViewById<View>(PlayerR.id.iv_previous_source).requestFocus()
                            }
                            screenshot("player-last")
                            key(KeyEvent.KEYCODE_DPAD_CENTER)
                            await(scenario, 15000) { player(it).getVideoSource().getGroupIndex() == 1 && player(it).isPlaying() }
                            key(KeyEvent.KEYCODE_DPAD_DOWN)
                            screenshot("player-middle")
                        }
                    }
                }
            }
        } finally {
            PlayerConfig.putUsePlayerType(oldKernel)
            PlayerInitializer.Subtitle.offsetPosition = oldOffset
            listOf(first, second, last).forEach { it.delete() }
        }
    }

    private fun player(activity: PlayerActivity) =
        activity.findViewById<FrameLayout>(PlayerR.id.player_container).getChildAt(0) as DanDanVideoPlayer

    private fun assertFullyVisible(view: View) {
        val visible = Rect()
        assertTrue("${view.javaClass.simpleName} must be visible", view.getGlobalVisibleRect(visible))
        assertTrue(
            "Focus/action must not be clipped: $visible / ${view.width}x${view.height}",
            visible.height() >= view.height - 2 && visible.width() >= view.width - 2
        )
    }

    private fun key(code: Int) {
        instrumentation.sendKeyDownUpSync(code)
        instrumentation.waitForIdleSync()
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(650)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(context.getExternalFilesDir("ui-polish-verification"), "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun <T : Activity> await(
        scenario: ActivityScenario<T>,
        timeout: Long = 6000,
        predicate: (T) -> Boolean
    ) {
        val deadline = SystemClock.uptimeMillis() + timeout
        var ready = false
        var diagnostic = ""
        while (!ready && SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                ready = predicate(it)
                diagnostic = "${it.javaClass.simpleName} focus=${it.currentFocus?.id}"
            }
            if (!ready) SystemClock.sleep(30)
        }
        assertTrue("UI state did not settle: $diagnostic", ready)
    }
}
