package com.xyoye.common_component.log

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.xyoye.common_component.log.http.HttpLogServerManager
import com.xyoye.common_component.log.http.HttpLogServerState
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
    private val defaultPolicyRepositoryFactory: (LogPolicy) -> LogPolicyRepository = { defaultPolicy ->
        LogPolicyRepository(defaultPolicy)
    }

    private val defaultWriterFactory: ((LogEvent) -> Unit) -> LogWriter = { httpSink ->
        LogWriter(
            httpLogSink = httpSink,
        )
    }

    private val stateRef =
        AtomicReference(
            LogRuntimeState(
                activePolicy = LogPolicy.defaultReleasePolicy(),
            ),
        )
    private val sequenceGenerator = AtomicLong(0)
    private val initLock = Any()

    internal var policyRepositoryFactory: (LogPolicy) -> LogPolicyRepository = defaultPolicyRepositoryFactory

    internal var writerFactory: ((LogEvent) -> Unit) -> LogWriter = defaultWriterFactory

    private var policyRepository: LogPolicyRepository = policyRepositoryFactory(LogPolicy.defaultReleasePolicy())

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
            HttpLogServerManager.init(context)
            policyRepository = policyRepositoryFactory(defaultPolicy)
            val initialState = policyRepository.loadFromStorage()
            stateRef.set(initialState)
            writer =
                writerFactory(
                    { event -> HttpLogServerManager.ingestAppEvent(event) },
                ).also { it.updateRuntimeState(initialState) }
            HttpLogServerManager.applyFromStorage()
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

    fun startDebugSession(): LogRuntimeState = updateDebugState(DebugToggleState.ON_CURRENT_SESSION)

    fun stopDebugSession(): LogRuntimeState = updateDebugState(DebugToggleState.OFF)

    fun markDiskError(): LogRuntimeState = updateDebugState(DebugToggleState.DISABLED_DUE_TO_ERROR)

    fun getRuntimeState(): LogRuntimeState = stateRef.get()

    fun isInitialized(): Boolean = initialized

    fun getHttpLogServerState(): HttpLogServerState = HttpLogServerManager.snapshot()

    fun setHttpLogServerEnabled(
        enabled: Boolean,
        port: Int = HttpLogServerManager.DEFAULT_PORT,
    ): HttpLogServerState {
        if (!initialized) {
            Log.w(LOG_TAG, "http log server state change before init, ignore")
            return HttpLogServerManager.snapshot()
        }
        return HttpLogServerManager.setEnabled(enabled, port)
    }

    fun resetHttpLogServerToken(): HttpLogServerState {
        if (!initialized) {
            Log.w(LOG_TAG, "http log server token reset before init, ignore")
            return HttpLogServerManager.snapshot()
        }
        return HttpLogServerManager.resetToken()
    }

    fun clearHttpLogServerLogs(): HttpLogServerState {
        if (!initialized) {
            Log.w(LOG_TAG, "http log server clear logs before init, ignore")
            return HttpLogServerManager.snapshot()
        }
        return HttpLogServerManager.clearLogs()
    }

    fun setHttpLogRetentionDays(days: Int): HttpLogServerState {
        if (!initialized) {
            Log.w(LOG_TAG, "http log server retention change before init, ignore")
            return HttpLogServerManager.snapshot()
        }
        return HttpLogServerManager.setRetentionDays(days)
    }

    fun log(event: LogEvent) {
        if (!initialized) {
            Log.w(LOG_TAG, "log called before init, fallback to logcat only")
            fallbackLogcat(event)
            return
        }
        val enriched =
            event.copy(
                sequenceId = if (event.sequenceId == 0L) nextSequenceId() else event.sequenceId,
            )
        writer?.submit(enriched)
    }

    internal fun nextSequenceId(): Long = sequenceGenerator.incrementAndGet()

    private fun updateDebugState(
        state: DebugToggleState
    ): LogRuntimeState {
        if (!initialized) {
            Log.w(LOG_TAG, "debug state change before init, ignore")
            return stateRef.get()
        }
        val updated = policyRepository.updateDebugState(state)
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

    @VisibleForTesting
    internal fun resetForTests() {
        synchronized(initLock) {
            initialized = false
            writer = null
            sequenceGenerator.set(0)
            policyRepositoryFactory = defaultPolicyRepositoryFactory
            writerFactory = defaultWriterFactory
            HttpLogServerManager.resetForTests()
            policyRepository = policyRepositoryFactory(LogPolicy.defaultReleasePolicy())
            stateRef.set(
                LogRuntimeState(
                    activePolicy = LogPolicy.defaultReleasePolicy(),
                ),
            )
        }
    }

    private const val LOG_TAG = "LogSystem"
}
