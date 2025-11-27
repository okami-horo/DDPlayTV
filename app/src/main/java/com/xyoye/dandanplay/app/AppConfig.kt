package com.xyoye.dandanplay.app

import android.content.Context
import androidx.annotation.XmlRes
import com.xyoye.common_component.utils.DDLog
import com.xyoye.dandanplay.BuildConfig
import com.xyoye.dandanplay.R
import java.util.concurrent.atomic.AtomicBoolean
import org.xmlpull.v1.XmlPullParser
import com.xyoye.common_component.config.AppConfig as CommonAppConfig

/**
 * Bootstraps remote-config style defaults before other modules request toggle state.
 */
object AppConfig {

    private const val TAG = "Media3-AppConfig"
    private const val KEY_MEDIA3_ENABLED = "media3_enabled"
    private const val KEY_SUBTITLE_GPU_ENABLED = "subtitle_gpu_enabled"
    private const val KEY_SUBTITLE_TELEMETRY_ENABLED = "subtitle_telemetry_enabled"
    private val initialized = AtomicBoolean(false)

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) {
            return
        }

        val defaults = parseDefaults(context, R.xml.remote_config_defaults)
        val resolvedValue = defaults[KEY_MEDIA3_ENABLED] ?: BuildConfig.MEDIA3_ENABLED_FALLBACK
        CommonAppConfig.putMedia3EnabledRemote(resolvedValue)
        DDLog.i(TAG, "media3_enabled default loaded: $resolvedValue")

        val gpuEnabled = defaults[KEY_SUBTITLE_GPU_ENABLED] ?: BuildConfig.SUBTITLE_GPU_ENABLED_FALLBACK
        CommonAppConfig.putSubtitleGpuEnabledRemote(gpuEnabled)
        DDLog.i(TAG, "subtitle_gpu_enabled default loaded: $gpuEnabled")

        val telemetryEnabled =
            defaults[KEY_SUBTITLE_TELEMETRY_ENABLED] ?: BuildConfig.SUBTITLE_TELEMETRY_ENABLED_FALLBACK
        CommonAppConfig.putSubtitleTelemetryEnabledRemote(telemetryEnabled)
        DDLog.i(TAG, "subtitle_telemetry_enabled default loaded: $telemetryEnabled")
    }

    fun media3RemoteValue(): Boolean = CommonAppConfig.isMedia3EnabledRemote()

    fun overrideMedia3Remote(value: Boolean) {
        CommonAppConfig.putMedia3EnabledRemote(value)
        DDLog.i(TAG, "media3_enabled override applied: $value")
    }

    fun subtitleGpuRemoteValue(): Boolean = CommonAppConfig.isSubtitleGpuEnabledRemote()

    fun overrideSubtitleGpuRemote(value: Boolean) {
        CommonAppConfig.putSubtitleGpuEnabledRemote(value)
        DDLog.i(TAG, "subtitle_gpu_enabled override applied: $value")
    }

    fun subtitleTelemetryRemoteValue(): Boolean = CommonAppConfig.isSubtitleTelemetryEnabledRemote()

    fun overrideSubtitleTelemetryRemote(value: Boolean) {
        CommonAppConfig.putSubtitleTelemetryEnabledRemote(value)
        DDLog.i(TAG, "subtitle_telemetry_enabled override applied: $value")
    }

    private fun parseDefaults(context: Context, @XmlRes xmlRes: Int): Map<String, Boolean> {
        val parser = context.resources.getXml(xmlRes)
        val defaults = mutableMapOf<String, Boolean>()
        var currentKey: String? = null
        var currentValue: Boolean? = null
        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "entry" -> {
                            currentKey = null
                            currentValue = null
                        }
                        "key" -> currentKey = parser.nextText()
                        "value" -> {
                            val rawValue = parser.nextText()
                            currentValue = rawValue.toBooleanStrictOrNull() ?: rawValue.equals("true", true)
                        }
                    }

                    XmlPullParser.END_TAG -> if (parser.name == "entry") {
                        val key = currentKey
                        val value = currentValue
                        if (key != null && value != null) {
                            defaults[key] = value
                        }
                    }
                }
                event = parser.next()
            }
        } finally {
            parser.close()
        }
        return defaults
    }
}
