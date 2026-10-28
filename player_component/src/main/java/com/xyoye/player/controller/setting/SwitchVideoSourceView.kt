package com.xyoye.player.controller.setting

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import androidx.recyclerview.widget.RecyclerView
import com.rubensousa.dpadrecyclerview.DpadRecyclerView
import com.xyoye.common_component.adapter.addItem
import com.xyoye.common_component.adapter.buildAdapter
import com.xyoye.common_component.adapter.setupDiffUtil
import com.xyoye.common_component.extension.setData
import com.xyoye.common_component.extension.vertical
import com.xyoye.common_component.utils.dp2px
import com.xyoye.common_component.utils.view.ItemDecorationOrientation
import com.xyoye.data_component.bean.VideoSourceBean
import com.xyoye.data_component.enums.SettingViewType
import com.xyoye.player_component.R
import com.xyoye.player_component.databinding.ItemVideoSourceBinding
import com.xyoye.player_component.databinding.LayoutSwitchVideoSourceBinding

/**
 * Created by xyoye on 2021/11/28.
 */

class SwitchVideoSourceView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
    ) : BaseSettingView<LayoutSwitchVideoSourceBinding>(context, attrs, defStyleAttr) {
        private var switchVideoSourceBlock: ((Int) -> Unit)? = null
        private val mVideoSources = mutableListOf<VideoSourceBean>()

        init {
            initRv()
        }

        override fun getLayoutId() = R.layout.layout_switch_video_source

        override fun getSettingViewType() = SettingViewType.SWITCH_VIDEO_SOURCE

        override fun getGravity() = Gravity.START

        override fun onViewShow() {
            buildVideoSource()
        }

        override fun onViewHide() {
            viewBinding.sourceRv.focusedChild?.clearFocus()
            viewBinding.sourceRv.clearFocus()
        }

        fun setSwitchVideoSourceBlock(block: (Int) -> Unit) {
            switchVideoSourceBlock = block
        }

        private fun initRv() {
            viewBinding.sourceRv.apply {
                layoutManager = vertical()

                adapter =
                    buildAdapter {
                        setupDiffUtil {
                            areItemsTheSame { old, new -> (old as VideoSourceBean).index == (new as VideoSourceBean).index }
                        }
                        addItem<VideoSourceBean, ItemVideoSourceBinding>(R.layout.item_video_source) {
                            initView { data, _, _ ->
                                itemBinding.tvSourceName.isSelected = data.isCurrent
                                itemBinding.tvSourceName.text = data.title
                                itemBinding.tvSourceName.setOnClickListener {
                                    if (data.isCurrent.not()) {
                                        mControlWrapper.hideSettingView()
                                        switchVideoSourceBlock?.invoke(data.index)
                                    }
                                }
                            }
                        }
                    }

                addItemDecoration(
                    ItemDecorationOrientation(
                        dividerPx = dp2px(10),
                        headerFooterPx = 0,
                        orientation = RecyclerView.VERTICAL,
                    ),
                )
            }
        }

        private fun buildVideoSource() {
            mVideoSources.clear()

            val videoSource = mControlWrapper.getVideoSource()
            for (index in 0 until videoSource.getGroupSize()) {
                mVideoSources.add(
                    VideoSourceBean(
                        index,
                        videoSource.indexTitle(index),
                        index == videoSource.getGroupIndex(),
                    ),
                )
            }
            viewBinding.sourceRv.setData(mVideoSources)
            val list = viewBinding.sourceRv
            if (list is DpadRecyclerView) {
                list.setSelectedPosition(videoSource.getGroupIndex())
            } else {
                list.scrollToPosition(videoSource.getGroupIndex())
            }
        }
    }
