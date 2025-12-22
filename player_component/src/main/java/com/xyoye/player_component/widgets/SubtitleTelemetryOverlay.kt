package com.xyoye.player_component.widgets

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import com.xyoye.player_component.R
import com.xyoye.common_component.log.SubtitleTelemetryLogger
import com.xyoye.data_component.bean.subtitle.TelemetrySample
import com.xyoye.data_component.bean.subtitle.TelemetrySnapshot
import com.xyoye.data_component.enums.SubtitleFrameStatus

/**
 * Lightweight overlay to surface GPU subtitle telemetry on-device without
 * spamming adb logs. A simple filter keeps noisy samples out of the log stream.
 */
class SubtitleTelemetryOverlay
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
    ) : FrameLayout(context, attrs, defStyleAttr) {
        private val titleView =
            TextView(context).apply {
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
                textSize = 12f
                text = context.getString(R.string.subtitle_telemetry_title)
                layoutParams =
                    LayoutParams(
                        LayoutParams.WRAP_CONTENT,
                        LayoutParams.WRAP_CONTENT,
                    ).apply {
                        gravity = Gravity.START or Gravity.TOP
                    }
            }

        private val metricsView =
            TextView(context).apply {
                setTextColor(Color.WHITE)
                textSize = 12f
                layoutParams =
                    LayoutParams(
                        LayoutParams.WRAP_CONTENT,
                        LayoutParams.WRAP_CONTENT,
                    ).apply {
                        gravity = Gravity.START or Gravity.TOP
                        topMargin = PADDING * 2
                    }
            }

        private var logFilter: ((TelemetrySample) -> Boolean)? = null

        init {
            setBackgroundColor(OVERLAY_BG)
            setPadding(PADDING, PADDING, PADDING, PADDING)
            addView(titleView)
            addView(metricsView)
            visibility = GONE
        }

        fun updateSnapshot(snapshot: TelemetrySnapshot?) {
            if (snapshot == null) {
                visibility = GONE
                return
            }
            visibility = VISIBLE
            val builder = StringBuilder()
            builder.append("Window: ").append(snapshot.windowMs ?: 0).append("ms\n")
            builder
                .append("Frames: ")
                .append(snapshot.renderedFrames)
                .append(" rendered / ")
                .append(snapshot.droppedFrames)
                .append(" dropped\n")
            builder
                .append("Vsync hit: ")
                .append(snapshot.vsyncHitRate?.let { "%.2f".format(it * 100) } ?: "n/a")
                .append("%\n")
            snapshot.cpuPeakPct?.let {
                builder.append("CPU max: ").append("%.1f".format(it)).append("%\n")
            }
            snapshot.mode?.let { builder.append("Mode: ").append(it.name) }
            metricsView.text = builder.toString().trimEnd()
        }

        fun logSample(sample: TelemetrySample) {
            val shouldLog = logFilter?.invoke(sample) ?: defaultFilter(sample)
            if (shouldLog) {
                SubtitleTelemetryLogger.logSample(sample, null)
            }
        }

        fun setLogFilter(filter: ((TelemetrySample) -> Boolean)?) {
            logFilter = filter
        }

        private fun defaultFilter(sample: TelemetrySample): Boolean =
            sample.frameStatus != SubtitleFrameStatus.Rendered ||
                (sample.gpuOverutilized == true) ||
                (sample.vsyncMiss == true)

        companion object {
            private const val PADDING = 12
            private const val OVERLAY_BG = 0x66000000
        }
    }
