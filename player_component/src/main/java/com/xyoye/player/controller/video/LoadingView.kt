package com.xyoye.player.controller.video

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Point
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.databinding.DataBindingUtil
import com.xyoye.data_component.enums.PlayState
import com.xyoye.player.wrapper.ControlWrapper
import com.xyoye.player_component.R
import com.xyoye.player_component.databinding.LayoutLoadingBinding

/**
 * Created by xyoye on 2021/1/22.
 */

class LoadingView(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr),
    InterControllerView {
    private val viewBinding =
        DataBindingUtil.inflate<LayoutLoadingBinding>(
            LayoutInflater.from(context),
            R.layout.layout_loading,
            this,
            true,
        )

    private val cycleAnimator =
        ObjectAnimator
            .ofFloat(viewBinding.loadingIv, "rotation", 360f)
            .apply {
                duration = 1500
                interpolator = LinearInterpolator()
                repeatCount = ValueAnimator.INFINITE
            }

    private var sourcePreparing = false
    private var kernelPreparing = false
    private var playState = PlayState.STATE_IDLE
    private var showPending = false
    private val showLoading =
        Runnable {
            showPending = false
            viewBinding.loadingPanel.isVisible = true
            cycleAnimator.start()
        }

    override fun attach(controlWrapper: ControlWrapper) {
    }

    override fun getView() = this

    override fun onVisibilityChanged(isVisible: Boolean) {
    }

    override fun onPlayStateChanged(playState: PlayState) {
        this.playState = playState
        kernelPreparing =
            when (playState) {
                PlayState.STATE_PREPARING -> true
                PlayState.STATE_PREPARED,
                PlayState.STATE_BUFFERING_PAUSED,
                PlayState.STATE_BUFFERING_PLAYING -> kernelPreparing
                else -> false
            }
        updateLoading()
    }

    internal fun setSourcePreparing(preparing: Boolean) {
        sourcePreparing = preparing
        updateLoading()
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

    private fun updateLoading() {
        val preparing = sourcePreparing || kernelPreparing
        if (preparing || playState == PlayState.STATE_BUFFERING_PAUSED) {
            viewBinding.loadingText.text = if (preparing) "正在准备播放…" else "正在缓冲…"
            if (viewBinding.loadingPanel.isGone && !showPending) {
                showPending = true
                postDelayed(showLoading, 200)
            }
        } else {
            hideLoading()
        }
    }

    private fun hideLoading() {
        removeCallbacks(showLoading)
        showPending = false
        if (viewBinding.loadingPanel.isVisible) {
            viewBinding.loadingPanel.isVisible = false
            cycleAnimator.cancel()
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(showLoading)
        showPending = false
        cycleAnimator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateLoading()
        if (viewBinding.loadingPanel.isVisible) cycleAnimator.start()
    }
}
