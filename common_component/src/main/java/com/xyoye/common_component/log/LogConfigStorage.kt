package com.xyoye.common_component.log

import com.xyoye.common_component.config.LogConfig
import com.xyoye.common_component.log.model.LogRuntimeState

interface LogConfigStorage {
    fun readPolicyName(): String?
    fun readDefaultLevel(): String?
    fun readDebugFileEnabled(): Boolean
    fun readExportable(): Boolean
    fun readPolicySource(): String?
    fun readDebugToggleState(): String?
    fun readLastPolicyUpdateTime(): Long

    fun write(state: LogRuntimeState)
}

class MmkvLogConfigStorage : LogConfigStorage {
    override fun readPolicyName(): String? = LogConfig.getPolicyName()

    override fun readDefaultLevel(): String? = LogConfig.getDefaultLevel()

    override fun readDebugFileEnabled(): Boolean = LogConfig.isDebugFileEnabled()

    override fun readExportable(): Boolean = LogConfig.isExportable()

    override fun readPolicySource(): String? = LogConfig.getPolicySource()

    override fun readDebugToggleState(): String? = LogConfig.getDebugToggleState()

    override fun readLastPolicyUpdateTime(): Long = LogConfig.getLastPolicyUpdateTime()

    override fun write(state: LogRuntimeState) {
        val policy = state.activePolicy
        LogConfig.putPolicyName(policy.name)
        LogConfig.putDefaultLevel(policy.defaultLevel.name)
        LogConfig.putDebugFileEnabled(policy.enableDebugFile)
        LogConfig.putExportable(policy.exportable)
        LogConfig.putPolicySource(state.policySource.name)
        LogConfig.putDebugToggleState(state.debugToggleState.name)
        LogConfig.putDebugSessionEnabled(state.debugSessionEnabled)
        LogConfig.putLastPolicyUpdateTime(state.lastPolicyUpdateTime)
    }
}
