package com.xyoye.player.subtitle.gpu

import com.xyoye.common_component.subtitle.pipeline.FallbackCommand
import com.xyoye.common_component.subtitle.pipeline.PipelineInitRequest
import com.xyoye.data_component.enums.SubtitlePipelineFallbackReason
import com.xyoye.data_component.enums.SubtitlePipelineMode
import com.xyoye.data_component.enums.SubtitlePipelineStatus
import com.xyoye.data_component.enums.SubtitleViewType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSubtitlePipelineApiTest {
    @Test
    fun fallback_preservesDegradedReasonUntilRecoveryCompletes() =
        runTest {
            val api = LocalSubtitlePipelineApi()
            val request =
                PipelineInitRequest(
                    surfaceId = "surface-1",
                    viewType = SubtitleViewType.SurfaceView,
                    width = 1920,
                    height = 1080,
                    rotation = 0,
                    telemetryEnabled = true,
                )
            val state =
                api.init(
                    request,
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
                        reason = SubtitlePipelineFallbackReason.SEEK,
                    ),
                )
            assertEquals(SubtitlePipelineMode.GPU_GL, recovering.mode)
            assertEquals(SubtitlePipelineStatus.Initializing, recovering.status)
            assertEquals(SubtitlePipelineFallbackReason.GL_ERROR, recovering.fallbackReason)
            assertNotNull(recovering.lastRecoverAt)

            val recovered = api.init(request)
            assertEquals(SubtitlePipelineMode.GPU_GL, recovered.mode)
            assertEquals(SubtitlePipelineStatus.Active, recovered.status)
            assertEquals(recovering.lastRecoverAt, recovered.lastRecoverAt)
            assertNull(recovered.fallbackReason)
        }

    @Test
    fun fallbackCommand_reportsRecoverableStateTransitions() {
        val degraded =
            FallbackCommand(
                targetMode = SubtitlePipelineMode.FALLBACK_CPU,
                reason = SubtitlePipelineFallbackReason.GL_ERROR,
            ).toEvent(SubtitlePipelineMode.GPU_GL, "surface-1")
        assertNotNull(degraded)
        assertTrue(degraded!!.recoverable)

        val unsupported =
            FallbackCommand(
                targetMode = SubtitlePipelineMode.FALLBACK_CPU,
                reason = SubtitlePipelineFallbackReason.UNSUPPORTED_GPU,
            ).toEvent(SubtitlePipelineMode.GPU_GL, "surface-1")
        assertNotNull(unsupported)
        assertFalse(unsupported!!.recoverable)

        val recoveryPending =
            FallbackCommand(
                targetMode = SubtitlePipelineMode.GPU_GL,
                reason = SubtitlePipelineFallbackReason.TRACK_CHANGED,
            ).toEvent(SubtitlePipelineMode.FALLBACK_CPU, "surface-1")
        assertNotNull(recoveryPending)
        assertTrue(recoveryPending!!.recoverable)
    }
}
