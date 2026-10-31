package com.xyoye.anime_component.ui.activities.anime_history

import android.view.inputmethod.EditorInfo
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import com.alibaba.android.arouter.facade.annotation.Autowired
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.xyoye.anime_component.BR
import com.xyoye.anime_component.R
import com.xyoye.anime_component.databinding.ActivityAnimeHistoryBinding
import com.xyoye.anime_component.ui.adapter.AnimeCinemaAdapter
import com.xyoye.common_component.base.BaseActivity
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.extension.collectAtStarted
import com.xyoye.common_component.extension.grid
import com.xyoye.common_component.extension.setData
import com.xyoye.common_component.utils.hideKeyboard
import com.xyoye.data_component.data.CloudHistoryListData

@Route(path = RouteTable.Anime.AnimeHistory)
class AnimeHistoryActivity : BaseActivity<AnimeHistoryViewModel, ActivityAnimeHistoryBinding>() {
    @Autowired
    @JvmField
    var historyData: CloudHistoryListData? = null

    override fun initViewModel() = ViewModelInit(BR.viewModel, AnimeHistoryViewModel::class.java)

    override fun getLayoutId() = R.layout.activity_anime_history

    override fun initView() {
        ARouter.getInstance().inject(this)
        title = "云端播放历史"
        dataBinding.historyRv.apply {
            layoutManager = grid(3)
            adapter = AnimeCinemaAdapter.create(this@AnimeHistoryActivity)
        }
        dataBinding.searchEt.setText(viewModel.state.value.keyword)
        dataBinding.searchEt.addTextChangedListener { viewModel.searchAnime(it.toString()) }
        dataBinding.searchEt.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard(dataBinding.searchEt)
                if (dataBinding.historyRv.isVisible) dataBinding.historyRv.requestFocus() else dataBinding.clearBt.requestFocus()
                true
            } else {
                false
            }
        }
        dataBinding.clearBt.setOnClickListener { dataBinding.searchEt.setText("") }
        dataBinding.refreshBt.setOnClickListener { viewModel.getCloudHistory() }
        viewModel.initialize(historyData?.playHistoryAnimes)
        viewModel.state.collectAtStarted(this) { state ->
            val items = state.visibleItems
            val empty = items.isEmpty()
            if (empty && dataBinding.historyRv.hasFocus()) dataBinding.clearBt.requestFocus()
            dataBinding.historyRv.isVisible = !empty
            dataBinding.historyRv.setData(items)
            dataBinding.statusTv.text = state.summary
            dataBinding.emptyTv.isVisible = empty
            dataBinding.emptyTitleTv.text = state.emptyTitle
            dataBinding.emptyMessageTv.text = state.emptyDescription
            dataBinding.refreshBt.text = if (state.loading) "加载中" else "刷新"
        }
        dataBinding.refreshBt.requestFocus()
    }
}
