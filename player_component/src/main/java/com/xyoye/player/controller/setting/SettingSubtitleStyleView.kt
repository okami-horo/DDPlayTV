package com.xyoye.player.controller.setting

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import androidx.core.view.isVisible
import androidx.media3.common.util.UnstableApi
import com.xyoye.common_component.config.SubtitleConfig
import com.xyoye.common_component.enums.SubtitleRendererBackend
import com.xyoye.common_component.extension.observeProgressChange
import com.xyoye.data_component.enums.SettingViewType
import com.xyoye.player.info.PlayerInitializer
import com.xyoye.player.subtitle.backend.SubtitleRendererRegistry
import com.xyoye.player_component.R
import com.xyoye.player_component.databinding.LayoutSettingSubtitleStyleBinding

/**
 * Created by xyoye on 2022/1/10
 */

@UnstableApi
class SettingSubtitleStyleView(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BaseSettingView<LayoutSettingSubtitleStyleBinding>(context, attrs, defStyleAttr) {
    companion object {
        private const val VERTICAL_OFFSET_MAX = 30
        private const val FONT_SCALE_OFFSET_MIN = -50
        private const val FONT_SCALE_OFFSET_MAX = 400
    }

    init {
        initSettingListener()
    }

    override fun getLayoutId() = R.layout.layout_setting_subtitle_style

    override fun getSettingViewType() = SettingViewType.SUBTITLE_STYLE

    override fun onViewShow() {
        applyBackendVisibility()
        applySubtitleStyleStatus()
    }

    override fun onViewHide() {
        viewBinding.subtitleSettingNsv.focusedChild?.clearFocus()
    }

    override fun onViewShowed() {
        if (isLibassMode()) {
            viewBinding.subtitleVerticalOffsetSb.requestFocus()
        } else {
            viewBinding.subtitleSizeSb.requestFocus()
        }
    }

    private fun applySubtitleStyleStatus() {
        // 文字大小
        val textSizePercent = PlayerInitializer.Subtitle.textSize
        val textSizeText = "$textSizePercent%"
        viewBinding.subtitleSizeTv.text = textSizeText
        viewBinding.subtitleSizeSb.progress = textSizePercent

        // 描边宽度
        val strokeWidthPercent = PlayerInitializer.Subtitle.strokeWidth
        val strokeWidthText = "$strokeWidthPercent%"
        viewBinding.subtitleStrokeWidthTv.text = strokeWidthText
        viewBinding.subtitleStrokeWidthSb.progress = strokeWidthPercent

        // 文字颜色
        viewBinding.subtitleColorSb.post {
            val textColor = PlayerInitializer.Subtitle.textColor
            viewBinding.subtitleColorSb.seekToColor(textColor)
            val textColorPosition = viewBinding.subtitleColorSb.getPositionFromColor(textColor)
            val textColorText = "$textColorPosition%"
            viewBinding.subtitleColorTv.text = textColorText
        }

        // 描边颜色
        viewBinding.subtitleStrokeColorSb.post {
            val strokeColor = PlayerInitializer.Subtitle.strokeColor
            viewBinding.subtitleStrokeColorSb.seekToColor(strokeColor)
            val strokeColorPosition =
                viewBinding.subtitleStrokeColorSb.getPositionFromColor(strokeColor)
            val strokeColorText = "$strokeColorPosition%"
            viewBinding.subtitleStrokeColorTv.text = strokeColorText
        }

        // 透明度
        val alphaPercent = PlayerInitializer.Subtitle.alpha
        val alphaText = "$alphaPercent%"
        viewBinding.subtitleAlphaTv.text = alphaText
        viewBinding.subtitleAlphaSb.progress = alphaPercent

        val fontScaleOffsetPercent =
            PlayerInitializer.Subtitle.fontScaleOffsetPercent.coerceIn(FONT_SCALE_OFFSET_MIN, FONT_SCALE_OFFSET_MAX)
        viewBinding.subtitleFontScaleOffsetTv.text = formatOffsetText(fontScaleOffsetPercent)
        viewBinding.subtitleFontScaleOffsetSb.progress =
            fontScaleOffsetValueToProgress(fontScaleOffsetPercent)

        val verticalOffset = PlayerInitializer.Subtitle.verticalOffset
        viewBinding.subtitleVerticalOffsetTv.text = formatOffsetText(verticalOffset)
        viewBinding.subtitleVerticalOffsetSb.progress = offsetValueToProgress(verticalOffset)

        viewBinding.tvResetSubtitleConfig.isVisible = isConfigChanged()
    }

    private fun applyBackendVisibility() {
        val enableLegacyStyling = !isLibassMode()
        val enableLibassScaling = isLibassMode()
        // libass 使用字幕文件自身样式，仅保留字号/颜色/描边等在传统后端
        viewBinding.subtitleSizeRow.isVisible = enableLegacyStyling
        viewBinding.subtitleSizeSb.isVisible = enableLegacyStyling
        viewBinding.subtitleStrokeWidthRow.isVisible = enableLegacyStyling
        viewBinding.subtitleStrokeWidthSb.isVisible = enableLegacyStyling
        viewBinding.subtitleColorRow.isVisible = enableLegacyStyling
        viewBinding.subtitleColorSb.isVisible = enableLegacyStyling
        viewBinding.subtitleStrokeColorRow.isVisible = enableLegacyStyling
        viewBinding.subtitleStrokeColorSb.isVisible = enableLegacyStyling
        viewBinding.subtitleAlphaRow.isVisible = !isMpvMode()
        viewBinding.subtitleAlphaSb.isVisible = !isMpvMode()
        viewBinding.subtitleNativeStyleTips.isVisible = isMpvMode()
        // 字号偏移仅对 libass 生效
        viewBinding.subtitleFontScaleOffsetRow.isVisible = enableLibassScaling
        viewBinding.subtitleFontScaleOffsetSb.isVisible = enableLibassScaling
    }

    private fun initSettingListener() {
        listOf(viewBinding.subtitleColorSb, viewBinding.subtitleStrokeColorSb).forEach { control ->
            control.setOnKeyListener { _, keyCode, event ->
                if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return@setOnKeyListener false
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) control.previousPosition() else control.nextPosition()
                }
                true
            }
        }
        viewBinding.tvResetSubtitleConfig.setOnClickListener {
            resetConfig()
        }

        viewBinding.subtitleSizeSb.observeProgressChange {
            updateSize(it)
        }

        viewBinding.subtitleStrokeWidthSb.observeProgressChange {
            updateStrokeWidth(it)
        }

        viewBinding.subtitleColorSb.setOnColorChangeListener { position, color ->
            updateTextColor(position, color)
        }

        viewBinding.subtitleStrokeColorSb.setOnColorChangeListener { position, color ->
            updateStrokeColor(position, color)
        }

        viewBinding.subtitleAlphaSb.observeProgressChange {
            updateAlpha(it)
        }

        viewBinding.subtitleFontScaleOffsetSb.observeProgressChange {
            updateFontScaleOffset(fontScaleOffsetProgressToValue(it))
        }

        viewBinding.subtitleVerticalOffsetSb.observeProgressChange {
            updateVerticalOffset(offsetProgressToValue(it))
        }
    }

    private fun updateSize(progress: Int) {
        if (PlayerInitializer.Subtitle.textSize == progress) {
            return
        }

        val progressText = "$progress%"
        viewBinding.subtitleSizeTv.text = progressText
        viewBinding.subtitleSizeSb.progress = progress

        SubtitleConfig.putTextSize(progress)
        PlayerInitializer.Subtitle.textSize = progress
        mControlWrapper.updateTextSize()
        onConfigChanged()
    }

    private fun updateStrokeWidth(progress: Int) {
        if (PlayerInitializer.Subtitle.strokeWidth == progress) {
            return
        }

        val progressText = "$progress%"
        viewBinding.subtitleStrokeWidthTv.text = progressText
        viewBinding.subtitleStrokeWidthSb.progress = progress

        SubtitleConfig.putStrokeWidth(progress)
        PlayerInitializer.Subtitle.strokeWidth = progress
        mControlWrapper.updateStrokeWidth()
        onConfigChanged()
    }

    private fun updateTextColor(
        position: Int,
        color: Int,
        isFromUser: Boolean = true
    ) {
        if (PlayerInitializer.Subtitle.textColor == color) {
            return
        }

        val progressText = "$position%"
        viewBinding.subtitleColorTv.text = progressText
        if (isFromUser.not()) {
            viewBinding.subtitleColorSb.seekTo(position)
        }

        SubtitleConfig.putTextColor(color)
        PlayerInitializer.Subtitle.textColor = color
        mControlWrapper.updateTextColor()
        onConfigChanged()
    }

    private fun updateStrokeColor(
        position: Int,
        color: Int,
        isFromUser: Boolean = true
    ) {
        if (PlayerInitializer.Subtitle.strokeColor == color) {
            return
        }

        val progressText = "$position%"
        viewBinding.subtitleStrokeColorTv.text = progressText
        if (isFromUser.not()) {
            viewBinding.subtitleStrokeColorSb.seekTo(position)
        }

        SubtitleConfig.putStrokeColor(color)
        PlayerInitializer.Subtitle.strokeColor = color
        mControlWrapper.updateStrokeColor()
        onConfigChanged()
    }

    private fun updateAlpha(progress: Int) {
        if (PlayerInitializer.Subtitle.alpha == progress) {
            return
        }

        val progressText = "$progress%"
        viewBinding.subtitleAlphaTv.text = progressText
        viewBinding.subtitleAlphaSb.progress = progress

        SubtitleConfig.putAlpha(progress)
        PlayerInitializer.Subtitle.alpha = progress
        mControlWrapper.updateAlpha()
        SubtitleRendererRegistry.current()?.updateOpacity(progress)
        onConfigChanged()
    }

    private fun updateFontScaleOffset(offsetPercent: Int) {
        val clampedOffset = offsetPercent.coerceIn(FONT_SCALE_OFFSET_MIN, FONT_SCALE_OFFSET_MAX)
        if (PlayerInitializer.Subtitle.fontScaleOffsetPercent == clampedOffset) {
            return
        }

        viewBinding.subtitleFontScaleOffsetTv.text = formatOffsetText(clampedOffset)
        viewBinding.subtitleFontScaleOffsetSb.progress = fontScaleOffsetValueToProgress(clampedOffset)

        SubtitleConfig.putSubtitleFontScaleOffsetPercent(clampedOffset)
        PlayerInitializer.Subtitle.fontScaleOffsetPercent = clampedOffset
        SubtitleRendererRegistry.current()?.updateFontScaleOffset(clampedOffset)
        // Media3 内置字幕（非 ASS/SSA）仍由传统字幕视图渲染，字号偏移需同步刷新文字大小/描边。
        mControlWrapper.updateTextSize()
        mControlWrapper.updateStrokeWidth()
        onConfigChanged()
    }

    private fun updateVerticalOffset(offsetPercent: Int) {
        val clampedOffset = offsetPercent.coerceIn(-VERTICAL_OFFSET_MAX, VERTICAL_OFFSET_MAX)
        if (PlayerInitializer.Subtitle.verticalOffset == clampedOffset) {
            return
        }

        viewBinding.subtitleVerticalOffsetTv.text = formatOffsetText(clampedOffset)
        viewBinding.subtitleVerticalOffsetSb.progress = offsetValueToProgress(clampedOffset)

        SubtitleConfig.putVerticalOffset(clampedOffset)
        PlayerInitializer.Subtitle.verticalOffset = clampedOffset
        mControlWrapper.updateVerticalOffset()
        SubtitleRendererRegistry.current()?.updateVerticalOffset(clampedOffset)
        onConfigChanged()
    }

    private fun resetConfig() {
        if (isMpvMode()) {
            updateFontScaleOffset(PlayerInitializer.Subtitle.DEFAULT_FONT_SCALE_OFFSET_PERCENT)
            updateVerticalOffset(PlayerInitializer.Subtitle.DEFAULT_VERTICAL_OFFSET)
            return
        }
        updateSize(PlayerInitializer.Subtitle.DEFAULT_SIZE)
        updateStrokeWidth(PlayerInitializer.Subtitle.DEFAULT_STROKE)
        updateAlpha(PlayerInitializer.Subtitle.DEFAULT_ALPHA)
        updateFontScaleOffset(PlayerInitializer.Subtitle.DEFAULT_FONT_SCALE_OFFSET_PERCENT)
        updateVerticalOffset(PlayerInitializer.Subtitle.DEFAULT_VERTICAL_OFFSET)

        val defaultTextColor = PlayerInitializer.Subtitle.DEFAULT_TEXT_COLOR
        val textColorPosition = viewBinding.subtitleColorSb.getPositionFromColor(defaultTextColor)
        updateTextColor(textColorPosition, defaultTextColor, isFromUser = false)

        val defaultStrokeColor = PlayerInitializer.Subtitle.DEFAULT_STROKE_COLOR
        val strokePosition =
            viewBinding.subtitleStrokeColorSb.getPositionFromColor(defaultStrokeColor)
        updateStrokeColor(strokePosition, defaultStrokeColor, isFromUser = false)
    }

    private fun onConfigChanged() {
        viewBinding.tvResetSubtitleConfig.isVisible = isConfigChanged()
    }

    private fun isConfigChanged(): Boolean {
        if (isMpvMode()) {
            return PlayerInitializer.Subtitle.fontScaleOffsetPercent != PlayerInitializer.Subtitle.DEFAULT_FONT_SCALE_OFFSET_PERCENT ||
                PlayerInitializer.Subtitle.verticalOffset != PlayerInitializer.Subtitle.DEFAULT_VERTICAL_OFFSET
        }
        return PlayerInitializer.Subtitle.textSize != PlayerInitializer.Subtitle.DEFAULT_SIZE ||
            PlayerInitializer.Subtitle.strokeWidth != PlayerInitializer.Subtitle.DEFAULT_STROKE ||
            PlayerInitializer.Subtitle.textColor != PlayerInitializer.Subtitle.DEFAULT_TEXT_COLOR ||
            PlayerInitializer.Subtitle.strokeColor != PlayerInitializer.Subtitle.DEFAULT_STROKE_COLOR ||
            PlayerInitializer.Subtitle.alpha != PlayerInitializer.Subtitle.DEFAULT_ALPHA ||
            PlayerInitializer.Subtitle.fontScaleOffsetPercent != PlayerInitializer.Subtitle.DEFAULT_FONT_SCALE_OFFSET_PERCENT ||
            PlayerInitializer.Subtitle.verticalOffset != PlayerInitializer.Subtitle.DEFAULT_VERTICAL_OFFSET
    }

    private fun offsetValueToProgress(value: Int): Int = value + VERTICAL_OFFSET_MAX

    private fun offsetProgressToValue(progress: Int): Int = progress - VERTICAL_OFFSET_MAX

    private fun fontScaleOffsetValueToProgress(value: Int): Int = value - FONT_SCALE_OFFSET_MIN

    private fun fontScaleOffsetProgressToValue(progress: Int): Int = progress + FONT_SCALE_OFFSET_MIN

    private fun formatOffsetText(offsetPercent: Int): String =
        if (offsetPercent > 0) {
            "+$offsetPercent%"
        } else {
            "$offsetPercent%"
        }

    private fun isLibassMode(): Boolean =
        (SubtitleRendererRegistry.current()?.backend ?: PlayerInitializer.Subtitle.backend) != SubtitleRendererBackend.LEGACY_CANVAS

    private fun isMpvMode(): Boolean = SubtitleRendererRegistry.current()?.backend == SubtitleRendererBackend.MPV_NATIVE
}
