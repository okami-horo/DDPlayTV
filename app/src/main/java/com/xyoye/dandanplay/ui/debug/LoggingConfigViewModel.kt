package com.xyoye.dandanplay.ui.debug

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.xyoye.common_component.base.BaseViewModel
import com.xyoye.common_component.log.LogSystem
import com.xyoye.common_component.log.model.LogLevel
import com.xyoye.common_component.log.model.LogRuntimeState
import com.xyoye.common_component.log.model.PolicySource

/**
 * 日志配置页 ViewModel：封装全局级别与调试开关的读取与更新。
 */
class LoggingConfigViewModel : BaseViewModel() {

    private val _runtimeState = MutableLiveData<LogRuntimeState>()
    val runtimeState: LiveData<LogRuntimeState> = _runtimeState

    fun loadState() {
        _runtimeState.postValue(LogSystem.getRuntimeState())
    }

    fun updateLevel(level: LogLevel) {
        val current = LogSystem.getRuntimeState()
        val updatedPolicy = current.activePolicy.copy(defaultLevel = level)
        _runtimeState.postValue(LogSystem.updateLoggingPolicy(updatedPolicy, PolicySource.USER_OVERRIDE))
    }

    fun setDebugLoggingEnabled(enabled: Boolean) {
        val current = LogSystem.getRuntimeState()
        val policyWithFlag = current.activePolicy.copy(enableDebugFile = enabled)
        LogSystem.updateLoggingPolicy(policyWithFlag, PolicySource.USER_OVERRIDE)
        val runtime = if (enabled) {
            LogSystem.startDebugSession()
        } else {
            LogSystem.stopDebugSession()
        }
        _runtimeState.postValue(runtime)
    }
}
