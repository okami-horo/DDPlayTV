package com.xyoye.data_component.repository.subtitle

import com.xyoye.data_component.bean.subtitle.FallbackEvent
import com.xyoye.data_component.bean.subtitle.SubtitlePipelineState
import com.xyoye.data_component.bean.subtitle.TelemetrySample
import com.xyoye.data_component.bean.subtitle.TelemetrySnapshot
import com.xyoye.data_component.enums.SubtitleFrameStatus
import com.xyoye.data_component.enums.SubtitlePipelineMode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.max

/**
 * Aggregates raw telemetry samples into a rolling snapshot that mirrors the
 * `/subtitle/pipeline/telemetry` contracts. Keeps a small in-memory buffer so
 * callers can fetch the latest window without hitting storage.
 */
class SubtitleTelemetryRepository(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val maxSamples: Int = DEFAULT_MAX_SAMPLES
) {
    private val mutex = Mutex()
    private val samples = ArrayDeque<TelemetrySample>()
    private var lastFallback: FallbackEvent? = null
    private var latestState: SubtitlePipelineState? = null

    suspend fun submit(sample: TelemetrySample, state: SubtitlePipelineState? = null) {
        mutex.withLock {
            state?.let { latestState = it }
            appendSampleLocked(sample)
        }
    }

    suspend fun latestSnapshot(): TelemetrySnapshot? {
        return mutex.withLock { buildSnapshotLocked() }
    }

    suspend fun updateState(state: SubtitlePipelineState) {
        mutex.withLock { latestState = state }
    }

    suspend fun recordFallback(event: FallbackEvent) {
        mutex.withLock { lastFallback = event }
    }

    private fun appendSampleLocked(sample: TelemetrySample) {
        if (samples.size >= maxSamples) {
            samples.removeFirst()
        }
        samples.addLast(sample)
        trimWindowLocked(sample.timestampMs)
    }

    private fun trimWindowLocked(nowMs: Long) {
        val cutoff = nowMs - windowMs
        while (samples.isNotEmpty() && samples.first().timestampMs < cutoff) {
            samples.removeFirst()
        }
    }

    private fun buildSnapshotLocked(): TelemetrySnapshot? {
        if (samples.isEmpty()) return null
        val newest = samples.last()
        val oldest = samples.first()
        val total = samples.size
        val rendered = samples.count { it.frameStatus == SubtitleFrameStatus.Rendered }
        val dropped = total - rendered
        val vsyncHitRate = if (total == 0) null else rendered.toDouble() / total.toDouble()
        val cpuPeak = samples.mapNotNull { it.cpuUsagePct }.maxOrNull()
        val snapshotWindow = max(newest.timestampMs - oldest.timestampMs, 0L)
        return TelemetrySnapshot(
            windowMs = snapshotWindow,
            renderedFrames = rendered,
            droppedFrames = dropped,
            vsyncHitRate = vsyncHitRate,
            cpuPeakPct = cpuPeak,
            mode = latestState?.mode ?: SubtitlePipelineMode.GPU_GL,
            lastFallback = lastFallback
        )
    }

    companion object {
        private const val DEFAULT_WINDOW_MS = 10_000L
        private const val DEFAULT_MAX_SAMPLES = 240
    }
}
