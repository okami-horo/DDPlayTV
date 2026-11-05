package com.xyoye.player.kernel.impl.media3

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.EbmlProcessor
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.SubtitleTranscodingExtractorOutput
import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.model.LogModule
import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** Extends Media3's EBML reader; audio/video and subtitle extraction remain upstream-owned. */
@UnstableApi
internal class LibassMatroskaExtractor private constructor(
    private val delegate: FontAttachmentExtractor,
    private val input: Media3SubtitleInput?,
    private val parserFactory: SubtitleParser.Factory
) : Extractor by delegate {
    constructor(
        fontSink: Media3FontAttachmentSink,
        input: Media3SubtitleInput? = null,
        parserFactory: SubtitleParser.Factory = DefaultSubtitleParserFactory()
    ) : this(FontAttachmentExtractor(fontSink), input, parserFactory)

    private var transcodingOutput: SubtitleTranscodingExtractorOutput? = null
    private var assOutput: LibassSubtitleExtractorOutput? = null

    override fun init(output: ExtractorOutput) {
        val subtitleInput = input
        if (subtitleInput == null) {
            delegate.init(output)
        } else {
            val transcoding = SubtitleTranscodingExtractorOutput(output, parserFactory)
            transcodingOutput = transcoding
            val capturing = LibassSubtitleExtractorOutput(transcoding, subtitleInput)
            assOutput = capturing
            delegate.init(capturing)
        }
    }

    override fun seek(
        position: Long,
        timeUs: Long
    ) {
        assOutput?.reset()
        transcodingOutput?.resetSubtitleParsers()
        delegate.seek(position, timeUs)
    }

    override fun release() {
        // Upstream release is final, so pending attachment ownership ends at this wrapper.
        delegate.discardPendingFont()
        assOutput?.release()
        delegate.release()
    }
}

