package com.xyoye.player.controller.video

import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.databinding.DataBindingUtil
import com.xyoye.common_component.focus.applyDpadFocusable
import com.xyoye.common_component.focus.requestDefaultFocus
import com.xyoye.data_component.enums.PlayState
import com.xyoye.data_component.enums.SettingViewType
import com.xyoye.data_component.enums.TrackType
import com.xyoye.player.controller.action.PlayerAction
import com.xyoye.player.utils.DecodeType
import com.xyoye.player.utils.formatDuration
import com.xyoye.player.wrapper.ControlWrapper
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

    private var switchVideoSourceBlock: ((Int) -> Unit)? = null

    private var isControllerVisible = false
    private var lastFocusedControlId = R.id.play_iv

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

        viewBinding.danmuControlIv.setOnClickListener {
            if (!controlsInputEnabled) return@setOnClickListener
            actionHandler?.invoke(PlayerAction.ToggleDanmu) ?: mControlWrapper.toggleDanmuVisible()
        }
        viewBinding.subtitleControlIv.setOnClickListener {
            if (controlsInputEnabled) mControlWrapper.showSettingView(SettingViewType.SUBTITLE_QUICK)
        }
        viewBinding.audioControl.setOnClickListener {
            if (controlsInputEnabled) mControlWrapper.showSettingView(SettingViewType.TRACKS, TrackType.AUDIO)
        }
        viewBinding.rewind.setOnClickListener { seekBy(-15_000L) }
        viewBinding.forward.setOnClickListener { seekBy(15_000L) }

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
        listOf(
            viewBinding.rewind,
            viewBinding.playIv,
            viewBinding.forward,
            viewBinding.ivPreviousSource,
            viewBinding.ivNextSource,
            viewBinding.videoListIv,
            viewBinding.subtitleControlIv,
            viewBinding.audioControl,
            viewBinding.danmuControlIv
        ).forEach { control ->
            control.setOnFocusChangeListener { view, focused ->
                if (focused) {
                    lastFocusedControlId = view.id
                }
                updateTransportFocusCaption(if (focused) view else null)
            }
        }
        updateFocusNavigation()
        updateControlsInteractiveState(false)
    }

    private fun seekBy(offsetMs: Long) {
        if (!controlsInputEnabled || !mControlWrapper.isUserSeekAllowed()) return
        actionHandler?.invoke(PlayerAction.SeekBy(offsetMs))
            ?: mControlWrapper.seekTo((mControlWrapper.getCurrentPosition() + offsetMs).coerceIn(0L, mControlWrapper.getDuration()))
    }

    override fun attach(controlWrapper: ControlWrapper) {
        mControlWrapper = controlWrapper

        updateDecodeTypeHint()
        updateDanmuState()
        updateSubtitleState()
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
            updateDanmuState()
            updateSubtitleState()
            if (isControllerVisible) {
                // Avoid re-focusing play on every DPAD event when controller is already visible.

                updateDecodeTypeHint()
                return
            }
            isControllerVisible = true

            updateDecodeTypeHint()
            updateControlsInteractiveState(true)
            ViewCompat
                .animate(viewBinding.playerBottomLl)
                .translationY(0f)
                .setDuration(300)
                .start()
            post {
                val previous = findViewById<View>(lastFocusedControlId)
                if (previous?.isShown == true && previous.isEnabled && previous.isFocusable) {
                    previous.requestDefaultFocus()
                } else {
                    viewBinding.playIv.requestDefaultFocus()
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
        viewBinding.playIv.contentDescription = if (viewBinding.playIv.isSelected) "暂停" else "播放"
        if (viewBinding.playIv.hasFocus()) updateTransportFocusCaption(viewBinding.playIv)
        updateSeekActions()
    }

    override fun onProgressChanged(
        duration: Long,
        position: Long
    ) {
        updateSeekActions()
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

        val bufferedPercent =
            if (mControlWrapper.supportBufferedPercentage()) {
                mControlWrapper
                    .getBufferedPercentage()
                    .coerceIn(0, 100)
                    .let { percent -> if (percent > 95) 100 else percent }
            } else {
                viewBinding.playSeekBar.progress
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

    override fun onTrackChanged(type: TrackType) {
        if (type == TrackType.DANMU) {
            updateDanmuState()
            updateControlsInteractiveState(controlsInputEnabled)
        }
        if (type == TrackType.SUBTITLE) {
            updateSubtitleState()
            updateControlsInteractiveState(controlsInputEnabled)
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

    fun setSwitchVideoSourceBlock(block: (Int) -> Unit) {
        switchVideoSourceBlock = block
    }

    private fun updateSourceAction() {
        val videoSource = mControlWrapper.getVideoSource()
        val grouped = videoSource.getGroupSize() > 1
        val focused = findFocus()
        viewBinding.ivNextSource.isVisible = grouped
        viewBinding.ivPreviousSource.isVisible = grouped
        viewBinding.videoListIv.isVisible = grouped
        viewBinding.episodeDivider.isVisible = grouped
        viewBinding.singleVideoHint.isVisible = !grouped
        viewBinding.ivNextSource.isEnabled = grouped && videoSource.hasNextSource()
        viewBinding.ivPreviousSource.isEnabled = grouped && videoSource.hasPreviousSource()
        viewBinding.ivNextSource.alpha = if (viewBinding.ivNextSource.isEnabled) 1f else 0.3f
        viewBinding.ivPreviousSource.alpha = if (viewBinding.ivPreviousSource.isEnabled) 1f else 0.3f
        updateControlsInteractiveState(controlsInputEnabled)
        if (focused != null && (!focused.isShown || !focused.isEnabled)) {
            if (grouped) viewBinding.videoListIv.requestDefaultFocus() else viewBinding.playIv.requestDefaultFocus()
        }
    }

    private fun updateSeekActions() {
        if (!this::mControlWrapper.isInitialized) return
        val allowed = mControlWrapper.isUserSeekAllowed() && !mControlWrapper.isLive() && mControlWrapper.getDuration() > 0
        val lostFocus = !allowed && (viewBinding.rewind.hasFocus() || viewBinding.forward.hasFocus())
        listOf(viewBinding.rewind, viewBinding.forward).forEach {
            it.isEnabled = allowed
            it.alpha = if (allowed) 1f else 0.3f
            it.applyDpadFocusable(controlsInputEnabled && allowed, clickable = controlsInputEnabled && allowed)
        }
        updateFocusNavigation()
        if (lostFocus) viewBinding.playIv.requestDefaultFocus()
    }

    private fun updateControlsInteractiveState(enabled: Boolean) {
        controlsInputEnabled = enabled
        val inTouchMode = isInTouchMode
        val focusables =
            listOf(
                viewBinding.playIv,
                viewBinding.rewind,
                viewBinding.forward,
                viewBinding.audioControl,
                viewBinding.ivPreviousSource,
                viewBinding.ivNextSource,
                viewBinding.videoListIv,
            )
        focusables.forEach { view ->
            view.applyDpadFocusable(
                enabled = enabled && view.isEnabled && view.isVisible,
                inTouchMode = inTouchMode,
                clickable = enabled && view.isEnabled && view.isVisible,
            )
        }

        val danmuReady = enabled
        viewBinding.danmuControlIv.applyDpadFocusable(
            enabled = danmuReady,
            inTouchMode = inTouchMode,
            clickable = enabled,
        )
        viewBinding.danmuControlIv.alpha = 1f
        if (!danmuReady && viewBinding.danmuControlIv.hasFocus() && !inTouchMode) {
            viewBinding.playIv.requestDefaultFocus()
        }
        val subtitleReady = enabled
        viewBinding.subtitleControlIv.applyDpadFocusable(
            enabled = subtitleReady,
            inTouchMode = inTouchMode,
            clickable = enabled,
        )
        viewBinding.subtitleControlIv.alpha = 1f
        if (!subtitleReady && viewBinding.subtitleControlIv.hasFocus() && !inTouchMode) {
            viewBinding.playIv.requestDefaultFocus()
        }
        updateSeekActions()
        updateFocusNavigation()
    }

    private fun updateFocusNavigation() {
        val focusables =
            listOf(
                viewBinding.rewind,
                viewBinding.playIv,
                viewBinding.forward,
                viewBinding.ivPreviousSource,
                viewBinding.ivNextSource,
                viewBinding.videoListIv,
                viewBinding.subtitleControlIv,
                viewBinding.audioControl,
                viewBinding.danmuControlIv
            ).filter { it.isVisible && it.isEnabled && it.isFocusable }

        focusables.forEachIndexed { index, view ->
            val left = focusables.getOrNull(index - 1) ?: view
            val right = focusables.getOrNull(index + 1) ?: view
            view.nextFocusLeftId = left.id
            view.nextFocusRightId = right.id
            view.nextFocusUpId = R.id.video_title_tv
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

    private fun updateDanmuState() {
        val visible = mControlWrapper.isUserDanmuVisible()
        viewBinding.danmuControlIv.isSelected = visible
        viewBinding.danmuControlLabel.setText(if (visible) R.string.player_danmaku_on else R.string.player_danmaku_off)
        viewBinding.danmuControlIv.contentDescription =
            context.getString(if (visible) R.string.player_danmaku_disable_action else R.string.player_danmaku_enable_action)
    }

    private fun updateSubtitleState() {
        val selected = mControlWrapper.getTracks(TrackType.SUBTITLE).firstOrNull { !it.disable && it.selected }
        val visible = mControlWrapper.isUserSubtitleVisible() && selected != null
        viewBinding.subtitleControlIv.isSelected = visible
        viewBinding.subtitleControlIv.contentDescription =
            if (visible) "字幕：${selected?.name}，按确认选择" else "字幕已关闭，按确认选择"
    }

    private fun updateTransportFocusCaption(control: View?) {
        val show =
            control != null && control.id in listOf(R.id.rewind, R.id.play_iv, R.id.forward, R.id.iv_previous_source, R.id.iv_next_source)
        viewBinding.transportFocusLabel.isVisible = show
        if (!show || control == null) return
        viewBinding.transportFocusLabel.text = control.contentDescription
        viewBinding.transportFocusLabel.doOnLayout { label ->
            val bounds = Rect()
            control.getDrawingRect(bounds)
            viewBinding.playerBottomLl.offsetDescendantRectToMyCoords(control, bounds)
            label.x = bounds.exactCenterX() - label.width / 2f
        }
    }
}
