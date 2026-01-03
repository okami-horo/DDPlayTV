package com.xyoye.player.controller.video

import android.content.Context
import android.graphics.Point
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import androidx.core.graphics.BlendModeColorFilterCompat
import androidx.core.graphics.BlendModeCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.databinding.DataBindingUtil
import com.xyoye.common_component.extension.toResColor
import com.xyoye.common_component.extension.toResDrawable
import com.xyoye.data_component.bean.SendDanmuBean
import com.xyoye.data_component.enums.PlayState
import com.xyoye.data_component.enums.SettingViewType
import com.xyoye.data_component.enums.TrackType
import com.xyoye.player.controller.action.PlayerAction
import com.xyoye.player.utils.formatDuration
import com.xyoye.player.wrapper.ControlWrapper
import com.xyoye.player.utils.DecodeType
import com.xyoye.player_component.R
import com.xyoye.player_component.databinding.LayoutPlayerBottomBinding

/**
 * Created by xyoye on 2020/11/3.
 */

class PlayerBottomView(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr),
    InterControllerView,
    OnSeekBarChangeListener {
    private var mIsDragging = false
    private lateinit var mControlWrapper: ControlWrapper
    private var controlsInputEnabled = false
    private var actionHandler: ((PlayerAction) -> Unit)? = null

    private var sendDanmuBlock: ((SendDanmuBean) -> Unit)? = null

    private var switchVideoSourceBlock: ((Int) -> Unit)? = null

    private var isControllerVisible = false

    private val viewBinding =
        DataBindingUtil.inflate<LayoutPlayerBottomBinding>(
            LayoutInflater.from(context),
            R.layout.layout_player_bottom,
            this,
            true,
        )

    init {

        viewBinding.playIv.setOnClickListener {
            if (!controlsInputEnabled) return@setOnClickListener
            actionHandler?.invoke(PlayerAction.TogglePlay) ?: mControlWrapper.togglePlay()
        }

        viewBinding.danmuControlIv.setOnCheckedChangeListener { _, isChecked ->
             if (!controlsInputEnabled) return@setOnCheckedChangeListener
             val isDanmuVisible = mControlWrapper.isUserDanmuVisible()
             if (isChecked != isDanmuVisible) {
                 actionHandler?.invoke(PlayerAction.ToggleDanmu) ?: mControlWrapper.toggleDanmuVisible()
                 syncDanmuToggleState()
             }
        }

        /*
        viewBinding.sendDanmuTv.setOnClickListener {
            if (!UserConfig.isUserLoggedIn()) {
                ToastCenter.showWarning(R.string.tips_login_required.toResString())
                return@setOnClickListener
            }

            if (!mControlWrapper.allowSendDanmu()) {
                ToastCenter.showOriginalToast("当前弹幕不支持发送弹幕")
                return@setOnClickListener
            }

            mControlWrapper.hideController()
            mControlWrapper.pause()
            SendDanmuDialog(mControlWrapper.getCurrentPosition(), context) {
                //添加弹幕到view
                mControlWrapper.addDanmuToView(it)

                //添加弹幕到文件和服务器
                sendDanmuBlock?.invoke(it)
            }.show()
        }
         */
        viewBinding.sendDanmuTv.apply {
            isVisible = false
            isEnabled = false
            setOnClickListener(null)
        }

        viewBinding.ivNextSource.setOnClickListener {
            if (!controlsInputEnabled) return@setOnClickListener
            if (!it.isEnabled) return@setOnClickListener
            val videoSource = mControlWrapper.getVideoSource()
            if (videoSource.hasNextSource()) {
                actionHandler?.invoke(PlayerAction.NextSource)
                    ?: switchVideoSourceBlock?.invoke(videoSource.getGroupIndex() + 1)
            }
        }

        viewBinding.ivPreviousSource.setOnClickListener {
            if (!controlsInputEnabled) return@setOnClickListener
            if (!it.isEnabled) return@setOnClickListener
            val videoSource = mControlWrapper.getVideoSource()
            if (videoSource.hasPreviousSource()) {
                actionHandler?.invoke(PlayerAction.PreviousSource)
                    ?: switchVideoSourceBlock?.invoke(videoSource.getGroupIndex() - 1)
            }
        }

        viewBinding.videoListIv.setOnClickListener {
            if (!controlsInputEnabled) return@setOnClickListener
            actionHandler?.invoke(PlayerAction.OpenSourceList)
                ?: mControlWrapper.showSettingView(SettingViewType.SWITCH_VIDEO_SOURCE)
        }

        viewBinding.playSeekBar.setOnSeekBarChangeListener(this)
        updateFocusNavigation()
        updateControlsInteractiveState(false)
    }

    override fun attach(controlWrapper: ControlWrapper) {
        mControlWrapper = controlWrapper
        syncDanmuToggleState()
        updateDecodeTypeHint()
    }

    override fun getView() = this

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (!controlsInputEnabled) {
            return false
        }
        return super.dispatchKeyEvent(event)
    }

    fun setActionHandler(handler: (PlayerAction) -> Unit) {
        actionHandler = handler
    }

    override fun onVisibilityChanged(isVisible: Boolean) {
        if (isVisible) {
            if (isControllerVisible) {
                // Avoid re-focusing play on every DPAD event when controller is already visible.
                syncDanmuToggleState()
                updateDecodeTypeHint()
                return
            }
            isControllerVisible = true
            syncDanmuToggleState()
            updateDecodeTypeHint()
            updateControlsInteractiveState(true)
            ViewCompat
                .animate(viewBinding.playerBottomLl)
                .translationY(0f)
                .setDuration(300)
                .start()
            post {
                if (!viewBinding.playIv.hasFocus()) {
                    viewBinding.playIv.requestFocus()
                }
            }
        } else {
            if (!isControllerVisible) {
                return
            }
            isControllerVisible = false
            val height = viewBinding.playerBottomLl.height.toFloat()
            ViewCompat
                .animate(viewBinding.playerBottomLl)
                .translationY(height)
                .setDuration(300)
                .start()
            clearFocus()
            updateControlsInteractiveState(false)
        }
    }

    override fun onPlayStateChanged(playState: PlayState) {
        updateDecodeTypeHint()
        when (playState) {
            PlayState.STATE_IDLE -> {
                viewBinding.playSeekBar.progress = 0
                viewBinding.playSeekBar.secondaryProgress = 0
            }

            PlayState.STATE_PREPARING -> {
                updateSourceAction()
                viewBinding.playIv.isSelected = false
            }

            PlayState.STATE_START_ABORT,
            PlayState.STATE_PREPARED,
            PlayState.STATE_PAUSED,
            PlayState.STATE_ERROR -> {
                viewBinding.playIv.isSelected = false
                mControlWrapper.stopProgress()
            }

            PlayState.STATE_PLAYING -> {
                viewBinding.playIv.isSelected = true
                mControlWrapper.startProgress()
            }

            PlayState.STATE_BUFFERING_PAUSED,
            PlayState.STATE_BUFFERING_PLAYING -> {
                viewBinding.playIv.isSelected = mControlWrapper.isPlaying()
            }

            PlayState.STATE_COMPLETED -> {
                mControlWrapper.stopProgress()
                viewBinding.playIv.isSelected = mControlWrapper.isPlaying()
            }
        }
    }

    override fun onProgressChanged(
        duration: Long,
        position: Long
    ) {
        if (mControlWrapper.isLive()) {
            mIsDragging = false
            viewBinding.playSeekBar.isEnabled = false
            viewBinding.playSeekBar.progress = viewBinding.playSeekBar.max
            viewBinding.playSeekBar.secondaryProgress = viewBinding.playSeekBar.max
            viewBinding.currentPositionTv.text = context.getString(R.string.text_live)
            viewBinding.durationTv.text = ""
            return
        }

        if (mIsDragging) return

        if (duration > 0) {
            viewBinding.playSeekBar.progress =
                (position.toFloat() / duration * viewBinding.playSeekBar.max).toInt()
        }

        viewBinding.playSeekBar.isEnabled = duration > 0 && mControlWrapper.isUserSeekAllowed()

        var bufferedPercent = mControlWrapper.getBufferedPercentage()
        if (bufferedPercent > 95) {
            bufferedPercent = 100
        }
        viewBinding.playSeekBar.secondaryProgress = bufferedPercent

        viewBinding.durationTv.text = formatDuration(duration)
        viewBinding.currentPositionTv.text =
            formatDuration(position)
    }

    override fun onLockStateChanged(isLocked: Boolean) {
        // 显示状态与锁定状态相反
        onVisibilityChanged(!isLocked)
    }

    override fun onVideoSizeChanged(videoSize: Point) {
    }

    override fun onPopupModeChanged(isPopup: Boolean) {
    }

    override fun onTrackChanged(type: TrackType) {
        if (type == TrackType.DANMU) {
            syncDanmuToggleState()
        }
    }

    override fun onProgressChanged(
        seekBar: SeekBar?,
        progress: Int,
        fromUser: Boolean
    ) {
        if (!fromUser) {
            return
        }
        if (!mControlWrapper.isUserSeekAllowed()) {
            return
        }
        val duration = mControlWrapper.getDuration()
        val newPosition = (duration * progress) / viewBinding.playSeekBar.max
        viewBinding.currentPositionTv.text =
            formatDuration(newPosition)
    }

    override fun onStartTrackingTouch(seekBar: SeekBar?) {
        if (!mControlWrapper.isUserSeekAllowed()) {
            return
        }
        mIsDragging = true
        mControlWrapper.stopProgress()
        mControlWrapper.stopFadeOut()
    }

    override fun onStopTrackingTouch(seekBar: SeekBar?) {
        if (!mControlWrapper.isUserSeekAllowed()) {
            mIsDragging = false
            return
        }
        mIsDragging = false
        val duration = mControlWrapper.getDuration()
        val newPosition =
            (duration * viewBinding.playSeekBar.progress) / viewBinding.playSeekBar.max
        mControlWrapper.seekTo(newPosition)
        mControlWrapper.startFadeOut()
    }

    fun setSendDanmuBlock(block: (SendDanmuBean) -> Unit) {
        sendDanmuBlock = block
    }

    fun setSwitchVideoSourceBlock(block: (Int) -> Unit) {
        switchVideoSourceBlock = block
    }

    private fun updateSourceAction() {
        val videoSource = mControlWrapper.getVideoSource()
        viewBinding.ivNextSource.isVisible = videoSource.hasNextSource()
        viewBinding.ivPreviousSource.isVisible = videoSource.hasPreviousSource()
        viewBinding.videoListIv.isVisible = videoSource.getGroupSize() > 1

        // 下一个视频资源是否可用
        val hasNextSource = videoSource.hasNextSource()
        viewBinding.ivNextSource.isEnabled = hasNextSource
        val nextIcon = R.drawable.ic_video_next.toResDrawable()
        if (hasNextSource.not() && nextIcon != null) {
            nextIcon.colorFilter =
                BlendModeColorFilterCompat.createBlendModeColorFilterCompat(
                    R.color.gray_60.toResColor(),
                    BlendModeCompat.SRC_IN,
                )
        }
        viewBinding.ivNextSource.setImageDrawable(nextIcon)

        // 上一个视频资源是否可用
        val hasPreviousSource = videoSource.hasPreviousSource()
        viewBinding.ivPreviousSource.isEnabled = hasPreviousSource
        val previousIcon = R.drawable.ic_video_previous.toResDrawable()
        if (hasPreviousSource.not() && previousIcon != null) {
            previousIcon.colorFilter =
                BlendModeColorFilterCompat.createBlendModeColorFilterCompat(
                    R.color.gray_60.toResColor(),
                    BlendModeCompat.SRC_IN,
                )
        }
        viewBinding.ivPreviousSource.setImageDrawable(previousIcon)
        updateFocusNavigation()
    }

    private fun updateControlsInteractiveState(enabled: Boolean) {
        controlsInputEnabled = enabled
        val focusables =
            listOf(
                viewBinding.playIv,
                viewBinding.ivPreviousSource,
                viewBinding.ivNextSource,
                viewBinding.videoListIv,
                viewBinding.danmuControlIv,
            )
        focusables.forEach { view ->
            view.isFocusable = enabled
            view.isFocusableInTouchMode = enabled
            view.isClickable = enabled
        }
    }

    private fun updateFocusNavigation() {
        val focusables = mutableListOf<View>()
        if (viewBinding.videoListIv.isVisible) {
            focusables.add(viewBinding.videoListIv)
        }
        if (viewBinding.ivPreviousSource.isVisible) {
            focusables.add(viewBinding.ivPreviousSource)
        }
        focusables.add(viewBinding.playIv)
        if (viewBinding.ivNextSource.isVisible) {
            focusables.add(viewBinding.ivNextSource)
        }
        focusables.add(viewBinding.danmuControlIv)

        focusables.forEachIndexed { index, view ->
            val left = focusables.getOrNull(index - 1) ?: view
            val right = focusables.getOrNull(index + 1) ?: view
            view.nextFocusLeftId = left.id
            view.nextFocusRightId = right.id
            view.nextFocusUpId = R.id.video_title_tv
        }
    }

    private fun syncDanmuToggleState() {
        if (this::mControlWrapper.isInitialized.not()) return
        val userVisible = mControlWrapper.isUserDanmuVisible()
        if (viewBinding.danmuControlIv.isChecked != userVisible) {
            viewBinding.danmuControlIv.isChecked = userVisible
        }
    }

    private fun updateDecodeTypeHint() {
        val decodeType =
            if (this::mControlWrapper.isInitialized) {
                mControlWrapper.getDecodeType()
            } else {
                DecodeType.HW
            }
        viewBinding.decodeTypeTv.text = decodeType.label
    }
}
