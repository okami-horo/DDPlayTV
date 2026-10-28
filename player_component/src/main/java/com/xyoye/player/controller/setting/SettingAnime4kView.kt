package com.xyoye.player.controller.setting

import android.content.Context
import android.util.AttributeSet
import androidx.recyclerview.widget.RecyclerView
import com.xyoye.common_component.adapter.addItem
import com.xyoye.common_component.adapter.buildAdapter
import com.xyoye.common_component.adapter.setupDiffUtil
import com.xyoye.common_component.extension.setData
import com.xyoye.common_component.extension.vertical
import com.xyoye.common_component.utils.dp2px
import com.xyoye.common_component.utils.view.ItemDecorationOrientation
import com.xyoye.data_component.enums.PlayerType
import com.xyoye.data_component.enums.SettingViewType
import com.xyoye.player.info.PlayerInitializer
import com.xyoye.player.kernel.anime4k.Anime4kMode
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
        mutableListOf<Anime4kOption>()

    init {
        initView()
    }

    override fun getLayoutId() = R.layout.layout_setting_anime4k

    override fun getSettingViewType() = SettingViewType.ANIME4K

    override fun onViewShow() {
        applyModeStatus()
    }

    override fun onViewHide() {
        viewBinding.rvAnime4k.focusedChild?.clearFocus()
        viewBinding.rvAnime4k.clearFocus()
    }

    private fun initView() {
        anime4kOptions.clear()
        anime4kOptions.add(Anime4kOption(Anime4kMode.MODE_OFF, "关闭"))
        anime4kOptions.add(Anime4kOption(Anime4kMode.MODE_PERFORMANCE, "性能"))
        if (
            PlayerInitializer.playerType == PlayerType.TYPE_MPV_PLAYER ||
            PlayerInitializer.playerType == PlayerType.TYPE_EXO_PLAYER
        ) {
            anime4kOptions.add(Anime4kOption(Anime4kMode.MODE_QUALITY, "质量"))
        }

        viewBinding.rvAnime4k.apply {
            itemAnimator = null

            layoutManager = vertical()

            adapter =
                buildAdapter {
                    setupDiffUtil {
                        areItemsTheSame { old, new -> (old as Anime4kOption).mode == (new as Anime4kOption).mode }
                    }
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
                    orientation = RecyclerView.VERTICAL,
                ),
            )

            setData(anime4kOptions)
        }
    }

    private fun applyModeStatus() {
        val currentMode = mControlWrapper.getAnime4kMode()
        anime4kOptions.forEach { option ->
            option.isChecked = option.mode == currentMode
        }
        viewBinding.rvAnime4k.setData(anime4kOptions)
    }

    private fun onClickMode(mode: Int) {
        mControlWrapper.setAnime4kMode(mode)
        applyModeStatus()
    }
}
