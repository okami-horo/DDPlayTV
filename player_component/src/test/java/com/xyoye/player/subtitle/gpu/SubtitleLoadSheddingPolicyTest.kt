package com.xyoye.player.subtitle.gpu

import com.xyoye.data_component.bean.subtitle.TelemetrySample
import com.xyoye.data_component.enums.SubtitleFrameStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleLoadSheddingPolicyTest {
    @Test
    fun evaluateTelemetry_throttlesAfterBurstOverBudget() {
        val policy =
            SubtitleLoadSheddingPolicy(
                frameBudgetMs = 1.0,
                dropBurstThreshold = 2,
                throttleWindowMs = 500L,
            )

        val overBudgetSample =
            TelemetrySample(
                timestampMs = 1_000L,
                subtitlePtsMs = 0L,
                renderLatencyMs = 10.0,
                uploadLatencyMs = 0.0,
                compositeLatencyMs = 0.0,
                frameStatus = SubtitleFrameStatus.Rendered,
            )

        val first = policy.evaluateTelemetry(overBudgetSample, nowMs = 1_000L)
        assertFalse(first.dropFrame)
        assertTrue(policy.allowRender(nowMs = 1_000L))

        val second = policy.evaluateTelemetry(overBudgetSample, nowMs = 1_000L)
        assertTrue(second.dropFrame)
        assertFalse(policy.allowRender(nowMs = 1_000L))
        assertTrue(policy.allowRender(nowMs = 1_500L))
    }
}

