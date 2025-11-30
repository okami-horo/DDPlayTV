package com.xyoye.player_component.subtitle.gpu

import com.xyoye.data_component.bean.subtitle.TelemetrySample
import com.xyoye.data_component.enums.SubtitleFrameStatus
import kotlin.math.max

/**
 * Simple load-shedding heuristic to avoid overwhelming the render/upload pipeline.
 * If recent frames exceed the budget or keep dropping, throttle both rendering
 * and telemetry submissions for a short cool-down window.
 */
class SubtitleLoadSheddingPolicy(
    private val frameBudgetMs: Double = 25.0,
    private val dropBurstThreshold: Int = 5,
    private val throttleWindowMs: Long = 500L
){
    private var throttleUntilMs: Long = 0L
    private var consecutiveOverBudget: Int = 0

    fun allowRender(nowMs: Long = System.currentTimeMillis()): Boolean {
        return nowMs >= throttleUntilMs
    }

    fun evaluateTelemetry(sample: TelemetrySample, nowMs: Long = System.currentTimeMillis()): LoadSheddingDecision {
        val composite = sample.compositeLatencyMs ?: 0.0
        val overBudget = (sample.renderLatencyMs + sample.uploadLatencyMs + composite) > frameBudgetMs
        val dropped = sample.frameStatus != SubtitleFrameStatus.Rendered
        if (overBudget || dropped) {
            consecutiveOverBudget++
        } else {
            consecutiveOverBudget = max(consecutiveOverBudget - 1, 0)
        }
        if (consecutiveOverBudget >= dropBurstThreshold) {
            throttleUntilMs = nowMs + throttleWindowMs
            consecutiveOverBudget = 0
        }
        val throttling = nowMs < throttleUntilMs
        return LoadSheddingDecision(
            dropFrame = throttling,
            skipTelemetry = throttling,
            gpuOverutilized = overBudget || throttling,
            vsyncMiss = dropped
        )
    }
}

data class LoadSheddingDecision(
    val dropFrame: Boolean,
    val skipTelemetry: Boolean,
    val gpuOverutilized: Boolean,
    val vsyncMiss: Boolean
)
