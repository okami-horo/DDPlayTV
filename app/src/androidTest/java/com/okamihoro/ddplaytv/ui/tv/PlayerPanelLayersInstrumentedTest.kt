package com.okamihoro.ddplaytv.ui.tv

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.lifecycle.MutableLiveData
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alibaba.android.arouter.launcher.ARouter
import com.google.android.material.slider.Slider
import com.okamihoro.ddplaytv.playback.withIndexedPlaybackFile
import com.xyoye.common_component.config.PlayerConfig
import com.xyoye.common_component.playback.handoff.PlaybackNavigation
import com.xyoye.common_component.playback.handoff.PlaybackRequestInput
import com.xyoye.common_component.playback.handoff.PlaybackRequestOrigin
import com.xyoye.common_component.playback.handoff.PlaybackSubmissionResult
import com.xyoye.common_component.services.PlaybackRequestService
import com.xyoye.data_component.bean.VideoTrackBean
import com.xyoye.data_component.data.DanmuEpisodeData
import com.xyoye.data_component.enums.PlayerType
import com.xyoye.data_component.enums.SettingViewType
import com.xyoye.data_component.enums.TrackType
import com.xyoye.player.DanDanVideoPlayer
import com.xyoye.player.controller.VideoController
import com.xyoye.player.controller.setting.BaseSettingView
import com.xyoye.player.controller.setting.DanmuPanelState
import com.xyoye.player.controller.setting.DanmuSearchState
import com.xyoye.player.controller.video.PlayerBottomView
import com.xyoye.player.info.PlayerInitializer
import com.xyoye.player.wrapper.ControlWrapper
import com.xyoye.player.wrapper.InterVideoPlayer
import com.xyoye.player_component.ui.activities.player.PlayerActivity
import com.xyoye.subtitle.MixedSubtitle
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import com.xyoye.player_component.R as PlayerR

