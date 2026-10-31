package com.xyoye.player.controller.setting

import android.content.Context
import android.util.AttributeSet
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.xyoye.common_component.adapter.addItem
import com.xyoye.common_component.adapter.buildAdapter
import com.xyoye.common_component.adapter.setupDiffUtil
import com.xyoye.common_component.extension.setData
import com.xyoye.common_component.extension.vertical
import com.xyoye.common_component.utils.dp2px
import com.xyoye.common_component.utils.view.ItemDecorationOrientation
import com.xyoye.data_component.bean.VideoTrackBean
import com.xyoye.data_component.enums.SettingViewType
import com.xyoye.data_component.enums.TrackType
import com.xyoye.player_component.R
import com.xyoye.player_component.databinding.ItemSettingTracksBinding
import com.xyoye.player_component.databinding.LayoutSettingTracksBinding

/**
 * Created by xyoye on 2024/1/26
 */

class SettingTracksView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
    ) : BaseSettingView<LayoutSettingTracksBinding>(
            context,
            attrs,
            defStyleAttr,
        ) {
        private var mTrackType = TrackType.DANMU
        private val tracks = mutableListOf<VideoTrackBean>()

        private val title
            get() =
                when (mTrackType) {
                    TrackType.AUDIO -> "音轨"
                    TrackType.DANMU -> "弹幕轨"
                    TrackType.SUBTITLE -> "字幕轨"
                    TrackType.VIDEO -> "视频轨"
                }

        private val actionText
            get() =
                when (mTrackType) {
                    TrackType.AUDIO -> "添加音轨"
                    TrackType.DANMU -> "添加弹幕轨"
                    TrackType.SUBTITLE -> "添加字幕轨"
                    TrackType.VIDEO -> "添加视频轨"
                }

        init {
            initView()

            initListener()
        }

        private val decoration
            get() =
                ItemDecorationOrientation(
                    dividerPx = dp2px(10),
                    headerFooterPx = 0,
                    orientation = RecyclerView.VERTICAL,
                )

        override fun getLayoutId(): Int = R.layout.layout_setting_tracks

        override fun getSettingViewType(): SettingViewType = SettingViewType.TRACKS

        override fun onViewShow() {
            viewBinding.tvTitle.text = title
            viewBinding.tvAddTrack.text = actionText
            viewBinding.tvAddTrack.isVisible = mTrackType != TrackType.VIDEO

            viewBinding.trackVisible.isVisible = mTrackType == TrackType.SUBTITLE || mTrackType == TrackType.DANMU
            viewBinding.trackStyle.isVisible = viewBinding.trackVisible.isVisible
            viewBinding.trackVisible.text = if (mTrackType == TrackType.SUBTITLE) "显示字幕" else "显示弹幕"
            viewBinding.trackVisible.isChecked =
                if (mTrackType == TrackType.SUBTITLE) mControlWrapper.isUserSubtitleVisible() else mControlWrapper.isUserDanmuVisible()
            refreshTracks()
        }

        override fun onViewHide() {
            viewBinding.rvTrack.focusedChild?.clearFocus()
            viewBinding.rvTrack.clearFocus()
        }

        override fun onViewShowed() {
            if (isInTouchMode) return
            when {
                tracks.isNotEmpty() -> viewBinding.rvTrack.requestFocus()
                viewBinding.trackVisible.isVisible -> viewBinding.trackVisible.requestFocus()
                else -> viewBinding.tvAddTrack.requestFocus()
            }
        }

        override fun onTrackChanged(type: TrackType) {
            if (isSettingShowing() && type == mTrackType) {
                postDelayed({ if (isSettingShowing()) refreshTracks() }, 500)
            }
        }

        private fun initView() {
            viewBinding.rvTrack.apply {
                itemAnimator = null

                layoutManager = vertical()

                adapter =
                    buildAdapter {
                        setupDiffUtil {
                            areItemsTheSame { old, new ->
                                old is VideoTrackBean &&
                                    new is VideoTrackBean &&
                                    old.type == new.type &&
                                    old.id == new.id &&
                                    old.disable == new.disable &&
                                    old.trackResource == new.trackResource
                            }
                        }
                        addItem<VideoTrackBean, ItemSettingTracksBinding>(R.layout.item_setting_tracks) {
                            initView { data, _, _ ->
                                itemBinding.tvName.text = data.name
                                itemBinding.tvName.isSelected = data.selected
                                itemBinding.tvName.setOnClickListener {
                                    onClickTrack(data)
                                }
                            }
                        }
                    }

                addItemDecoration(decoration)
            }
        }

        private fun initListener() {
            viewBinding.panelClose.setOnClickListener { mControlWrapper.hideSettingView() }
            viewBinding.trackVisible.setOnClickListener {
                if (mTrackType == TrackType.SUBTITLE) mControlWrapper.toggleSubtitleVisible() else mControlWrapper.toggleDanmuVisible()
                viewBinding.trackVisible.isChecked =
                    if (mTrackType == TrackType.SUBTITLE) mControlWrapper.isUserSubtitleVisible() else mControlWrapper.isUserDanmuVisible()
            }
            viewBinding.trackStyle.setOnClickListener {
                mControlWrapper.showSettingView(
                    if (mTrackType ==
                        TrackType.SUBTITLE
                    ) {
                        SettingViewType.SUBTITLE_STYLE
                    } else {
                        SettingViewType.DANMU_STYLE
                    }
                )
            }
            viewBinding.tvAddTrack.apply {
                setOnClickListener {
                    mControlWrapper.showSettingView(SettingViewType.SWITCH_SOURCE, mTrackType)
                }
            }
        }

        private fun onClickTrack(track: VideoTrackBean) {
            if (track.selected) {
                return
            }

            if (track.disable) {
                mControlWrapper.deselectTrack(mTrackType)
            } else {
                mControlWrapper.selectTrack(track)
            }
        }

        private fun refreshTracks() {
            tracks.clear()
            tracks.addAll(mControlWrapper.getTracks(mTrackType))
            viewBinding.rvTrack.setData(tracks)

            viewBinding.tvEmptyTrack.isVisible = tracks.isEmpty()
        }

        fun setTrackType(trackType: TrackType) {
            this.mTrackType = trackType
        }
    }
