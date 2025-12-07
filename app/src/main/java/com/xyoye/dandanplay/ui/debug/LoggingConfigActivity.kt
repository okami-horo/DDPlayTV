package com.xyoye.dandanplay.ui.debug

import android.view.View
import android.widget.AdapterView
import com.alibaba.android.arouter.facade.annotation.Route
import com.xyoye.common_component.base.BaseActivity
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.log.model.LogLevel
import com.xyoye.dandanplay.BR
import com.xyoye.dandanplay.R
import com.xyoye.dandanplay.databinding.ActivityLoggingConfigBinding

/**
 * 日志配置页：提供全局日志级别与调试日志开关的入口。
 */
@Route(path = RouteTable.Debug.LoggingConfig)
class LoggingConfigActivity :
    BaseActivity<LoggingConfigViewModel, ActivityLoggingConfigBinding>() {

    private val levels = LogLevel.values().toList()
    private var suppressLevelChange = false
    private var suppressToggleChange = false

    override fun initViewModel() =
        ViewModelInit(
            BR.viewModel,
            LoggingConfigViewModel::class.java
        )

    override fun getLayoutId() = R.layout.activity_logging_config

    override fun initView() {
        title = "日志配置"
        initLoggingControls()
        viewModel.loadState()
    }

    private fun initLoggingControls() {
        dataBinding.loggingLevelSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    if (suppressLevelChange) return
                    levels.getOrNull(position)?.let { viewModel.updateLevel(it) }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }

        dataBinding.debugLogSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (suppressToggleChange) return@setOnCheckedChangeListener
            viewModel.setDebugLoggingEnabled(isChecked)
        }

        viewModel.runtimeState.observe(this) { state ->
            val levelIndex = levels.indexOf(state.activePolicy.defaultLevel).coerceAtLeast(0)
            suppressLevelChange = true
            dataBinding.loggingLevelSpinner.setSelection(levelIndex, false)
            suppressLevelChange = false

            val debugEnabled = state.activePolicy.enableDebugFile && state.debugSessionEnabled
            suppressToggleChange = true
            dataBinding.debugLogSwitch.isChecked = debugEnabled
            suppressToggleChange = false
        }
    }
}
