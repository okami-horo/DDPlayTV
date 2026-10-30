package com.okamihoro.ddplaytv.ui.anime

import android.view.KeyEvent
import android.view.View
import android.view.inspector.WindowInspector
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.xyoye.anime_component.ui.fragment.search_magnet.SearchMagnetFragment
import com.xyoye.anime_component.ui.fragment.search_magnet.SearchMagnetFragmentViewModel
import com.xyoye.data_component.data.MagnetScreenData
import com.xyoye.data_component.data.MagnetTypeData
import com.xyoye.user_component.ui.activities.about_us.AboutUsActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class SearchDialogLifecycleInstrumentedTest {
    @Test
    fun consumedCategoryDialogDoesNotReopenWhenTheSameFragmentCreatesANewView() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.setInTouchMode(false)
        var loads = 0
        val viewModel =
            SearchMagnetFragmentViewModel(
                typeRequest = {
                    loads++
                    Result.success(MagnetTypeData(mutableListOf(MagnetScreenData(1, "Local category"))))
                },
                domain = { "https://fixture.invalid" }
            )
        val fragment = SearchMagnetFragment.newInstance(null)
        ActivityScenario.launch(AboutUsActivity::class.java).use { scenario ->
            lateinit var activity: AboutUsActivity
            lateinit var firstView: View
            scenario.onActivity {
                activity = it
                FragmentViewLifecycleFixture.mount(it, fragment, viewModel)
                firstView = fragment.requireView()
                viewModel.getMagnetType()
            }
            FragmentViewLifecycleFixture.await("The requested local category dialog is shown") {
                categoryList()?.adapter?.itemCount == 1
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            FragmentViewLifecycleFixture.await("BACK consumes the displayed category dialog") {
                activity.hasWindowFocus() && categoryList() == null
            }
            scenario.onActivity {
                val firstOwner = fragment.viewLifecycleOwner
                it.supportFragmentManager
                    .beginTransaction()
                    .detach(fragment)
                    .commitNow()
                assertEquals(Lifecycle.State.DESTROYED, firstOwner.lifecycle.currentState)
                it.supportFragmentManager
                    .beginTransaction()
                    .attach(fragment)
                    .commitNow()
                assertNotSame(firstView, fragment.requireView())
                assertSame(viewModel, ViewModelProvider(fragment)[SearchMagnetFragmentViewModel::class.java])
            }
            FragmentViewLifecycleFixture.await("A rebuilt View does not replay the consumed dialog request") {
                fragment.lifecycle.currentState == Lifecycle.State.RESUMED && activity.hasWindowFocus()
            }
            scenario.onActivity {
                assertFalse("Cached filters must not open a dialog by observing the new View", categoryList() != null)
                assertEquals(1, loads)
                viewModel.getMagnetType()
            }
            FragmentViewLifecycleFixture.await("A new explicit request may reuse cached categories") {
                categoryList()?.adapter?.itemCount == 1
            }
            scenario.onActivity { assertEquals("View recreation must retain the category cache", 1, loads) }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            FragmentViewLifecycleFixture.await("The second explicit dialog closes normally") {
                activity.hasWindowFocus() && categoryList() == null
            }
        }
    }

    private fun categoryList(): RecyclerView? =
        WindowInspector
            .getGlobalWindowViews()
            .firstOrNull { it.hasWindowFocus() }
            ?.findViewById(com.xyoye.anime_component.R.id.screen_rv)
}
