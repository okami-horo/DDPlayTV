package com.xyoye.player.kernel.impl.vlc

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.xyoye.common_component.config.PlayerConfig
import com.xyoye.data_component.enums.VLCAudioOutput
import com.xyoye.player.info.PlayerInitializer
import com.xyoye.player.utils.VideoLog

object VlcAudioPolicy {
    private const val TAG = "VlcAudioPolicy"

    fun resolveOutput(preferred: VLCAudioOutput): VLCAudioOutput {
        if (preferred == VLCAudioOutput.OPEN_SL_ES) {
            return VLCAudioOutput.OPEN_SL_ES
        }

        if (PlayerConfig.isVlcAudioCompatMode()) {
            VideoLog.d("$TAG--resolve--> cached compat mode, force OpenSL ES")
            return VLCAudioOutput.OPEN_SL_ES
        }

        if (PlayerConfig.isVlcAudioCompatChecked()) {
            return preferred
        }

        val supportPcm32 = supportsPcm32()
        PlayerConfig.putVlcAudioCompatChecked(true)
        if (!supportPcm32) {
            PlayerConfig.putVlcAudioCompatMode(true)
            VideoLog.d("$TAG--resolve--> PCM32 unsupported, fallback to OpenSL ES")
            return VLCAudioOutput.OPEN_SL_ES
        }

        VideoLog.d("$TAG--resolve--> PCM32 supported, keep preferred output")
        return preferred
    }

    fun markCompatAfterError() {
        if (PlayerConfig.isVlcAudioCompatMode()) {
            return
        }
        PlayerConfig.putVlcAudioCompatMode(true)
        PlayerInitializer.Player.vlcAudioOutput = VLCAudioOutput.OPEN_SL_ES
        VideoLog.d("$TAG--error--> marked compat mode after playback error")
    }

    private fun supportsPcm32(): Boolean {
        val encoding32 = try {
            AudioFormat::class.java.getField("ENCODING_PCM_32BIT").getInt(null)
        } catch (e: Exception) {
            VideoLog.d("$TAG--probe--> PCM32 field missing, treat as unsupported")
            return false
        }

        return try {
            val sampleRate = 44_100
            val channelMask = AudioFormat.CHANNEL_OUT_STEREO
            val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding32)
            if (bufferSize <= 0) {
                VideoLog.d("$TAG--probe--> getMinBufferSize <= 0 for PCM32")
                return false
            }

            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(encoding32)
                .setChannelMask(channelMask)
                .build()
            val track = AudioTrack(
                attributes,
                format,
                bufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            val initialized = track.state == AudioTrack.STATE_INITIALIZED
            track.release()
            if (!initialized) {
                VideoLog.d("$TAG--probe--> AudioTrack not initialized with PCM32")
            }
            initialized
        } catch (e: Throwable) {
            VideoLog.d("$TAG--probe--> exception while probing PCM32: ${e.message}")
            false
        }
    }
}
