package com.xyoye.player.controller.setting

import android.content.Context
import android.util.AttributeSet
import android.view.View
import com.xyoye.common_component.adapter.addItem
import com.xyoye.common_component.adapter.buildAdapter
import com.xyoye.common_component.adapter.setupDiffUtil
import com.xyoye.common_component.config.PlayerConfig
import com.xyoye.common_component.extension.setData
import com.xyoye.common_component.extension.vertical
import com.xyoye.common_component.utils.dp2px
import com.xyoye.common_component.utils.view.ItemDecorationSpace
import com.xyoye.data_component.enums.PlayerType
import com.xyoye.data_component.enums.SettingViewType
import com.xyoye.data_component.enums.TrackType
import com.xyoye.player.info.PlayerInitializer
import com.xyoye.player.info.SettingAction
import com.xyoye.player.info.SettingActionType
import com.xyoye.player.info.SettingItem
import com.xyoye.player_component.R
import com.xyoye.player_component.databinding.ItemPlayerSettingBinding
import com.xyoye.player_component.databinding.ItemPlayerSettingTypeBinding
import com.xyoye.player_component.databinding.LayoutPlayerSettingBinding

/**
 * Created by xyoye on 2022/10/11
 */

class PlayerSettingView(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BaseSettingView<LayoutPlayerSettingBinding>(context, attrs, defStyleAttr) {
    // 操作项集合（每次展示时根据运行态重建）
    private var settingItems: List<Any> = emptyList()

    init {
        initRv()
        viewBinding.panelClose.setOnClickListener { mControlWrapper.hideSettingView() }
    }

    override fun getLayoutId() = R.layout.layout_player_setting

    override fun getSettingViewType(): SettingViewType = SettingViewType.PLAYER_SETTING

    override fun onViewShow() {
        settingItems = generateItems()
        settingItems
            .asSequence()
            .filter { it is SettingItem }
            .forEach { applyItemStatus(it as SettingItem) }
        viewBinding.settingRv.setData(settingItems)
    }

    override fun onViewHide() {
        viewBinding.settingRv.focusedChild?.clearFocus()
        viewBinding.settingRv.clearFocus()
    }

    override fun onViewShowed() {
        viewBinding.settingRv.requestFocus()
    }

    override fun focusSearch(
        focused: View,
        direction: Int
    ): View? {
        if (viewBinding.settingRv.hasFocus() && (direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT)) {
            return focused
        }
        return super.focusSearch(focused, direction)
    }

    private fun initRv() {
        viewBinding.settingRv.apply {
            layoutManager = vertical()

            adapter =
                buildAdapter {
                    setupDiffUtil {
                        areItemsTheSame { old, new ->
                            if (old is SettingItem &&
                                new is SettingItem
                            ) {
                                old.action == new.action
                            } else {
                                old == new
                            }
                        }
                    }
                    addItem<Any, ItemPlayerSettingBinding>(R.layout.item_player_setting) {
                        checkType { data, _ -> data is SettingItem }
                        initView { data, _, _ ->
                            (data as SettingItem).apply {
                                itemBinding.tvSetting.text = display
                                itemBinding.ivSetting.setImageResource(icon)
                                itemBinding.clCard.isSelected = selected
                                itemBinding.clCard.setOnClickListener {
                                    onItemClick(this)
                                }
                            }
                        }
                    }

                    addItem<Any, ItemPlayerSettingTypeBinding>(R.layout.item_player_setting_type) {
                        checkType { data, _ -> data is SettingActionType }
                        initView { data, _, _ ->
                            (data as SettingActionType).apply {
                                itemBinding.tvType.text = display
                            }
                        }
                    }

                    itemAnimator = null

                    addItemDecoration(ItemDecorationSpace(0, dp2px(8)))
                }
        }
    }

    private fun generateItems(): List<Any> {
        val items = mutableListOf<Any>()
        val disabledActions =
            mutableSetOf(
                SettingAction.SCREEN_SHOT,
                SettingAction.BACKGROUND_PLAY,
            )

        if (PlayerInitializer.playerType != PlayerType.TYPE_EXO_PLAYER ||
            mControlWrapper.getTracks(TrackType.VIDEO).size <= 1
        ) {
            disabledActions.add(SettingAction.VIDEO_TRACK)
        }

        SettingAction
            .values()
            .asSequence()
            .filterNot { disabledActions.contains(it) }
            .sortedBy {
                it.type.widget
            }.groupBy {
                it.type
            }.entries
            .forEach {
                items.add(it.key)
                items.addAll(
                    it.value.map { action -> SettingItem(action, action.display, action.icon) },
                )
            }
        return items
    }

    private fun applyItemStatus(item: SettingItem) {
        var selected = false

        when (item.action) {
            SettingAction.BACKGROUND_PLAY -> {
                selected = PlayerConfig.isBackgroundPlay()
            }

            SettingAction.NEXT_EPISODE -> {
                selected = PlayerInitializer.Player.isAutoPlayNext
            }

            else -> {}
        }
        item.selected = selected
    }

    private fun onItemClick(item: SettingItem) {
        when (item.action) {
            SettingAction.BACKGROUND_PLAY -> {
                val newStatus = PlayerConfig.isBackgroundPlay().not()
                PlayerConfig.putBackgroundPlay(newStatus)
                updateItemStatus(item.action, newStatus)
            }

            SettingAction.NEXT_EPISODE -> {
                val newStatus = !PlayerInitializer.Player.isAutoPlayNext
                PlayerInitializer.Player.isAutoPlayNext = newStatus
                PlayerConfig.putAutoPlayNext(newStatus)
                updateItemStatus(item.action, newStatus)
            }

            SettingAction.VIDEO_SPEED -> {
                mControlWrapper.showSettingView(SettingViewType.VIDEO_SPEED)
            }

            SettingAction.VIDEO_ASPECT -> {
                mControlWrapper.showSettingView(SettingViewType.VIDEO_ASPECT)
            }

            SettingAction.AUDIO_TRACK -> {
                mControlWrapper.showSettingView(SettingViewType.TRACKS, TrackType.AUDIO)
            }

            SettingAction.VIDEO_TRACK -> {
                mControlWrapper.showSettingView(SettingViewType.TRACKS, TrackType.VIDEO)
            }

            SettingAction.DANMU_TRACK -> {
                mControlWrapper.showSettingView(SettingViewType.TRACKS, TrackType.DANMU)
            }

            SettingAction.DANMU_CONFIG -> {
                mControlWrapper.showSettingView(SettingViewType.DANMU_CONFIGURE)
            }

            SettingAction.DANMU_STYLE -> {
                mControlWrapper.showSettingView(SettingViewType.DANMU_STYLE)
            }

            SettingAction.DANMU_TIME -> {
                mControlWrapper.showSettingView(SettingViewType.DANMU_OFFSET_TIME)
            }

            SettingAction.SUBTITLE_TRACK -> {
                mControlWrapper.showSettingView(SettingViewType.TRACKS, TrackType.SUBTITLE)
            }

            SettingAction.SUBTITLE_STYLE -> {
                mControlWrapper.showSettingView(SettingViewType.SUBTITLE_STYLE)
            }

            SettingAction.SUBTITLE_TIME -> {
                mControlWrapper.showSettingView(SettingViewType.SUBTITLE_OFFSET_TIME)
            }

            SettingAction.SCREEN_SHOT -> {
                // TV adaptation: 截图入口已隐藏，占位保持分支完整
            }
        }
    }

    private fun updateItemStatus(
        action: SettingAction,
        newStatus: Boolean
    ) {
        settingItems
            .find { it is SettingItem && it.action == action }
            ?.let { (it as SettingItem).selected = newStatus }
        viewBinding.settingRv.setData(settingItems)
    }
}
