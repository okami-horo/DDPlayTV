package com.xyoye.common_component.utils.danmu.source

import com.xyoye.common_component.utils.danmu.source.impl.IOFileDanmuSource
import com.xyoye.common_component.utils.danmu.source.impl.NetworkDanmuSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.net.URLEncoder
import java.nio.file.Files

class DanmuSourceMetadataTest {
    @Test
    fun ioFileSourceReturnsNameWithoutExtensionAndSize() = runBlocking {
        val file = File(Files.createTempDirectory("danmu-source").toFile(), "Episode 01.mkv")
        file.writeText("video")

        val source = IOFileDanmuSource(file.absolutePath)

        assertEquals("Episode 01", source.fileName())
        assertEquals(file.length(), source.fileSize())
        assertNull(source.durationMs())
    }

    @Test
    fun networkSourceReturnsDecodedNameWithoutExtensionAndNoSize() = runBlocking {
        val encodedName = URLEncoder.encode("Episode 02.mkv", "UTF-8").replace("+", "%20")
        val source = NetworkDanmuSource("https://example.com/video/$encodedName?token=1")

        assertEquals("Episode 02", source.fileName())
        assertNull(source.fileSize())
    }
}
