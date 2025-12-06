package com.xyoye.common_component.log

import com.xyoye.common_component.log.model.LogEvent
import com.xyoye.common_component.log.model.LogLevel
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.common_component.log.model.LogPolicy
import com.xyoye.common_component.log.model.SamplingRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class LogSamplingRuleTest {

    @Test
    fun dropEventsWhenSampleRateIsZero() {
        val policy = LogPolicy(
            name = "sampling-zero",
            defaultLevel = LogLevel.DEBUG,
            enableDebugFile = true,
            samplingRules = listOf(
                SamplingRule(LogModule.CORE, LogLevel.DEBUG, sampleRate = 0.0)
            )
        )
        val sampler = LogSampler(randomProvider = { 0.5 })

        assertFalse(sampler.shouldAllow(event(LogLevel.DEBUG, LogModule.CORE), policy))
        assertTrue(sampler.shouldAllow(event(LogLevel.INFO, LogModule.PLAYER), policy))
    }

    @Test
    fun limitEventsPerMinute() {
        var now = 0L
        val sampler = LogSampler(
            nowProvider = { now },
            randomProvider = { 0.0 } // always pass sample gate
        )
        val rule = SamplingRule(
            targetModule = LogModule.PLAYER,
            minLevel = LogLevel.INFO,
            sampleRate = 1.0,
            maxEventsPerMinute = 2
        )
        val policy = LogPolicy(
            name = "limit-per-minute",
            defaultLevel = LogLevel.DEBUG,
            enableDebugFile = true,
            samplingRules = listOf(rule)
        )

        assertTrue(sampler.shouldAllow(event(LogLevel.INFO, LogModule.PLAYER), policy))
        assertTrue(sampler.shouldAllow(event(LogLevel.ERROR, LogModule.PLAYER), policy))
        assertFalse(sampler.shouldAllow(event(LogLevel.WARN, LogModule.PLAYER), policy))

        now += 61_000
        assertTrue(sampler.shouldAllow(event(LogLevel.ERROR, LogModule.PLAYER), policy))
    }

    @Test
    fun respectsMinLevelThreshold() {
        val sampler = LogSampler(randomProvider = { 0.0 })
        val policy = LogPolicy(
            name = "min-level",
            defaultLevel = LogLevel.DEBUG,
            enableDebugFile = true,
            samplingRules = listOf(
                SamplingRule(LogModule.NETWORK, LogLevel.WARN, sampleRate = 0.0, maxEventsPerMinute = 1)
            )
        )

        assertTrue(sampler.shouldAllow(event(LogLevel.INFO, LogModule.NETWORK), policy))
        assertFalse(sampler.shouldAllow(event(LogLevel.ERROR, LogModule.NETWORK), policy))
    }

    private fun event(level: LogLevel, module: LogModule) = LogEvent(
        level = level,
        module = module,
        message = "sample-event"
    )
}
