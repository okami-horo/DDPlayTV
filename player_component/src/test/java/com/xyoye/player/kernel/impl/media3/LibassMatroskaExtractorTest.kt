package com.xyoye.player.kernel.impl.media3

import androidx.media3.extractor.Extractor
import androidx.media3.extractor.PositionHolder
import androidx.media3.test.utils.FakeExtractorInput
import androidx.media3.test.utils.FakeExtractorOutput
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class LibassMatroskaExtractorTest {
    @Test
    fun release_returnsTheBudgetForAnIncompleteAttachmentExactlyOnce() {
        val sink = BudgetSink()
        val extractor = LibassMatroskaExtractor(sink)
        extractor.init(FakeExtractorOutput())
        // Both masters announce more data, simulating cancellation before AttachedFile closes.
        val partial =
            byteArrayOf(0x19, 0x41, 0xA4.toByte(), 0x69, 0xE4.toByte(), 0x61, 0xA7.toByte(), 0xE4.toByte()) +
                element(byteArrayOf(0x46, 0x5C), byteArrayOf(0, 1, 0, 0))
        val input = FakeExtractorInput.Builder().setData(partial).build()
        while (extractor.read(input, PositionHolder()) != Extractor.RESULT_END_OF_INPUT) {
            // EOF leaves the attachment pending until release.
        }
        assertEquals(4, sink.pending)

        extractor.release()
        extractor.release()

        assertEquals(0, sink.pending)
    }

    @Test
    fun attachments_areRecoveredAfterInvalidEbmlBytes() {
        val sink = BudgetSink()
        val extractor = LibassMatroskaExtractor(sink)
        val font =
            element(byteArrayOf(0x46, 0x6E), "font.ttf".toByteArray()) +
                element(byteArrayOf(0x46, 0x60), "font/ttf".toByteArray()) +
                element(byteArrayOf(0x46, 0x5C), byteArrayOf(0, 1, 0, 0))
        val data =
            byteArrayOf(0x08, 0, 0, 0, 0) +
                element(
                    byteArrayOf(0x19, 0x41, 0xA4.toByte(), 0x69),
                    element(byteArrayOf(0x61, 0xA7.toByte()), font)
                )
        val input = FakeExtractorInput.Builder().setData(data).build()
        extractor.init(FakeExtractorOutput())
        try {
            while (extractor.read(input, PositionHolder()) != Extractor.RESULT_END_OF_INPUT) {
                // Read through the attachment using the real upstream EBML reader.
            }
            assertEquals(1, sink.fonts)
            assertEquals(0, sink.pending)
        } finally {
            extractor.release()
        }
    }

    private fun element(
        id: ByteArray,
        data: ByteArray
    ): ByteArray = id + byteArrayOf((0x80 or data.size).toByte()) + data

    private class BudgetSink : Media3FontAttachmentSink {
        var pending = 0
        var fonts = 0

        override fun reserve(size: Int): Boolean {
            pending += size
            return true
        }

        override fun cancel(size: Int) {
            pending -= size
        }

        override fun onFont(
            name: String,
            data: ByteArray
        ) {
            pending -= data.size
            fonts++
        }
    }
}
