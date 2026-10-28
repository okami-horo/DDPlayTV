package com.xyoye.player.controller.setting

import android.content.Context
import android.util.AttributeSet
import androidx.core.view.isGone
import com.google.android.material.slider.LabelFormatter
import com.xyoye.common_component.config.PlayerConfig
import com.xyoye.data_component.enums.SettingViewType
import com.xyoye.player.info.PlayerInitializer
import com.xyoye.player_component.R
import com.xyoye.player_component.databinding.LayoutSettingVideoSpeedBinding
import kotlin.math.roundToInt

/**
 * Created by xyoye on 2022/10/12
 */

class SettingVideoSpeedView(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BaseSettingView<LayoutSettingVideoSpeedBinding>(context, attrs, defStyleAttr) {
    init {
        initVideoSpeed()
    }

    override fun getLayoutId() = R.layout.layout_setting_video_speed

    override fun getSettingViewType() = SettingViewType.VIDEO_SPEED

    override fun onViewShowed() {
        viewBinding.speedSlider.value = PlayerInitializer.Player.videoSpeed
        viewBinding.speedSlider.labelBehavior = LabelFormatter.LABEL_VISIBLE

        viewBinding.sliderPressSpeed.value = PlayerInitializer.Player.pressVideoSpeed
        viewBinding.sliderPressSpeed.labelBehavior = LabelFormatter.LABEL_VISIBLE

        viewBinding.speedSlider.requestFocus()
    }

    override fun onViewHide() {
        viewBinding.speedSlider.labelBehavior = LabelFormatter.LABEL_WITHIN_BOUNDS
        viewBinding.speedSlider.clearFocus()

        viewBinding.sliderPressSpeed.labelBehavior = LabelFormatter.LABEL_WITHIN_BOUNDS
        viewBinding.sliderPressSpeed.clearFocus()
    }

    private fun initVideoSpeed() {
        viewBinding.speedSlider.addOnChangeListener { _, approximation, _ ->
            val value = (approximation * 100).roundToInt() / 100f
            viewBinding.resetTv.isGone = value == PlayerInitializer.Player.DEFAULT_SPEED

            PlayerConfig.putNewVideoSpeed(value)
            PlayerInitializer.Player.videoSpeed = value
            mControlWrapper.setSpeed(value)
        }

        viewBinding.resetTv.setOnClickListener {
            viewBinding.speedSlider.value = PlayerInitializer.Player.DEFAULT_SPEED
            viewBinding.speedSlider.requestFocus()
        }

        viewBinding.sliderPressSpeed.addOnChangeListener { _, approximation, _ ->
            val value = (approximation * 100).roundToInt() / 100f
            viewBinding.tvResetPressSpeed.isGone = value == PlayerInitializer.Player.DEFAULT_PRESS_SPEED

            PlayerConfig.putPressVideoSpeed(value)
            PlayerInitializer.Player.pressVideoSpeed = value
        }

        viewBinding.tvResetPressSpeed.setOnClickListener {
            viewBinding.sliderPressSpeed.value = PlayerInitializer.Player.DEFAULT_PRESS_SPEED
            viewBinding.sliderPressSpeed.requestFocus()
        }
    }
}
