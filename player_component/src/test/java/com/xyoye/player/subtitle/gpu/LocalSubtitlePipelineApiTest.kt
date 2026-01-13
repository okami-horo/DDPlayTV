package com.xyoye.player.subtitle.gpu

import com.xyoye.common_component.subtitle.pipeline.FallbackCommand
import com.xyoye.common_component.subtitle.pipeline.PipelineInitRequest
import com.xyoye.data_component.enums.SubtitlePipelineFallbackReason
import com.xyoye.data_component.enums.SubtitlePipelineMode
import com.xyoye.data_component.enums.SubtitlePipelineStatus
import com.xyoye.data_component.enums.SubtitleViewType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class LocalSubtitlePipelineApiTest {
    @Test
    fun fallback_transitionsBetweenGpuAndCpuModes() =
        runTest {
            val api = LocalSubtitlePipelineApi()
            val state =
                api.init(
                    PipelineInitRequest(
                        surfaceId = "surface-1",
                        viewType = SubtitleViewType.SurfaceView,
                        width = 1920,
                        height = 1080,
                        rotation = 0,
                        telemetryEnabled = true,
                    ),
                )

            assertEquals(SubtitlePipelineMode.GPU_GL, state.mode)
            assertEquals(SubtitlePipelineStatus.Active, state.status)

            val degraded =
                api.fallback(
                    FallbackCommand(
                        targetMode = SubtitlePipelineMode.FALLBACK_CPU,
                        reason = SubtitlePipelineFallbackReason.GL_ERROR,
                    ),
                )
            assertEquals(SubtitlePipelineMode.FALLBACK_CPU, degraded.mode)
            assertEquals(SubtitlePipelineStatus.Degraded, degraded.status)
            assertEquals(SubtitlePipelineFallbackReason.GL_ERROR, degraded.fallbackReason)

            val recovering =
                api.fallback(
                    FallbackCommand(
                        targetMode = SubtitlePipelineMode.GPU_GL,
                        reason = SubtitlePipelineFallbackReason.GL_ERROR,
                    ),
                )
            assertEquals(SubtitlePipelineMode.GPU_GL, recovering.mode)
            assertEquals(SubtitlePipelineStatus.Initializing, recovering.status)
            assertNotNull(recovering.lastRecoverAt)
        }
}

