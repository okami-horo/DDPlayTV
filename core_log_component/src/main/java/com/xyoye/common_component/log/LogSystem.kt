package com.xyoye.common_component.log

import android.content.Context
import android.util.Log
import com.tencent.mmkv.MMKV
import com.xyoye.common_component.log.model.DebugToggleState
import com.xyoye.common_component.log.model.LogEvent
import com.xyoye.common_component.log.model.LogLevel
import com.xyoye.common_component.log.model.LogPolicy
import com.xyoye.common_component.log.model.LogRuntimeState
import com.xyoye.common_component.log.model.PolicySource
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 日志系统单例，负责初始化、策略状态维护与写入调度。
 */
object LogSystem {
    private val stateRef =
        AtomicReference(
            LogRuntimeState(
                activePolicy = LogPolicy.defaultReleasePolicy(),
            ),
        )
    private val sequenceGenerator = AtomicLong(0)
    private val initLock = Any()

    private var policyRepository: LogPolicyRepository = LogPolicyRepository()

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
            MMKV.initialize(context.applicationContext)
            policyRepository = LogPolicyRepository(defaultPolicy)
            val initialState = policyRepository.loadFromStorage()
            stateRef.set(initialState)
            writer = LogWriter(context.applicationContext).also { it.updateRuntimeState(initialState) }
            initialized = true
        }
    }

    fun loadPolicyFromStorage(): LogRuntimeState {
        if (!initialized) {
            Log.w(LOG_TAG, "loadPolicyFromStorage called before init, ignore")
            return stateRef.get()
        }
        val refreshed = policyRepository.loadFromStorage()
        return applyRuntimeState(refreshed)
    }

    fun getLoggingPolicy(): LogRuntimeState = getRuntimeState()

    fun updateLoggingPolicy(
        policy: LogPolicy,
        source: PolicySource = PolicySource.USER_OVERRIDE
    ): LogRuntimeState {
        if (!initialized) {
            Log.w(LOG_TAG, "updatePolicy called before init, ignore")
            return stateRef.get()
        }
        val updated = policyRepository.updatePolicy(policy, source)
        return applyRuntimeState(updated)
    }

    fun updatePolicy(
        policy: LogPolicy,
        source: PolicySource = PolicySource.USER_OVERRIDE
    ): LogRuntimeState = updateLoggingPolicy(policy, source)

    fun startDebugSession(): LogRuntimeState = updateDebugState(DebugToggleState.ON_CURRENT_SESSION, forceEnableFile = true)

    fun stopDebugSession(): LogRuntimeState = updateDebugState(DebugToggleState.OFF, forceEnableFile = false)

    fun markDiskError(): LogRuntimeState = updateDebugState(DebugToggleState.DISABLED_DUE_TO_ERROR, forceEnableFile = false)

    fun getRuntimeState(): LogRuntimeState = stateRef.get()

    fun isInitialized(): Boolean = initialized

    fun log(event: LogEvent) {
        if (!initialized) {
            Log.w(LOG_TAG, "log called before init, fallback to logcat only")
            fallbackLogcat(event)
            return
        }
        val enriched =
            event.copy(
                sequenceId = if (event.sequenceId == 0L) sequenceGenerator.incrementAndGet() else event.sequenceId,
            )
        writer?.submit(enriched)
    }

    private fun updateDebugState(
        state: DebugToggleState,
        forceEnableFile: Boolean? = null
    ): LogRuntimeState {
        if (!initialized) {
            Log.w(LOG_TAG, "debug state change before init, ignore")
            return stateRef.get()
        }
        val updated = policyRepository.updateDebugState(state, forceEnableFile)
        return applyRuntimeState(updated)
    }

    private fun fallbackLogcat(event: LogEvent) {
        val formatter = LogFormatter()
        val content = formatter.formatForLogcat(event)
        when (event.level) {
            LogLevel.DEBUG -> Log.d(LOG_TAG, content, event.throwable)
            LogLevel.INFO -> Log.i(LOG_TAG, content, event.throwable)
            LogLevel.WARN -> Log.w(LOG_TAG, content, event.throwable)
            LogLevel.ERROR -> Log.e(LOG_TAG, content, event.throwable)
        }
    }

    private fun applyRuntimeState(state: LogRuntimeState): LogRuntimeState {
        stateRef.set(state)
        writer?.updateRuntimeState(state)
        SubtitleTelemetryLogger.updateFromRuntime(state)
        return state
    }

    private const val LOG_TAG = "LogSystem"
}
