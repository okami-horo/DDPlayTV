package com.xyoye.player.controller.setting

import android.content.Context
import android.util.AttributeSet
import android.widget.TextView
import androidx.core.view.isVisible
import com.xyoye.data_component.enums.SettingViewType
import com.xyoye.player.info.PlayerInitializer
import com.xyoye.player_component.R
import com.xyoye.player_component.databinding.LayoutSettingOffsetTimeBinding
import java.math.RoundingMode
import java.text.DecimalFormat
import kotlin.math.abs

/**
 * Created by xyoye on 2022/10/18
 */

class SettingOffsetTimeView(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BaseSettingView<LayoutSettingOffsetTimeBinding>(context, attrs, defStyleAttr) {
    private val actionViews = mutableListOf<TextView>()
    private var mSettingType = SettingViewType.DANMU_OFFSET_TIME

    init {
        viewBinding.root.translationY = 0f
        initView()
    }

    override fun getLayoutId() = R.layout.layout_setting_offset_time

    override fun getSettingViewType() = SettingViewType.DANMU_OFFSET_TIME

    override fun getGravity() = android.view.Gravity.CENTER

    override fun onViewShow() {
        changeTime(0f, dispatch = false)
    }

    private fun initView() {
        actionViews.clear()
        actionViews.add(viewBinding.tvReduceTen)
        actionViews.add(viewBinding.tvReduceFive)
        actionViews.add(viewBinding.tvReduceHalf)
        actionViews.add(viewBinding.tvReset)
        actionViews.add(viewBinding.tvAddHalf)
        actionViews.add(viewBinding.tvAddFive)
        actionViews.add(viewBinding.tvAddTen)

        actionViews.forEach { textView ->
            textView.setOnClickListener {
                if (textView == viewBinding.tvReset) {
                    changeTime(0f, reset = true)
                    return@setOnClickListener
                }

                val time =
                    textView.text.toString().toFloatOrNull()
                        ?: return@setOnClickListener
                changeTime(time)
            }
        }
    }

    private fun changeTime(
        time: Float,
        reset: Boolean = false,
        dispatch: Boolean = true
    ) {
        val offsetTime =
            if (mSettingType == SettingViewType.SUBTITLE_OFFSET_TIME) {
                PlayerInitializer.Subtitle.offsetPosition
            } else {
                PlayerInitializer.Danmu.offsetPosition
            }
        val currentOffset = time * 1000
        var newOffset = offsetTime + currentOffset

        if (reset) {
            newOffset = 0f
        }

        if (dispatch) {
            if (mSettingType == SettingViewType.SUBTITLE_OFFSET_TIME) {
                PlayerInitializer.Subtitle.offsetPosition = newOffset.toLong()
                mControlWrapper.updateSubtitleOffsetTime()
            } else {
                PlayerInitializer.Danmu.offsetPosition = newOffset.toLong()
                mControlWrapper.updateDanmuOffsetTime()
            }
        }

        val display = "${numberFormat(abs(newOffset / 1000))}秒"
        val status =
            when {
                newOffset > 0 -> "提前"
                newOffset < 0 -> "延迟"
                else -> ""
            }

        viewBinding.tvDisplay.text = display
        viewBinding.tvStatus.text = status
        viewBinding.tvReset.isVisible = newOffset != 0f
    }

    private fun numberFormat(num: Float): String =
        DecimalFormat("0.#").run {
            roundingMode = RoundingMode.HALF_UP
            format(num)
        }

    fun setSettingType(settingType: SettingViewType) {
        this.mSettingType = settingType
    }
}
