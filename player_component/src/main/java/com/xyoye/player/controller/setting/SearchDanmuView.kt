package com.xyoye.player.controller.setting

import android.content.Context
import android.util.AttributeSet
import android.view.inputmethod.EditorInfo
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import com.xyoye.common_component.adapter.addItem
import com.xyoye.common_component.adapter.buildAdapter
import com.xyoye.common_component.adapter.setupDiffUtil
import com.xyoye.common_component.extension.setData
import com.xyoye.common_component.extension.vertical
import com.xyoye.common_component.utils.hideKeyboard
import com.xyoye.data_component.data.DanmuEpisodeData
import com.xyoye.data_component.enums.SettingViewType
import com.xyoye.data_component.enums.TrackType
import com.xyoye.player_component.R
import com.xyoye.player_component.databinding.ItemSearchDanmuBinding
import com.xyoye.player_component.databinding.LayoutSearchDanmuBinding

class SearchDanmuView(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BaseSettingView<LayoutSearchDanmuBinding>(context, attrs, defStyleAttr) {
    private var search: ((String) -> Unit)? = null
    private var download: ((DanmuEpisodeData) -> Unit)? = null
    private val mSearchDanmuData = mutableListOf<DanmuEpisodeData>()

    init {
        initRv()

        initListener()
    }

    override fun getLayoutId() = R.layout.layout_search_danmu

    override fun getSettingViewType() = SettingViewType.SEARCH_DANMU

    private fun initRv() {
        viewBinding.danmuRv.apply {
            layoutManager = vertical()

            adapter =
                buildAdapter {
                    setupDiffUtil {
                        areItemsTheSame { old, new -> (old as DanmuEpisodeData).episodeId == (new as DanmuEpisodeData).episodeId }
                    }
                    addItem<DanmuEpisodeData, ItemSearchDanmuBinding>(R.layout.item_search_danmu) {
                        initView { data, position, _ ->
                            val positionText = (position + 1).toString()
                            itemBinding.positionTv.text = positionText
                            itemBinding.episodeTv.text = data.episodeTitle
                            itemBinding.animeTv.text = data.animeTitle
                            itemBinding.itemLayout.setOnClickListener {
                                download(data)
                            }
                        }
                    }
                }
        }
    }

    private fun initListener() {
        viewBinding.panelClose.setOnClickListener { mControlWrapper.hideSettingView() }
        viewBinding.tvSelectLocalDanmu.setOnClickListener {
            mControlWrapper.showSettingView(SettingViewType.SWITCH_SOURCE, TrackType.DANMU)
        }

        viewBinding.searchDanmuEt.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                search()
                return@setOnEditorActionListener true
            }
            return@setOnEditorActionListener false
        }

        viewBinding.searchDanmuTv.setOnClickListener {
            search()
        }
    }

    private fun search() {
        hideKeyboard(viewBinding.searchDanmuEt)
        viewBinding.searchDanmuCl.requestFocus()
        val searchText =
            viewBinding.searchDanmuEt.text
                .toString()
                .trim()
        search?.invoke(searchText)
    }

    private fun download(data: DanmuEpisodeData) {
        download?.invoke(data)
    }

    fun setDanmuSearch(
        search: (String) -> Unit,
        download: (DanmuEpisodeData) -> Unit,
        searchResult: () -> LiveData<DanmuPanelState>
    ) {
        this.search = search
        this.download = download
        searchResult.invoke().observe(context as LifecycleOwner, ::renderState)
    }

    internal fun renderState(state: DanmuPanelState) {
        val episodes = (state.search as? DanmuSearchState.Content)?.episodes.orEmpty()
        mSearchDanmuData.clear()
        mSearchDanmuData.addAll(episodes)
        viewBinding.danmuRv.setData(mSearchDanmuData)
        viewBinding.danmuStatusTv.text =
            when {
                state.downloading -> "正在下载弹幕…"
                state.downloadError != null -> state.downloadError
                state.search is DanmuSearchState.Loading -> "正在搜索…"
                state.search is DanmuSearchState.Error -> state.search.message
                state.search is DanmuSearchState.Empty -> "没有找到弹幕，可修改关键词重试"
                else -> ""
            }
    }
}
