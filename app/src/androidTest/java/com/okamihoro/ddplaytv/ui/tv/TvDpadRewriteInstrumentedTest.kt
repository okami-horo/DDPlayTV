package com.okamihoro.ddplaytv.ui.tv

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alibaba.android.arouter.launcher.ARouter
import com.okamihoro.ddplaytv.R
import com.okamihoro.ddplaytv.playback.withIndexedPlaybackFile
import com.rubensousa.dpadrecyclerview.DpadRecyclerView
import com.xyoye.anime_component.ui.fragment.home_page.HomePageFragment
import com.xyoye.common_component.adapter.BaseAdapter
import com.xyoye.common_component.config.PlayerConfig
import com.xyoye.common_component.config.SubtitleConfig
import com.xyoye.common_component.database.DatabaseProvider
import com.xyoye.common_component.playback.handoff.PlaybackNavigation
import com.xyoye.common_component.playback.handoff.PlaybackRequestInput
import com.xyoye.common_component.playback.handoff.PlaybackRequestOrigin
import com.xyoye.common_component.playback.handoff.PlaybackSubmissionResult
import com.xyoye.common_component.services.PlaybackRequestService
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.data_component.bean.LocalDanmuBean
import com.xyoye.data_component.bean.VideoTrackBean
import com.xyoye.data_component.data.AnimeData
import com.xyoye.data_component.data.BangumiAnimeData
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.entity.VideoEntity
import com.xyoye.data_component.enums.PlayerType
import com.xyoye.local_component.ui.activities.shooter_subtitle.ShooterSubtitleActivity
import com.xyoye.player.DanDanVideoPlayer
import com.xyoye.player.controller.VideoController
import com.xyoye.player.info.PlayerInitializer
import com.xyoye.player.info.SettingAction
import com.xyoye.player.info.SettingItem
import com.xyoye.player.subtitle.ui.SubtitleSurfaceOverlay
import com.xyoye.player_component.ui.activities.player.PlayerActivity
import com.xyoye.storage_component.ui.activities.storage_file.StorageFileActivity
import com.xyoye.storage_component.ui.fragment.storage_file.StorageFileFragment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class TvDpadRewriteInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Before fun remoteMode() {
        instrumentation.setInTouchMode(false)
    }

    @Test fun gridKeepsItsItemAcrossUpdatesAndLeavesAtTheTopEdge() {
        val items = (1..60).map { AnimeData(animeId = it, animeTitle = "Focus $it") }
        ActivityScenario.launch(TvMainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.supportFragmentManager
                    .beginTransaction()
                    .replace(R.id.fragment_container, HomePageFragment.newInstance(BangumiAnimeData(items.toMutableList())))
                    .commitNow()
                it.findViewById<DpadRecyclerView>(com.xyoye.anime_component.R.id.page_anime_rv).requestFocus()
            }
            await(scenario) { gridPosition(it) == 0 && it.hasWindowFocus() }
            send(KeyEvent.KEYCODE_DPAD_RIGHT)
            await(scenario) { gridPosition(it) == 1 }
            send(KeyEvent.KEYCODE_DPAD_DOWN)
            await(scenario) { gridPosition(it) == 5 }
            send(KeyEvent.KEYCODE_DPAD_LEFT)
            await(scenario) { gridPosition(it) == 4 }
            scenario.onActivity {
                val list = it.findViewById<DpadRecyclerView>(com.xyoye.anime_component.R.id.page_anime_rv)
                val adapter = list.adapter as BaseAdapter
                adapter.setData(listOf(AnimeData(animeId = 100, animeTitle = "Inserted")) + items)
            }
            await(scenario) { gridPosition(it) == 5 }
            scenario.onActivity {
                val list = it.findViewById<DpadRecyclerView>(com.xyoye.anime_component.R.id.page_anime_rv)
                val adapter = list.adapter as BaseAdapter
                assertEquals(5, (adapter.items[gridPosition(it)] as AnimeData).animeId)
                adapter.setData(adapter.items.filterNot { item -> (item as AnimeData).animeId == 5 })
            }
            await(scenario) { gridPosition(it) >= 0 }
            scenario.onActivity {
                val list = it.findViewById<DpadRecyclerView>(com.xyoye.anime_component.R.id.page_anime_rv)
                list.setSelectedPosition(0)
            }
            await(scenario) {
                val list = it.findViewById<DpadRecyclerView>(com.xyoye.anime_component.R.id.page_anime_rv)
                gridPosition(it) == 0 && list.scrollState == RecyclerView.SCROLL_STATE_IDLE
            }
            send(KeyEvent.KEYCODE_DPAD_UP)
            await(scenario) { it.findViewById<DpadRecyclerView>(R.id.tv_nav_rv).hasFocus() }
        }
    }

    @Test fun storageRestoresDirectoryItemAndSupportsTheSecondaryAction() {
        val folder = File(context.cacheDir, "dpad-${UUID.randomUUID()}").apply { mkdirs() }
        val files = (1..8).map { File(folder, "video-$it.mp4").apply { writeBytes(byteArrayOf(0)) } }
        val dao = DatabaseProvider.instance.getVideoDao()
        runBlocking { dao.insert(*files.map { VideoEntity(fileId = 1, filePath = it.path, folderPath = folder.path) }.toTypedArray()) }
        try {
            val intent = Intent(context, StorageFileActivity::class.java).putExtra("storageLibrary", MediaLibraryEntity.LOCAL)
            ActivityScenario.launch<StorageFileActivity>(intent).use { scenario ->
                await(scenario) { activity -> storageItems(activity).any { it.filePath() == folder.path } }
                scenario.onActivity { activity ->
                    val list = storageList(activity)
                    list.setSelectedPosition(storageItems(activity).indexOfFirst { it.filePath() == folder.path })
                    list.requestFocus()
                }
                await(scenario) { focusedStorageFile(it)?.filePath() == folder.path && it.hasWindowFocus() }
                send(KeyEvent.KEYCODE_DPAD_CENTER)
                await(scenario) { storageItems(it).size == 8 }
                SystemClock.sleep(350) // Capture the page after its directory transition.
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(context.getExternalFilesDir("cinema-reference"), "files.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
                send(KeyEvent.KEYCODE_DPAD_DOWN)
                await(scenario) { storageList(it).hasFocus() }
                send(KeyEvent.KEYCODE_DPAD_DOWN)
                await(scenario) { focusedStorageFile(it)?.filePath() == files[1].path }
                send(KeyEvent.KEYCODE_DPAD_RIGHT)
                await(scenario) { it.currentFocus?.id == com.xyoye.core_ui_component.R.id.more_action_iv }
                send(KeyEvent.KEYCODE_DPAD_CENTER)
                send(KeyEvent.KEYCODE_BACK)
                await(scenario) { it.currentFocus?.id == com.xyoye.core_ui_component.R.id.more_action_iv }
                scenario.recreate()
                await(scenario) { storageItems(it).size == 8 }
                scenario.onActivity { storageList(it).requestFocus() }
                await(scenario) { focusedStorageFile(it)?.filePath() == files[1].path }
                send(KeyEvent.KEYCODE_BACK)
                await(scenario) { focusedStorageFile(it)?.filePath() == folder.path }
            }
        } finally {
            runBlocking { dao.deleteByPaths(files.map { it.path }) }
            files.forEach { it.delete() }
            folder.delete()
        }
    }

    @Test fun emptySubtitleListReturnsToAnActionAfterDialogCancellation() {
        val previousSecret = SubtitleConfig.getShooterSecret()
        SubtitleConfig.putShooterSecret("")
        try {
            ActivityScenario.launch(ShooterSubtitleActivity::class.java).use { scenario ->
                await(scenario) { !it.hasWindowFocus() }
                send(KeyEvent.KEYCODE_BACK)
                // The first BACK belongs to the IME when it is visible.
                val deadline = SystemClock.uptimeMillis() + 1000
                var hasWindow = false
                while (!hasWindow && SystemClock.uptimeMillis() < deadline) {
                    scenario.onActivity { hasWindow = it.hasWindowFocus() }
                    if (!hasWindow) SystemClock.sleep(25)
                }
                if (!hasWindow) send(KeyEvent.KEYCODE_BACK)
                await(scenario) {
                    val toolbar = it.findViewById<androidx.appcompat.widget.Toolbar>(com.xyoye.core_ui_component.R.id.toolbar)
                    it.hasWindowFocus() && toolbar.hasFocus() && it.currentFocus?.isShown == true && it.currentFocus?.isClickable == true
                }
                // The default toolbar target is its native navigation button.
                scenario.onActivity { assertTrue(it.currentFocus is android.widget.ImageButton) }
                send(KeyEvent.KEYCODE_DPAD_CENTER)
                val exitDeadline = SystemClock.uptimeMillis() + 5000
                while (scenario.state != androidx.lifecycle.Lifecycle.State.DESTROYED && SystemClock.uptimeMillis() < exitDeadline) {
                    SystemClock.sleep(25)
                }
                assertEquals(androidx.lifecycle.Lifecycle.State.DESTROYED, scenario.state)
            }
        } finally {
            SubtitleConfig.putShooterSecret(previousSecret.orEmpty())
        }
    }

    @Test fun playerPanelArrowsDoNotSeekAndBackReturnsToTheCallingSetting() = verifyPlayerPanelNavigation(PlayerType.TYPE_EXO_PLAYER)

    @Test fun mpvPlayerPanelSupportsDpadPauseResumeAndBack() = verifyPlayerPanelNavigation(PlayerType.TYPE_MPV_PLAYER)

    private fun verifyPlayerPanelNavigation(kernel: PlayerType) {
        val fixture = File("/sdcard/Download/ddplaytv-lease-playback-long.mp4")
        assertTrue(fixture.isFile)
        val previousKernel = PlayerConfig.getUsePlayerType()
        val previousScale = PlayerInitializer.screenScale
        val previousSubtitleOffset = SubtitleConfig.getVerticalOffset()
        val previousSubtitleAlpha = SubtitleConfig.getAlpha()
        PlayerConfig.putUsePlayerType(kernel.value)
        try {
            withIndexedPlaybackFile(fixture) { descriptor ->
                val provider = ARouter.getInstance().navigation(PlaybackRequestService::class.java)
                val request =
                    runBlocking {
                        provider.submit(
                            PlaybackRequestInput(
                                descriptor,
                                null,
                                PlaybackRequestOrigin.STORAGE_FILE
                            )
                        )
                    } as PlaybackSubmissionResult.Accepted
                val intent = Intent(context, PlayerActivity::class.java).putExtra(PlaybackNavigation.REQUEST_ID, request.requestId)
                ActivityScenario.launch<PlayerActivity>(intent).use { scenario ->
                    await(scenario, 15000) { player(it).isPlaying() && it.hasWindowFocus() }
                    verifySubtitleLayer(scenario, kernel)
                    lateinit var menuCaller: android.view.View
                    scenario.onActivity { menuCaller = requireNotNull(it.currentFocus) }
                    send(KeyEvent.KEYCODE_MENU)
                    await(scenario) { it.findViewById<DpadRecyclerView>(com.xyoye.player_component.R.id.setting_rv)?.hasFocus() == true }
                    var position = 0L
                    scenario.onActivity { position = player(it).getCurrentPosition() }
                    repeat(5) {
                        send(KeyEvent.KEYCODE_DPAD_RIGHT)
                        send(KeyEvent.KEYCODE_DPAD_LEFT)
                    }
                    scenario.onActivity {
                        assertTrue(player(it).isPlaying())
                        assertTrue("Panel arrows must not seek", player(it).getCurrentPosition() < position + 8000)
                        val list = it.findViewById<DpadRecyclerView>(com.xyoye.player_component.R.id.setting_rv)
                        val index =
                            (list.adapter as BaseAdapter).items.indexOfFirst { item ->
                                item is SettingItem &&
                                    item.action == SettingAction.VIDEO_ASPECT
                            }
                        list.setSelectedPosition(index)
                    }
                    send(KeyEvent.KEYCODE_DPAD_CENTER)
                    await(scenario) { it.findViewById<DpadRecyclerView>(com.xyoye.player_component.R.id.rv_aspect)?.hasFocus() == true }
                    send(KeyEvent.KEYCODE_DPAD_DOWN)
                    scenario.onActivity { assertEquals(previousScale, PlayerInitializer.screenScale) }
                    send(KeyEvent.KEYCODE_BACK)
                    await(scenario) { activity ->
                        val list = activity.findViewById<DpadRecyclerView>(com.xyoye.player_component.R.id.setting_rv)
                        val item = (list.adapter as BaseAdapter).items.getOrNull(list.getSelectedPosition())
                        list.hasFocus() && item is SettingItem && item.action == SettingAction.VIDEO_ASPECT
                    }
                    if (kernel == PlayerType.TYPE_MPV_PLAYER) {
                        scenario.onActivity {
                            assertEquals(
                                com.xyoye.common_component.enums.SubtitleRendererBackend.MPV_NATIVE,
                                com.xyoye.player.subtitle.backend.SubtitleRendererRegistry
                                    .current()
                                    ?.backend
                            )
                            val list = it.findViewById<DpadRecyclerView>(com.xyoye.player_component.R.id.setting_rv)
                            val index =
                                (list.adapter as BaseAdapter).items.indexOfFirst { item ->
                                    item is SettingItem && item.action == SettingAction.SUBTITLE_STYLE
                                }
                            list.setSelectedPosition(index)
                        }
                        await(scenario) { activity ->
                            val list = activity.findViewById<DpadRecyclerView>(com.xyoye.player_component.R.id.setting_rv)
                            val position = activity.currentFocus?.let { list.findContainingViewHolder(it)?.bindingAdapterPosition }
                            val item = (list.adapter as BaseAdapter).items.getOrNull(position ?: -1)
                            list.scrollState == RecyclerView.SCROLL_STATE_IDLE &&
                                item is SettingItem &&
                                item.action == SettingAction.SUBTITLE_STYLE
                        }
                        send(KeyEvent.KEYCODE_DPAD_CENTER)
                        await(scenario) { it.currentFocus?.id == com.xyoye.player_component.R.id.subtitle_vertical_offset_sb }
                        scenario.onActivity {
                            assertFalse(it.findViewById<android.view.View>(com.xyoye.player_component.R.id.subtitle_alpha_sb).isShown)
                            assertTrue(
                                it.findViewById<android.view.View>(com.xyoye.player_component.R.id.subtitle_native_style_tips).isShown
                            )
                        }
                        send(KeyEvent.KEYCODE_DPAD_RIGHT)
                        assertNotEquals(previousSubtitleOffset, SubtitleConfig.getVerticalOffset())
                        assertEquals(previousSubtitleAlpha, SubtitleConfig.getAlpha())
                        send(KeyEvent.KEYCODE_BACK)
                        await(scenario) { activity ->
                            val list = activity.findViewById<DpadRecyclerView>(com.xyoye.player_component.R.id.setting_rv)
                            val item = (list.adapter as BaseAdapter).items.getOrNull(list.getSelectedPosition())
                            list.hasFocus() && item is SettingItem && item.action == SettingAction.SUBTITLE_STYLE
                        }
                    }
                    send(KeyEvent.KEYCODE_BACK)
                    await(scenario) { it.currentFocus === menuCaller }
                    if (menuCaller !is VideoController) send(KeyEvent.KEYCODE_BACK)
                    await(scenario) { it.currentFocus is VideoController }
                    send(KeyEvent.KEYCODE_DPAD_CENTER)
                    await(scenario) { !player(it).isPlaying() }
                    send(KeyEvent.KEYCODE_DPAD_CENTER)
                    await(scenario) { player(it).isPlaying() }
                }
            }
        } finally {
            PlayerConfig.putUsePlayerType(previousKernel)
            PlayerInitializer.screenScale = previousScale
            SubtitleConfig.putVerticalOffset(previousSubtitleOffset)
            PlayerInitializer.Subtitle.verticalOffset = previousSubtitleOffset
        }
    }

    private fun gridPosition(activity: TvMainActivity): Int {
        val list = activity.findViewById<DpadRecyclerView>(com.xyoye.anime_component.R.id.page_anime_rv)
        return activity.currentFocus?.let { list?.findContainingViewHolder(it)?.bindingAdapterPosition } ?: -1
    }

    private fun verifySubtitleLayer(
        scenario: ActivityScenario<PlayerActivity>,
        kernel: PlayerType
    ) {
        val media3 = kernel == PlayerType.TYPE_EXO_PLAYER
        val subtitle =
            File(context.cacheDir, if (media3) "media3-subtitle-ui.ass" else "native-subtitle-ui.srt").apply {
                writeText(
                    if (media3) {
                        "[Script Info]\nScriptType: v4.00+\nPlayResX: 1920\nPlayResY: 1080\n" +
                            "[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, Alignment\n" +
                            "Style: Default,Noto Sans CJK SC,72,&H00FFFFFF,2\n" +
                            "[Events]\nFormat: Layer, Start, End, Style, Text\n" +
                            "Dialogue: 0,0:00:00.00,0:05:00.00,Default,Media3 ASS 字幕测试\n"
                    } else {
                        "1\n00:00:00,000 --> 00:05:00,000\nMPV 原生字幕测试\n"
                    }
                )
            }
        val danmaku =
            File(context.cacheDir, "native-subtitle-danmaku.xml").apply {
                writeText(
                    "<i>" +
                        (0..120).joinToString("") { second ->
                            "<d p=\"$second,5,25,65280,0,0,test,$second\">弹幕叠加测试</d>"
                        } + "</i>"
                )
            }
        try {
            lateinit var overlay: SubtitleSurfaceOverlay
            scenario.onActivity {
                val view = player(it)
                val children = (0 until view.childCount).map(view::getChildAt)
                val controller = children.filterIsInstance<VideoController>().single()
                overlay = children.filterIsInstance<SubtitleSurfaceOverlay>().single()
                assertTrue(
                    "Controls and danmaku must remain above the subtitle plane",
                    view.indexOfChild(controller) > view.indexOfChild(overlay)
                )
                controller.addExtendTrack(VideoTrackBean.subtitle(subtitle.absolutePath))
                controller.addExtendTrack(VideoTrackBean.danmu(LocalDanmuBean(danmaku.absolutePath)))
            }

            fun assertSurfacePixels(surface: SurfaceView) {
                var visiblePixels = 0
                val deadline = SystemClock.elapsedRealtime() + 10000
                while (visiblePixels < 50 && SystemClock.elapsedRealtime() < deadline) {
                    val bitmap = Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
                    val copied = CountDownLatch(1)
                    var result = -1
                    PixelCopy.request(surface, bitmap, {
                        result = it
                        copied.countDown()
                    }, Handler(Looper.getMainLooper()))
                    assertTrue(copied.await(3, TimeUnit.SECONDS))
                    if (result == PixelCopy.SUCCESS) {
                        val colors = IntArray(bitmap.width * bitmap.height)
                        bitmap.getPixels(colors, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        visiblePixels = colors.count { Color.alpha(it) > 0 && Color.red(it) > 40 }
                    }
                    bitmap.recycle()
                    if (visiblePixels < 50) SystemClock.sleep(25)
                }
                assertTrue("The actual video/subtitle surface must contain visible pixels", visiblePixels > 50)
            }
            assertSurfacePixels(overlay)
            if (media3) {
                lateinit var video: SurfaceView
                var pausedPosition = 0L
                scenario.onActivity {
                    val view = player(it)
                    view.pause()
                    pausedPosition = view.getCurrentPosition()
                    video = view.getRenderView()!!.getView() as SurfaceView
                    video.visibility = View.INVISIBLE
                    overlay.visibility = View.INVISIBLE
                }
                await(scenario) { !video.holder.surface.isValid && !overlay.holder.surface.isValid }
                scenario.onActivity {
                    video.visibility = View.VISIBLE
                    overlay.visibility = View.VISIBLE
                }
                await(scenario) {
                    video.holder.surface.isValid &&
                        overlay.holder.surface.isValid &&
                        !player(it).isPlaying() &&
                        kotlin.math.abs(player(it).getCurrentPosition() - pausedPosition) < 250
                }
                assertSurfacePixels(overlay)
                assertSurfacePixels(video)
                scenario.onActivity { player(it).start() }
                await(scenario) { player(it).isPlaying() }
            }
            instrumentation.uiAutomation.takeScreenshot()?.let { screenshot ->
                File(
                    context.getExternalFilesDir(null),
                    if (media3) "media3-libass-subtitles.png" else "mpv-native-subtitles.png"
                ).outputStream().use {
                    screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                screenshot.recycle()
            }
        } finally {
            subtitle.delete()
            danmaku.delete()
        }
    }

    private fun storageList(activity: StorageFileActivity): DpadRecyclerView =
        activity.supportFragmentManager.fragments
            .filterIsInstance<StorageFileFragment>()
            .first { !it.isHidden }
            .requireView()
            .findViewById(com.xyoye.storage_component.R.id.storage_file_rv)

    private fun storageItems(activity: StorageFileActivity): List<StorageFile> =
        (storageList(activity).adapter as BaseAdapter).items.filterIsInstance<StorageFile>()

    private fun focusedStorageFile(activity: StorageFileActivity): StorageFile? {
        val list = storageList(activity)
        val position = activity.currentFocus?.let { list.findContainingViewHolder(it)?.bindingAdapterPosition } ?: -1
        return (list.adapter as BaseAdapter).items.getOrNull(position) as? StorageFile
    }

    private fun player(activity: PlayerActivity): DanDanVideoPlayer =
        activity.findViewById<FrameLayout>(com.xyoye.player_component.R.id.player_container).getChildAt(0) as DanDanVideoPlayer

    private fun send(key: Int) {
        instrumentation.sendKeyDownUpSync(key)
        instrumentation.waitForIdleSync()
    }

    private fun <T : android.app.Activity> await(
        scenario: ActivityScenario<T>,
        timeout: Long = 5000,
        predicate: (T) -> Boolean
    ) {
        val deadline = SystemClock.uptimeMillis() + timeout
        var ready = false
        var diagnostic = ""
        while (!ready && SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                ready = predicate(it)
                diagnostic = "window=${it.hasWindowFocus()} focus=${it.currentFocus?.javaClass?.simpleName}:${it.currentFocus?.id}"
            }
            if (!ready) SystemClock.sleep(30)
        }
        assertTrue("Expected actual focus and page state: $diagnostic", ready)
    }
}
