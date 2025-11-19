package com.xyoye.player.kernel.impl.media3

import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo

@UnstableApi
object Media3Diagnostics {
    private const val TAG = "Media3Diag"

    fun logDecoderSelected(mime: String, secure: Boolean, decoder: MediaCodecInfo?) {
        Log.i(TAG, "Decoder selected: mime=$mime secure=$secure name=${decoder?.name ?: "<none>"}")
    }

    fun logDecoderCandidates(mime: String, secure: Boolean, candidates: List<MediaCodecInfo>) {
        val names = candidates.joinToString { it.name }
        Log.d(TAG, "Decoder candidates for mime=$mime secure=$secure -> [$names]")
    }

    fun logFormatRewritten(original: Format, rewritten: Format) {
        if (original === rewritten) return
        Log.i(TAG, "Format rewritten: mime=${original.sampleMimeType} -> ${rewritten.sampleMimeType}")
    }

    fun logHdrSelection(format: Format, hdrDisplay: Boolean, hdrTier: Int) {
        val transfer = format.colorInfo?.colorTransfer
        val isDv = format.sampleMimeType == androidx.media3.common.MimeTypes.VIDEO_DOLBY_VISION
        Log.i(TAG, "HDR preference applied: selected codec=${format.codecs} hdrDisplay=$hdrDisplay hdrTier=$hdrTier transfer=$transfer isDv=$isDv")
    }
}
