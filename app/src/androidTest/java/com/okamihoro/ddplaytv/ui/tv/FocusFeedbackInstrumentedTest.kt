package com.okamihoro.ddplaytv.ui.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alibaba.android.arouter.launcher.ARouter
import com.okamihoro.ddplaytv.R
import com.okamihoro.ddplaytv.playback.withIndexedPlaybackFile
import com.rubensousa.dpadrecyclerview.DpadRecyclerView
import com.xyoye.common_component.config.PlayerConfig
import com.xyoye.common_component.playback.handoff.PlaybackNavigation
import com.xyoye.common_component.playback.handoff.PlaybackRequestInput
import com.xyoye.common_component.playback.handoff.PlaybackRequestOrigin
import com.xyoye.common_component.playback.handoff.PlaybackSubmissionResult
import com.xyoye.common_component.services.PlaybackRequestService
import com.xyoye.data_component.bean.VideoTrackBean
import com.xyoye.data_component.enums.PlayerType
import com.xyoye.player.DanDanVideoPlayer
import com.xyoye.player.controller.VideoController
import com.xyoye.player_component.ui.activities.player.PlayerActivity
import com.xyoye.subtitle.format.FormatFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import com.xyoye.core_ui_component.R as UiR
import com.xyoye.local_component.R as LocalR
import com.xyoye.player_component.R as PlayerR

