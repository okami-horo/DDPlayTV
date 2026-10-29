package com.xyoye.player.controller.video

import android.content.Context
import android.graphics.Point
import android.view.LayoutInflater
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.databinding.DataBindingUtil
import com.xyoye.common_component.utils.dp2px
import com.xyoye.data_component.enums.PlayState
import com.xyoye.data_component.enums.SettingViewType
import com.xyoye.player.utils.MessageTime
import com.xyoye.player.wrapper.ControlWrapper
import com.xyoye.player_component.R
import com.xyoye.player_component.databinding.LayoutPlayerControllerBinding

/**
 * Created by xyoye on 2022/11/13.
 */

class PlayerControlView(
    context: Context,
) : InterControllerView {
    private val isScreenShotEnabled = false

    private val viewBinding =
        DataBindingUtil.inflate<LayoutPlayerControllerBinding>(
            LayoutInflater.from(context),
            R.layout.layout_player_controller,
            null,
            false,
        )

    private lateinit var mControlWrapper: ControlWrapper

    init {
        // 锁定按钮当前不在 TV 端开放，显隐和交互统一收敛到隐藏态。
        viewBinding.playerLockIv.apply {
            isVisible = false
            isEnabled = false
            isFocusable = false
            setOnClickListener(null)
        }

        if (isScreenShotEnabled) {
            viewBinding.playerShotIv.setOnClickListener {
                mControlWrapper.showSettingView(SettingViewType.SCREEN_SHOT)
            }
        } else {
            hideShotImmediately()
        }
    }

    override fun attach(controlWrapper: ControlWrapper) {
        mControlWrapper = controlWrapper
    }

    override fun getView() = viewBinding.root

    override fun onVisibilityChanged(isVisible: Boolean) {
        updateShotVisible(isVisible)
    }

    override fun onPlayStateChanged(playState: PlayState) {
        // 播放/暂停状态由底部控制器负责渲染，此处仅保留扩展点。
    }

    override fun onProgressChanged(
        duration: Long,
        position: Long,
    ) {
        // 进度条由专门控制器管理，此处不重复处理。
    }

    override fun onLockStateChanged(isLocked: Boolean) {
        // 锁定能力当前默认关闭，保留状态同步以便未来恢复。
        viewBinding.playerLockIv.isSelected = isLocked
        updateShotVisible(!isLocked)
    }

    override fun onVideoSizeChanged(videoSize: Point) {
        // 该视图不依赖视频尺寸进行布局。
    }

    fun showMessage(
        text: String,
        time: MessageTime,
    ) {
        viewBinding.messageContainer.showMessage(text, time)
    }

    fun clearMessage() {
        viewBinding.messageContainer.clearMessage()
    }

    private fun hideShotImmediately() {
        viewBinding.playerShotIv.apply {
            isVisible = false
            translationX = 0f
            clearAnimation()
        }
    }

    private fun updateShotVisible(isVisible: Boolean) {
        if (!isScreenShotEnabled) {
            hideShotImmediately()
            return
        }

        if (isVisible) {
            viewBinding.playerShotIv.isVisible = true
            ViewCompat
                .animate(viewBinding.playerShotIv)
                .translationX(0f)
                .setDuration(300)
                .start()
        } else {
            val translateX = dp2px(60).toFloat()
            ViewCompat
                .animate(viewBinding.playerShotIv)
                .translationX(translateX)
                .setDuration(300)
                .start()
        }
    }
}
