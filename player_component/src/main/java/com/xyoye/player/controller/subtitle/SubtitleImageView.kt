package com.xyoye.player.controller.subtitle

import android.content.Context
import android.graphics.Color
import android.graphics.Point
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.xyoye.common_component.config.SubtitleConfig
import com.xyoye.data_component.enums.PlayState
import com.xyoye.player.controller.video.InterControllerView
import com.xyoye.player.info.PlayerInitializer
import com.xyoye.player.wrapper.ControlWrapper

/**
 * Created by xyoye on 2020/12/21.
 *
 * Displays complete Media3 text and bitmap Cues in the video viewport.
 */

@UnstableApi
class SubtitleImageView(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr),
    InterControllerView {
    private lateinit var mControlWrapper: ControlWrapper
    private val subtitleView = SubtitleView(context)

    private var lastCues: List<Cue>? = null
    private var anchorView: View? = null

    private val anchorLayoutListener =
        View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            alignToRenderView()
        }

    init {
        updateStyle()
        addView(subtitleView)
    }

    override fun attach(controlWrapper: ControlWrapper) {
        mControlWrapper = controlWrapper
        refreshAnchor()
        post { alignToRenderView() }
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
        refreshAnchor()
        if (!alignToRenderView()) {
            // Video size usually arrives before the render view finishes measuring (especially for 4K HDR sources).
            // Retry once on the next frame to avoid binding the subtitle viewport to the raw encoded size.
            post {
                refreshAnchor()
                alignToRenderView()
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refreshAnchor()
        anchorView?.removeOnLayoutChangeListener(anchorLayoutListener)
        anchorView?.addOnLayoutChangeListener(anchorLayoutListener)
        post { alignToRenderView() }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        anchorView?.removeOnLayoutChangeListener(anchorLayoutListener)
    }

    private fun refreshAnchor() {
        if (!this::mControlWrapper.isInitialized) {
            return
        }
        val candidate = mControlWrapper.getRenderView()?.getView()
        if (candidate === anchorView) {
            return
        }
        anchorView?.removeOnLayoutChangeListener(anchorLayoutListener)
        anchorView = candidate
        anchorView?.addOnLayoutChangeListener(anchorLayoutListener)
    }

    private fun alignToRenderView(): Boolean {
        val anchor = anchorView ?: return false
        val width = anchor.width
        val height = anchor.height
        if (width <= 0 || height <= 0) {
            return false
        }
        val anchorLocation = IntArray(2)
        val selfLocation = IntArray(2)
        anchor.getLocationOnScreen(anchorLocation)
        getLocationOnScreen(selfLocation)
        val left = anchorLocation[0] - selfLocation[0]
        val top = anchorLocation[1] - selfLocation[1]

        val layoutParams =
            (subtitleView.layoutParams as? LayoutParams)
                ?: LayoutParams(width, height)
        val targetGravity = Gravity.START or Gravity.TOP
        var changed = false
        if (layoutParams.width != width) {
            layoutParams.width = width
            changed = true
        }
        if (layoutParams.height != height) {
            layoutParams.height = height
            changed = true
        }
        if (layoutParams.leftMargin != left) {
            layoutParams.leftMargin = left
            changed = true
        }
        if (layoutParams.topMargin != top) {
            layoutParams.topMargin = top
            changed = true
        }
        if (layoutParams.rightMargin != 0) {
            layoutParams.rightMargin = 0
            changed = true
        }
        if (layoutParams.bottomMargin != 0) {
            layoutParams.bottomMargin = 0
            changed = true
        }
        if (layoutParams.gravity != targetGravity) {
            layoutParams.gravity = targetGravity
            changed = true
        }
        if (changed) {
            subtitleView.layoutParams = layoutParams
        }
        subtitleView.translationY = -height * PlayerInitializer.Subtitle.verticalOffset / 100f
        return true
    }

    fun isEmptySubtitle() = lastCues.isNullOrEmpty()

    fun setSubtitle(cues: List<Cue>?) {
        lastCues = cues
        subtitleView.setCues(cues)
    }

    fun updateStyle() {
        val settings = PlayerInitializer.Subtitle
        subtitleView.setFixedTextSize(TypedValue.COMPLEX_UNIT_DIP, 40f * settings.textSize / 100f)
        val edge =
            when {
                settings.strokeWidth > 0 -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
                SubtitleConfig.isShadowEnabled() -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
                else -> CaptionStyleCompat.EDGE_TYPE_NONE
            }
        subtitleView.setStyle(
            CaptionStyleCompat(settings.textColor, Color.TRANSPARENT, Color.TRANSPARENT, edge, settings.strokeColor, null)
        )
        subtitleView.alpha = settings.alpha.coerceIn(0, 100) / 100f
        subtitleView.translationY = -subtitleView.height * settings.verticalOffset / 100f
    }
}
