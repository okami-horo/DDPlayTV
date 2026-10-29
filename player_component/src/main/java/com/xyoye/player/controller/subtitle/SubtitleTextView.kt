package com.xyoye.player.controller.subtitle

import android.content.Context
import android.graphics.Point
import android.util.AttributeSet
import com.xyoye.common_component.enums.SubtitleRendererBackend
import com.xyoye.data_component.enums.PlayState
import com.xyoye.player.controller.video.InterControllerView
import com.xyoye.player.info.PlayerInitializer
import com.xyoye.player.wrapper.ControlWrapper
import com.xyoye.subtitle.BaseSubtitleView
import com.xyoye.subtitle.SubtitleText

/**
 * Created by xyoye on 2020/12/14.
 *
 * 用于显示所有文字字幕（外挂字幕、播放器内置字幕）
 */

class SubtitleTextView(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BaseSubtitleView(context, attrs, defStyleAttr),
    InterControllerView {
    private lateinit var mControlWrapper: ControlWrapper

    // 最后一次显示的字幕
    private var lastSubtitle: List<SubtitleText> = emptyList()

    init {
        updateTextSize()
        updateStrokeWidth()
        updateTextColor()
        updateStrokeColor()
        updateAlpha()
        updateShadow()
        updateVerticalOffset()
    }

    override fun attach(controlWrapper: ControlWrapper) {
        mControlWrapper = controlWrapper
    }

    override fun getView() = this

    override fun onVisibilityChanged(isVisible: Boolean) {
    }

    override fun onPlayStateChanged(playState: PlayState) {
    }

    override fun onProgressChanged(
        duration: Long,
        position: Long
    ) {
    }

    override fun onLockStateChanged(isLocked: Boolean) {
    }

    override fun onVideoSizeChanged(videoSize: Point) {
    }

    fun setSubtitle(subtitle: List<SubtitleText>?) {
        lastSubtitle = subtitle ?: emptyList()
        showSubtitle(lastSubtitle)
    }

    fun isEmptySubtitle() = lastSubtitle.isEmpty()

    fun updateTextSize() {
        val fontScale = subtitleFontScale()
        val textSize = PlayerInitializer.Subtitle.textSize
        val realSize = (40f * textSize / 100f * fontScale).toInt()
        setTextSize(realSize)
    }

    fun updateStrokeWidth() {
        val fontScale = subtitleFontScale()
        val strokeWidth = PlayerInitializer.Subtitle.strokeWidth
        val realWidth = (10f * strokeWidth / 100f * fontScale).toInt()
        setStrokeWidth(realWidth)
    }

    fun updateTextColor() {
        val textColor = PlayerInitializer.Subtitle.textColor
        setTextColor(textColor)
    }

    fun updateStrokeColor() {
        val strokeColor = PlayerInitializer.Subtitle.strokeColor
        setStrokeColor(strokeColor)
    }

    fun updateAlpha() {
        val alpha = PlayerInitializer.Subtitle.alpha
        setAlpha(alpha)
    }

    fun updateVerticalOffset() {
        val offsetPercent = PlayerInitializer.Subtitle.verticalOffset
        setVerticalOffset(offsetPercent)
    }

    fun updateShadow() {
        // 直接调用父类的updateShadowLayer方法来更新阴影设置
        updateShadowLayer()
    }

    private fun subtitleFontScale(): Float {
        if (PlayerInitializer.Subtitle.backend != SubtitleRendererBackend.LIBASS) {
            return 1f
        }
        val offsetPercent =
            PlayerInitializer.Subtitle.fontScaleOffsetPercent.coerceIn(
                FONT_SCALE_OFFSET_MIN,
                FONT_SCALE_OFFSET_MAX
            )
        return (1f + offsetPercent / 100f).coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
    }

    private companion object {
        private const val FONT_SCALE_OFFSET_MIN = -50
        private const val FONT_SCALE_OFFSET_MAX = 400
        private const val MIN_FONT_SCALE = 0.1f
        private const val MAX_FONT_SCALE = 5f
    }
}
