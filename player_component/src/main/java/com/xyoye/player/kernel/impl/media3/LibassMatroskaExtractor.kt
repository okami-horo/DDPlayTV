package com.xyoye.player.kernel.impl.media3

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.EbmlProcessor
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.model.LogModule

/** Extends Media3's EBML reader; audio/video and subtitle extraction remain upstream-owned. */
@UnstableApi
internal class LibassMatroskaExtractor(
    private val fontSink: Media3FontAttachmentSink
) : MatroskaExtractor(DefaultSubtitleParserFactory(), FLAG_EMIT_RAW_SUBTITLE_DATA) {
    private var attachmentName = ""
    private var attachmentMime = ""
    private var attachmentData: ByteArray? = null

    override fun getElementType(id: Int): Int =
        when (id) {
            ID_ATTACHMENTS, ID_ATTACHED_FILE -> EbmlProcessor.ELEMENT_TYPE_MASTER
            ID_FILE_NAME, ID_FILE_MIME -> EbmlProcessor.ELEMENT_TYPE_STRING
            ID_FILE_DATA -> EbmlProcessor.ELEMENT_TYPE_BINARY
            else -> super.getElementType(id)
        }

    override fun startMasterElement(
        id: Int,
        contentPosition: Long,
        contentSize: Long
    ) {
        super.startMasterElement(id, contentPosition, contentSize)
        if (id == ID_ATTACHED_FILE) {
            discardPendingFont()
            attachmentName = ""
            attachmentMime = ""
        }
    }

    override fun stringElement(
        id: Int,
        value: String
    ) {
        when (id) {
            ID_FILE_NAME -> attachmentName = value
            ID_FILE_MIME -> attachmentMime = value
            else -> super.stringElement(id, value)
        }
    }

    override fun binaryElement(
        id: Int,
        contentSize: Int,
        input: ExtractorInput
    ) {
        if (id != ID_FILE_DATA) {
            super.binaryElement(id, contentSize, input)
            return
        }
        discardPendingFont()
        if (!fontSink.reserve(contentSize)) {
            input.skipFully(contentSize)
            return
        }
        val data = ByteArray(contentSize)
        attachmentData = data
        try {
            input.readFully(data, 0, contentSize)
        } catch (error: java.io.IOException) {
            discardPendingFont()
            throw error
        }
    }

    override fun endMasterElement(id: Int) {
        super.endMasterElement(id)
        if (id != ID_ATTACHED_FILE) return
        val data = attachmentData ?: return
        attachmentData = null
        if (isFont(attachmentName, attachmentMime, data)) {
            fontSink.onFont(attachmentName.ifEmpty { "attachment.ttf" }, data)
        } else {
            fontSink.cancel(data.size)
            LogFacade.w(
                LogModule.PLAYER,
                "LibassMatroskaExtractor",
                "attachment skipped: unsupported font name=$attachmentName mime=$attachmentMime bytes=${data.size}"
            )
        }
    }

    private fun discardPendingFont() {
        attachmentData?.let { fontSink.cancel(it.size) }
        attachmentData = null
    }

    override fun seek(
        position: Long,
        timeUs: Long
    ) {
        discardPendingFont()
        super.seek(position, timeUs)
    }

    companion object {
        private const val ID_ATTACHMENTS = 0x1941A469
        private const val ID_ATTACHED_FILE = 0x61A7
        private const val ID_FILE_NAME = 0x466E
        private const val ID_FILE_MIME = 0x4660
        private const val ID_FILE_DATA = 0x465C

        private fun isFont(
            name: String,
            mime: String,
            data: ByteArray
        ): Boolean {
            if (data.size < 4) return false
            val signature = data.take(4).toByteArray()
            val sfnt =
                signature.contentEquals(byteArrayOf(0, 1, 0, 0)) ||
                    String(signature, Charsets.US_ASCII) in listOf("OTTO", "ttcf", "true", "typ1")
            val fontType =
                mime.startsWith("font/", true) ||
                    mime.contains("font", true) ||
                    name.endsWith(".ttf", true) ||
                    name.endsWith(".otf", true) ||
                    name.endsWith(".ttc", true)
            return sfnt && fontType
        }
    }
}

@UnstableApi
internal class LibassExtractorsFactory(
    private val fontSink: Media3FontAttachmentSink
) : ExtractorsFactory {
    private val delegate = DefaultExtractorsFactory().experimentalSetTextTrackTranscodingEnabled(false)

    override fun createExtractors(): Array<Extractor> = replace(delegate.createExtractors())

    override fun createExtractors(
        uri: Uri,
        responseHeaders: Map<String, List<String>>
    ): Array<Extractor> = replace(delegate.createExtractors(uri, responseHeaders))

    private fun replace(extractors: Array<Extractor>): Array<Extractor> =
        extractors.map { if (it is MatroskaExtractor) LibassMatroskaExtractor(fontSink) else it }.toTypedArray()
}
