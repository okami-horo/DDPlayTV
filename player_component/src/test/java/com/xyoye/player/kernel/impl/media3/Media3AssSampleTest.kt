package com.xyoye.player.kernel.impl.media3

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Media3AssSampleTest {
    @Test
    fun matroskaSample_preservesChineseOverridesAndCommasWithContainerDuration() {
        val raw = "17,2,Default,,1,2,3,,{\\pos(12,34)}简体，繁體,逗号\\N第二行"
        val (data, duration) = Media3AssSample.normalize("Dialogue: 0:00:00:00,0:00:02:50,$raw".toByteArray())
        assertArrayEquals(raw.toByteArray(), data)
        assertEquals(2_500_000L, duration)
    }

    @Test
    fun rawSample_isNotRewrittenAndNoDurationIsInvented() {
        val raw = "0,0,Default,,0,0,0,,Raw line".toByteArray()
        val (data, duration) = Media3AssSample.normalize(raw)
        assertArrayEquals(raw, data)
        assertNull(duration)
    }

    @Test
    fun codecPrivate_skipsOnlyTheMedia3WrapperAndRemovesTrailingNuls() {
        val wrapper = "Format: Start, End, ReadOrder, Layer, Style, Name, MarginL, MarginR, MarginV, Effect, Text".toByteArray()
        val headers =
            listOf(
                "[Script Info]\nTitle: 简体字幕\n",
                "[Script Info]\n[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour\n",
                "[Script Info]\nTitle: before\u0000after\n[Events]\nFormat: Layer, Start, End, Name, Style, MarginL, MarginR, MarginV, Effect, Text\n"
            )
        headers.forEach { text ->
            val header = text.toByteArray()
            assertArrayEquals(header, Media3AssSample.codecPrivate(listOf(header + ByteArray(3))))
            assertArrayEquals(header, Media3AssSample.codecPrivate(listOf(wrapper, header + ByteArray(3))))
        }
        assertNull(Media3AssSample.codecPrivate(emptyList()))
    }
}
