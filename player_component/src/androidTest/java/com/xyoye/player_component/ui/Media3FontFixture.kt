package com.xyoye.player_component.ui

import android.net.Uri
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.PositionHolder
import androidx.media3.test.utils.FakeExtractorOutput
import androidx.test.platform.app.InstrumentationRegistry
import com.xyoye.player.kernel.impl.media3.LibassMatroskaExtractor
import com.xyoye.player.kernel.impl.media3.Media3FontAttachmentSink
import java.io.File

/** Generates a real MKV attachment from an AVD font without redistributing system font binaries. */
internal object Media3FontFixture {
    fun extract(fontSink: Media3FontAttachmentSink) {
        val source =
            InstrumentationRegistry
                .getInstrumentation()
                .context.assets
                .open("media3/subtitle-compatibility.mkv")
                .use { it.readBytes() }
        val font = File("/system/fonts/NotoSerif-Regular.ttf").readBytes()
        val attachedFile =
            element(
                0x61A7,
                element(0x465C, font) + // Deliberately put data before MIME/name.
                    element(0x466E, "NotoSerif-Regular.ttf".toByteArray()) +
                    element(0x4660, "font/ttf".toByteArray()) +
                    element(0x46AE, byteArrayOf(1))
            )
        val attachment = element(0x1941A469, attachedFile)
        val segmentId = byteArrayOf(0x18, 0x53, 0x80.toByte(), 0x67)
        val segmentOffset =
            source.indices.first { offset ->
                offset + 4 <= source.size && segmentId.indices.all { source[offset + it] == segmentId[it] }
            }
        val sizeOffset = segmentOffset + 4
        var sizeLength = 1
        while ((source[sizeOffset].toInt() and (0x80 shr (sizeLength - 1))) == 0) sizeLength++
        // The original seek positions remain valid; an unknown Segment size permits appended data.
        source[sizeOffset] = ((1 shl (9 - sizeLength)) - 1).toByte()
        for (index in 1 until sizeLength) source[sizeOffset + index] = 0xFF.toByte()
        val data = source + attachment
        val dataSource = ByteArrayDataSource(data)
        val extractor = LibassMatroskaExtractor(fontSink)
        extractor.init(FakeExtractorOutput())
        val seek = PositionHolder()
        var position = 0L
        try {
            while (true) {
                dataSource.open(
                    DataSpec
                        .Builder()
                        .setUri(Uri.EMPTY)
                        .setPosition(position)
                        .build()
                )
                val input = DefaultExtractorInput(dataSource, position, data.size.toLong())
                var result: Int
                do {
                    result = extractor.read(input, seek)
                } while (result == Extractor.RESULT_CONTINUE)
                dataSource.close()
                if (result == Extractor.RESULT_END_OF_INPUT) break
                position = seek.position
            }
        } finally {
            dataSource.close()
            extractor.release()
        }
    }

    private fun element(
        id: Int,
        payload: ByteArray
    ): ByteArray {
        val idBytes = (3 downTo 0).map { (id ushr (it * 8)).toByte() }.dropWhile { it == 0.toByte() }.toByteArray()
        var width = 1
        while (payload.size.toLong() >= (1L shl (7 * width)) - 1) width++
        val encodedSize = payload.size.toLong() or (1L shl (7 * width))
        val sizeBytes = (width - 1 downTo 0).map { (encodedSize ushr (it * 8)).toByte() }.toByteArray()
        return idBytes + sizeBytes + payload
    }
}
