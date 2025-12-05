package com.xyoye.common_component.log

import com.xyoye.common_component.log.model.DebugToggleState
import com.xyoye.common_component.log.model.LogLevel
import com.xyoye.common_component.log.model.LogPolicy
import com.xyoye.common_component.log.model.LogRuntimeState
import com.xyoye.common_component.log.model.PolicySource
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * 封装日志策略与调试会话在 MMKV 中的读写，并提供变更监听。
 */
class LogPolicyRepository(
    private val defaultPolicy: LogPolicy = LogPolicy.defaultReleasePolicy(),
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
    private val storage: LogConfigStorage = MmkvLogConfigStorage()
) {
    private val stateRef = AtomicReference(
        buildRuntimeState(
            policy = defaultPolicy,
            source = PolicySource.DEFAULT,
            toggleState = DebugToggleState.OFF,
            lastUpdated = timeProvider()
        )
    )
    private val listeners = CopyOnWriteArrayList<(LogRuntimeState) -> Unit>()

    fun loadFromStorage(): LogRuntimeState {
        val stored = readStoredRuntime()
        stateRef.set(stored)
        persist(stored)
        notifyListeners(stored)
        return stored
    }

    fun getState(): LogRuntimeState = stateRef.get()

    fun updatePolicy(policy: LogPolicy, source: PolicySource = PolicySource.USER_OVERRIDE): LogRuntimeState =
        updateState { current ->
            buildRuntimeState(
                policy = policy,
                source = source,
                toggleState = current.debugToggleState,
                lastUpdated = timeProvider()
            )
        }

    fun updateDebugState(
        toggleState: DebugToggleState,
        forceEnableFile: Boolean? = null
    ): LogRuntimeState =
        updateState { current ->
            val policyToUse = forceEnableFile?.let { current.activePolicy.copy(enableDebugFile = it) }
                ?: current.activePolicy
            buildRuntimeState(
                policy = policyToUse,
                source = current.policySource,
                toggleState = toggleState,
                lastUpdated = timeProvider()
            )
        }

    fun addListener(listener: (LogRuntimeState) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (LogRuntimeState) -> Unit) {
        listeners.remove(listener)
    }

    private fun updateState(block: (LogRuntimeState) -> LogRuntimeState): LogRuntimeState {
        while (true) {
            val current = stateRef.get()
            val updated = block(current)
            if (stateRef.compareAndSet(current, updated)) {
                persist(updated)
                notifyListeners(updated)
                return updated
            }
        }
    }

    private fun notifyListeners(state: LogRuntimeState) {
        listeners.forEach { listener -> listener(state) }
    }

    private fun readStoredRuntime(): LogRuntimeState {
        val storedLevel = safeLogLevel(storage.readDefaultLevel(), defaultPolicy.defaultLevel)
        val storedPolicy = LogPolicy(
            name = storage.readPolicyName() ?: defaultPolicy.name,
            defaultLevel = storedLevel,
            enableDebugFile = storage.readDebugFileEnabled(),
            samplingRules = emptyList(),
            exportable = storage.readExportable()
        )
        val source = safePolicySource(storage.readPolicySource()) ?: PolicySource.DEFAULT
        val toggle = safeDebugToggle(storage.readDebugToggleState()) ?: DebugToggleState.OFF
        val lastUpdated = storage.readLastPolicyUpdateTime().takeIf { it > 0 } ?: timeProvider()
        return buildRuntimeState(
            policy = storedPolicy,
            source = source,
            toggleState = toggle,
            lastUpdated = lastUpdated
        )
    }

    private fun persist(state: LogRuntimeState) {
        storage.write(state)
    }

    private fun buildRuntimeState(
        policy: LogPolicy,
        source: PolicySource,
        toggleState: DebugToggleState,
        lastUpdated: Long
    ): LogRuntimeState {
        val effectiveToggle = toggleState
        val sessionEnabled = effectiveToggle == DebugToggleState.ON_CURRENT_SESSION && policy.enableDebugFile
        return LogRuntimeState(
            activePolicy = policy,
            policySource = source,
            debugToggleState = effectiveToggle,
            debugSessionEnabled = sessionEnabled,
            lastPolicyUpdateTime = lastUpdated,
            recentErrors = emptyList()
        )
    }

    private fun safeLogLevel(raw: String?, fallback: LogLevel): LogLevel {
        return runCatching { raw?.let { LogLevel.valueOf(it) } }.getOrNull() ?: fallback
    }

    private fun safePolicySource(raw: String?): PolicySource? {
        return runCatching { raw?.let { PolicySource.valueOf(it) } }.getOrNull()
    }

    private fun safeDebugToggle(raw: String?): DebugToggleState? {
        return runCatching { raw?.let { DebugToggleState.valueOf(it) } }.getOrNull()
    }
}
