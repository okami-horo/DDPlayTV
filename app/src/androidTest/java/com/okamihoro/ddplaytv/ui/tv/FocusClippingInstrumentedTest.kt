package com.okamihoro.ddplaytv.ui.tv

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xyoye.common_component.extension.grid
import com.xyoye.common_component.extension.horizontal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import com.xyoye.core_ui_component.R as UiR
import com.xyoye.local_component.R as LocalR
import com.xyoye.player_component.R as PlayerR

/** Uses production layouts with deterministic data, independent of accounts and network results. */
@RunWith(AndroidJUnit4::class)
class FocusClippingInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: TvMainActivity
    private lateinit var root: View
    private val longName = "动画作品归档目录_2026_第一季_完整版_国语日语双音轨_内封中文字幕_1080p"

    @Test
    fun mediaGridOuterColumnsKeepTheirFocusBorder() =
        scene(LocalR.layout.fragment_media) {
            val list = root.findViewById<RecyclerView>(LocalR.id.library_cards)
            onMain {
                root.findViewById<View>(LocalR.id.library_count).visibility = View.GONE
                list.layoutManager = list.grid(3)
                list.adapter = Rows(LocalR.layout.item_metadata_landscape, 9)
            }
            focus(list, 0)
            repeat(2) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
            assertPosition(list, 2)
            screenshot("media-right")
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            assertPosition(list, 5)
            repeat(2) { key(KeyEvent.KEYCODE_DPAD_LEFT) }
            assertPosition(list, 3)
            screenshot("media-left")
        }

    @Test
    fun fileDialogLongBreadcrumbRemainsInsideViewport() =
        scene(UiR.layout.dialog_file_manager, 560) {
            val list = root.findViewById<RecyclerView>(UiR.id.path_rv)
            onMain {
                list.layoutManager = list.horizontal()
                list.adapter = Rows(UiR.layout.item_file_manager_path, 3, longName)
            }
            focus(list, 0)
            repeat(2) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
            assertPosition(list, 2)
            screenshot("file-dialog-long-path")
        }

    @Test
    fun playerLongBreadcrumbRemainsInsideViewport() =
        scene(PlayerR.layout.layout_switch_source) {
            val list = root.findViewById<RecyclerView>(PlayerR.id.rv_path)
            onMain {
                root.translationX = 0f // The player animates the panel into this position when opened.
                root.findViewById<TextView>(PlayerR.id.title_tv).text = "选择字幕轨文件"
                root.findViewById<RecyclerView>(PlayerR.id.rv_common_folder).apply {
                    layoutManager = horizontal()
                    adapter = Rows(PlayerR.layout.item_file_manager_folder, 3)
                }
                list.layoutManager = list.horizontal()
                list.adapter = Rows(PlayerR.layout.item_file_manager_folder, 3, longName)
            }
            focus(list, 0)
            repeat(2) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
            assertPosition(list, 2)
            screenshot("player-long-path")
        }

    private fun scene(
        layout: Int,
        widthDp: Int? = null,
        test: () -> Unit
    ) {
        instrumentation.setInTouchMode(false)
        ActivityScenario.launch(TvMainActivity::class.java).use { scenario ->
            scenario.onActivity {
                activity = it
                val host = FrameLayout(it)
                root = it.layoutInflater.inflate(layout, host, false)
                if (widthDp == null) {
                    host.addView(root)
                } else {
                    host.addView(
                        root,
                        FrameLayout.LayoutParams(
                            (widthDp * it.resources.displayMetrics.density).toInt(),
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            Gravity.CENTER
                        )
                    )
                }
                it.setContentView(host)
            }
            settle()
            test()
        }
    }

    private fun focus(
        list: RecyclerView,
        position: Int
    ) {
        settle()
        onMain {
            assertTrue(requireNotNull(list.findViewHolderForAdapterPosition(position)).itemView.requestFocus())
        }
        settle()
        assertPosition(list, position)
    }

    private fun assertPosition(
        list: RecyclerView,
        position: Int
    ) = onMain {
        val focused = requireNotNull(activity.currentFocus)
        assertEquals(
            "DPAD should reach the expected item",
            position,
            list.findContainingViewHolder(focused)?.bindingAdapterPosition
        )
        assertFullyVisible(focused)
    }

    private fun assertFullyVisible(view: View) {
        val original = bounds(view)
        assertTrue("Focused view must have a nonzero viewport", view.isShown && !original.isEmpty)
        val visible = Rect(original)
        var parent = view.parent
        while (parent is ViewGroup) {
            val clip = bounds(parent)
            if (parent.clipToPadding && parent.paddingLeft + parent.paddingRight + parent.paddingTop + parent.paddingBottom > 0) {
                clip.left += parent.paddingLeft
                clip.right -= parent.paddingRight
                clip.top += parent.paddingTop
                clip.bottom -= parent.paddingBottom
            } else if (!parent.clipChildren) {
                parent = parent.parent
                continue
            }
            if (!visible.intersect(clip)) visible.setEmpty()
            parent = parent.parent
        }
        assertEquals("Focused bounds must survive ancestor clipping", original, visible)
    }

    private fun bounds(view: View): Rect {
        val point = IntArray(2)
        view.getLocationOnScreen(point)
        return Rect(point[0], point[1], point[0] + view.width, point[1] + view.height)
    }

    private fun key(code: Int) {
        instrumentation.sendKeyDownUpSync(code)
        settle()
    }

    private fun settle() {
        SystemClock.sleep(350)
        repeat(30) {
            var scrolling = false
            onMain {
                fun inspect(view: View) {
                    if (view is RecyclerView && view.scrollState != RecyclerView.SCROLL_STATE_IDLE) scrolling = true
                    if (view is ViewGroup) for (i in 0 until view.childCount) inspect(view.getChildAt(i))
                }
                inspect(root)
            }
            if (!scrolling) return
            SystemClock.sleep(50)
        }
        throw AssertionError("Lists did not stop scrolling")
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun screenshot(name: String) {
        val directory = requireNotNull(activity.getExternalFilesDir("focus-clipping"))
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private inner class Rows(
        private val layout: Int,
        private val count: Int,
        private val label: String = "测试内容"
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = count

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int
        ) = object : RecyclerView.ViewHolder(activity.layoutInflater.inflate(layout, parent, false)) {}

        override fun onBindViewHolder(
            holder: RecyclerView.ViewHolder,
            position: Int
        ) {
            fun fill(view: View) {
                if (view is TextView) {
                    view.text = "$label ${position + 1}"
                    if (view.id == PlayerR.id.tv_name) view.textSize = 14f
                }
                if (view is ViewGroup) for (i in 0 until view.childCount) fill(view.getChildAt(i))
            }
            fill(holder.itemView)
        }
    }
}
