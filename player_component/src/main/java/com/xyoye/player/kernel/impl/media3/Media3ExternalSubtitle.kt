package com.xyoye.player.kernel.impl.media3

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import com.xyoye.data_component.bean.VideoTrackBean
import com.xyoye.player.subtitle.gpu.AssGpuNativeBridge
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

@UnstableApi
internal object Media3ExternalSubtitle {
    fun id(track: VideoTrackBean): String {
        val path = requireNotNull(track.type.getSubtitle(track.trackResource))
        val uri = Uri.parse(path).normalizeScheme()
        val identity =
            when (uri.scheme) {
                null -> Uri.fromFile(File(path).absoluteFile.normalize()).toString()
                "file" -> Uri.fromFile(File(requireNotNull(uri.path)).absoluteFile.normalize()).toString()
                else -> uri.toString()
            }
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        return "dd-external-" + digest.joinToString("") { "%02x".format(it) }
    }

    /** MergingMediaPeriod adds numeric child prefixes; retain the full ID for selection. */
    fun matchesId(
        formatId: String?,
        subtitleId: String
    ): Boolean {
        var candidate = formatId ?: return false
        while (candidate != subtitleId) {
            val prefix = candidate.substringBefore(':', "").toIntOrNull() ?: return false
            if (prefix < 0) return false
            candidate = candidate.substringAfter(':')
        }
        return true
    }

    fun mimeType(
        context: Context,
        track: VideoTrackBean
    ): String? {
        val path = track.type.getSubtitle(track.trackResource) ?: return null
        val uri = Uri.parse(path)
        val name =
            if (uri.scheme == "content") {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: track.name
            } else {
                track.name.ifBlank { uri.lastPathSegment.orEmpty() }
            }
        return when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            "srt" -> MimeTypes.APPLICATION_SUBRIP
            "vtt" -> MimeTypes.TEXT_VTT
            "ttml", "dfxp", "xml" -> MimeTypes.APPLICATION_TTML
            else -> null
        }
    }

    /** Validate before replacing the active source, so unreadable files leave playback intact. */
    suspend fun prepare(
        context: Context,
        track: VideoTrackBean,
        mimeType: String,
        directory: File
    ): MediaItem.SubtitleConfiguration {
        val path = requireNotNull(track.type.getSubtitle(track.trackResource))
        val uri = Uri.parse(path).let { if (it.scheme == null) Uri.fromFile(File(path)) else it }
        val source = DefaultDataSource.Factory(context).createDataSource()
        val bytes = ByteArrayOutputStream()
        try {
            val size = source.open(DataSpec.Builder().setUri(uri).build())
            require(size == C.LENGTH_UNSET.toLong() || size <= MAX_BYTES) { "Subtitle exceeds size limit" }
            val buffer = ByteArray(8192)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = source.read(buffer, 0, buffer.size)
                if (read == C.RESULT_END_OF_INPUT) break
                require(bytes.size() <= MAX_BYTES - read) { "Subtitle exceeds size limit" }
                bytes.write(buffer, 0, read)
            }
        } finally {
            source.close()
        }
        val data = bytes.toByteArray()
        require(data.isNotEmpty()) { "Empty subtitle" }
        if (mimeType == MimeTypes.TEXT_SSA) {
            val native = AssGpuNativeBridge()
            try {
                require(native.readDocument(data, 0, emptyList(), null) && (native.embeddedTrackInfo()?.eventCount ?: 0) > 0) {
                    "No ASS events"
                }
            } finally {
                native.release()
            }
        } else {
            val parser = DefaultSubtitleParserFactory().create(Format.Builder().setSampleMimeType(mimeType).build())
            var hasCues = false
            parser.parse(data, SubtitleParser.OutputOptions.allCues()) { if (it.cues.isNotEmpty()) hasCues = true }
            require(hasCues) { "No subtitle cues" }
        }
        currentCoroutineContext().ensureActive()
        directory.mkdirs()
        val file = File(directory, UUID.randomUUID().toString())
        try {
            file.writeBytes(data)
            currentCoroutineContext().ensureActive()
        } catch (error: Exception) {
            file.delete()
            throw error
        }
        return MediaItem.SubtitleConfiguration
            .Builder(Uri.fromFile(file))
            .setId(id(track))
            .setLabel(track.name)
            .setMimeType(mimeType)
            .build()
    }

    private const val MAX_BYTES = 16 * 1024 * 1024
}
