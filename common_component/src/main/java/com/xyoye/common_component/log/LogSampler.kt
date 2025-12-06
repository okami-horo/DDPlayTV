package com.xyoye.common_component.log

import com.xyoye.common_component.log.model.LogEvent
import com.xyoye.common_component.log.model.LogLevel
import com.xyoye.common_component.log.model.LogPolicy
import com.xyoye.common_component.log.model.SamplingRule
import java.util.concurrent.ThreadLocalRandom

/**
 * 采样 / 限流决策器：根据策略中的 SamplingRule 判定日志是否继续输出。
 */
class LogSampler(
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
    private val randomProvider: () -> Double = { ThreadLocalRandom.current().nextDouble() }
) {
    private val counterLock = Any()
    private val counters = mutableMapOf<String, WindowCounter>()

    data class WindowCounter(var windowStartMs: Long, var count: Int)

    fun shouldAllow(event: LogEvent, policy: LogPolicy): Boolean {
        val rule = selectRule(event, policy) ?: return true
        if (rule.sampleRate <= 0.0) return false
        if (rule.sampleRate < 1.0 && randomProvider() > rule.sampleRate) {
            return false
        }
        val limit = rule.maxEventsPerMinute ?: return true
        val key = ruleKey(rule)
        val allowed = synchronized(counterLock) {
            val now = nowProvider()
            val counter = counters.getOrPut(key) { WindowCounter(now, 0) }
            if (now - counter.windowStartMs >= ONE_MINUTE_MS) {
                counter.windowStartMs = now
                counter.count = 0
            }
            if (counter.count >= limit) {
                return@synchronized false
            }
            counter.count++
            return@synchronized true
        }
        return allowed
    }

    private fun selectRule(event: LogEvent, policy: LogPolicy): SamplingRule? {
        return policy.samplingRules.firstOrNull { rule ->
            rule.targetModule == event.module && levelPriority(event.level) >= levelPriority(rule.minLevel)
        }
    }

    private fun ruleKey(rule: SamplingRule): String =
        "${rule.targetModule.code}-${rule.minLevel.name}".lowercase()

    private fun levelPriority(level: LogLevel): Int {
        return when (level) {
            LogLevel.DEBUG -> 0
            LogLevel.INFO -> 1
            LogLevel.WARN -> 2
            LogLevel.ERROR -> 3
        }
    }

    companion object {
        private const val ONE_MINUTE_MS = 60_000L
    }
}
