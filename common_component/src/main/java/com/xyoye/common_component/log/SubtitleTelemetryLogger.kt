package com.xyoye.common_component.log

import com.xyoye.common_component.utils.DDLog
import com.xyoye.data_component.bean.subtitle.FallbackEvent
import com.xyoye.data_component.bean.subtitle.SubtitlePipelineState
import com.xyoye.data_component.bean.subtitle.TelemetrySample
import com.xyoye.data_component.enums.SubtitleFrameStatus
import com.xyoye.data_component.enums.SubtitlePipelineStatus

object SubtitleTelemetryLogger {
    private const val TAG = "SUB-GPU"

    fun logSample(sample: TelemetrySample, state: SubtitlePipelineState?) {
        val builder = StringBuilder()
            .append("frame=").append(sample.frameStatus.name)
            .append(" pts=").append(sample.subtitlePtsMs)
            .append(" render=").append(sample.renderLatencyMs)
            .append(" upload=").append(sample.uploadLatencyMs)

        sample.compositeLatencyMs?.let { builder.append(" composite=").append(it) }
        sample.dropReason?.let { builder.append(" drop=").append(it) }
        sample.cpuUsagePct?.let { builder.append(" cpu=").append(it) }
        sample.gpuOverutilized?.let { builder.append(" gpu_over=").append(it) }
        sample.vsyncMiss?.let { builder.append(" vsync_miss=").append(it) }
        state?.let {
            builder.append(" mode=").append(it.mode.name)
            builder.append(" status=").append(it.status.name)
        }

        val message = builder.toString()
        if (sample.frameStatus == SubtitleFrameStatus.Rendered) {
            DDLog.i(TAG, message)
        } else {
            DDLog.w(TAG, message)
        }
    }

    fun logFallback(event: FallbackEvent) {
        DDLog.w(
            TAG,
            "fallback from=${event.fromMode.name} to=${event.toMode.name} reason=${event.reason.name} recoverable=${event.recoverable} surface=${event.surfaceId}"
        )
    }

    fun logState(state: SubtitlePipelineState) {
        val message =
            "state mode=${state.mode.name} status=${state.status.name} surface=${state.surfaceId} fallback=${state.fallbackReason} telemetry=${state.telemetryEnabled}"
        if (state.status == SubtitlePipelineStatus.Error) {
            DDLog.e(TAG, message)
        } else {
            DDLog.i(TAG, message)
        }
    }
}
