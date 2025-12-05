package com.xyoye.common_component.config

import com.xyoye.common_component.log.model.DebugToggleState
import com.xyoye.common_component.log.model.LogLevel
import com.xyoye.common_component.log.model.PolicySource
import com.xyoye.mmkv_annotation.MMKVFiled
import com.xyoye.mmkv_annotation.MMKVKotlinClass

/**
 * 日志策略与调试会话配置表，持久化全局级别与调试开关。
 */
@MMKVKotlinClass(className = "LogConfig")
object LogConfigTable {

    @MMKVFiled
    var policyName: String = DevelopLogConfigDefaults.DEFAULT_LOG_POLICY_NAME

    @MMKVFiled
    var defaultLevel: String = LogLevel.INFO.name

    @MMKVFiled
    var debugFileEnabled: Boolean = DevelopLogConfigDefaults.DEFAULT_ENABLE_DEBUG_FILE

    @MMKVFiled
    var exportable: Boolean = false

    @MMKVFiled
    var policySource: String = PolicySource.DEFAULT.name

    @MMKVFiled
    var debugToggleState: String = DebugToggleState.OFF.name

    @MMKVFiled
    var debugSessionEnabled: Boolean = false

    @MMKVFiled
    var lastPolicyUpdateTime: Long = 0L
}
