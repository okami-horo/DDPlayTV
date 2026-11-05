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
import com.xyoye.common_component.config.DanmuConfig
import com.xyoye.common_component.config.PlayerConfig
import com.xyoye.common_component.config.SubtitleConfig
import com.xyoye.common_component.config.SubtitlePreferenceUpdater
import com.xyoye.common_component.playback.handoff.PlaybackNavigation
import com.xyoye.common_component.playback.handoff.PlaybackRequestInput
import com.xyoye.common_component.playback.handoff.PlaybackRequestOrigin
import com.xyoye.common_component.playback.handoff.PlaybackSubmissionResult
import com.xyoye.common_component.services.PlaybackRequestService
import com.xyoye.data_component.bean.LocalDanmuBean
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
import com.xyoye.player.controller.setting.SubtitleQuickView
import com.xyoye.player.controller.setting.SettingSubtitleStyleView
import com.xyoye.player.controller.video.PlayerBottomView
import com.xyoye.player.info.PlayerInitializer
import com.xyoye.player.info.SettingItem
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

    @Test fun bottomDanmakuToggleKeepsFocusAndSidebarDetailsStayEditable() =
        withPlayback { scenario, controller, _ ->
            val danmu = controller.getDanmuController()
            val original = danmu.isUserDanmuVisible()
            val savedSize = DanmuConfig.getDanmuSize()
            val runtimeSize = PlayerInitializer.Danmu.size
            try {
                scenario.onActivity { danmu.setUserDanmuVisible(true) }
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                await(scenario) { it.currentFocus?.id == PlayerR.id.play_iv }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                scenario.onActivity {
                    controller.stopFadeOut()
                    bottom(it).onProgressChanged(1_440_000, 582_000)
                    controller.getSubtitleController().setUserSubtitleVisible(true)
                    controller.onSubtitleTextOutput(MixedSubtitle.fromText("为什么那次衣服没被撞破啊？"))
                }
                repeat(6) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
                await(scenario) { it.currentFocus?.id == PlayerR.id.danmu_control_iv }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                scenario.onActivity {
                    assertFalse("Bottom danmaku must toggle directly", controller.getSettingController().isSettingViewShowing())
                    assertFalse("An empty track must still allow turning danmaku off", danmu.isUserDanmuVisible())
                    assertDanmakuButton(it, false, true)
                }
                screenshot("off-focused", "player-danmaku-toggle")
                repeat(6) { key(KeyEvent.KEYCODE_DPAD_LEFT) }
                scenario.onActivity { assertDanmakuButton(it, false, false) }
                screenshot("off-idle", "player-danmaku-toggle")
                repeat(6) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                scenario.onActivity { assertDanmakuButton(it, true, true) }
                screenshot("on-focused", "player-danmaku-toggle")
                repeat(6) { key(KeyEvent.KEYCODE_DPAD_LEFT) }
                scenario.onActivity { assertDanmakuButton(it, true, false) }
                screenshot("on-idle", "player-danmaku-toggle")
                repeat(6) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
                key(KeyEvent.KEYCODE_BACK)
                scenario.onActivity { assertFalse(it.findViewById<View>(PlayerR.id.danmu_control_iv).isFocusable) }
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                await(scenario) { it.currentFocus?.id == PlayerR.id.danmu_control_iv }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                key(KeyEvent.KEYCODE_MENU)
                await(scenario) {
                    activePanel(controller)?.getSettingViewType() == SettingViewType.PLAYER_SETTING &&
                        panel(controller).hasFocus() &&
                        panel(controller).getChildAt(0).translationX == 0f
                }
                var trackEntry = false
                repeat(12) {
                    scenario.onActivity {
                        trackEntry =
                            it.currentFocus
                                ?.findViewById<TextView>(PlayerR.id.tv_setting)
                                ?.text
                                ?.toString() == "弹幕轨"
                    }
                    if (!trackEntry) key(KeyEvent.KEYCODE_DPAD_DOWN)
                }
                assertTrue("Danmaku details must remain reachable from the settings sidebar", trackEntry)
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                await(scenario) { activePanel(controller)?.getSettingViewType() == SettingViewType.TRACKS && panel(controller).hasFocus() }
                screenshot("danmaku-tracks-empty", "player-danmaku-sidebar-$phase")
                scenario.onActivity {
                    val toggle = panel(controller).findViewById<View>(PlayerR.id.track_visible)
                    assertFalse("The sidebar must not repeat the bottom danmaku toggle", toggle.isShown)
                    assertFalse("The hidden switch must not take DPAD focus", toggle.requestFocus())
                    assertTrue(panel(controller).findViewById<View>(PlayerR.id.track_style).isShown)
                    assertEquals(PlayerR.id.tv_add_track, it.currentFocus?.id)
                    assertDanmakuButton(it, false, false)
                }
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                await(scenario) { it.currentFocus?.id == PlayerR.id.track_style }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                await(scenario) {
                    activePanel(controller)?.getSettingViewType() == SettingViewType.DANMU_STYLE &&
                        panel(controller).hasFocus()
                }
                var size = 0
                scenario.onActivity { size = it.findViewById<android.widget.SeekBar>(PlayerR.id.danmu_size_sb).progress }
                key(if (size < 100) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
                scenario.onActivity {
                    val changed = it.findViewById<android.widget.SeekBar>(PlayerR.id.danmu_size_sb).progress
                    assertNotEquals("Detailed settings must remain editable while danmaku is off", size, changed)
                    assertEquals(changed, DanmuConfig.getDanmuSize())
                    size = changed
                    assertDanmakuButton(it, false, false)
                }
                screenshot("danmaku-style-while-off", "player-danmaku-sidebar-$phase")
                key(KeyEvent.KEYCODE_BACK)
                await(scenario) {
                    activePanel(controller)?.getSettingViewType() == SettingViewType.TRACKS &&
                        it.currentFocus?.id == PlayerR.id.tv_add_track
                }
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                await(scenario) { it.currentFocus?.id == PlayerR.id.track_style }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                await(scenario) {
                    activePanel(controller)?.getSettingViewType() == SettingViewType.DANMU_STYLE &&
                        panel(controller).hasFocus()
                }
                scenario.onActivity { assertEquals(size, it.findViewById<android.widget.SeekBar>(PlayerR.id.danmu_size_sb).progress) }
                key(KeyEvent.KEYCODE_BACK)
                await(scenario) {
                    activePanel(controller)?.getSettingViewType() == SettingViewType.TRACKS &&
                        it.currentFocus?.id == PlayerR.id.tv_add_track
                }
                key(KeyEvent.KEYCODE_BACK)
                await(scenario) {
                    activePanel(controller)?.getSettingViewType() == SettingViewType.PLAYER_SETTING &&
                        panel(controller).hasFocus()
                }
                key(KeyEvent.KEYCODE_BACK)
                await(scenario) { it.currentFocus?.id == PlayerR.id.danmu_control_iv }
                scenario.onActivity { assertDanmakuButton(it, false, true) }
                val fixture = File(context.getExternalFilesDir("a06-fixtures"), "toggle-danmaku.xml")
                fixture.writeText("<i><d p=\"1,1,25,16777215,0,0,0,0\">toggle fixture</d></i>")
                try {
                    scenario.onActivity {
                        val track = VideoTrackBean.danmu(LocalDanmuBean(fixture.path))
                        assertTrue(danmu.addTrack(track))
                        danmu.selectTrack(track)
                        assertFalse("Loading a late track must preserve the off preference", danmu.isUserDanmuVisible())
                        assertDanmakuButton(it, false, true)
                    }
                    key(KeyEvent.KEYCODE_DPAD_CENTER)
                    scenario.onActivity {
                        assertTrue(danmu.isUserDanmuVisible())
                        assertTrue("An enabled selected track must be shown", controller.getChildAt(0).isShown)
                        assertDanmakuButton(it, true, true)
                    }
                    key(KeyEvent.KEYCODE_DPAD_CENTER)
                    scenario.onActivity {
                        assertFalse("Turning off a loaded track must hide its rendering view", controller.getChildAt(0).isShown)
                        assertDanmakuButton(it, false, true)
                    }
                    show(scenario, controller, SettingViewType.TRACKS, TrackType.DANMU)
                    scenario.onActivity {
                        assertFalse(panel(controller).findViewById<View>(PlayerR.id.track_visible).isShown)
                        assertTrue(panel(controller).findViewById<View>(PlayerR.id.track_style).isShown)
                        assertTrue(panel(controller).findViewById<View>(PlayerR.id.rv_track).hasFocus())
                        assertDanmakuButton(it, false, false)
                    }
                    screenshot("danmaku-tracks-loaded", "player-danmaku-sidebar-$phase")
                    key(KeyEvent.KEYCODE_BACK)
                    await(scenario) { it.currentFocus?.id == PlayerR.id.danmu_control_iv }
                } finally {
                    fixture.delete()
                }
                key(KeyEvent.KEYCODE_BACK)
            } finally {
                scenario.onActivity {
                    DanmuConfig.putDanmuSize(savedSize)
                    PlayerInitializer.Danmu.size = runtimeSize
                    danmu.updateDanmuSize()
                    danmu.setUserDanmuVisible(original)
                }
            }
        }

    @Test fun bottomDanmakuPreferenceSurvivesARecreatedPlayer() {
        val original = DanmuConfig.isShowDanmu()
        try {
            withPlayback { scenario, controller, _ ->
                scenario.onActivity {
                    controller.getDanmuController().setUserDanmuVisible(false)
                    assertFalse(DanmuConfig.isShowDanmu())
                }
            }
            withPlayback { scenario, controller, _ ->
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                await(scenario) { it.currentFocus?.id == PlayerR.id.play_iv }
                scenario.onActivity {
                    controller.stopFadeOut()
                    assertFalse(controller.getDanmuController().isUserDanmuVisible())
                    assertDanmakuButton(it, false, false)
                }
                repeat(6) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
                await(scenario) { it.currentFocus?.id == PlayerR.id.danmu_control_iv }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                scenario.onActivity {
                    assertTrue(DanmuConfig.isShowDanmu())
                    assertDanmakuButton(it, true, true)
                }
            }
        } finally {
            DanmuConfig.putShowDanmu(original)
        }
    }

    @Test fun subtitleQuickWorksWithBothKernelsAndSidebarContainsOnlyDetails() {
        val savedOffset = SubtitlePreferenceUpdater.currentOffset()
        try {
            for (kernel in listOf(PlayerType.TYPE_EXO_PLAYER, PlayerType.TYPE_MPV_PLAYER)) {
                SubtitlePreferenceUpdater.persistOffset(1500)
                withPlayback(kernel) { scenario, controller, _ ->
                    val subtitle = File(context.getExternalFilesDir("a06-fixtures"), "a06-subtitle.srt")
                    subtitle.writeText("1\n00:00:00,000 --> 00:01:50,000\n为什么那次衣服没被撞破啊？\n\n")
                    key(KeyEvent.KEYCODE_DPAD_DOWN)
                    repeat(4) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
                    await(scenario) { it.currentFocus?.id == PlayerR.id.subtitle_control_iv }
                    key(KeyEvent.KEYCODE_DPAD_CENTER)
                    await(scenario) { quickRow(it, 0)?.hasFocus() == true }
                    scenario.onActivity {
                        assertEquals(1, quickList(it).adapter!!.itemCount)
                        assertTrue(quickRow(it, 0)!!.isActivated)
                        assertTrue(it.findViewById<View>(PlayerR.id.subtitle_quick_message).isShown)
                    }
                    captureSubtitle(scenario, "empty-${kernel.name}")
                    key(KeyEvent.KEYCODE_BACK)
                    await(scenario) { it.currentFocus?.id == PlayerR.id.subtitle_control_iv }
                    scenario.onActivity { assertTrue(controller.addExtendTrack(VideoTrackBean.subtitle(subtitle.path))) }
                    key(KeyEvent.KEYCODE_DPAD_CENTER)
                    await(scenario) { quickList(it).adapter!!.itemCount == 2 && quickRow(it, 1)?.isActivated == true }
                    var position = 0L
                    scenario.onActivity { position = player(it).getCurrentPosition() }
                    SystemClock.sleep(700)
                    scenario.onActivity {
                        assertTrue("$kernel must keep playing behind the quick panel", player(it).isPlaying())
                        assertTrue(player(it).getCurrentPosition() > position)
                    }
                    key(KeyEvent.KEYCODE_DPAD_UP)
                    scenario.onActivity {
                        assertTrue(quickRow(it, 0)!!.hasFocus())
                        assertFalse(quickRow(it, 0)!!.isActivated)
                        assertTrue(quickRow(it, 1)!!.isActivated)
                    }
                    key(KeyEvent.KEYCODE_DPAD_CENTER)
                    await(scenario) { it.currentFocus?.id == PlayerR.id.subtitle_control_iv }
                    scenario.onActivity {
                        assertFalse(controller.getSubtitleController().isUserSubtitleVisible())
                        assertTrue(controlWrapper(it).getTracks(TrackType.SUBTITLE).any { track -> !track.disable && track.selected })
                    }
                    key(KeyEvent.KEYCODE_DPAD_CENTER)
                    await(scenario) { quickRow(it, 0)?.hasFocus() == true }
                    key(KeyEvent.KEYCODE_DPAD_DOWN)
                    key(KeyEvent.KEYCODE_BACK)
                    await(scenario) { it.currentFocus?.id == PlayerR.id.subtitle_control_iv }
                    scenario.onActivity { assertFalse(controller.getSubtitleController().isUserSubtitleVisible()) }
                    key(KeyEvent.KEYCODE_DPAD_CENTER)
                    await(scenario) { quickRow(it, 0)?.hasFocus() == true }
                    key(KeyEvent.KEYCODE_DPAD_DOWN)
                    key(KeyEvent.KEYCODE_DPAD_CENTER)
                    await(scenario) { it.currentFocus?.id == PlayerR.id.subtitle_control_iv }
                    scenario.onActivity { assertTrue(controller.getSubtitleController().isUserSubtitleVisible()) }
                    key(KeyEvent.KEYCODE_DPAD_CENTER)
                    await(scenario) { quickRow(it, 1)?.hasFocus() == true && quickRow(it, 1)?.isActivated == true }
                    captureSubtitle(scenario, "selected-${kernel.name}")
                    key(KeyEvent.KEYCODE_BACK)
                    key(KeyEvent.KEYCODE_MENU)
                    await(scenario) {
                        activePanel(controller)?.getSettingViewType() == SettingViewType.PLAYER_SETTING &&
                            panel(controller).hasFocus()
                    }
                    scenario.onActivity {
                        val adapter =
                            it
                                .findViewById<RecyclerView>(
                                    PlayerR.id.setting_rv
                                ).adapter as com.xyoye.common_component.adapter.BaseAdapter
                        val labels = adapter.items.filterIsInstance<SettingItem>().map { item -> item.display }
                        assertTrue(labels.containsAll(listOf("样式与位置", "时间同步", "添加外挂字幕")))
                        assertFalse(labels.contains("字幕轨"))
                        assertFalse(labels.contains("显示字幕"))
                    }
                    focusPlayerSetting(scenario, "样式与位置")
                    key(KeyEvent.KEYCODE_DPAD_CENTER)
                    await(scenario) {
                        activePanel(controller)?.getSettingViewType() == SettingViewType.SUBTITLE_STYLE &&
                            panel(controller).hasFocus()
                    }
                    scenario.onActivity {
                        val nativeText = kernel == PlayerType.TYPE_EXO_PLAYER
                        assertEquals(nativeText, it.findViewById<View>(PlayerR.id.subtitle_size_sb).isShown)
                        assertEquals(nativeText, it.findViewById<View>(PlayerR.id.subtitle_color_sb).isShown)
                        assertEquals(nativeText, it.findViewById<View>(PlayerR.id.subtitle_stroke_color_sb).isShown)
                        assertEquals(nativeText, it.findViewById<View>(PlayerR.id.subtitle_alpha_sb).isShown)
                        if (nativeText) assertEquals(PlayerR.id.subtitle_size_sb, it.currentFocus?.id)
                    }
                    if (kernel == PlayerType.TYPE_EXO_PLAYER) {
                        for (control in listOf(PlayerR.id.subtitle_stroke_width_sb, PlayerR.id.subtitle_color_sb,
                            PlayerR.id.subtitle_stroke_color_sb, PlayerR.id.subtitle_alpha_sb, PlayerR.id.subtitle_vertical_offset_sb)) {
                            key(KeyEvent.KEYCODE_DPAD_DOWN)
                            await(scenario) { it.currentFocus?.id == control }
                        }
                        repeat(5) { key(KeyEvent.KEYCODE_DPAD_UP) }
                        await(scenario) { it.currentFocus?.id == PlayerR.id.subtitle_size_sb }
                    } else {
                        key(KeyEvent.KEYCODE_DPAD_UP)
                        await(scenario) { it.currentFocus?.id == PlayerR.id.subtitle_font_scale_offset_sb }
                        key(KeyEvent.KEYCODE_DPAD_DOWN)
                        await(scenario) { it.currentFocus?.id == PlayerR.id.subtitle_vertical_offset_sb }
                    }
                    screenshot("style-text-${kernel.name}", "subtitle-feature-fixes")
                    key(KeyEvent.KEYCODE_BACK)
                    await(scenario) {
                        activePanel(controller)?.getSettingViewType() == SettingViewType.PLAYER_SETTING &&
                            panel(controller).hasFocus() &&
                            panel(controller).getChildAt(0).translationX == 0f
                    }
                    focusPlayerSetting(scenario, "时间同步")
                    key(KeyEvent.KEYCODE_DPAD_CENTER)
                    await(scenario) {
                        it.findViewById<TextView>(PlayerR.id.offset_title)?.text == "字幕时间偏移" &&
                            panel(controller).hasFocus()
                    }
                    scenario.onActivity {
                        assertEquals("1.5秒", it.findViewById<TextView>(PlayerR.id.tv_display).text.toString())
                        assertEquals("提前", it.findViewById<TextView>(PlayerR.id.tv_status).text.toString())
                        it.findViewById<View>(PlayerR.id.tv_reduce_half).requestFocus()
                    }
                    key(KeyEvent.KEYCODE_DPAD_CENTER)
                    scenario.onActivity {
                        assertEquals("1秒", it.findViewById<TextView>(PlayerR.id.tv_display).text.toString())
                        assertEquals(1000L, SubtitlePreferenceUpdater.currentOffset())
                        it.findViewById<View>(PlayerR.id.tv_reset).requestFocus()
                    }
                    key(KeyEvent.KEYCODE_DPAD_CENTER)
                    scenario.onActivity {
                        assertEquals("0秒", it.findViewById<TextView>(PlayerR.id.tv_display).text.toString())
                        assertEquals(0L, SubtitlePreferenceUpdater.currentOffset())
                        assertEquals(PlayerR.id.tv_add_half, it.currentFocus?.id)
                    }
                    key(KeyEvent.KEYCODE_BACK)
                    await(scenario) {
                        activePanel(controller)?.getSettingViewType() == SettingViewType.PLAYER_SETTING &&
                            panel(controller).hasFocus() &&
                            panel(controller).getChildAt(0).translationX == 0f
                    }
                    focusPlayerSetting(scenario, "添加外挂字幕")
                    screenshot("sidebar-details-${kernel.name}", "player-subtitle-quick")
                    key(KeyEvent.KEYCODE_DPAD_CENTER)
                    await(scenario) {
                        activePanel(controller)?.getSettingViewType() == SettingViewType.SWITCH_SOURCE &&
                            panel(controller).hasFocus()
                    }
                    key(KeyEvent.KEYCODE_BACK)
                    await(scenario) {
                        activePanel(controller)?.getSettingViewType() == SettingViewType.PLAYER_SETTING &&
                            panel(controller).hasFocus()
                    }
                    key(KeyEvent.KEYCODE_BACK)
                    await(scenario) { it.currentFocus?.id == PlayerR.id.subtitle_control_iv }
                    key(KeyEvent.KEYCODE_BACK)
                }
            }
        } finally {
            SubtitlePreferenceUpdater.persistOffset(savedOffset)
        }
    }

    @Test fun subtitleStyleFollowsActiveFormatAndPreservesHiddenSettingsOnReset() =
        withPlayback { scenario, controller, _ ->
            val settings = PlayerInitializer.Subtitle
            val previous = intArrayOf(settings.textSize, settings.strokeWidth, settings.textColor, settings.strokeColor,
                settings.alpha, settings.fontScaleOffsetPercent, settings.verticalOffset)
            lateinit var style: SettingSubtitleStyleView
            lateinit var original: ControlWrapper
            var styleAttached = false
            var mime = "application/x-subrip"
            try {
                scenario.onActivity {
                    settings.textSize = 71
                    settings.strokeWidth = settings.DEFAULT_STROKE
                    settings.textColor = settings.DEFAULT_TEXT_COLOR
                    settings.strokeColor = settings.DEFAULT_STROKE_COLOR
                    settings.alpha = settings.DEFAULT_ALPHA
                    settings.verticalOffset = settings.DEFAULT_VERTICAL_OFFSET
                    settings.fontScaleOffsetPercent = 125
                }
                show(scenario, controller, SettingViewType.SUBTITLE_STYLE)
                scenario.onActivity {
                    style = panel(controller) as SettingSubtitleStyleView
                    original = controlWrapper(it)
                    val fixture = object : InterVideoPlayer by original {
                        override fun getTracks(type: TrackType): List<VideoTrackBean> =
                            if (type == TrackType.SUBTITLE) {
                                listOf(VideoTrackBean.internal("fixture", "Format capability fixture", type, true, mime))
                            } else original.getTracks(type)
                    }
                    style.attach(ControlWrapper(fixture, original, original, original, original))
                    styleAttached = true
                    style.onTrackChanged(TrackType.SUBTITLE)
                    assertTrue(it.findViewById<View>(PlayerR.id.subtitle_size_sb).isShown)
                    assertFalse(it.findViewById<View>(PlayerR.id.subtitle_font_scale_offset_sb).isShown)
                    it.findViewById<View>(PlayerR.id.tv_reset_subtitle_config).requestFocus()
                }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                scenario.onActivity {
                    assertEquals(settings.DEFAULT_SIZE, settings.textSize)
                    assertEquals(125, settings.fontScaleOffsetPercent)
                    assertFalse(it.findViewById<View>(PlayerR.id.tv_reset_subtitle_config).isShown)
                    assertEquals(PlayerR.id.subtitle_size_sb, it.currentFocus?.id)
                    settings.textSize = 73
                    it.findViewById<View>(PlayerR.id.subtitle_size_sb).requestFocus()
                    mime = "text/x-ssa"
                    style.onTrackChanged(TrackType.SUBTITLE)
                    assertFalse(it.findViewById<View>(PlayerR.id.subtitle_size_sb).isShown)
                    assertTrue(it.findViewById<View>(PlayerR.id.subtitle_font_scale_offset_sb).isShown)
                    assertTrue(it.findViewById<View>(PlayerR.id.subtitle_alpha_sb).isShown)
                    assertEquals(PlayerR.id.subtitle_vertical_offset_sb, it.currentFocus?.id)
                }
                screenshot("style-ass", "subtitle-feature-fixes")
                scenario.onActivity { it.findViewById<View>(PlayerR.id.tv_reset_subtitle_config).requestFocus() }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                scenario.onActivity {
                    assertEquals(0, settings.fontScaleOffsetPercent)
                    assertEquals(73, settings.textSize)
                    assertFalse(it.findViewById<View>(PlayerR.id.tv_reset_subtitle_config).isShown)
                    assertEquals(PlayerR.id.subtitle_vertical_offset_sb, it.currentFocus?.id)
                    settings.fontScaleOffsetPercent = 180
                    mime = "application/pgs"
                    style.onTrackChanged(TrackType.SUBTITLE)
                    assertFalse(it.findViewById<View>(PlayerR.id.subtitle_size_sb).isShown)
                    assertFalse(it.findViewById<View>(PlayerR.id.subtitle_font_scale_offset_sb).isShown)
                    assertTrue(it.findViewById<View>(PlayerR.id.subtitle_alpha_sb).isShown)
                    assertFalse(it.findViewById<View>(PlayerR.id.tv_reset_subtitle_config).isShown)
                    it.findViewById<View>(PlayerR.id.subtitle_alpha_sb).requestFocus()
                }
                key(KeyEvent.KEYCODE_DPAD_LEFT)
                scenario.onActivity {
                    assertTrue(settings.alpha < settings.DEFAULT_ALPHA)
                    assertEquals(settings.alpha, SubtitleConfig.getAlpha())
                    it.findViewById<View>(PlayerR.id.tv_reset_subtitle_config).requestFocus()
                }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                scenario.onActivity {
                    assertEquals(settings.DEFAULT_ALPHA, settings.alpha)
                    assertEquals(180, settings.fontScaleOffsetPercent)
                    assertEquals(73, settings.textSize)
                    assertEquals(PlayerR.id.subtitle_alpha_sb, it.currentFocus?.id)
                }
                screenshot("style-bitmap", "subtitle-feature-fixes")
                key(KeyEvent.KEYCODE_BACK)
            } finally {
                scenario.onActivity {
                    if (styleAttached) style.attach(original)
                    settings.textSize = previous[0]
                    settings.strokeWidth = previous[1]
                    settings.textColor = previous[2]
                    settings.strokeColor = previous[3]
                    settings.alpha = previous[4]
                    settings.fontScaleOffsetPercent = previous[5]
                    settings.verticalOffset = previous[6]
                    SubtitleConfig.putTextSize(previous[0])
                    SubtitleConfig.putStrokeWidth(previous[1])
                    SubtitleConfig.putTextColor(previous[2])
                    SubtitleConfig.putStrokeColor(previous[3])
                    SubtitleConfig.putAlpha(previous[4])
                    SubtitleConfig.putSubtitleFontScaleOffsetPercent(previous[5])
                    SubtitleConfig.putVerticalOffset(previous[6])
                }
            }
        }

    @Test fun subtitleQuickLongNamesStaySingleLineAndOnlyFocusedTextMoves() =
        withPlayback { scenario, controller, _ ->
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            repeat(4) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
            await(scenario) { it.currentFocus?.id == PlayerR.id.subtitle_control_iv }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await(scenario) { quickRow(it, 0)?.hasFocus() == true }
            key(KeyEvent.KEYCODE_BACK)
            lateinit var original: ControlWrapper
            val names =
                listOf(
                    "简体中文",
                    "English",
                    "简体中文 / English",
                    "繁體中文（台灣）",
                    "日本語",
                    "Deutsch (Deutschland) – Untertitel für Hörgeschädigte",
                    "Français (Canada) — sous-titres",
                    "العربية (السعودية) — ترجمة وصفية للصم وضعاف السمع",
                    "[字幕组] 药屋少女的呢喃 第50集 [简体中文&English][WEB-DL 1080p][修订版].ass"
                )
            var selected = 8
            lateinit var quick: SubtitleQuickView
            scenario.onActivity {
                player(it).pause()
                controller.stopFadeOut()
                bottom(it).onProgressChanged(1_440_000, 582_000)
                controller.onSubtitleTextOutput(MixedSubtitle.fromText("为什么那次衣服没被撞破啊？"))
                original = controlWrapper(it)
                val fixture =
                    object : InterVideoPlayer by original {
                        override fun supportAddTrack(type: TrackType) = type == TrackType.SUBTITLE || original.supportAddTrack(type)

                        override fun getTracks(type: TrackType): List<VideoTrackBean> =
                            if (type ==
                                TrackType.SUBTITLE
                            ) {
                                names.mapIndexed { index, name ->
                                    VideoTrackBean.internal(
                                        "$index",
                                        name,
                                        type,
                                        index == selected
                                    )
                                }
                            } else {
                                original.getTracks(type)
                            }

                        override fun selectTrack(track: VideoTrackBean) {
                            selected = track.id!!.toInt()
                        }
                    }
                quick =
                    (0 until controller.childCount)
                        .map { index ->
                            controller.getChildAt(index)
                        }.filterIsInstance<SubtitleQuickView>()
                        .single()
                val wrapper = ControlWrapper(fixture, original, original, original, original)
                quick.attach(wrapper)
                bottom(it).attach(wrapper)
            }
            try {
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                await(scenario) { quickRow(it, 9)?.hasFocus() == true }
                lateinit var first: Bitmap
                var tick = Rect()
                scenario.onActivity {
                    val row = requireNotNull(quickRow(it, 9))
                    val name = row.findViewById<TextView>(PlayerR.id.subtitle_quick_name)
                    assertEquals(1, name.lineCount)
                    assertTrue(name.isSelected)
                    assertEquals(8, selected)
                    tick = bounds(row.findViewById(PlayerR.id.subtitle_quick_check))
                    first = drawView(name)
                    val list = quickList(it)
                    for (index in 0 until list.childCount) {
                        val other = list.getChildAt(index)
                        assertEquals(row.width, other.width)
                        assertEquals(row.height, other.height)
                        assertEquals(tick.left, bounds(other.findViewById(PlayerR.id.subtitle_quick_check)).left)
                        assertEquals(other.hasFocus(), other.findViewById<TextView>(PlayerR.id.subtitle_quick_name).isSelected)
                    }
                    val visible = Rect()
                    assertTrue(row.getGlobalVisibleRect(visible))
                    assertEquals(row.height, visible.height())
                }
                SystemClock.sleep(400)
                scenario.onActivity {
                    assertTrue(
                        "Marquee must wait before moving",
                        first.sameAs(drawView(quickRow(it, 9)!!.findViewById(PlayerR.id.subtitle_quick_name)))
                    )
                }
                captureSubtitle(scenario, "long-name-waiting")
                SystemClock.sleep(1600)
                scenario.onActivity {
                    assertFalse(
                        "Focused long text must actually move",
                        first.sameAs(drawView(quickRow(it, 9)!!.findViewById(PlayerR.id.subtitle_quick_name)))
                    )
                    assertEquals(tick, bounds(quickRow(it, 9)!!.findViewById(PlayerR.id.subtitle_quick_check)))
                }
                captureSubtitle(scenario, "long-name-moving")
                first.recycle()
                key(KeyEvent.KEYCODE_DPAD_UP)
                await(scenario) { quickRow(it, 8)?.hasFocus() == true }
                scenario.onActivity {
                    val name = quickRow(it, 8)!!.findViewById<TextView>(PlayerR.id.subtitle_quick_name)
                    assertEquals(-1, name.layout.getParagraphDirection(0))
                    assertEquals(1, name.lineCount)
                    assertEquals(8, selected)
                    val old = quickRow(it, 9)!!.findViewById<TextView>(PlayerR.id.subtitle_quick_name)
                    assertFalse(old.isSelected)
                    assertEquals(android.text.TextUtils.TruncateAt.END, old.ellipsize)
                    first = drawView(old)
                }
                SystemClock.sleep(400)
                scenario.onActivity {
                    val reset = drawView(quickRow(it, 9)!!.findViewById(PlayerR.id.subtitle_quick_name))
                    assertTrue("Unfocused text must stop and stay at its initial position", first.sameAs(reset))
                    reset.recycle()
                    first.recycle()
                }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                await(scenario) { it.currentFocus?.id == PlayerR.id.subtitle_control_iv }
                assertEquals(7, selected)
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                await(scenario) { quickRow(it, 8)?.hasFocus() == true }
                captureSubtitle(scenario, "arabic-waiting")
                scenario.onActivity {
                    first = drawView(quickRow(it, 8)!!.findViewById(PlayerR.id.subtitle_quick_name))
                    tick = bounds(quickRow(it, 8)!!.findViewById(PlayerR.id.subtitle_quick_check))
                }
                SystemClock.sleep(1800)
                captureSubtitle(scenario, "arabic-moving")
                scenario.onActivity {
                    val moving = drawView(quickRow(it, 8)!!.findViewById(PlayerR.id.subtitle_quick_name))
                    assertFalse("Focused RTL text must also move", first.sameAs(moving))
                    assertEquals(tick, bounds(quickRow(it, 8)!!.findViewById(PlayerR.id.subtitle_quick_check)))
                    first.recycle()
                    moving.recycle()
                }
                repeat(8) { key(KeyEvent.KEYCODE_DPAD_UP) }
                await(scenario) { quickRow(it, 0)?.hasFocus() == true }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                await(scenario) { it.currentFocus?.id == PlayerR.id.subtitle_control_iv }
                scenario.onActivity { assertFalse(controller.getSubtitleController().isUserSubtitleVisible()) }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                await(scenario) { quickRow(it, 0)?.hasFocus() == true }
                scenario.onActivity { controller.setTrackUpdated(TrackType.SUBTITLE) }
                await(scenario) { quickRow(it, 0)?.hasFocus() == true && quickRow(it, 0)?.isActivated == true }
                captureSubtitle(scenario, "off-selected")
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                scenario.onActivity {
                    assertFalse(controller.getSubtitleController().isUserSubtitleVisible())
                    first = drawView(quickRow(it, 1)!!.findViewById(PlayerR.id.subtitle_quick_name))
                }
                SystemClock.sleep(1400)
                scenario.onActivity {
                    val short = drawView(quickRow(it, 1)!!.findViewById(PlayerR.id.subtitle_quick_name))
                    assertTrue("A short name must stay still while focused", first.sameAs(short))
                    first.recycle()
                    short.recycle()
                }
                captureSubtitle(scenario, "short-focused")
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                await(scenario) { it.currentFocus?.id == PlayerR.id.subtitle_control_iv }
                scenario.onActivity { assertTrue(controller.getSubtitleController().isUserSubtitleVisible()) }
                key(KeyEvent.KEYCODE_BACK)
            } finally {
                scenario.onActivity {
                    quick.attach(original)
                    bottom(it).attach(original)
                }
            }
        }

    private fun quickList(activity: PlayerActivity) = activity.findViewById<RecyclerView>(PlayerR.id.subtitle_quick_list)

    private fun focusPlayerSetting(
        scenario: ActivityScenario<PlayerActivity>,
        label: String
    ) {
        repeat(20) {
            var before: CharSequence? = null
            scenario.onActivity { before = it.currentFocus?.findViewById<TextView>(PlayerR.id.tv_setting)?.text }
            if (before == label) return
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await(scenario) { it.currentFocus?.findViewById<TextView>(PlayerR.id.tv_setting)?.text != before }
        }
        fail("Setting $label was not reachable by DPAD")
    }

    private fun captureSubtitle(
        scenario: ActivityScenario<PlayerActivity>,
        name: String
    ) {
        await(scenario) { (quickList(it).parent as View).alpha == 1f }
        scenario.onActivity { activity ->
            fun rect(view: View): JSONArray = bounds(view).let { JSONArray(listOf(it.left, it.top, it.right, it.bottom)) }
            val list = quickList(activity)
            val rows = JSONArray()
            for (index in 0 until list.childCount) {
                val row = list.getChildAt(index)
                val text = row.findViewById<TextView>(PlayerR.id.subtitle_quick_name)
                rows.put(
                    JSONObject()
                        .put("name", text.text)
                        .put("bounds", rect(row))
                        .put("nameBounds", rect(text))
                        .put("checkBounds", rect(row.findViewById(PlayerR.id.subtitle_quick_check)))
                        .put("focused", row.hasFocus())
                        .put("selected", row.isActivated)
                        .put("lines", text.lineCount)
                        .put("direction", text.layout.getParagraphDirection(0))
                        .put("marqueeSelected", text.isSelected)
                        .put("ellipsize", text.ellipsize.toString())
                        .put("textWidth", text.paint.measureText(text.text.toString()))
                        .put("lineWidth", text.layout.getLineWidth(0))
                        .put("scrollX", text.scrollX)
                )
            }
            File(context.getExternalFilesDir("player-subtitle-quick"), "$name.json").writeText(
                JSONObject()
                    .put("density", activity.resources.displayMetrics.density)
                    .put("fontScale", activity.resources.configuration.fontScale)
                    .put("menuBounds", rect(list.parent as View))
                    .put("listBounds", rect(list))
                    .put("rows", rows)
                    .toString(2)
            )
        }
        screenshot(name, "player-subtitle-quick", 0)
    }

    private fun quickRow(
        activity: PlayerActivity,
        position: Int
    ) = quickList(activity)?.findViewHolderForAdapterPosition(position)?.itemView

    private fun controlWrapper(activity: PlayerActivity) =
        PlayerBottomView::class.java
            .getDeclaredField(
                "mControlWrapper"
            ).apply { isAccessible = true }
            .get(bottom(activity)) as ControlWrapper

    private fun drawView(view: View): Bitmap =
        Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
            val canvas = Canvas(it)
            canvas.translate(-view.scrollX.toFloat(), -view.scrollY.toFloat())
            view.draw(canvas)
        }

    private fun assertDanmakuButton(
        activity: PlayerActivity,
        visible: Boolean,
        focused: Boolean
    ) {
        val button = activity.findViewById<View>(PlayerR.id.danmu_control_iv)
        val label = (button.parent as ViewGroup).getChildAt(1) as TextView
        assertEquals(visible, button.isSelected)
        assertEquals(if (visible) "弹幕开" else "弹幕关", label.text.toString())
        assertEquals(focused, button.hasFocus())
        assertTrue(button.isEnabled && button.isFocusable)
        assertEquals(1f, button.alpha, 0f)
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
                if (id in listOf(PlayerR.id.subtitle_control_iv, PlayerR.id.audio_control)) {
                    key(KeyEvent.KEYCODE_DPAD_CENTER)
                    await(scenario) {
                        activePanel(controller)?.getSettingViewType() ==
                            (if (id == PlayerR.id.subtitle_control_iv) SettingViewType.SUBTITLE_QUICK else SettingViewType.TRACKS) &&
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
            await(scenario) {
                activePanel(controller)?.getSettingViewType() == SettingViewType.SUBTITLE_QUICK &&
                    panel(controller).hasFocus()
            }
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
            key(KeyEvent.KEYCODE_MENU)
            await(scenario) {
                activePanel(controller)?.getSettingViewType() == SettingViewType.PLAYER_SETTING &&
                    panel(controller).hasFocus()
            }
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
                key(KeyEvent.KEYCODE_MENU)
                await(scenario) {
                    activePanel(controller)?.getSettingViewType() == SettingViewType.PLAYER_SETTING &&
                        panel(controller).hasFocus()
                }
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
        directoryName: String = "a06-$phase",
        settleMs: Long = 600
    ) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(settleMs)
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
