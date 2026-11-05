package com.xyoye.player.controller.setting

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import androidx.core.view.isVisible
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import com.xyoye.common_component.config.SubtitleConfig
import com.xyoye.common_component.enums.SubtitleRendererBackend
import com.xyoye.common_component.extension.observeProgressChange
import com.xyoye.data_component.enums.SettingViewType
import com.xyoye.data_component.enums.TrackType
import com.xyoye.player.info.PlayerInitializer
import com.xyoye.player.kernel.impl.media3.Media3SubtitleFormatClassifier
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

    private enum class StyleMode { TEXT, ASS, BITMAP, MPV }

    private var styleMode = StyleMode.TEXT

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
        when (styleMode) {
            StyleMode.TEXT -> viewBinding.subtitleSizeSb.requestFocus()
            StyleMode.BITMAP -> viewBinding.subtitleAlphaSb.requestFocus()
            StyleMode.ASS, StyleMode.MPV -> viewBinding.subtitleVerticalOffsetSb.requestFocus()
        }
    }

    override fun onTrackChanged(type: TrackType) {
        if (type != TrackType.SUBTITLE || !isSettingShowing()) return
        val focused = findFocus()
        applyBackendVisibility()
        applySubtitleStyleStatus()
        if (focused != null && !focused.isShown) onViewShowed()
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
        val track = mControlWrapper.getTracks(TrackType.SUBTITLE).firstOrNull { it.selected && !it.disable }
        styleMode =
            when {
                isMpvMode() -> StyleMode.MPV
                Media3SubtitleFormatClassifier.isSsaMime(track?.subtitleMimeType) -> StyleMode.ASS
                track?.subtitleMimeType in setOf(MimeTypes.APPLICATION_PGS, MimeTypes.APPLICATION_VOBSUB, MimeTypes.APPLICATION_DVBSUBS) ->
                    StyleMode.BITMAP
                else -> StyleMode.TEXT
            }
        val enableLegacyStyling = styleMode == StyleMode.TEXT
        val enableLibassScaling = styleMode == StyleMode.ASS || styleMode == StyleMode.MPV
        // 按当前轨道的实际渲染能力显示设置，ASS 保留文件样式，位图没有文字样式。
        viewBinding.subtitleSizeRow.isVisible = enableLegacyStyling
        viewBinding.subtitleSizeSb.isVisible = enableLegacyStyling
        viewBinding.subtitleStrokeWidthRow.isVisible = enableLegacyStyling
        viewBinding.subtitleStrokeWidthSb.isVisible = enableLegacyStyling
        viewBinding.subtitleColorRow.isVisible = enableLegacyStyling
        viewBinding.subtitleColorSb.isVisible = enableLegacyStyling
        viewBinding.subtitleStrokeColorRow.isVisible = enableLegacyStyling
        viewBinding.subtitleStrokeColorSb.isVisible = enableLegacyStyling
        viewBinding.subtitleAlphaRow.isVisible = styleMode != StyleMode.MPV
        viewBinding.subtitleAlphaSb.isVisible = styleMode != StyleMode.MPV
        viewBinding.subtitleNativeStyleTips.isVisible = styleMode == StyleMode.MPV
        // 字号偏移供 libass / mpv 使用，普通文字使用独立的文字大小。
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
        updateVerticalOffset(PlayerInitializer.Subtitle.DEFAULT_VERTICAL_OFFSET)
        if (styleMode != StyleMode.MPV) updateAlpha(PlayerInitializer.Subtitle.DEFAULT_ALPHA)
        if (styleMode == StyleMode.ASS || styleMode == StyleMode.MPV) {
            updateFontScaleOffset(PlayerInitializer.Subtitle.DEFAULT_FONT_SCALE_OFFSET_PERCENT)
        }
        if (styleMode != StyleMode.TEXT) return
        updateSize(PlayerInitializer.Subtitle.DEFAULT_SIZE)
        updateStrokeWidth(PlayerInitializer.Subtitle.DEFAULT_STROKE)

        val defaultTextColor = PlayerInitializer.Subtitle.DEFAULT_TEXT_COLOR
        val textColorPosition = viewBinding.subtitleColorSb.getPositionFromColor(defaultTextColor)
        updateTextColor(textColorPosition, defaultTextColor, isFromUser = false)

        val defaultStrokeColor = PlayerInitializer.Subtitle.DEFAULT_STROKE_COLOR
        val strokePosition =
            viewBinding.subtitleStrokeColorSb.getPositionFromColor(defaultStrokeColor)
        updateStrokeColor(strokePosition, defaultStrokeColor, isFromUser = false)
    }

    private fun onConfigChanged() {
        val resetFocused = viewBinding.tvResetSubtitleConfig.hasFocus()
        viewBinding.tvResetSubtitleConfig.isVisible = isConfigChanged()
        if (resetFocused && !viewBinding.tvResetSubtitleConfig.isVisible) onViewShowed()
    }

    private fun isConfigChanged(): Boolean {
        val settings = PlayerInitializer.Subtitle
        return settings.verticalOffset != settings.DEFAULT_VERTICAL_OFFSET ||
            (styleMode != StyleMode.MPV && settings.alpha != settings.DEFAULT_ALPHA) ||
            (
                (styleMode == StyleMode.ASS || styleMode == StyleMode.MPV) &&
                    settings.fontScaleOffsetPercent != settings.DEFAULT_FONT_SCALE_OFFSET_PERCENT
            ) ||
            (
                styleMode == StyleMode.TEXT &&
                    (
                        settings.textSize != settings.DEFAULT_SIZE ||
                            settings.strokeWidth != settings.DEFAULT_STROKE ||
                            settings.textColor != settings.DEFAULT_TEXT_COLOR ||
                            settings.strokeColor != settings.DEFAULT_STROKE_COLOR
                    )
            )
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

    private fun isMpvMode(): Boolean = SubtitleRendererRegistry.current()?.backend == SubtitleRendererBackend.MPV_NATIVE
}