@UnstableApi
private class FontAttachmentExtractor(
    private val fontSink: Media3FontAttachmentSink
) : MatroskaExtractor(DefaultSubtitleParserFactory(), FLAG_EMIT_RAW_SUBTITLE_DATA) {
    private var attachmentName = ""
    private var attachmentMime = ""
    private var attachmentData: ByteArray? = null
    private var compression: Long? = null
    private val compressedTextTracks = mutableMapOf<Int, ByteArray?>()

    override fun isLevel1Element(id: Int): Boolean = id == ID_ATTACHMENTS || super.isLevel1Element(id)

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
        if (id == ID_TRACK_ENTRY) compression = null
        // ContentCompAlgo is optional and defaults to zlib, not header stripping.
        if (id == ID_CONTENT_COMPRESSION) compression = 0
        if (id == ID_ATTACHED_FILE) {
            discardPendingFont()
            attachmentName = ""
            attachmentMime = ""
        }
    }

    override fun integerElement(
        id: Int,
        value: Long
    ) {
        if (id == ID_CONTENT_COMPRESSION_ALGORITHM) {
            compression = value
            // The block adapter handles zlib; keep upstream's validation for other algorithms.
            super.integerElement(id, if (value == 0L) 3L else value)
        } else {
            super.integerElement(id, value)
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
        if ((id == ID_BLOCK || id == ID_SIMPLE_BLOCK) && compressedTextTracks.isNotEmpty()) {
            val first = ByteArray(8)
            input.peekFully(first, 0, 1)
            val vintSize = Integer.numberOfLeadingZeros(first[0].toInt() and 0xFF) - 23
            try {
                if (vintSize in 1..8 && vintSize + 3 < contentSize) {
                    input.peekFully(first, 1, vintSize - 1)
                    var number = (first[0].toInt() and (0xFF ushr vintSize)).toLong()
                    for (index in 1 until vintSize) number = (number shl 8) or (first[index].toLong() and 0xFF)
                    if (number <= Int.MAX_VALUE && compressedTextTracks.containsKey(number.toInt())) {
                        if (contentSize > MAX_TEXT_BYTES) throw malformed("Compressed subtitle block exceeds limit")
                        val block = ByteArray(contentSize)
                        input.resetPeekPosition()
                        input.readFully(block, 0, block.size)
                        val headerSize = vintSize + 3
                        if (block[headerSize - 1].toInt() and 0x06 != 0) throw malformed("Laced compressed subtitle block")
                        val prefix = compressedTextTracks[number.toInt()]
                        val payload =
                            if (prefix == null) {
                                inflate(block, headerSize)
                            } else {
                                if (prefix.size > MAX_TEXT_BYTES - block.size) throw malformed("Stripped subtitle exceeds limit")
                                prefix + block.copyOfRange(headerSize, block.size)
                            }
                        val rewritten = block.copyOf(headerSize) + payload
                        super.binaryElement(id, rewritten.size, byteInput(rewritten))
                        return
                    }
                }
            } finally {
                input.resetPeekPosition()
            }
        }
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
        if (id == ID_TRACK_ENTRY && compression != null) {
            val track = getCurrentTrack(id)
            if (track.codecId in TEXT_CODECS) {
                compressedTextTracks[track.number] = if (compression == 0L) null else track.sampleStrippedBytes ?: byteArrayOf()
                track.sampleStrippedBytes = byteArrayOf()
            } else if (compression == 0L) {
                throw ParserException.createForUnsupportedContainerFeature("Zlib compression is only supported for text subtitles")
            }
        }
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

    fun discardPendingFont() {
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
        private const val ID_TRACK_ENTRY = 0xAE
        private const val ID_CONTENT_COMPRESSION = 0x5034
        private const val ID_CONTENT_COMPRESSION_ALGORITHM = 0x4254
        private const val ID_BLOCK = 0xA1
        private const val ID_SIMPLE_BLOCK = 0xA3
        private const val MAX_TEXT_BYTES = 16 * 1024 * 1024
        private val TEXT_CODECS = setOf("S_TEXT/ASS", "S_TEXT/SSA", "S_TEXT/UTF8", "S_TEXT/WEBVTT")
        private const val ID_ATTACHMENTS = 0x1941A469
        private const val ID_ATTACHED_FILE = 0x61A7
        private const val ID_FILE_NAME = 0x466E
        private const val ID_FILE_MIME = 0x4660
        private const val ID_FILE_DATA = 0x465C

        private fun malformed(
            message: String,
            cause: Throwable? = null
        ): ParserException = ParserException.createForMalformedContainer(message, cause)

        private fun inflate(
            block: ByteArray,
            headerSize: Int
        ): ByteArray {
            val inflater = Inflater()
            val limit = minOf(MAX_TEXT_BYTES.toLong(), maxOf(1024L * 1024, (block.size - headerSize).toLong() * 1024))
            try {
                inflater.setInput(block, headerSize, block.size - headerSize)
                val output = ByteArrayOutputStream()
                val scratch = ByteArray(4096)
                while (!inflater.finished()) {
                    val count = inflater.inflate(scratch)
                    if (count == 0 && !inflater.finished()) throw malformed("Incomplete zlib subtitle")
                    if (output.size().toLong() + count > limit) throw malformed("Inflated subtitle exceeds limit")
                    output.write(scratch, 0, count)
                }
                if (inflater.remaining != 0) throw malformed("Trailing data in zlib subtitle")
                return output.toByteArray()
            } catch (error: DataFormatException) {
                throw malformed("Invalid zlib subtitle", error)
            } finally {
                inflater.end()
            }
        }

        private fun byteInput(bytes: ByteArray): ExtractorInput {
            var position = 0
            return DefaultExtractorInput(
                DataReader { target, offset, length ->
                    if (length == 0) {
                        0
                    } else if (position == bytes.size) {
                        C.RESULT_END_OF_INPUT
                    } else {
                        val count = minOf(length, bytes.size - position)
                        bytes.copyInto(target, offset, position, position + count)
                        position += count
                        count
                    }
                },
                0,
                bytes.size.toLong()
            )
        }

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
    private val fontSink: Media3FontAttachmentSink,
    private val input: Media3SubtitleInput,
    private val parserFactory: SubtitleParser.Factory = LibassSubtitleParserFactory(input)
) : ExtractorsFactory {
    private val delegate = DefaultExtractorsFactory().setSubtitleParserFactory(parserFactory)

    override fun createExtractors(): Array<Extractor> = replace(delegate.createExtractors())

    override fun createExtractors(
        uri: Uri,
        responseHeaders: Map<String, List<String>>
    ): Array<Extractor> = replace(delegate.createExtractors(uri, responseHeaders))

    private fun replace(extractors: Array<Extractor>): Array<Extractor> =
        extractors.map { if (it is MatroskaExtractor) LibassMatroskaExtractor(fontSink, input, parserFactory) else it }.toTypedArray()
}
