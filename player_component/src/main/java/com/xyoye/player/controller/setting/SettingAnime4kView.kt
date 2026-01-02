package com.xyoye.player.controller.setting

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import androidx.recyclerview.widget.RecyclerView
import com.xyoye.common_component.adapter.addItem
import com.xyoye.common_component.adapter.buildAdapter
import com.xyoye.common_component.config.PlayerConfig
import com.xyoye.common_component.extension.nextItemIndex
import com.xyoye.common_component.extension.previousItemIndex
import com.xyoye.common_component.extension.requestIndexChildFocus
import com.xyoye.common_component.extension.setData
import com.xyoye.common_component.extension.vertical
import com.xyoye.common_component.utils.dp2px
import com.xyoye.common_component.utils.view.ItemDecorationOrientation
import com.xyoye.data_component.enums.SettingViewType
import com.xyoye.player.kernel.impl.mpv.Anime4kShaderManager
import com.xyoye.player_component.R
import com.xyoye.player_component.databinding.ItemSettingAnime4kBinding
import com.xyoye.player_component.databinding.LayoutSettingAnime4kBinding

class SettingAnime4kView(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BaseSettingView<LayoutSettingAnime4kBinding>(context, attrs, defStyleAttr) {

    private data class Anime4kOption(
        val mode: Int,
        val display: String,
        var isChecked: Boolean = false
    )

    private val anime4kOptions =
        mutableListOf(
            Anime4kOption(Anime4kShaderManager.MODE_OFF, "关闭"),
            Anime4kOption(Anime4kShaderManager.MODE_PERFORMANCE, "性能"),
            Anime4kOption(Anime4kShaderManager.MODE_QUALITY, "质量")
        )

    init {
        initView()
    }

    override fun getLayoutId() = R.layout.layout_setting_anime4k

    override fun getSettingViewType() = SettingViewType.MPV_ANIME4K

    override fun onViewShow() {
        applyModeStatus()
    }

    override fun onViewHide() {
        viewBinding.rvAnime4k.focusedChild?.clearFocus()
        viewBinding.rvAnime4k.clearFocus()
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

        viewBinding.rvAnime4k.requestIndexChildFocus(0)
        return true
    }

    private fun initView() {
        viewBinding.rvAnime4k.apply {
            itemAnimator = null

            layoutManager = vertical()

            adapter =
                buildAdapter {
                    addItem<Anime4kOption, ItemSettingAnime4kBinding>(R.layout.item_setting_anime4k) {
                        initView { data, _, _ ->
                            itemBinding.tvName.text = data.display
                            itemBinding.tvName.isSelected = data.isChecked
                            itemBinding.tvName.setOnClickListener {
                                onClickMode(data.mode)
                            }
                        }
                    }
                }

            addItemDecoration(
                ItemDecorationOrientation(
                    dividerPx = dp2px(10),
                    headerFooterPx = 0,
                    orientation = RecyclerView.VERTICAL
                )
            )

            setData(anime4kOptions)
        }
    }

    private fun applyModeStatus() {
        val currentMode = PlayerConfig.getMpvAnime4kMode()
        anime4kOptions.forEach { option ->
            option.isChecked = option.mode == currentMode
        }
        viewBinding.rvAnime4k.setData(anime4kOptions)
    }

    private fun onClickMode(mode: Int) {
        PlayerConfig.putMpvAnime4kMode(mode)
        mControlWrapper.setMpvAnime4kMode(mode)
        applyModeStatus()
    }

    private fun handleKeyCode(keyCode: Int): Boolean {
        val focusedChild = viewBinding.rvAnime4k.focusedChild ?: return false
        val focusedIndex = viewBinding.rvAnime4k.getChildAdapterPosition(focusedChild)
        if (focusedIndex == -1) {
            return false
        }
        val targetIndex = getTargetIndexByKeyCode(keyCode, focusedIndex)
        viewBinding.rvAnime4k.requestIndexChildFocus(targetIndex)
        return true
    }

    private fun getTargetIndexByKeyCode(
        keyCode: Int,
        focusedIndex: Int
    ): Int =
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> {
                anime4kOptions.previousItemIndex<Anime4kOption>(focusedIndex)
            }

            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> {
                anime4kOptions.nextItemIndex<Anime4kOption>(focusedIndex)
            }

            else -> {
                -1
            }
        }
}
