package com.xyoye.player.subtitle.gpu

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SubtitleTelemetryCollectorTest {
    @Test
    fun packingCost_participatesInTheExistingFrameBudget() =
        runTest {
            val controller = SubtitlePipelineController(LocalSubtitlePipelineApi(), UnconfinedTestDispatcher(testScheduler))
            val policy = SubtitleLoadSheddingPolicy(frameBudgetMs = 25.0, dropBurstThreshold = 2)
            val collector = SubtitleTelemetryCollector(controller, policy, this)
            val frame =
                AssGpuNativeBridge.NativeRenderResult(
                    rendered = true,
                    renderLatencyMs = 0.1,
                    uploadLatencyMs = 0.1,
                    compositeLatencyMs = 0.1,
                    packLatencyMs = 30.0
                )
            collector.recordRenderResult(frame, 0, null, telemetryEnabled = false)
            assertTrue(collector.allowRender())
            collector.recordRenderResult(frame, 16, null, telemetryEnabled = false)
            assertFalse("Packing cannot bypass the load-shedding budget", collector.allowRender())
        }
}
