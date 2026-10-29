package com.xyoye.player.controller.setting

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.core.view.isVisible
import com.xyoye.common_component.config.DanmuConfig
import com.xyoye.common_component.utils.hideKeyboard
import com.xyoye.data_component.enums.DanmakuLanguage
import com.xyoye.data_component.enums.SettingViewType
import com.xyoye.player.info.PlayerInitializer
import com.xyoye.player_component.R
import com.xyoye.player_component.databinding.LayoutSettingDanmuConfigureBinding
import master.flame.danmaku.danmaku.model.BaseDanmaku

/**
 * Created by xyoye on 2022/10/18
 */

class SettingDanmuConfigureView(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BaseSettingView<LayoutSettingDanmuConfigureBinding>(context, attrs, defStyleAttr) {
    private var settingMode = BaseDanmaku.TYPE_SCROLL_RL
    private val maxLineCommitCoordinator =
        DanmuMaxLineCommitCoordinator(
            debounceMillis = MAX_LINE_COMMIT_DEBOUNCE_MS,
            scheduler = HandlerScheduler(Handler(Looper.getMainLooper())),
            commitAction = ::commitMaxLine
        )

    init {
        initView()
    }

    override fun getLayoutId() = R.layout.layout_setting_danmu_configure

    override fun getSettingViewType() = SettingViewType.DANMU_CONFIGURE

    override fun onViewShow() {
        updateLanguage(PlayerInitializer.Danmu.language)
        applyDanmuConfigureStatus()
    }

    override fun onViewHide() {
        flushPendingMaxLineCommit()
        hideKeyboard(viewBinding.etScreenMaxNum)
        viewBinding.playerSettingNsv.focusedChild?.clearFocus()
    }

    override fun onDetachedFromWindow() {
        flushPendingMaxLineCommit()
        maxLineCommitCoordinator.cancelPending()
        super.onDetachedFromWindow()
    }

    override fun onViewShowed() {
        val target =
            when (settingMode) {
                BaseDanmaku.TYPE_SCROLL_RL -> viewBinding.llScrollDanmu
                BaseDanmaku.TYPE_FIX_TOP -> viewBinding.llTopDanmu
                BaseDanmaku.TYPE_FIX_BOTTOM -> viewBinding.llBottomDanmu
                else -> viewBinding.llScrollDanmu
            }
        target.requestFocus()
    }

    private fun initView() {
        viewBinding.groupLineLimitTv.setOnKeyListener { _, keyCode, event ->
            if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return@setOnKeyListener false
            if (event.action == KeyEvent.ACTION_DOWN) stepTvMaxLine(if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
            true
        }
        viewBinding.tvLanguageOrigin.setOnClickListener {
            updateLanguage(DanmakuLanguage.ORIGINAL)
            mControlWrapper.setLanguage(DanmakuLanguage.ORIGINAL)
        }

        viewBinding.tvLanguageSc.setOnClickListener {
            updateLanguage(DanmakuLanguage.SC)
            mControlWrapper.setLanguage(DanmakuLanguage.SC)
        }

        viewBinding.tvLanguageTc.setOnClickListener {
            updateLanguage(DanmakuLanguage.TC)
            mControlWrapper.setLanguage(DanmakuLanguage.TC)
        }

        viewBinding.tvKeywordBlock.setOnClickListener {
            mControlWrapper.showSettingView(SettingViewType.KEYWORD_BLOCK)
        }

        viewBinding.llScrollDanmu.setOnClickListener {
            selectSettingMode(BaseDanmaku.TYPE_SCROLL_RL)
        }
        viewBinding.llTopDanmu.setOnClickListener {
            selectSettingMode(BaseDanmaku.TYPE_FIX_TOP)
        }
        viewBinding.llBottomDanmu.setOnClickListener {
            selectSettingMode(BaseDanmaku.TYPE_FIX_BOTTOM)
        }

        viewBinding.llScrollDanmu.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                selectSettingMode(BaseDanmaku.TYPE_SCROLL_RL)
            }
        }

        viewBinding.llTopDanmu.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                selectSettingMode(BaseDanmaku.TYPE_FIX_TOP)
            }
        }

        viewBinding.llBottomDanmu.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                selectSettingMode(BaseDanmaku.TYPE_FIX_BOTTOM)
            }
        }

        viewBinding.switchDanmuEnable.setOnCheckedChangeListener { _, isChecked ->
            updateDanmuEnable(isChecked)
        }

        viewBinding.groupLineLimitTv.setOnClickListener {
            // Keep DPAD-left/right as the primary interaction; click increments as a convenience.
            stepTvMaxLine(+1)
        }

        viewBinding.tvScreenNoLimit.setOnClickListener {
            viewBinding.etScreenMaxNum.clearFocus()
            updateScreenLimit(PlayerInitializer.Danmu.DEFAULT_MAX_NUM)
        }

        viewBinding.tvScreenAutoLimit.setOnClickListener {
            viewBinding.etScreenMaxNum.clearFocus()
            updateScreenLimit(-1)
        }

        viewBinding.etScreenMaxNum.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                // 输入为空，设置为无限制
                val maxNumText = viewBinding.etScreenMaxNum.text.toString()
                val newMaxNum = maxNumText.toIntOrNull() ?: PlayerInitializer.Danmu.DEFAULT_MAX_NUM

                updateScreenLimit(newMaxNum)
                hideKeyboard(viewBinding.etScreenMaxNum)
                return@setOnEditorActionListener true
            }
            return@setOnEditorActionListener false
        }
    }

    private fun applyDanmuConfigureStatus() {
        applySettingModeSelectedState()
        when (settingMode) {
            BaseDanmaku.TYPE_SCROLL_RL -> applyScrollDanmuConfigure()
            BaseDanmaku.TYPE_FIX_TOP -> applyTopDanmuConfigure()
            BaseDanmaku.TYPE_FIX_BOTTOM -> applyBottomDanmuConfigure()
        }
    }

    private fun selectSettingMode(mode: Int) {
        if (settingMode == mode) {
            return
        }
        flushPendingMaxLineCommit()
        settingMode = mode
        applyDanmuConfigureStatus()
    }

    private fun applySettingModeSelectedState() {
        viewBinding.llScrollDanmu.isSelected = settingMode == BaseDanmaku.TYPE_SCROLL_RL
        viewBinding.llTopDanmu.isSelected = settingMode == BaseDanmaku.TYPE_FIX_TOP
        viewBinding.llBottomDanmu.isSelected = settingMode == BaseDanmaku.TYPE_FIX_BOTTOM
    }

    private fun applyScrollDanmuConfigure() {
        viewBinding.tvLineLimitTips.text = context.getString(R.string.tips_scroll_danmu_line_limit)
        viewBinding.tvDanmuEnableTips.text = context.getString(R.string.tips_enable_scroll_danmu)
        viewBinding.groupScreenLimit.isVisible = true
        viewBinding.switchDanmuEnable.isChecked = PlayerInitializer.Danmu.mobileDanmu
        applyMaxLineView(PlayerInitializer.Danmu.maxScrollLine)

        when (PlayerInitializer.Danmu.maxNum) {
            PlayerInitializer.Danmu.DEFAULT_MAX_NUM -> {
                viewBinding.tvScreenNoLimit.isSelected = true
                viewBinding.tvScreenAutoLimit.isSelected = false
                viewBinding.etScreenMaxNum.setText("")
            }
            -1 -> {
                viewBinding.tvScreenNoLimit.isSelected = false
                viewBinding.tvScreenAutoLimit.isSelected = true
                viewBinding.etScreenMaxNum.setText("")
            }
            else -> {
                viewBinding.tvScreenNoLimit.isSelected = false
                viewBinding.tvScreenAutoLimit.isSelected = false
                viewBinding.etScreenMaxNum.setText(PlayerInitializer.Danmu.maxNum.toString())
            }
        }
    }

    private fun applyTopDanmuConfigure() {
        viewBinding.tvLineLimitTips.text = context.getString(R.string.tips_top_danmu_line_limit)
        viewBinding.tvDanmuEnableTips.text = context.getString(R.string.tips_enable_top_danmu)
        viewBinding.groupScreenLimit.isVisible = false
        viewBinding.switchDanmuEnable.isChecked = PlayerInitializer.Danmu.topDanmu
        applyMaxLineView(PlayerInitializer.Danmu.maxTopLine)
    }

    private fun applyBottomDanmuConfigure() {
        viewBinding.tvLineLimitTips.text = context.getString(R.string.tips_bottom_danmu_line_limit)
        viewBinding.tvDanmuEnableTips.text = context.getString(R.string.tips_enable_bottom_danmu)
        viewBinding.groupScreenLimit.isVisible = false
        viewBinding.switchDanmuEnable.isChecked = PlayerInitializer.Danmu.bottomDanmu
        applyMaxLineView(PlayerInitializer.Danmu.maxBottomLine)
    }

    private fun updateDanmuEnable(enable: Boolean) {
        when (settingMode) {
            BaseDanmaku.TYPE_SCROLL_RL -> {
                PlayerInitializer.Danmu.mobileDanmu = enable
                DanmuConfig.putShowMobileDanmu(enable)
                mControlWrapper.updateMobileDanmuState()
            }
            BaseDanmaku.TYPE_FIX_TOP -> {
                PlayerInitializer.Danmu.topDanmu = enable
                DanmuConfig.putShowTopDanmu(enable)
                mControlWrapper.updateTopDanmuState()
            }
            BaseDanmaku.TYPE_FIX_BOTTOM -> {
                PlayerInitializer.Danmu.bottomDanmu = enable
                DanmuConfig.putShowBottomDanmu(enable)
                mControlWrapper.updateBottomDanmuState()
            }
        }
    }

    private fun requestMaxLineChange(
        line: Int,
        debounce: Boolean = true
    ) {
        val mode = settingMode
        val currentLine = getMaxLineByMode(mode)
        val normalizedLine = normalizeMaxLineValue(line)
        if (currentLine == normalizedLine) {
            return
        }
        setMaxLineByMode(mode, normalizedLine)
        applyDanmuConfigureStatus()
        maxLineCommitCoordinator.request(mode, currentLine, normalizedLine, debounce)
    }

    private fun flushPendingMaxLineCommit() {
        maxLineCommitCoordinator.flush()
    }

    private fun commitMaxLine(
        mode: Int,
        line: Int
    ) {
        val normalizedLine = normalizeMaxLineValue(line)
        setMaxLineByMode(mode, normalizedLine)
        persistMaxLineByMode(mode, normalizedLine)
        mControlWrapper.updateMaxLine()
    }

    private fun persistMaxLineByMode(
        mode: Int,
        line: Int
    ) {
        when (mode) {
            BaseDanmaku.TYPE_SCROLL_RL -> DanmuConfig.putDanmuScrollMaxLine(line)
            BaseDanmaku.TYPE_FIX_TOP -> DanmuConfig.putDanmuTopMaxLine(line)
            BaseDanmaku.TYPE_FIX_BOTTOM -> DanmuConfig.putDanmuBottomMaxLine(line)
            else -> Unit
        }
    }

    private fun getMaxLineByMode(mode: Int): Int =
        when (mode) {
            BaseDanmaku.TYPE_SCROLL_RL -> PlayerInitializer.Danmu.maxScrollLine
            BaseDanmaku.TYPE_FIX_TOP -> PlayerInitializer.Danmu.maxTopLine
            BaseDanmaku.TYPE_FIX_BOTTOM -> PlayerInitializer.Danmu.maxBottomLine
            else -> PlayerInitializer.Danmu.maxScrollLine
        }

    private fun setMaxLineByMode(
        mode: Int,
        line: Int
    ): Boolean {
        val normalizedLine = normalizeMaxLineValue(line)
        val currentLine = getMaxLineByMode(mode)
        if (currentLine == normalizedLine) {
            return false
        }
        when (mode) {
            BaseDanmaku.TYPE_SCROLL_RL -> PlayerInitializer.Danmu.maxScrollLine = normalizedLine
            BaseDanmaku.TYPE_FIX_TOP -> PlayerInitializer.Danmu.maxTopLine = normalizedLine
            BaseDanmaku.TYPE_FIX_BOTTOM -> PlayerInitializer.Danmu.maxBottomLine = normalizedLine
            else -> return false
        }
        return true
    }

    private fun normalizeMaxLineValue(line: Int): Int {
        val normalizedLine =
            if (line <= PlayerInitializer.Danmu.DEFAULT_MAX_LINE) {
                PlayerInitializer.Danmu.DEFAULT_MAX_LINE
            } else {
                line
            }
        return normalizedLine.coerceIn(PlayerInitializer.Danmu.DEFAULT_MAX_LINE, TV_MAX_LINE_LIMIT)
    }

    private fun updateScreenLimit(limit: Int) {
        PlayerInitializer.Danmu.maxNum = limit
        DanmuConfig.putDanmuMaxCount(limit)
        mControlWrapper.updateMaxScreenNum()
        applyDanmuConfigureStatus()
    }

    private fun applyMaxLineView(maxLine: Int) {
        val normalizedMaxLine = maxLine.coerceIn(PlayerInitializer.Danmu.DEFAULT_MAX_LINE, TV_MAX_LINE_LIMIT)
        viewBinding.groupLineLimitTv.isSelected = normalizedMaxLine == PlayerInitializer.Danmu.DEFAULT_MAX_LINE
        viewBinding.tvLineLimitValue.text =
            if (normalizedMaxLine == PlayerInitializer.Danmu.DEFAULT_MAX_LINE) {
                context.getString(R.string.text_danmu_line_unlimited)
            } else {
                normalizedMaxLine.toString()
            }
        if (normalizedMaxLine != maxLine) {
            requestMaxLineChange(normalizedMaxLine, debounce = false)
        }
    }

    private fun stepTvMaxLine(delta: Int) {
        val currentMaxLine =
            when (settingMode) {
                BaseDanmaku.TYPE_SCROLL_RL -> PlayerInitializer.Danmu.maxScrollLine
                BaseDanmaku.TYPE_FIX_TOP -> PlayerInitializer.Danmu.maxTopLine
                BaseDanmaku.TYPE_FIX_BOTTOM -> PlayerInitializer.Danmu.maxBottomLine
                else -> PlayerInitializer.Danmu.maxScrollLine
            }
        val tvCurrent = currentMaxLine.coerceIn(PlayerInitializer.Danmu.DEFAULT_MAX_LINE, TV_MAX_LINE_LIMIT)
        val tvNext = (tvCurrent + delta).coerceIn(PlayerInitializer.Danmu.DEFAULT_MAX_LINE, TV_MAX_LINE_LIMIT)
        if (tvNext != tvCurrent) {
            requestMaxLineChange(tvNext)
        }
    }

    private fun updateLanguage(language: DanmakuLanguage) {
        PlayerInitializer.Danmu.language = language
        DanmuConfig.putDanmuLanguage(language.value)

        viewBinding.tvLanguageOrigin.isSelected = language == DanmakuLanguage.ORIGINAL
        viewBinding.tvLanguageSc.isSelected = language == DanmakuLanguage.SC
        viewBinding.tvLanguageTc.isSelected = language == DanmakuLanguage.TC
    }

    private companion object {
        private const val TV_MAX_LINE_LIMIT = 12
        private const val MAX_LINE_COMMIT_DEBOUNCE_MS = 180L
    }
}

private class HandlerScheduler(
    private val handler: Handler
) : DanmuMaxLineCommitCoordinator.Scheduler {
    override fun postDelayed(
        delayMillis: Long,
        action: () -> Unit
    ): DanmuMaxLineCommitCoordinator.Cancelable {
        val runnable = Runnable { action() }
        handler.postDelayed(runnable, delayMillis)
        return DanmuMaxLineCommitCoordinator.Cancelable {
            handler.removeCallbacks(runnable)
        }
    }
}
