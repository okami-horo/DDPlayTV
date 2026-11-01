package com.okamihoro.ddplaytv.ui.tv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.dispose
import coil.load
import com.xyoye.anime_component.utils.loadAnimeCover
import com.xyoye.common_component.extension.grid
import com.xyoye.common_component.extension.loadVideoCover
import com.xyoye.common_component.weight.dialog.FileManagerDialog
import com.xyoye.data_component.enums.FileManagerAction
import com.xyoye.local_component.ui.metadata.MetadataCard
import com.xyoye.local_component.ui.metadata.MetadataCardAdapter
import com.xyoye.user_component.ui.dialog.UserCoverDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import com.xyoye.anime_component.R as AnimeR
import com.xyoye.core_ui_component.R as UiR
import com.xyoye.local_component.R as LocalR
import com.xyoye.storage_component.R as StorageR
import com.xyoye.user_component.R as UserR

/** Production layouts and dialogs, with assertions independent of Android outline clipping. */
@RunWith(AndroidJUnit4::class)
class SharedCardRenderingInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val directory = requireNotNull(instrumentation.targetContext.getExternalFilesDir("card-rendering"))
    private lateinit var activity: TvMainActivity
    private lateinit var root: View

    @Test
    fun homeArtworkAndProgressKeepTheirCornersWithoutSystemOutline() =
        scene(LocalR.layout.item_metadata_continue, 218, 160) {
            val image = root.findViewById<ImageView>(LocalR.id.card_image)
            onMain {
                root.findViewById<TextView>(LocalR.id.card_title).text = "深海来信"
                root.findViewById<android.widget.ProgressBar>(LocalR.id.card_progress).progress = 100
                // Isolate artwork pixels from the focus stroke's antialiasing; Cinema covers focused cards.
                root.isFocusable = false
            }
            for (state in listOf("loaded", "placeholder", "missing", "error")) {
                onMain {
                    image.dispose()
                    when (state) {
                        "loaded" -> image.setImageDrawable(ColorDrawable(Color.MAGENTA))
                        "placeholder" -> image.setImageResource(LocalR.drawable.cinema_media_placeholder)
                        else ->
                            image.load(if (state == "missing") null else File(directory, "missing.png")) {
                                fallback(LocalR.drawable.cinema_media_placeholder)
                                error(LocalR.drawable.cinema_media_placeholder)
                            }
                    }
                }
                settle()
                assertArtwork(image, "home-$state")
            }
        }

    @Test
    fun browserThumbnailClipsLoadedPlaceholderMissingAndErrorArtwork() =
        scene(StorageR.layout.item_file_browser, 500, 52) {
            val image = root.findViewById<ImageView>(StorageR.id.cover_iv)
            onMain {
                image.scaleType = ImageView.ScaleType.CENTER_CROP
                root.requestFocus()
            }
            for (state in listOf("loaded", "placeholder", "missing", "error")) {
                onMain {
                    image.dispose()
                    if (state == "loaded") {
                        image.setImageDrawable(ColorDrawable(Color.MAGENTA))
                    } else if (state == "placeholder") {
                        image.setImageResource(StorageR.drawable.file_browser_placeholder)
                    } else {
                        image.load(if (state == "missing") null else File(directory, "missing.png")) {
                            placeholder(StorageR.drawable.file_browser_placeholder)
                            fallback(StorageR.drawable.file_browser_placeholder)
                            error(StorageR.drawable.file_browser_placeholder)
                        }
                    }
                }
                settle()
                assertArtwork(image, "browser-$state")
            }
        }

    @Test
    fun animeArtworkClipsFollowAndStatusOverlaysInEveryImageState() =
        scene(AnimeR.layout.item_anime_cinema, 270, 162) {
            val image = root.findViewById<ImageView>(AnimeR.id.cover_iv)
            onMain {
                root.findViewById<TextView>(AnimeR.id.anime_name_tv).text = "深海来信"
                root.findViewById<TextView>(AnimeR.id.status_tv).text = "连载中"
                root.requestFocus()
            }
            // Follow/history omit status; recommendations show it. Both may have a followed badge.
            for (withStatus in listOf(false, true)) {
                onMain {
                    root.findViewById<View>(AnimeR.id.follow_tag_view).visibility = View.VISIBLE
                    root.findViewById<View>(AnimeR.id.status_tv).visibility = if (withStatus) View.VISIBLE else View.GONE
                }
                for (state in listOf("loaded", "missing", "error")) {
                    onMain {
                        image.dispose()
                        if (state == "loaded") {
                            image.setImageDrawable(ColorDrawable(Color.MAGENTA))
                        } else {
                            image.loadAnimeCover(if (state == "missing") null else File(directory, "missing.png").toURI().toString())
                        }
                    }
                    settle()
                    assertArtwork(image, "anime-$withStatus-$state")
                }
            }
        }

    @Test
    fun tagFocusAndProblemReadingPanelUseTheirOwnCornerRadii() {
        scene(AnimeR.layout.item_anime, 240, 170) {
            onMain {
                root.findViewById<ImageView>(AnimeR.id.cover_iv).setImageDrawable(ColorDrawable(Color.TRANSPARENT))
                assertTrue(root.requestFocus())
            }
            settle()
            onMain { assertTransparentCorners(root) }
            screenshot("tag-focus")
        }
        scene(LocalR.layout.activity_metadata_match) {
            val panel = root.findViewById<View>(LocalR.id.status_panel)
            onMain {
                root.findViewById<TextView>(LocalR.id.file_name).text = "深海来信 S01E03.mkv"
                root.findViewById<TextView>(LocalR.id.status).text = "资料暂时无法获取"
                root.findViewById<TextView>(LocalR.id.advice).text = "检查来源后，可重新识别或刷新资料。"
                panel.nextFocusDownId = LocalR.id.retry // MetadataMatchActivity chooses the first enabled action.
                assertTrue(panel.requestFocus())
            }
            settle()
            onMain {
                assertTransparentCorners(panel)
                val overlay = requireNotNull(panel.foreground)
                val bitmap = Bitmap.createBitmap(panel.width, panel.height, Bitmap.Config.ARGB_8888)
                overlay.draw(Canvas(bitmap))
                assertEquals("13dp panel corner must stay outside the focus overlay", 0, Color.alpha(bitmap.getPixel(dp(3), dp(3))))
                assertTrue("Focus stroke must remain visible", Color.alpha(bitmap.getPixel(panel.width / 2, 0)) > 0)
                bitmap.recycle()
            }
            screenshot("problem-panel-focus")
            repeat(12) {
                var focused = false
                onMain { focused = root.findViewById<View>(LocalR.id.retry).hasFocus() }
                if (!focused) key(KeyEvent.KEYCODE_DPAD_DOWN)
            }
            onMain { assertTrue(root.findViewById<View>(LocalR.id.retry).hasFocus()) }
            key(KeyEvent.KEYCODE_DPAD_UP)
            onMain { assertTrue(panel.hasFocus()) }
        }
    }

    @Test
    fun videoSummaryKeepsDurationInsideTheArtworkBoundary() =
        scene(UiR.layout.item_storage_video, 500, 76) {
            val image = root.findViewById<ImageView>(UiR.id.cover_iv)
            onMain {
                root.findViewById<TextView>(UiR.id.title_tv).text = "深海来信 S01E03.mkv"
                root.findViewById<TextView>(UiR.id.duration_tv).apply {
                    text = "00:42 / 23:41"
                    visibility = View.VISIBLE
                }
                image.setImageDrawable(ColorDrawable(Color.MAGENTA))
                root.requestFocus()
            }
            settle()
            assertArtwork(image, "history-duration")
            onMain { image.loadVideoCover(File(directory, "missing.png")) }
            settle()
            assertArtwork(image, "history-error")
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            onMain { assertTrue(root.findViewById<View>(UiR.id.more_action_iv).hasFocus()) }
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            onMain { assertTrue(root.hasFocus()) }
        }

    @Test
    fun fileChooserFirstRowKeepsItsHeightSpacingAndDpadSelection() =
        scene(UiR.layout.layout_empty) {
            val folder = File(directory, "subtitles").apply { mkdirs() }
            repeat(10) { File(folder, "字幕${it.toString().padStart(2, '0')}.srt").writeText("fixture") }
            var selected = ""
            lateinit var dialog: FileManagerDialog
            onMain {
                dialog = FileManagerDialog(activity, FileManagerAction.ACTION_SELECT_SUBTITLE, folder.path) { selected = it }
                dialog.show()
                root = requireNotNull(dialog.window).decorView
            }
            settle()
            val list = root.findViewById<RecyclerView>(UiR.id.file_rv)
            onMain {
                assertEquals(10, list.adapter?.itemCount)
                val first = requireNotNull(list.findViewHolderForAdapterPosition(0)).itemView
                val second = requireNotNull(list.findViewHolderForAdapterPosition(1)).itemView
                assertEquals("First row must retain its measured height", first.measuredHeight, first.height)
                assertTrue(first.height >= dp(56))
                assertEquals(first.height, second.height)
                assertEquals("Keep the original 6dp gap", dp(6), second.top - first.bottom)
                assertTrue(first.requestFocus())
            }
            screenshot("file-chooser-first")
            repeat(6) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
            assertFocusedItem(list, 6)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            onMain {
                assertTrue(selected.endsWith("字幕06.srt"))
                assertTrue(!dialog.isShowing)
                root = activity.window.decorView
            }
        }

    @Test
    fun avatarNamesFitAndGridSelectionRemainsReachable() =
        scene(UiR.layout.layout_empty) {
            lateinit var dialog: UserCoverDialog
            var selected = -1
            onMain {
                dialog = UserCoverDialog(activity) { selected = it }
                dialog.show()
                root = requireNotNull(dialog.window).decorView
            }
            settle()
            val list = root.findViewById<RecyclerView>(UserR.id.user_cover_rv)
            onMain { assertTrue(requireNotNull(list.findViewHolderForAdapterPosition(0)).itemView.requestFocus()) }
            screenshot("avatar-first-row")
            repeat(3) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
            assertFocusedItem(list, 3)
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            assertFocusedItem(list, 7)
            screenshot("avatar-second-row")
            onMain {
                for (i in 0 until list.childCount) {
                    val card = list.getChildAt(i)
                    val label = card.findViewById<TextView>(UserR.id.user_cover_tv)
                    assertTrue("Avatar label exceeds its card", label.bottom <= card.height - card.paddingBottom)
                    assertTextFits(label)
                }
            }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            onMain {
                assertEquals(7, selected)
                assertTrue(!dialog.isShowing)
                root = activity.window.decorView
            }
        }

    @Test
    fun recentCardsFitAvailableHeightAcrossFontScaleAndViewportChanges() =
        scene(LocalR.layout.fragment_media) {
            val list = root.findViewById<RecyclerView>(LocalR.id.library_cards)
            var selected = ""
            onMain {
                root.findViewById<View>(LocalR.id.library_count).visibility = View.GONE
                list.layoutManager = list.grid(3)
                list.adapter =
                    MetadataCardAdapter(landscape = true, click = { selected = it.key }).also {
                        it.submitList((0..11).map { n -> MetadataCard("card:$n", "深海来信 $n", "2026 · 剧集 / 番剧", progress = 100) })
                    }
            }
            settle()
            onMain { assertTrue(requireNotNull(list.findViewHolderForAdapterPosition(0)).itemView.requestFocus()) }
            for (height in listOf(-1, dp(480), -1)) {
                onMain { root.layoutParams = root.layoutParams.apply { this.height = height } }
                settle()
                repeat(2) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
                val start = focusedPosition(list)
                assertFocusedItem(list, start)
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                assertFocusedItem(list, start + 3)
                onMain {
                    val card = requireNotNull(list.findContainingItemView(requireNotNull(root.findFocus())))
                    val artwork = card.findViewById<View>(LocalR.id.card_image).parent as View
                    val subtitle = card.findViewById<TextView>(LocalR.id.card_subtitle)
                    val cardBounds = bounds(card)
                    val artworkBounds = bounds(artwork)
                    val inset = artworkBounds.left - cardBounds.left
                    assertTrue("Focus stroke must have space around the content", inset > 0)
                    assertEquals("Focus inset above artwork must match the sides", inset, artworkBounds.top - cardBounds.top)
                    assertEquals("Focus inset must match on both sides", inset, cardBounds.right - artworkBounds.right)
                    assertEquals("Focus inset below metadata must match the artwork", inset, cardBounds.bottom - bounds(subtitle).bottom)
                    assertEquals("Title must align with artwork", artworkBounds.left, bounds(card.findViewById(LocalR.id.card_title)).left)
                    assertEquals("Metadata must align with artwork", artworkBounds.left, bounds(subtitle).left)
                    val outer = card.background.current as GradientDrawable
                    val inner = artwork.background as GradientDrawable
                    assertEquals("Focus and artwork corners must share a center", inner.cornerRadius + inset, outer.cornerRadius, 0.01f)
                    assertTextFits(card.findViewById(LocalR.id.card_title))
                    assertTextFits(subtitle)
                    assertTrue(card.height <= list.height - list.paddingTop - list.paddingBottom)
                    assertTrue(card.findViewById<View>(LocalR.id.card_image).height <= dp(160))
                }
                screenshot("recent-height-$height")
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                assertEquals("card:${focusedPosition(list)}", selected)
                key(KeyEvent.KEYCODE_DPAD_UP)
                repeat(2) { key(KeyEvent.KEYCODE_DPAD_LEFT) }
            }
        }

    private fun scene(
        layout: Int,
        widthDp: Int? = null,
        heightDp: Int? = null,
        test: () -> Unit
    ) {
        instrumentation.setInTouchMode(false)
        ActivityScenario.launch(TvMainActivity::class.java).use { scenario ->
            scenario.onActivity {
                activity = it
                val host = FrameLayout(it).apply { setBackgroundColor(Color.rgb(17, 17, 20)) }
                root = it.layoutInflater.inflate(layout, host, false)
                host.addView(root, FrameLayout.LayoutParams(widthDp?.let(::dp) ?: -1, heightDp?.let(::dp) ?: -1, Gravity.CENTER))
                it.setContentView(host)
            }
            settle()
            try {
                test()
            } catch (failure: Throwable) {
                screenshot("failure-$layout")
                throw failure
            }
        }
    }

    private fun assertArtwork(
        image: ImageView,
        name: String
    ) {
        onMain {
            val artwork = image.parent as View
            assertEquals("Clipping boundary must match artwork width", image.width, artwork.width)
            assertEquals("Clipping boundary must match artwork height", image.height, artwork.height)
            disableOutlines(root)
            assertTransparentCorners(artwork)
        }
        settle()
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val artwork = image.parent as View
        onMain { artwork.visibility = View.INVISIBLE }
        settle()
        val withoutArtwork = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        onMain {
            val rect = bounds(artwork)
            assertEquals(artwork.width, rect.width())
            for ((x, y) in corners(artwork.width, artwork.height)) {
                assertEquals(
                    "Hardware corner must exclude artwork and overlays: $name at (${rect.left + x},${rect.top + y})",
                    withoutArtwork.getPixel(rect.left + x, rect.top + y),
                    screenshot.getPixel(
                        rect.left + x,
                        rect.top + y
                    )
                )
            }
            artwork.visibility = View.VISIBLE
        }
        withoutArtwork.recycle()
        save(screenshot, name)
    }

    private fun assertTransparentCorners(view: View) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        for ((x, y) in corners(bitmap.width, bitmap.height)) {
            assertEquals("Artwork/overlay pixel escapes at ($x,$y)", 0, Color.alpha(bitmap.getPixel(x, y)))
        }
        assertTrue("Interior must remain visible", Color.alpha(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)) > 0)
        bitmap.recycle()
    }

    private fun corners(
        width: Int,
        height: Int
    ) = listOf(0 to 0, (width - 1) to 0, 0 to (height - 1), (width - 1) to (height - 1))

    private fun disableOutlines(view: View) {
        view.clipToOutline = false
        if (view is ViewGroup) for (i in 0 until view.childCount) disableOutlines(view.getChildAt(i))
    }

    private fun assertTextFits(label: TextView) {
        assertTrue(
            "Text height exceeds its view: ${label.text}",
            label.layout.height <= label.height - label.compoundPaddingTop - label.compoundPaddingBottom
        )
    }

    private fun assertFocusedItem(
        list: RecyclerView,
        position: Int
    ) = onMain {
        assertEquals(position, focusedPosition(list))
        val view = requireNotNull(list.findContainingItemView(requireNotNull(root.findFocus())))
        val visible = Rect()
        assertTrue(view.getGlobalVisibleRect(visible))
        val windowOrigin = IntArray(2)
        view.rootView.getLocationOnScreen(windowOrigin)
        visible.offset(windowOrigin[0], windowOrigin[1])
        val parents = StringBuilder()
        var parent = view.parent
        while (parent is ViewGroup) {
            parents.append(
                "${parent.javaClass.simpleName}=${bounds(
                    parent
                )} measured=${parent.measuredHeight} padding=${parent.paddingTop},${parent.paddingBottom}; "
            )
            parent = parent.parent
        }
        assertEquals(
            "Entire focused card must survive ancestor clipping; card=${view.height}/${view.measuredHeight}; $parents",
            bounds(view),
            visible
        )
    }

    private fun focusedPosition(list: RecyclerView): Int {
        var position = -1
        onMain { position = list.findContainingViewHolder(requireNotNull(root.findFocus()))?.bindingAdapterPosition ?: -1 }
        return position
    }

    private fun bounds(view: View): Rect {
        val p = IntArray(2)
        view.getLocationOnScreen(p)
        return Rect(p[0], p[1], p[0] + view.width, p[1] + view.height)
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    private fun key(code: Int) {
        instrumentation.sendKeyDownUpSync(code)
        settle()
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else instrumentation.runOnMainSync(block)
    }

    private fun settle() {
        instrumentation.waitForIdleSync()
        var last = ""
        var stable = 0
        repeat(80) {
            var moving = false
            val signature = StringBuilder()
            onMain {
                fun visit(view: View) {
                    val p = IntArray(2)
                    view.getLocationOnScreen(p)
                    signature.append("${p[0]},${p[1]},${view.width},${view.height},${view.scrollX},${view.scrollY};")
                    if (view is RecyclerView &&
                        (view.scrollState != RecyclerView.SCROLL_STATE_IDLE || view.isComputingLayout || view.isAnimating)
                    ) {
                        moving = true
                    }
                    if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
                }
                visit(root)
            }
            if (!moving && last == signature.toString()) stable++ else stable = 0
            if (stable >= 4) return
            last = signature.toString()
            SystemClock.sleep(75)
        }
        throw AssertionError("Card rendering did not settle")
    }

    private fun screenshot(name: String) = save(requireNotNull(instrumentation.uiAutomation.takeScreenshot()), name)

    private fun save(
        bitmap: Bitmap,
        name: String
    ) {
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