@RunWith(AndroidJUnit4::class)
class PlayerPanelLayersInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val phase = InstrumentationRegistry.getArguments().getString("a06_phase", "after")
    private val panelTypes =
        listOf(
            SettingViewType.SWITCH_VIDEO_SOURCE,
            SettingViewType.TRACKS,
            SettingViewType.SUBTITLE_STYLE,
            SettingViewType.DANMU_STYLE,
            SettingViewType.PLAYER_SETTING,
            SettingViewType.SUBTITLE_OFFSET_TIME,
            SettingViewType.VIDEO_SPEED,
            SettingViewType.VIDEO_ASPECT,
            SettingViewType.SEARCH_DANMU
        )

    @Before fun remote() {
        instrumentation.setInTouchMode(false)
    }

    @Test fun openBottomBarRetainsEveryActionAndRemotePanelReturn() =
        withPlayback { scenario, controller, background ->
            val allActions =
                listOf(
                    PlayerR.id.rewind,
                    PlayerR.id.play_iv,
                    PlayerR.id.forward,
                    PlayerR.id.iv_previous_source,
                    PlayerR.id.iv_next_source,
                    PlayerR.id.video_list_iv,
                    PlayerR.id.subtitle_control_iv,
                    PlayerR.id.audio_control,
                    PlayerR.id.danmu_control_iv
                )
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await(scenario) { it.currentFocus?.id == PlayerR.id.play_iv }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await(scenario) { !player(it).isPlaying() }
            scenario.onActivity {
                controller.stopFadeOut()
                val previous = it.findViewById<View>(PlayerR.id.iv_previous_source)
                assertTrue(previous.isShown)
                assertFalse(previous.isEnabled)
                assertFalse(previous.isFocusable)
                assertEquals(it.findViewById<View>(PlayerR.id.iv_next_source).width, previous.width)
            }
            captureBottom(scenario, "first")
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            await(scenario) { it.currentFocus?.id == PlayerR.id.rewind }
            for (id in allActions.drop(1).filter { it != PlayerR.id.iv_previous_source }) {
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                await(scenario) { it.currentFocus?.id == id }
                if (id in listOf(PlayerR.id.subtitle_control_iv, PlayerR.id.audio_control, PlayerR.id.danmu_control_iv)) {
                    key(KeyEvent.KEYCODE_DPAD_CENTER)
                    await(scenario) {
                        activePanel(controller)?.getSettingViewType() == SettingViewType.TRACKS &&
                            panel(controller).hasFocus()
                    }
                    screenshot("bottom-panel-$id", "player-bottom-open")
                    key(KeyEvent.KEYCODE_BACK)
                    await(scenario) { it.currentFocus?.id == id }
                    scenario.onActivity { controller.stopFadeOut() }
                }
            }
            repeat(3) { key(KeyEvent.KEYCODE_DPAD_LEFT) }
            await(scenario) { it.currentFocus?.id == PlayerR.id.video_list_iv }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await(scenario) {
                activePanel(controller)?.getSettingViewType() == SettingViewType.SWITCH_VIDEO_SOURCE &&
                    panel(controller).hasFocus()
            }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await(scenario, 20000) { player(it).getVideoSource().getGroupIndex() == 1 && player(it).isPlaying() }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            scenario.onActivity {
                player(it).pause()
                controller.stopFadeOut()
                controller.setVideoTitle("药屋少女的呢喃第三季 - S01E50.mkv")
                placeReferenceBackground(it, controller, background)
                controller.getSubtitleController().setUserSubtitleVisible(true)
                controller.onSubtitleTextOutput(MixedSubtitle.fromText("为什么那次衣服没被撞破啊？"))
                bottom(it).onProgressChanged(1_440_000, 582_000)
            }
            repeat(10) { key(KeyEvent.KEYCODE_DPAD_LEFT) }
            await(scenario) { it.currentFocus?.id == PlayerR.id.rewind }
            for (id in allActions) {
                await(scenario) { it.currentFocus?.id == id }
                scenario.onActivity { activity ->
                    val seek = bounds(activity.findViewById(PlayerR.id.play_seek_bar))
                    val reservedSubtitle = Rect(dp(325), dp(480), dp(600), dp(510))
                    assertNull(activity.findViewById<View>(PlayerR.id.transport_tray).background)
                    for (action in allActions) {
                        val view = activity.findViewById<View>(action)
                        val rect = bounds(view)
                        assertTrue(view.isShown && view.isEnabled && view.isFocusable)
                        assertTrue("Timeline must remain above all actions", seek.bottom < rect.top)
                        assertFalse("Actions must preserve the accepted subtitle area", Rect.intersects(rect, reservedSubtitle))
                        val visible = Rect()
                        assertTrue(view.getGlobalVisibleRect(visible))
                        assertTrue("Actions must not be clipped", visible.width() >= rect.width() && visible.height() >= rect.height())
                    }
                    val caption = activity.findViewById<TextView>(PlayerR.id.transport_focus_label)
                    if (id in allActions.take(5)) {
                        assertTrue(caption.isShown)
                        assertEquals(activity.currentFocus?.contentDescription, caption.text.toString())
                        assertTrue(bounds(caption).bottom <= controller.height)
                    } else {
                        assertFalse(caption.isShown)
                    }
                }
                if (id in listOf(PlayerR.id.play_iv, PlayerR.id.forward, PlayerR.id.video_list_iv, PlayerR.id.subtitle_control_iv)) {
                    captureBottom(scenario, "middle-${context.resources.getResourceEntryName(id)}")
                }
                if (id != allActions.last()) key(KeyEvent.KEYCODE_DPAD_RIGHT)
            }
            repeat(4) { key(KeyEvent.KEYCODE_DPAD_LEFT) }
            await(scenario) { it.currentFocus?.id == PlayerR.id.iv_next_source }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await(scenario, 20000) { player(it).getVideoSource().getGroupIndex() == 2 && player(it).isPlaying() }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            scenario.onActivity {
                player(it).pause()
                controller.stopFadeOut()
                placeReferenceBackground(it, controller, background)
                val next = it.findViewById<View>(PlayerR.id.iv_next_source)
                assertTrue(next.isShown)
                assertFalse(next.isEnabled)
                assertFalse(next.isFocusable)
            }
            captureBottom(scenario, "last")
            key(KeyEvent.KEYCODE_BACK)
            key(KeyEvent.KEYCODE_BACK)
            key(KeyEvent.KEYCODE_BACK)
        }

    @Test fun openBottomBarSingleAndUnseekableKeepAUsableFocus() =
        withPlayback(grouped = false) { scenario, controller, _ ->
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await(scenario) { it.currentFocus?.id == PlayerR.id.play_iv }
            scenario.onActivity {
                player(it).pause()
                controller.stopFadeOut()
                for (id in listOf(PlayerR.id.iv_previous_source, PlayerR.id.iv_next_source, PlayerR.id.video_list_iv)) {
                    assertFalse(it.findViewById<View>(id).isShown)
                }
                assertTrue(it.findViewById<View>(PlayerR.id.single_video_hint).isShown)
            }
            captureBottom(scenario, "single")
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await(scenario) { it.currentFocus?.id == PlayerR.id.forward }
            lateinit var original: ControlWrapper
            scenario.onActivity {
                val bottom = bottom(it)
                original =
                    PlayerBottomView::class.java
                        .getDeclaredField(
                            "mControlWrapper"
                        ).apply { isAccessible = true }
                        .get(bottom) as ControlWrapper
                val unseekable =
                    object : InterVideoPlayer by original {
                        override fun isSeekable() = false
                    }
                bottom.attach(ControlWrapper(unseekable, original, original, original, original))
                bottom.onProgressChanged(120_000, 10_000)
            }
            try {
                await(scenario) { it.currentFocus?.id == PlayerR.id.play_iv }
                scenario.onActivity {
                    for (id in listOf(PlayerR.id.rewind, PlayerR.id.forward)) {
                        assertFalse(it.findViewById<View>(id).isEnabled)
                        assertFalse(it.findViewById<View>(id).isFocusable)
                    }
                }
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                await(scenario) { it.currentFocus?.id == PlayerR.id.subtitle_control_iv }
                captureBottom(scenario, "unseekable")
            } finally {
                scenario.onActivity {
                    bottom(it).attach(original)
                    bottom(it).onProgressChanged(player(it).getDuration(), player(it).getCurrentPosition())
                }
            }
        }

    @Test fun openBottomBarKeepsLabelsAndFocusOnBrightAndDarkFrames() =
        withPlayback { scenario, controller, background ->
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await(scenario) { it.currentFocus?.id == PlayerR.id.play_iv }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            scenario.onActivity {
                controller.stopFadeOut()
                bottom(it).onProgressChanged(1_440_000, 582_000)
            }
            val fontTag = if (context.resources.configuration.fontScale > 1f) "large" else "normal"
            for ((name, brightness) in listOf("cinema" to 1f, "bright" to 1.45f, "dark" to .3f)) {
                scenario.onActivity {
                    background.background.colorFilter =
                        ColorMatrixColorFilter(
                            floatArrayOf(
                                brightness,
                                0f,
                                0f,
                                0f,
                                0f,
                                0f,
                                brightness,
                                0f,
                                0f,
                                0f,
                                0f,
                                0f,
                                brightness,
                                0f,
                                0f,
                                0f,
                                0f,
                                0f,
                                1f,
                                0f
                            )
                        )
                }
                captureBottom(scenario, "$name-$fontTag")
            }
            repeat(3) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
            await(scenario) { it.currentFocus?.id == PlayerR.id.video_list_iv }
            captureBottom(scenario, "picker-$fontTag")
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            await(scenario) { it.currentFocus?.id == PlayerR.id.subtitle_control_iv }
            captureBottom(scenario, "subtitle-$fontTag")
            scenario.onActivity { activity ->
                val actions = activity.findViewById<ViewGroup>(PlayerR.id.player_bottom_actions)

                fun checkLabels(view: View) {
                    if (view is TextView && view.isShown) {
                        val label = bounds(view)
                        assertTrue(
                            "Labels must fit below the timeline and inside the viewport",
                            label.top > bounds(activity.findViewById(PlayerR.id.play_seek_bar)).bottom && label.bottom <= controller.height
                        )
                        assertTrue(
                            "Labels must retain every character",
                            view.layout.getEllipsisCount(0) == 0 &&
                                view.paint.measureText(view.text.toString()) <=
                                view.width - view.paddingLeft - view.paddingRight - view.compoundDrawablePadding -
                                (view.compoundDrawables[0]?.intrinsicWidth ?: 0)
                        )
                    }
                    if (view is ViewGroup) for (index in 0 until view.childCount) checkLabels(view.getChildAt(index))
                }
                checkLabels(actions)
            }
            key(KeyEvent.KEYCODE_BACK)
            scenario.onActivity {
                assertFalse(it.findViewById<View>(PlayerR.id.subtitle_control_iv).isFocusable)
            }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await(scenario) { it.currentFocus?.id == PlayerR.id.subtitle_control_iv }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await(scenario) { activePanel(controller)?.getSettingViewType() == SettingViewType.TRACKS && panel(controller).hasFocus() }
            key(KeyEvent.KEYCODE_BACK)
            await(scenario) { it.currentFocus?.id == PlayerR.id.subtitle_control_iv }
        }

    private fun bottom(activity: PlayerActivity) = activity.findViewById<View>(PlayerR.id.player_bottom_ll).parent as PlayerBottomView

    private fun bounds(view: View): Rect {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
    }

    private fun captureBottom(
        scenario: ActivityScenario<PlayerActivity>,
        name: String
    ) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(200)
        scenario.onActivity { activity ->
            val geometry = JSONArray()
            for (id in listOf(
                PlayerR.id.current_position_tv,
                PlayerR.id.play_seek_bar,
                PlayerR.id.transport_tray,
                PlayerR.id.play_iv,
                PlayerR.id.video_list_iv,
                PlayerR.id.subtitle_control_iv,
                PlayerR.id.audio_control,
                PlayerR.id.danmu_control_iv
            )) {
                val view = activity.findViewById<View>(id)
                val rect = bounds(view)
                geometry.put(
                    JSONObject()
                        .put(
                            "id",
                            context.resources.getResourceEntryName(id)
                        ).put(
                            "bounds",
                            JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom))
                        ).put("focused", view.hasFocus())
                        .put("visible", view.isShown)
                )
            }
            File(context.getExternalFilesDir("player-bottom-open"), "$name.json").writeText(
                JSONObject()
                    .put(
                        "density",
                        activity.resources.displayMetrics.density
                    ).put("fontScale", activity.resources.configuration.fontScale)
                    .put("geometry", geometry)
                    .toString(2)
            )
        }
        screenshot(name, "player-bottom-open")
    }

    @Test fun recordNinePanelsAndApplicableContentStates() =
        withPlayback { scenario, controller, background ->
            scenario.onActivity { player(it).pause() }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            scenario.onActivity { controller.stopFadeOut() }
            for (type in panelTypes) {
                show(scenario, controller, type)
                scenario.onActivity {
                    val root = panel(controller).getChildAt(0)
                    assertEquals(dp(264), root.width)
                    val visible = Rect()
                    assertTrue(root.getGlobalVisibleRect(visible))
                    assertEquals(root.width, visible.width())
                    assertEquals(root.height, visible.height())
                    assertTrue("Panel must choose a visible remote target", root.hasFocus())
                }
                screenshot(type.name.lowercase())
                closeAll(scenario, controller)
            }
            for (trackType in listOf(TrackType.AUDIO, TrackType.DANMU, TrackType.VIDEO)) {
                show(scenario, controller, SettingViewType.TRACKS, trackType)
                screenshot("tracks-${trackType.name.lowercase()}")
                closeAll(scenario, controller)
            }
            show(scenario, controller, SettingViewType.DANMU_OFFSET_TIME)
            screenshot("danmu-offset")
            closeAll(scenario, controller)
            show(scenario, controller, SettingViewType.TRACKS)
            screenshot("subtitle-empty")
            closeAll(scenario, controller)
            scenario.onActivity {
                val subtitle = File(context.getExternalFilesDir("a06-fixtures"), "a06-subtitle.srt")
                subtitle.writeText("1\n00:00:00,000 --> 00:01:50,000\nA06 subtitle fixture\n\n")
                assertTrue(controller.addExtendTrack(VideoTrackBean.subtitle(subtitle.path)))
            }
            show(scenario, controller, SettingViewType.TRACKS)
            screenshot("subtitle-selected-away")
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            screenshot("subtitle-selected-focused")
            closeAll(scenario, controller)
            val searchState = MutableLiveData<DanmuPanelState>()
            scenario.onActivity { controller.observerDanmuSearch({}, {}, { searchState }) }
            show(scenario, controller, SettingViewType.SEARCH_DANMU)
            for ((name, state) in listOf(
                "loading" to DanmuSearchState.Loading("A06"),
                "empty" to DanmuSearchState.Empty("A06"),
                "error" to DanmuSearchState.Error("A06", "连接失败，请重试")
            )) {
                scenario.onActivity { searchState.value = DanmuPanelState(search = state) }
                screenshot("search-$name")
            }
            scenario.onActivity {
                searchState.value =
                    DanmuPanelState(
                        search =
                            DanmuSearchState.Content(
                                "A06",
                                List(20) {
                                    DanmuEpisodeData(
                                        episodeId = "a06-$it",
                                        animeTitle = "用于核对侧栏阅读层次的长作品名称与搜索结果",
                                        episodeTitle = "第 ${it + 1} 话 · 长标题换行及遥控器滚动测试"
                                    )
                                }
                            )
                    )
            }
            await(scenario) { panel(controller).findViewById<RecyclerView>(PlayerR.id.danmu_rv).childCount > 0 }
            scenario.onActivity {
                assertEquals(
                    "Recovered results must clear the earlier failure",
                    "",
                    panel(controller).findViewById<TextView>(PlayerR.id.danmu_status_tv).text.toString()
                )
                panel(controller).findViewById<RecyclerView>(PlayerR.id.danmu_rv).requestFocus()
            }
            screenshot("search-content")
            repeat(20) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
            screenshot("search-content-scrolled")
            closeAll(scenario, controller)
            for ((scene, color) in listOf("bright" to Color.WHITE, "dark" to Color.rgb(8, 8, 12))) {
                scenario.onActivity { background.setBackgroundColor(color) }
                show(scenario, controller, SettingViewType.TRACKS)
                screenshot("subtitle-$scene")
                closeAll(scenario, controller)
            }
        }

    @Test fun busyBackgroundCannotChangeThePanelReadingSurface() =
        withPlayback { scenario, controller, _ ->
            for (type in panelTypes) {
                show(scenario, controller, type)
                scenario.onActivity {
                    val surface = panel(controller).getChildAt(0)
                    val bounds = surface.background.bounds
                    val oldBounds = Rect(bounds)

                    fun sample(background: Int): Int {
                        val bitmap = Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bitmap)
                        canvas.drawColor(background)
                        surface.background.setBounds(0, 0, bitmap.width, bitmap.height)
                        surface.background.draw(canvas)
                        return bitmap.getPixel(bitmap.width / 2, bitmap.height / 2).also { bitmap.recycle() }
                    }
                    assertEquals(
                        "$type must isolate its reading surface from bright video and controls",
                        sample(Color.BLACK),
                        sample(Color.WHITE)
                    )
                    surface.background.bounds = oldBounds
                }
                closeAll(scenario, controller)
            }
        }

    @Test fun stackChangesKeepOneDimLayerAndClearItForOtherPanelFormsAndRelease() =
        withPlayback { scenario, controller, _ ->
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            repeat(4) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
            await(scenario) { it.currentFocus?.id == PlayerR.id.subtitle_control_iv }
            var undimmedLeft = 0
            var undimmedRight = 0
            var undimmedMiddle = 0
            scenario.onActivity {
                controller.stopFadeOut()
                undimmedLeft = backgroundAlpha(controller, .4f)
                undimmedRight = backgroundAlpha(controller, .6f)
                undimmedMiddle = backgroundAlpha(controller, .55f)
            }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await(scenario) { activePanel(controller)?.getSettingViewType() == SettingViewType.TRACKS && panel(controller).hasFocus() }
            SystemClock.sleep(550)
            var alpha = 0
            scenario.onActivity {
                alpha = backgroundAlpha(controller, .6f)
                assertEquals("Left half must retain the movie's original brightness", undimmedLeft, backgroundAlpha(controller, .4f))
                assertTrue("Right-side background must recede", alpha > undimmedRight)
            }
            show(scenario, controller, SettingViewType.SUBTITLE_STYLE)
            scenario.onActivity { assertEquals("Nested right panels must not stack dimming", alpha, backgroundAlpha(controller, .6f)) }
            show(scenario, controller, SettingViewType.SWITCH_SOURCE)
            SystemClock.sleep(550)
            scenario.onActivity {
                assertEquals(
                    "Left picker retains its own presentation",
                    undimmedRight,
                    backgroundAlpha(controller, .6f)
                )
            }
            screenshot("left-picker")
            key(KeyEvent.KEYCODE_BACK)
            await(scenario) {
                activePanel(controller)?.getSettingViewType() == SettingViewType.SUBTITLE_STYLE &&
                    panel(controller).hasFocus()
            }
            SystemClock.sleep(550)
            scenario.onActivity { assertEquals(alpha, backgroundAlpha(controller, .6f)) }
            show(scenario, controller, SettingViewType.KEYWORD_BLOCK)
            SystemClock.sleep(550)
            scenario.onActivity {
                assertEquals(
                    "Keyword form must keep its existing uniform dimming without another right-side gradient",
                    backgroundAlpha(controller, .4f),
                    backgroundAlpha(controller, .6f)
                )
            }
            screenshot("keyword-form")
            key(KeyEvent.KEYCODE_BACK)
            await(scenario) {
                activePanel(controller)?.getSettingViewType() == SettingViewType.SUBTITLE_STYLE &&
                    panel(controller).hasFocus()
            }
            show(scenario, controller, SettingViewType.DANMU_CONFIGURE)
            SystemClock.sleep(550)
            scenario.onActivity {
                assertEquals(
                    "Unchanged 320dp panel is outside this material scope",
                    undimmedMiddle,
                    backgroundAlpha(controller, .55f)
                )
            }
            closeAll(scenario, controller)
            await(scenario) { it.currentFocus?.id == PlayerR.id.subtitle_control_iv }
            scenario.onActivity {
                repeat(12) {
                    controller.getSettingController().showSettingView(SettingViewType.PLAYER_SETTING)
                    controller.getSettingController().showSettingView(SettingViewType.SUBTITLE_STYLE)
                    controller.getSettingController().hideSettingView()
                    controller.getSettingController().hideSettingView()
                }
            }
            show(scenario, controller, SettingViewType.PLAYER_SETTING)
            show(scenario, controller, SettingViewType.SUBTITLE_STYLE)
            scenario.onActivity { controller.release() }
            SystemClock.sleep(600)
            scenario.onActivity {
                assertFalse("Release must clear the entire nested panel stack", controller.getSettingController().isSettingViewShowing())
                assertEquals("Release must clear the backdrop", undimmedRight, backgroundAlpha(controller, .6f))
            }
            show(scenario, controller, SettingViewType.PLAYER_SETTING)
            scenario.onActivity { controller.destroy() }
            SystemClock.sleep(600)
            scenario.onActivity { assertEquals("Destroy must clear the backdrop", undimmedRight, backgroundAlpha(controller, .6f)) }
        }

    @Test fun rightPanelKeepsBothRealKernelsPlayingAndReturnsByRemote() {
        for (kernel in listOf(PlayerType.TYPE_EXO_PLAYER, PlayerType.TYPE_MPV_PLAYER)) {
            withPlayback(kernel, false) { scenario, controller, _ ->
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                repeat(4) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
                await(scenario) { it.currentFocus?.id == PlayerR.id.subtitle_control_iv }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                await(scenario) { activePanel(controller)?.getSettingViewType() == SettingViewType.TRACKS && panel(controller).hasFocus() }
                var position = 0L
                scenario.onActivity { position = player(it).getCurrentPosition() }
                SystemClock.sleep(1200)
                scenario.onActivity {
                    assertTrue("$kernel must continue actual playback behind its panel", player(it).isPlaying())
                    assertTrue("$kernel timeline must advance", player(it).getCurrentPosition() > position)
                }
                screenshot("real-${kernel.name.lowercase()}-panel")
                key(KeyEvent.KEYCODE_BACK)
                await(scenario) { it.currentFocus?.id == PlayerR.id.subtitle_control_iv }
                key(KeyEvent.KEYCODE_BACK)
            }
        }
    }

    @Test fun speedChangesPersistAcrossPanelReopeningAndResetByRemote() =
        withPlayback { scenario, controller, _ ->
            val savedSpeed = PlayerConfig.getNewVideoSpeed()
            val runtimeSpeed = PlayerInitializer.Player.videoSpeed
            try {
                show(scenario, controller, SettingViewType.VIDEO_SPEED)
                var original = 0f
                scenario.onActivity { original = panel(controller).findViewById<Slider>(PlayerR.id.speed_slider).value }
                key(if (original < 4f) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
                var changed = 0f
                scenario.onActivity {
                    changed = panel(controller).findViewById<Slider>(PlayerR.id.speed_slider).value
                    assertNotEquals(original, changed)
                    assertEquals(changed, PlayerConfig.getNewVideoSpeed(), .001f)
                    assertEquals(changed, PlayerInitializer.Player.videoSpeed, .001f)
                    assertTrue(panel(controller).findViewById<View>(PlayerR.id.reset_tv).isShown)
                }
                screenshot("speed-adjusted")
                closeAll(scenario, controller)
                show(scenario, controller, SettingViewType.VIDEO_SPEED)
                scenario.onActivity { assertEquals(changed, panel(controller).findViewById<Slider>(PlayerR.id.speed_slider).value, .001f) }
                key(KeyEvent.KEYCODE_DPAD_UP)
                await(scenario) { it.currentFocus?.id == PlayerR.id.reset_tv }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                scenario.onActivity {
                    assertEquals(PlayerInitializer.Player.DEFAULT_SPEED, PlayerConfig.getNewVideoSpeed(), .001f)
                    assertFalse(panel(controller).findViewById<View>(PlayerR.id.reset_tv).isShown)
                    assertEquals(PlayerR.id.speed_slider, it.currentFocus?.id)
                }
                screenshot("speed-reset")
                closeAll(scenario, controller)
            } finally {
                PlayerConfig.putNewVideoSpeed(savedSpeed)
                PlayerInitializer.Player.videoSpeed = runtimeSpeed
            }
        }

    private fun backgroundAlpha(
        controller: VideoController,
        fraction: Float
    ): Int {
        val bitmap = Bitmap.createBitmap(controller.width, controller.height, Bitmap.Config.ARGB_8888)
        controller.draw(Canvas(bitmap))
        return Color.alpha(bitmap.getPixel((bitmap.width * fraction).toInt(), bitmap.height / 2)).also { bitmap.recycle() }
    }

    private fun activePanel(controller: VideoController): BaseSettingView<*>? =
        (0 until controller.childCount)
            .map {
                controller.getChildAt(it)
            }.filterIsInstance<BaseSettingView<*>>()
            .lastOrNull { it.isSettingShowing() }

    private fun panel(controller: VideoController) = requireNotNull(activePanel(controller))

    private fun show(
        scenario: ActivityScenario<PlayerActivity>,
        controller: VideoController,
        type: SettingViewType,
        trackType: TrackType = TrackType.SUBTITLE
    ) {
        scenario.onActivity { controller.getSettingController().showSettingView(type, trackType) }
        await(scenario) {
            activePanel(controller)?.let { active -> active.getChildAt(0).translationX == 0f && active.hasFocus() } == true
        }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(100)
    }

    private fun closeAll(
        scenario: ActivityScenario<PlayerActivity>,
        controller: VideoController
    ) {
        scenario.onActivity {
            while (controller.getSettingController().isSettingViewShowing()) controller.getSettingController().hideSettingView()
        }
        SystemClock.sleep(550)
        instrumentation.waitForIdleSync()
    }

    private fun withPlayback(
        kernel: PlayerType = PlayerType.TYPE_EXO_PLAYER,
        useReferenceBackground: Boolean = true,
        grouped: Boolean = true,
        block: (ActivityScenario<PlayerActivity>, VideoController, View) -> Unit
    ) {
        val directory = requireNotNull(context.getExternalFilesDir("a06-fixtures"))
        val first = File(directory, "01-first.mp4")
        val second = File(directory, "02-middle.mp4")
        val last = File(directory, "03-long-episode-name-to-check-two-lines-and-focus-restoration.mp4")
        val fixture = File("/sdcard/Download/ddplaytv-lease-playback-long.mp4")
        listOf(first, second, last).forEach { fixture.copyTo(it, overwrite = true) }
        val oldKernel = PlayerConfig.getUsePlayerType()
        PlayerConfig.putUsePlayerType(kernel.value)
        try {
            withIndexedPlaybackFile(last) { lastDescriptor ->
                withIndexedPlaybackFile(second) { secondDescriptor ->
                    withIndexedPlaybackFile(first) { descriptor ->
                        val grouped =
                            descriptor.copy(
                                queueContext =
                                    requireNotNull(descriptor.queueContext).copy(
                                        fallbackItems =
                                            if (grouped) {
                                                listOf(
                                                    descriptor.file,
                                                    secondDescriptor.file,
                                                    lastDescriptor.file
                                                )
                                            } else {
                                                listOf(descriptor.file)
                                            }
                                    )
                            )
                        val provider = ARouter.getInstance().navigation(PlaybackRequestService::class.java)
                        val request =
                            runBlocking {
                                provider.submit(PlaybackRequestInput(grouped, null, PlaybackRequestOrigin.STORAGE_FILE))
                            } as PlaybackSubmissionResult.Accepted
                        ActivityScenario
                            .launch<PlayerActivity>(
                                Intent(context, PlayerActivity::class.java).putExtra(PlaybackNavigation.REQUEST_ID, request.requestId)
                            ).use { scenario ->
                                lateinit var controller: VideoController
                                lateinit var background: View
                                await(scenario, 20000) { player(it).isPlaying() && it.hasWindowFocus() }
                                scenario.onActivity { activity ->
                                    val host = player(activity)
                                    controller =
                                        (0 until host.childCount).map { host.getChildAt(it) }.filterIsInstance<VideoController>().single()
                                    background =
                                        View(activity).apply {
                                            val image = File(directory, "cinema-frame.jpg")
                                            setBackground(BitmapDrawable(resources, requireNotNull(BitmapFactory.decodeFile(image.path))))
                                        }
                                    if (useReferenceBackground) placeReferenceBackground(activity, controller, background)
                                    controller.setVideoTitle("药屋少女的呢喃第三季 - S01E50.mkv")
                                }
                                block(scenario, controller, background)
                            }
                    }
                }
            }
        } finally {
            PlayerConfig.putUsePlayerType(oldKernel)
            listOf(first, second, last, File(directory, "a06-subtitle.srt")).forEach { it.delete() }
        }
    }

    private fun placeReferenceBackground(
        activity: PlayerActivity,
        controller: VideoController,
        background: View
    ) {
        (background.parent as? ViewGroup)?.removeView(background)
        val host = player(activity)
        host.addView(
            background,
            host.indexOfChild(controller),
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
    }

    private fun player(activity: PlayerActivity) =
        activity.findViewById<FrameLayout>(PlayerR.id.player_container).getChildAt(0) as DanDanVideoPlayer

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private fun key(code: Int) {
        instrumentation.sendKeyDownUpSync(code)
        instrumentation.waitForIdleSync()
    }

    private fun screenshot(
        name: String,
        directoryName: String = "a06-$phase"
    ) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(600)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = requireNotNull(context.getExternalFilesDir(directoryName))
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun await(
        scenario: ActivityScenario<PlayerActivity>,
        timeout: Long = 6000,
        predicate: (PlayerActivity) -> Boolean
    ) {
        val deadline = SystemClock.uptimeMillis() + timeout
        var ready = false
        while (!ready && SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            scenario.onActivity { ready = predicate(it) }
            if (!ready) SystemClock.sleep(30)
        }
        var diagnostic = ""
        if (!ready) {
            scenario.onActivity {
                diagnostic = "playing=${player(it).isPlaying()}, windowFocus=${it.hasWindowFocus()}, focus=${it.currentFocus}"
            }
        }
        assertTrue("Player panel did not settle: $diagnostic", ready)
    }
}
