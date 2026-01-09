package com.xyoye.player.controller.setting

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.xyoye.common_component.adapter.addItem
import com.xyoye.common_component.adapter.buildAdapter
import com.xyoye.common_component.extension.nextItemIndex
import com.xyoye.common_component.extension.previousItemIndex
import com.xyoye.common_component.extension.requestIndexChildFocus
import com.xyoye.common_component.extension.setData
import com.xyoye.common_component.extension.vertical
import com.xyoye.common_component.playback.addon.PlaybackSettingSpec
import com.xyoye.common_component.playback.addon.PlaybackSettingUpdate
import com.xyoye.common_component.playback.addon.PlaybackSettingsAddon
import com.xyoye.common_component.utils.dp2px
import com.xyoye.common_component.utils.view.ItemDecorationOrientation
import com.xyoye.data_component.enums.SettingViewType
import com.xyoye.player_component.R
import com.xyoye.player_component.databinding.ItemPlayerSettingTypeBinding
import com.xyoye.player_component.databinding.ItemSettingTracksBinding
import com.xyoye.player_component.databinding.LayoutSettingPlaybackAddonBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingPlaybackAddonView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : BaseSettingView<LayoutSettingPlaybackAddonBinding>(context, attrs, defStyleAttr) {
        private data class SectionRow(
            val title: String,
        )

        private data class OptionRow(
            val label: String,
            val update: PlaybackSettingUpdate,
            val selected: Boolean,
        )

        private var rows: List<Any> = emptyList()
        private var updateBlock: ((PlaybackSettingUpdate) -> Unit)? = null

        private var loadJob: Job? = null
        private var loadScope: CoroutineScope? = null

        private val decoration
            get() =
                ItemDecorationOrientation(
                    dividerPx = dp2px(10),
                    headerFooterPx = 0,
                    orientation = RecyclerView.VERTICAL,
                )

        init {
            initView()
        }

        override fun getLayoutId(): Int = R.layout.layout_setting_playback_addon

        // 过渡阶段沿用旧 ViewType，避免修改枚举/路由
        override fun getSettingViewType(): SettingViewType = SettingViewType.BILIBILI_PLAYBACK

        override fun onViewShow() {
            viewBinding.tvTitle.text = "播放源设置"
            refreshRows()
        }

        override fun onViewHide() {
            viewBinding.rvOptions.focusedChild?.clearFocus()
            viewBinding.rvOptions.clearFocus()
            cancelLoadJob()
        }

        override fun onDetachedFromWindow() {
            cancelLoadJob()
            loadScope?.cancel()
            loadScope = null
            super.onDetachedFromWindow()
        }

        override fun onKeyDown(
            keyCode: Int,
            event: KeyEvent?,
        ): Boolean {
            if (isSettingShowing().not()) {
                return false
            }

            val handled = handleKeyCode(keyCode)
            if (handled) {
                return true
            }

            val firstIndex = rows.indexOfFirst { it is OptionRow }
            if (firstIndex != -1) {
                viewBinding.rvOptions.requestIndexChildFocus(firstIndex)
            }
            return true
        }

        fun setUpdateBlock(block: (PlaybackSettingUpdate) -> Unit) {
            updateBlock = block
        }

        private fun initView() {
            viewBinding.rvOptions.apply {
                itemAnimator = null
                layoutManager = vertical()
                adapter =
                    buildAdapter {
                        addItem<Any, ItemPlayerSettingTypeBinding>(R.layout.item_player_setting_type) {
                            checkType { data, _ -> data is SectionRow }
                            initView { data, _, _ ->
                                itemBinding.tvType.text = (data as SectionRow).title
                            }
                        }

                        addItem<Any, ItemSettingTracksBinding>(R.layout.item_setting_tracks) {
                            checkType { data, _ -> data is OptionRow }
                            initView { data, _, _ ->
                                val option = data as OptionRow
                                itemBinding.tvName.text = option.label
                                itemBinding.tvName.isSelected = option.selected
                                itemBinding.tvName.setOnClickListener {
                                    if (!option.selected) {
                                        updateBlock?.invoke(option.update)
                                        onSettingVisibilityChanged(false)
                                    }
                                }
                            }
                        }
                    }
                addItemDecoration(decoration)
            }
        }

        private fun refreshRows() {
            cancelLoadJob()

            viewBinding.tvEmpty.isVisible = false
            rows = emptyList()
            viewBinding.rvOptions.setData(rows)

            val scope = (loadScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)).also { loadScope = it }
            loadJob =
                scope.launch {
                    val addon = mControlWrapper.getVideoSource().getPlaybackAddon() as? PlaybackSettingsAddon
                    if (addon == null) {
                        rows = emptyList()
                        viewBinding.rvOptions.setData(rows)
                        viewBinding.tvEmpty.isVisible = true
                        return@launch
                    }

                    val spec =
                        withContext(Dispatchers.IO) {
                            addon.getSettingSpec().getOrNull()
                        }

                    rows = spec?.let { buildRows(it) }.orEmpty()
                    viewBinding.rvOptions.setData(rows)
                    viewBinding.tvEmpty.isVisible = rows.none { it is OptionRow }
                }
        }

        private fun buildRows(spec: PlaybackSettingSpec): List<Any> {
            val items = mutableListOf<Any>()
            spec.sections.forEach { section ->
                items.add(SectionRow(section.title))

                section.items.forEach { item ->
                    when (item) {
                        is PlaybackSettingSpec.Item.SingleChoice -> {
                            item.options.forEach { option ->
                                items.add(
                                    OptionRow(
                                        label = option.label,
                                        update =
                                            PlaybackSettingUpdate(
                                                settingId = item.settingId,
                                                optionId = option.optionId,
                                            ),
                                        selected = item.selectedOptionId == option.optionId,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
            return items
        }

        private fun handleKeyCode(keyCode: Int): Boolean {
            val focusedView =
                viewBinding.rvOptions.focusedChild
                    ?: return false
            val focusedIndex = viewBinding.rvOptions.getChildAdapterPosition(focusedView)
            if (focusedIndex == -1) {
                return false
            }

            val targetIndex =
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT -> rows.previousItemIndex<OptionRow>(focusedIndex)
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> rows.nextItemIndex<OptionRow>(focusedIndex)
                    else -> return false
                }
            if (targetIndex == -1) {
                return false
            }
            viewBinding.rvOptions.requestIndexChildFocus(targetIndex)
            return true
        }

        private fun cancelLoadJob() {
            loadJob?.cancel()
            loadJob = null
        }
    }
