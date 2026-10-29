package com.xyoye.common_component.focus

import android.app.Application
import android.app.UiModeManager
import android.content.res.Configuration
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.preference.EditTextPreference
import com.rubensousa.dpadrecyclerview.DpadRecyclerView
import com.xyoye.common_component.base.BasePreferenceFragmentCompat
import com.xyoye.core_ui_component.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TvUiDefaultsTest {
    @Test
    fun actionListUsesDpadRecyclerViewWithoutTelevisionMode() {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(R.style.AppTheme)
        val activity = controller.setup().get()
        try {
            assertNotEquals(
                Configuration.UI_MODE_TYPE_TELEVISION,
                activity.getSystemService(UiModeManager::class.java).currentModeType
            )
            activity.setContentView(R.layout.dialog_bottom_action)

            assertTrue(activity.findViewById<View>(R.id.content_rv) is DpadRecyclerView)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun preferenceListUsesDpadRecyclerViewWithoutTelevisionMode() {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(R.style.AppTheme)
        val activity = controller.setup().get()
        try {
            assertNotEquals(
                Configuration.UI_MODE_TYPE_TELEVISION,
                activity.getSystemService(UiModeManager::class.java).currentModeType
            )
            val container = FrameLayout(activity).apply { id = View.generateViewId() }
            activity.setContentView(container)
            val fragment = TestPreferencesFragment()
            activity.supportFragmentManager
                .beginTransaction()
                .add(container.id, fragment)
                .commitNow()

            assertTrue(fragment.listView is DpadRecyclerView)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun listBackgroundsExposeFocusedFeedbackWithoutTelevisionResources() {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(R.style.AppTheme)
        val activity = controller.setup().get()
        try {
            val backgrounds =
                intArrayOf(
                    R.drawable.background_focus_overlay,
                    R.drawable.background_item_press,
                    R.drawable.background_item_press_corner,
                    R.drawable.background_storage_item_icon_selector,
                    R.drawable.background_storage_item_selector,
                    R.drawable.background_storage_path_selector,
                    R.drawable.background_tab_press
                )
            backgrounds.forEach { resourceId ->
                val drawable = ContextCompat.getDrawable(activity, resourceId) as StateListDrawable
                val hasFocusedState =
                    (0 until drawable.stateCount).any { index ->
                        drawable.getStateSet(index).contains(android.R.attr.state_focused)
                    }

                assertTrue(hasFocusedState)
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun preferenceInputWaitsForExplicitActivationWithoutTelevisionMode() {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(R.style.AppTheme)
        val activity = controller.setup().get()
        try {
            val container = FrameLayout(activity).apply { id = View.generateViewId() }
            activity.setContentView(container)
            val fragment = TestPreferencesFragment()
            activity.supportFragmentManager
                .beginTransaction()
                .add(container.id, fragment)
                .commitNow()
            val preference =
                EditTextPreference(activity).apply {
                    key = "input"
                    isPersistent = false
                    text = "existing value"
                }
            fragment.preferenceScreen.addPreference(preference)

            fragment.onDisplayPreferenceDialog(preference)
            activity.supportFragmentManager.executePendingTransactions()
            val dialog =
                activity.supportFragmentManager.fragments
                    .filterIsInstance<DialogFragment>()
                    .single()
            val editText = dialog.requireDialog().findViewById<EditText>(android.R.id.edit)

            assertFalse(editText.showSoftInputOnFocus)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    class TestPreferencesFragment : BasePreferenceFragmentCompat() {
        override fun onCreatePreferences(
            savedInstanceState: Bundle?,
            rootKey: String?
        ) {
            preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
        }
    }
}
