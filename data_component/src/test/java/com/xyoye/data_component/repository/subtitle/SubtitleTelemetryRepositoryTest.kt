package com.xyoye.data_component.repository.subtitle

import com.xyoye.data_component.bean.subtitle.FallbackEvent
import com.xyoye.data_component.bean.subtitle.SubtitlePipelineState
import com.xyoye.data_component.bean.subtitle.TelemetrySample
import com.xyoye.data_component.enums.SubtitleFrameStatus
import com.xyoye.data_component.enums.SubtitlePipelineFallbackReason
import com.xyoye.data_component.enums.SubtitlePipelineMode
import com.xyoye.data_component.enums.SubtitlePipelineStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleTelemetryRepositoryTest {
    @Test
    fun latestSnapshot_returnsNull_whenNoSamples() =
        runTest {
            val repository = SubtitleTelemetryRepository(windowMs = 1_000L, maxSamples = 10)
            assertNull(repository.latestSnapshot())
        }

    @Test
    fun latestSnapshot_rollsWindowAndIncludesStateAndFallback() =
        runTest {
            val repository = SubtitleTelemetryRepository(windowMs = 1_000L, maxSamples = 10)
            val initialState =
                SubtitlePipelineState(
                    mode = SubtitlePipelineMode.GPU_GL,
                    status = SubtitlePipelineStatus.Active,
                    surfaceId = "surface-1",
                )
            repository.updateState(initialState)

            repository.submit(
                TelemetrySample(
                    timestampMs = 0L,
                    subtitlePtsMs = 0L,
                    renderLatencyMs = 1.0,
                    uploadLatencyMs = 1.0,
                    compositeLatencyMs = 1.0,
                    frameStatus = SubtitleFrameStatus.Rendered,
                    cpuUsagePct = 12.0,
                ),
            )
            repository.submit(
                TelemetrySample(
                    timestampMs = 500L,
                    subtitlePtsMs = 500L,
                    renderLatencyMs = 1.0,
                    uploadLatencyMs = 1.0,
                    compositeLatencyMs = 1.0,
                    frameStatus = SubtitleFrameStatus.Dropped,
                ),
            )

            val fallbackEvent =
                FallbackEvent(
                    timestampMs = 600L,
                    fromMode = SubtitlePipelineMode.GPU_GL,
                    toMode = SubtitlePipelineMode.FALLBACK_CPU,
                    reason = SubtitlePipelineFallbackReason.GL_ERROR,
                    surfaceId = "surface-1",
                    recoverable = false,
                )
            repository.recordFallback(fallbackEvent)
            repository.updateState(initialState.copy(mode = SubtitlePipelineMode.FALLBACK_CPU))

            repository.submit(
                TelemetrySample(
                    timestampMs = 1_500L,
                    subtitlePtsMs = 1_500L,
                    renderLatencyMs = 1.0,
                    uploadLatencyMs = 1.0,
                    compositeLatencyMs = 1.0,
                    frameStatus = SubtitleFrameStatus.Rendered,
                    cpuUsagePct = 9.0,
                ),
            )

            val snapshot = repository.latestSnapshot()
            assertNotNull(snapshot)
            snapshot!!

            assertEquals(1_000L, snapshot.windowMs)
            assertEquals(1, snapshot.renderedFrames)
            assertEquals(1, snapshot.droppedFrames)
            assertNotNull(snapshot.vsyncHitRate)
            assertEquals(0.5, snapshot.vsyncHitRate!!, 0.0001)
            assertEquals(SubtitlePipelineMode.FALLBACK_CPU, snapshot.mode)
            assertEquals(fallbackEvent, snapshot.lastFallback)
            assertNotNull(snapshot.cpuPeakPct)
            assertEquals(9.0, snapshot.cpuPeakPct!!, 0.0001)
        }
}
