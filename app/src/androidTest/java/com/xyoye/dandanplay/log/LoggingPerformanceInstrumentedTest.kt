package com.xyoye.dandanplay.log

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xyoye.common_component.log.LogPaths
import com.xyoye.common_component.log.LogSystem
import com.xyoye.common_component.log.LogWriter
import com.xyoye.common_component.log.model.DebugToggleState
import com.xyoye.common_component.log.model.LogEvent
import com.xyoye.common_component.log.model.LogLevel
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.common_component.log.model.LogPolicy
import com.xyoye.common_component.log.model.LogRuntimeState
import com.xyoye.common_component.log.model.PolicySource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * 性能向 Instrumentation 测试：对比默认策略与高日志量策略下的
 * 冷启动日志写入与高频交互日志写入的耗时，确保高日志量策略不会显著拖慢日志系统。
 */
@RunWith(AndroidJUnit4::class)
class LoggingPerformanceInstrumentedTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        resetLogs()
        if (!LogSystem.isInitialized()) {
            LogSystem.init(context)
        }
        restoreDefaultPolicy()
    }

    @After
    fun tearDown() {
        restoreDefaultPolicy()
        resetLogs()
    }

    @Test
    fun compareDefaultAndHighVolumePolicies() {
        val defaultResult =
            measureScenario(
                policy = LogPolicy.defaultReleasePolicy(),
                enableDebug = false,
            )

        val highVolumeResult =
            measureScenario(
                policy = LogPolicy.highVolumePolicy(),
                enableDebug = true,
            )

        // 默认策略不应生成本地日志文件
        assertEquals(0L, defaultResult.fileSizeBytes)
        assertTrue("高日志量策略应产生本地日志文件", highVolumeResult.fileSizeBytes > 0L)

        // 冷启动阶段高日志量写入的耗时应在合理范围内（允许一定放大但不可过大）
        val coldStartBudget = defaultResult.coldStartMs + 1200
        assertTrue(
            "高日志量冷启动耗时超出预期: ${highVolumeResult.coldStartMs}ms > ${coldStartBudget}ms",
            highVolumeResult.coldStartMs <= coldStartBudget,
        )

        // 高频交互阶段允许高日志量策略有额外开销，但不应超过基线的约 3 倍或额外 500ms（取更大值作为上限）
        val interactionBudget =
            (defaultResult.interactionMs * 3)
                .coerceAtLeast(defaultResult.interactionMs + 500)
        assertTrue(
            "高日志量交互耗时超出预期: ${highVolumeResult.interactionMs}ms > ${interactionBudget}ms",
            highVolumeResult.interactionMs <= interactionBudget,
        )
    }

    private fun measureScenario(
        policy: LogPolicy,
        enableDebug: Boolean,
        events: Int = 200
    ): PerfResult {
        resetLogs()
        LogSystem.updateLoggingPolicy(policy, PolicySource.USER_OVERRIDE)
        if (enableDebug) {
            LogSystem.startDebugSession()
        } else {
            LogSystem.stopDebugSession()
        }

        // 冷启动：首次日志写入（包含可能的文件 prepare）
        val coldStartMs =
            measureMs {
                LogSystem.log(
                    LogEvent(
                        level = LogLevel.INFO,
                        module = LogModule.CORE,
                        message = "cold-start-log",
                    ),
                )
                awaitWriterDrain()
            }

        // 高频交互：连续写入多条日志
        val interactionMs =
            measureMs {
                repeat(events) { index ->
                    LogSystem.log(
                        LogEvent(
                            level = LogLevel.DEBUG,
                            module = LogModule.PLAYER,
                            message = "interaction-$index",
                            context =
                                mapOf(
                                    "scene" to "performance_test",
                                    "seq" to index.toString(),
                                ),
                        ),
                    )
                }
                awaitWriterDrain()
            }

        return PerfResult(
            coldStartMs = coldStartMs,
            interactionMs = interactionMs,
            fileSizeBytes = currentLogSize(),
        )
    }

    private fun awaitWriterDrain(timeoutMs: Long = 5_000L) {
        val writer = getLogWriter() ?: return
        val executor = getWriterExecutor(writer)
        val latch = CountDownLatch(1)
        executor.execute { latch.countDown() }
        assertTrue("日志写入线程未在超时时间内完成", latch.await(timeoutMs, TimeUnit.MILLISECONDS))
    }

    private fun getLogWriter(): LogWriter? {
        val field = LogSystem::class.java.getDeclaredField("writer")
        field.isAccessible = true
        return field.get(LogSystem) as? LogWriter
    }

    private fun getWriterExecutor(writer: LogWriter): ExecutorService {
        val field = LogWriter::class.java.getDeclaredField("executor")
        field.isAccessible = true
        return field.get(writer) as ExecutorService
    }

    private fun currentLogSize(): Long {
        val current = LogPaths.currentLogFile(context)
        val previous = LogPaths.previousLogFile(context)
        return listOf(current, previous)
            .filter { it.exists() }
            .sumOf { it.length() }
    }

    private fun resetLogs() {
        val dir: File = LogPaths.logDirectory(context)
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }

    private fun restoreDefaultPolicy() {
        LogSystem.updateLoggingPolicy(LogPolicy.defaultReleasePolicy(), PolicySource.DEFAULT)
        val defaultState =
            LogRuntimeState(
                activePolicy = LogPolicy.defaultReleasePolicy(),
                policySource = PolicySource.DEFAULT,
                debugToggleState = DebugToggleState.OFF,
                debugSessionEnabled = false,
            )
        getLogWriter()?.updateRuntimeState(defaultState)
        LogSystem.stopDebugSession()
    }

    private inline fun measureMs(block: () -> Unit): Long {
        val start = SystemClock.elapsedRealtime()
        block()
        return SystemClock.elapsedRealtime() - start
    }

    private data class PerfResult(
        val coldStartMs: Long,
        val interactionMs: Long,
        val fileSizeBytes: Long
    )
}