@RunWith(AndroidJUnit4::class)
class FocusFeedbackInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val enabled = android.R.attr.state_enabled
    private val focused = android.R.attr.state_focused
    private val selected = android.R.attr.state_selected
    private val pressed = android.R.attr.state_pressed

    @Before fun remote() {
        instrumentation.setInTouchMode(false)
    }

    @Test fun primaryAndListSelectorsKeepContinuousFillAndPressedFocus() {
        val primary = render(UiR.drawable.cinema_primary, enabled, focused)
        val normal = render(UiR.drawable.cinema_primary, enabled)
        val y = primary.height / 2
        assertEquals("Ordinary primary retains its original inset", 0, Color.alpha(normal.getPixel(dp(3), y)))
        for (x in 1 until primary.width - 1) {
            assertEquals("Focused primary has no transparent gap at $x", 255, Color.alpha(primary.getPixel(x, y)))
        }
        for (resource in listOf(
            UiR.drawable.cinema_button,
            PlayerR.drawable.background_player_setting_text,
            PlayerR.drawable.background_player_setting_text_transparent
        )) {
            val focus = render(resource, enabled, focused)
            val both = render(resource, enabled, focused, selected)
            val down = render(resource, enabled, focused, selected, pressed)
            val chosen = render(resource, enabled, selected)
            val ordinary = render(resource, enabled)
            val unavailable = render(resource, focused, selected, pressed)
            val mid = focus.height / 2
            val edge = focus.getPixel(dp(1), mid)
            val fill = focus.getPixel(dp(8), mid)
            assertTrue("Focus edge must contrast with the fill", ColorUtils.calculateContrast(edge, fill) > 4.5)
            assertEquals("Focus edge ends inside the first 3dp", fill, focus.getPixel(dp(3), mid))
            assertEquals("Selection must not add a second focus edge", edge, both.getPixel(dp(1), mid))
            assertEquals("Pressing keeps the focus edge", edge, down.getPixel(dp(1), mid))
            assertNotEquals("Pressing has visible feedback", fill, down.getPixel(dp(8), mid))
            assertEquals("Unfocused selection has no extra edge", chosen.getPixel(dp(8), mid), chosen.getPixel(dp(1), mid))
            assertTrue("Unavailable controls cannot retain focus emphasis", ordinary.sameAs(unavailable))
            listOf(focus, both, down, chosen, ordinary, unavailable).forEach(Bitmap::recycle)
        }
        val transparent = render(PlayerR.drawable.background_player_setting_text_transparent, enabled)
        assertEquals("Ordinary episode rows remain transparent", 0, Color.alpha(transparent.getPixel(dp(8), y)))
        listOf(primary, normal, transparent).forEach(Bitmap::recycle)
    }

    @Test fun navigationFocusMovesWithoutChangingTheSelectedPage() {
        ActivityScenario.launch(TvMainActivity::class.java).use { scenario ->
            lateinit var activity: TvMainActivity
            lateinit var nav: DpadRecyclerView
            scenario.onActivity {
                activity = it
                nav = it.findViewById(R.id.tv_nav_rv)
                nav.requestFocus()
                nav.setSelectedPosition(0)
            }
            await(scenario) { nav.findViewHolderForAdapterPosition(0)?.itemView?.hasFocus() == true }

            fun item(position: Int) = requireNotNull(nav.findViewHolderForAdapterPosition(position)).itemView as TextView
            scenario.onActivity {
                assertTrue(item(0).isSelected)
                assertReadable(item(0))
                assertMarker(item(0), dark = true)
            }
            screenshot("navigation-focused-selected")
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            scenario.onActivity {
                assertTrue(item(1).hasFocus())
                assertTrue(item(0).isSelected)
                assertFalse(item(1).isSelected)
                assertReadable(item(0))
                assertReadable(item(1))
                assertMarker(item(0), dark = false)
            }
            screenshot("navigation-selection-away")
            instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
            scenario.onActivity {
                assertTrue(item(1).hasFocus())
                assertTrue(item(0).isSelected)
                assertTrue(item(1).isPressed)
                assertReadable(item(1))
            }
            instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
            await(scenario) { item(1).isSelected && !item(0).isSelected }
            repeat(20) {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
            }
            await(scenario) { item(1).hasFocus() }
            scenario.onActivity {
                assertTrue(item(1).isSelected)
                assertMarker(item(1), dark = true)
                val visible = Rect()
                assertTrue(item(1).getGlobalVisibleRect(visible))
                assertEquals(item(1).width, visible.width())
                assertEquals(item(1).height, visible.height())
            }
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            await(scenario) { !nav.hasFocus() && activity.currentFocus?.isShown == true }
            key(KeyEvent.KEYCODE_BACK)
            await(scenario) { nav.hasFocus() }
        }
    }

    @Test fun inflatedSeasonTabsKeepContrastAndContentPositionAcrossFocus() {
        ActivityScenario.launch(TvMainActivity::class.java).use { scenario ->
            lateinit var first: TextView
            lateinit var second: TextView
            scenario.onActivity { activity ->
                val host =
                    LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER
                        setBackgroundColor(Color.rgb(17, 17, 20))
                    }
                first = activity.layoutInflater.inflate(LocalR.layout.item_metadata_season, host, false) as TextView
                second = activity.layoutInflater.inflate(LocalR.layout.item_metadata_season, host, false) as TextView
                first.text = "第 1 季"
                second.text = "第 2 季"
                first.isSelected = true
                host.addView(first)
                host.addView(second)
                activity.setContentView(host)
                first.requestFocus()
            }
            await(scenario) { first.hasFocus() && first.width > 0 }
            var baseline = 0
            var padding = 0
            scenario.onActivity {
                baseline = first.baseline
                padding = first.paddingBottom
                assertReadable(first)
                assertMarker(first, dark = true)
            }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            scenario.onActivity {
                assertTrue(second.hasFocus())
                assertTrue(first.isSelected)
                assertFalse(second.isSelected)
                assertEquals(baseline, first.baseline)
                assertEquals(padding, first.paddingBottom)
                assertReadable(first)
                assertReadable(second)
                assertMarker(first, dark = false)
            }
            screenshot("season-selection-away")
        }
    }

    private fun assertMarker(
        view: TextView,
        dark: Boolean
    ) {
        val bitmap =
            render(
                view.background.constantState!!
                    .newDrawable()
                    .mutate(),
                view.drawableState,
                view.width,
                view.height
            )
        val marker = bitmap.getPixel(bitmap.width / 2, bitmap.height - dp(4))
        val fill = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        assertTrue("Selected marker remains distinct from focus", ColorUtils.calculateContrast(marker, fill) > 4.5)
        assertEquals(dark, ColorUtils.calculateLuminance(marker) < ColorUtils.calculateLuminance(fill))
        bitmap.recycle()
    }

    @Test fun subtitleSelectionSurvivesFocusAndDisableRemainsAnAction() {
        val directory = requireNotNull(context.getExternalFilesDir("a02-focus"))
        val file = File(directory, "focus-playback.mp4")
        val subtitle = File(directory, "focus-subtitle.srt")
        File("/sdcard/Download/ddplaytv-lease-playback-long.mp4").copyTo(file, overwrite = true)
        subtitle.writeText("1\n00:00:00,000 --> 00:01:50,000\nA02 subtitle fixture\n\n")
        assertFalse(
            FormatFactory
                .findFormat(subtitle.path)!!
                .parseFile(subtitle)
                .captions
                .isEmpty()
        )
        val oldKernel = PlayerConfig.getUsePlayerType()
        PlayerConfig.putUsePlayerType(PlayerType.TYPE_EXO_PLAYER.value)
        try {
            withIndexedPlaybackFile(file) { descriptor ->
                val service = ARouter.getInstance().navigation(PlaybackRequestService::class.java)
                val request =
                    runBlocking {
                        service.submit(PlaybackRequestInput(descriptor, null, PlaybackRequestOrigin.STORAGE_FILE))
                    } as PlaybackSubmissionResult.Accepted
                ActivityScenario
                    .launch<PlayerActivity>(
                        Intent(context, PlayerActivity::class.java)
                            .putExtra(PlaybackNavigation.REQUEST_ID, request.requestId)
                    ).use { scenario ->
                        fun player(activity: PlayerActivity) =
                            activity.findViewById<FrameLayout>(PlayerR.id.player_container).getChildAt(0) as DanDanVideoPlayer

                        fun tracks(activity: PlayerActivity) = activity.findViewById<RecyclerView>(PlayerR.id.subtitle_quick_list)

                        fun row(
                            activity: PlayerActivity,
                            position: Int
                        ) = tracks(activity).findViewHolderForAdapterPosition(position)?.itemView
                        lateinit var controller: VideoController
                        await(scenario, 20000) { player(it).isPlaying() && it.hasWindowFocus() }
                        key(KeyEvent.KEYCODE_DPAD_DOWN)
                        await(scenario) { it.findViewById<View>(PlayerR.id.play_iv).isShown }
                        scenario.onActivity { it.findViewById<View>(PlayerR.id.subtitle_control_iv).requestFocus() }
                        key(KeyEvent.KEYCODE_DPAD_CENTER)
                        await(scenario) { tracks(it)?.adapter?.itemCount == 1 && row(it, 0)?.hasFocus() == true }
                        screenshot("subtitle-empty")
                        key(KeyEvent.KEYCODE_BACK)
                        await(scenario) { it.currentFocus?.id == PlayerR.id.subtitle_control_iv }
                        scenario.onActivity {
                            controller =
                                PlayerActivity::class.java
                                    .getDeclaredMethod("getVideoController")
                                    .apply { isAccessible = true }
                                    .invoke(it) as VideoController
                            assertTrue(controller.addExtendTrack(VideoTrackBean.subtitle(subtitle.path)))
                        }
                        key(KeyEvent.KEYCODE_DPAD_CENTER)
                        await(scenario) { tracks(it).adapter?.itemCount == 2 && row(it, 1)?.hasFocus() == true }
                        key(KeyEvent.KEYCODE_DPAD_UP)
                        scenario.onActivity {
                            val disable = requireNotNull(row(it, 0))
                            assertTrue(disable.isEnabled)
                            assertTrue(disable.isFocusable)
                            assertTrue(disable.hasFocus())
                            assertFalse(disable.isActivated)
                            assertTrue(row(it, 1)!!.isActivated)
                        }
                        screenshot("subtitle-selected-away")
                        key(KeyEvent.KEYCODE_DPAD_CENTER)
                        await(scenario) { it.currentFocus?.id == PlayerR.id.subtitle_control_iv }
                        scenario.onActivity { assertFalse(controller.getSubtitleController().isUserSubtitleVisible()) }
                        key(KeyEvent.KEYCODE_DPAD_CENTER)
                        await(scenario) { row(it, 0)?.hasFocus() == true && row(it, 0)?.isActivated == true }
                        screenshot("subtitle-disabled-selected")
                        key(KeyEvent.KEYCODE_DPAD_DOWN)
                        scenario.onActivity {
                            assertTrue(row(it, 0)!!.isActivated)
                            assertFalse(row(it, 1)!!.isActivated)
                            assertFalse(controller.getSubtitleController().isUserSubtitleVisible())
                        }
                        key(KeyEvent.KEYCODE_BACK)
                        await(scenario) { it.currentFocus?.id == PlayerR.id.subtitle_control_iv }
                        scenario.onActivity { assertFalse(controller.getSubtitleController().isUserSubtitleVisible()) }
                        key(KeyEvent.KEYCODE_DPAD_CENTER)
                        await(scenario) { row(it, 0)?.hasFocus() == true }
                        key(KeyEvent.KEYCODE_DPAD_DOWN)
                        key(KeyEvent.KEYCODE_DPAD_CENTER)
                        await(scenario) { it.currentFocus?.id == PlayerR.id.subtitle_control_iv }
                        scenario.onActivity { assertTrue(controller.getSubtitleController().isUserSubtitleVisible()) }
                        key(KeyEvent.KEYCODE_DPAD_CENTER)
                        await(scenario) { row(it, 1)?.hasFocus() == true && row(it, 1)?.isActivated == true }
                        screenshot("subtitle-focused-selected")
                        key(KeyEvent.KEYCODE_BACK)
                        await(scenario) { it.currentFocus?.id == PlayerR.id.subtitle_control_iv }
                    }
            }
        } finally {
            PlayerConfig.putUsePlayerType(oldKernel)
            file.delete()
            subtitle.delete()
        }
    }

    @Test fun trackCheckKeepsItsDrawingSpaceWhenInflatedUnselected() {
        ActivityScenario.launch(TvMainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val row = activity.layoutInflater.inflate(PlayerR.layout.item_setting_tracks, null) as TextView
                val check = requireNotNull(row.compoundDrawablesRelative[2])
                assertTrue("The check must reserve drawable space before selection", check.bounds.width() > 0 && check.bounds.height() > 0)
                for (chosen in listOf(false, true, false)) {
                    row.isSelected = chosen
                    val bitmap = render(check, row.drawableState, check.bounds.width(), check.bounds.height())
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    assertEquals("Only the actual selection draws a check", chosen, pixels.any { Color.alpha(it) > 0 })
                    bitmap.recycle()
                }
            }
        }
    }

    private fun assertReadable(view: TextView) {
        val bitmap =
            render(
                view.background.constantState!!
                    .newDrawable()
                    .mutate(),
                view.drawableState,
                view.width,
                view.height
            )
        assertTrue(
            "Text must contrast with its actual state fill",
            ColorUtils.calculateContrast(
                view.currentTextColor,
                bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
            ) >= 4.5
        )
        bitmap.recycle()
    }

    private fun render(
        resource: Int,
        vararg state: Int
    ) = render(context.getDrawable(resource)!!.mutate(), state, dp(160), dp(46))

    private fun render(
        drawable: android.graphics.drawable.Drawable,
        state: IntArray,
        width: Int,
        height: Int
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        drawable.state = state
        drawable.setBounds(0, 0, width, height)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private fun key(code: Int) {
        instrumentation.sendKeyDownUpSync(code)
        instrumentation.waitForIdleSync()
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(context.getExternalFilesDir("a02-focus"), "$name.png").outputStream().use {
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
        while (!ready && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { ready = predicate(it) }
            if (!ready) SystemClock.sleep(30)
        }
        assertTrue("Focus state did not settle", ready)
    }
}
