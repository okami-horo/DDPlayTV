package com.xyoye.player.controller.setting

import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.MutableLiveData
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.RecyclerView
import com.xyoye.data_component.data.DanmuEpisodeData
import com.xyoye.data_component.enums.SettingViewType
import com.xyoye.player_component.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
@UnstableApi
class SearchDanmuViewTest {
    @Test fun emptyFailureAndLoadingHaveDifferentFeedback() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            val view = SearchDanmuView(activity.get())
            val status = view.findViewById<TextView>(R.id.danmu_status_tv)
            view.renderState(DanmuPanelState(search = DanmuSearchState.Loading("q")))
            assertEquals("正在搜索…", status.text.toString())
            view.renderState(DanmuPanelState(search = DanmuSearchState.Empty("q")))
            assertTrue(status.text.contains("没有找到"))
            view.renderState(DanmuPanelState(search = DanmuSearchState.Error("q", "offline")))
            assertEquals("offline", status.text.toString())
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test fun downloadFailureKeepsPanelAndSelectionUntilAppliedSuccessfully() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            val parent = FrameLayout(activity.get())
            activity.get().setContentView(parent)
            val state = MutableLiveData(DanmuPanelState(search = DanmuSearchState.Content("q", listOf(DanmuEpisodeData(episodeId = "1")))))
            var downloads = 0
            val controller = SettingController(activity.get(), { parent.addView(it.getView()) })
            controller.setDanmuSearch({}, { downloads++ }, { state })
            controller.showSettingView(SettingViewType.SEARCH_DANMU, null)
            val list = parent.findViewById<RecyclerView>(R.id.danmu_rv)
            val adapter = list.adapter as com.xyoye.common_component.adapter.BaseAdapter
            val holder = adapter.createViewHolder(list, adapter.getItemViewType(0))
            adapter.bindViewHolder(holder, 0)
            holder.itemView.performClick()
            assertEquals(1, downloads)
            assertTrue(controller.isSettingViewShowing())
            state.value = state.value!!.copy(downloadError = "download failed")
            assertEquals("download failed", parent.findViewById<TextView>(R.id.danmu_status_tv).text.toString())
            assertEquals(1, adapter.itemCount)
            assertTrue(controller.isSettingViewShowing())
            holder.itemView.performClick()
            assertEquals(2, downloads)
            controller.onDanmuApplied()
            assertFalse(controller.isSettingViewShowing())
        } finally {
            activity.pause().stop().destroy()
        }
    }
}
