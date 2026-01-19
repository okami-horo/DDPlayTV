package com.xyoye.player.controller.setting

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.xyoye.common_component.adapter.addItem
import com.xyoye.common_component.adapter.buildAdapter
import com.xyoye.common_component.extension.requestIndexChildFocus
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

            refreshTracks()
        }

        override fun onViewHide() {
            viewBinding.rvTrack.focusedChild?.clearFocus()
            viewBinding.rvTrack.clearFocus()
        }

        override fun onTrackChanged(type: TrackType) {
            if (isSettingShowing() && type == mTrackType) {
                postDelayed({ refreshTracks() }, 500)
            }
        }

        override fun onKeyDown(
            keyCode: Int,
            event: KeyEvent?
        ): Boolean {
            if (isSettingShowing().not()) {
                return false
            }

            val handled = handleKeyCode(keyCode)
            if (handled) {
                return true
            }

            if (tracks.size > 0) {
                viewBinding.rvTrack.requestIndexChildFocus(0)
            }
            return true
        }

        private fun initView() {
            viewBinding.rvTrack.apply {
                itemAnimator = null

                layoutManager = vertical()

                adapter =
                    buildAdapter {
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
            viewBinding.tvAddTrack.apply {
                setOnClickListener {
                    mControlWrapper.showSettingView(SettingViewType.SWITCH_SOURCE, mTrackType)
                    onSettingVisibilityChanged(false)
                }
                setOnKeyListener { _, keyCode, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) {
                        return@setOnKeyListener false
                    }
                    if (keyCode == KeyEvent.KEYCODE_DPAD_UP && tracks.isNotEmpty()) {
                        viewBinding.rvTrack.requestIndexChildFocus(tracks.lastIndex)
                        return@setOnKeyListener true
                    }
                    false
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

        /**
         * 处理KeyCode事件
         */
        private fun handleKeyCode(keyCode: Int): Boolean {
            if (tracks.isEmpty()) {
                return false
            }

            // 已取得焦点的Item
            val focusedChild =
                viewBinding.rvTrack.focusedChild
                    ?: return false
            val focusedChildIndex = viewBinding.rvTrack.getChildAdapterPosition(focusedChild)
            if (focusedChildIndex == -1) {
                return false
            }

            // 从列表最后一个条目向下移动时，跳转到“添加”按钮
            if (focusedChildIndex == tracks.lastIndex &&
                (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
            ) {
                if (viewBinding.tvAddTrack.isVisible.not()) {
                    return true
                }
                viewBinding.tvAddTrack.requestFocus()
                return true
            }

            val targetIndex = getTargetIndexByKeyCode(keyCode, focusedChildIndex)
            if (targetIndex == -1) {
                return false
            }
            viewBinding.rvTrack.requestIndexChildFocus(targetIndex)
            return true
        }

        /**
         * 根据KeyCode与当前焦点位置，取得目标焦点位置
         */
        private fun getTargetIndexByKeyCode(
            keyCode: Int,
            focusedIndex: Int
        ): Int =
            when (keyCode) {
                // 左、上规则
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> {
                    val previousIndex = focusedIndex - 1
                    if (previousIndex >= 0) previousIndex else -1
                }
                // 右、下规则
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    val nextIndex = focusedIndex + 1
                    if (nextIndex < tracks.size) nextIndex else -1
                }

                else -> {
                    -1
                }
            }

        /**
         * 刷新轨道列表
         */
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
