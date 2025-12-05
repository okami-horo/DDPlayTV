package com.xyoye.common_component.log

import android.content.Context
import android.util.Log
import com.xyoye.common_component.log.model.DebugToggleState
import com.xyoye.common_component.log.model.LogEvent
import com.xyoye.common_component.log.model.LogPolicy
import com.xyoye.common_component.log.model.LogRuntimeState
import com.xyoye.common_component.log.model.PolicySource
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/**
 * 日志系统单例，负责初始化、策略状态维护与写入调度。
 */
object LogSystem {
    private val stateRef = AtomicReference(
        LogRuntimeState(
            activePolicy = LogPolicy.defaultReleasePolicy()
        )
    )
    private val sequenceGenerator = AtomicLong(0)
    private val initLock = Any()

    @Volatile
    private var initialized = false

    @Volatile
    private var writer: LogWriter? = null

    fun init(
        context: Context,
        defaultPolicy: LogPolicy = LogPolicy.defaultReleasePolicy()
    ) {
        if (initialized) return
        synchronized(initLock) {
            if (initialized) return
            val initialState = LogRuntimeState(
                activePolicy = defaultPolicy,
                policySource = PolicySource.DEFAULT,
                debugToggleState = DebugToggleState.OFF
            )
            stateRef.set(initialState)
            writer = LogWriter(context.applicationContext).also {
                it.updateRuntimeState(initialState)
            }
            initialized = true
        }
    }

    fun loadPolicyFromStorage(): LogRuntimeState {
        if (!initialized) {
            Log.w(LOG_TAG, "loadPolicyFromStorage called before init, ignore")
            return stateRef.get()
        }
        // 预留与存储的对接，当前保持默认策略
        val refreshed = updateState { state ->
            state.copy(lastPolicyUpdateTime = System.currentTimeMillis())
        }
        writer?.updateRuntimeState(refreshed)
        return refreshed
    }

    fun updatePolicy(policy: LogPolicy, source: PolicySource = PolicySource.USER_OVERRIDE): LogRuntimeState {
        if (!initialized) {
            Log.w(LOG_TAG, "updatePolicy called before init, ignore")
            return stateRef.get()
        }
        val updated = updateState { state ->
            state.copy(
                activePolicy = policy,
                policySource = source,
                lastPolicyUpdateTime = System.currentTimeMillis()
            )
        }
        writer?.updateRuntimeState(updated)
        return updated
    }

    fun startDebugSession(): LogRuntimeState = updateDebugState(DebugToggleState.ON_CURRENT_SESSION)

    fun stopDebugSession(): LogRuntimeState = updateDebugState(DebugToggleState.OFF)

    fun markDiskError(): LogRuntimeState = updateDebugState(DebugToggleState.DISABLED_DUE_TO_ERROR)

    fun getRuntimeState(): LogRuntimeState = stateRef.get()

    fun isInitialized(): Boolean = initialized

    fun log(event: LogEvent) {
        if (!initialized) {
            Log.w(LOG_TAG, "log called before init, fallback to logcat only")
            fallbackLogcat(event)
            return
        }
        val enriched = event.copy(
            sequenceId = if (event.sequenceId == 0L) sequenceGenerator.incrementAndGet() else event.sequenceId
        )
        writer?.submit(enriched)
    }

    private fun updateDebugState(state: DebugToggleState): LogRuntimeState {
        if (!initialized) {
            Log.w(LOG_TAG, "debug state change before init, ignore")
            return stateRef.get()
        }
        val updated = updateState { runtime ->
            runtime.copy(
                debugToggleState = state,
                debugSessionEnabled = state == DebugToggleState.ON_CURRENT_SESSION,
                lastPolicyUpdateTime = System.currentTimeMillis()
            )
        }
        writer?.updateRuntimeState(updated)
        return updated
    }

    private fun fallbackLogcat(event: LogEvent) {
        val formatter = LogFormatter()
        val content = formatter.formatForLogcat(event)
        when (event.level) {
            com.xyoye.common_component.log.model.LogLevel.DEBUG -> Log.d(LOG_TAG, content, event.throwable)
            com.xyoye.common_component.log.model.LogLevel.INFO -> Log.i(LOG_TAG, content, event.throwable)
            com.xyoye.common_component.log.model.LogLevel.WARN -> Log.w(LOG_TAG, content, event.throwable)
            com.xyoye.common_component.log.model.LogLevel.ERROR -> Log.e(LOG_TAG, content, event.throwable)
        }
    }

    @OptIn(ExperimentalContracts::class)
    private inline fun updateState(block: (LogRuntimeState) -> LogRuntimeState): LogRuntimeState {
        contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
        while (true) {
            val current = stateRef.get()
            val updated = block(current)
            if (stateRef.compareAndSet(current, updated)) {
                return updated
            }
        }
    }

    private const val LOG_TAG = "LogSystem"
}
