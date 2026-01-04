package com.xyoye.common_component.bilibili.mpd

import com.xyoye.data_component.data.bilibili.BilibiliDashData
import com.xyoye.data_component.data.bilibili.BilibiliDashMediaData
import com.xyoye.data_component.data.bilibili.BilibiliSegmentBaseData
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class BilibiliMpdGeneratorTest {
    @Test
    fun writeDashMpdIncludesBackupBaseUrls() {
        val dash =
            BilibiliDashData(
                duration = 100,
                minBufferTime = 1.5,
                video = emptyList(),
                audio = emptyList(),
            )
        val video =
            BilibiliDashMediaData(
                id = 64,
                baseUrl = "https://a.example.com/video.mp4",
                backupUrl = listOf("https://b.example.com/video.mp4"),
                bandwidth = 3_000_000,
                mimeType = "video/mp4",
                codecs = "avc1.640028",
                width = 1920,
                height = 1080,
                segmentBase =
                    BilibiliSegmentBaseData(
                        initialization = "0-999",
                        indexRange = "1000-1999",
                    ),
            )
        val audio =
            BilibiliDashMediaData(
                id = 30280,
                baseUrl = "https://a.example.com/audio.mp4",
                backupUrl = listOf("https://b.example.com/audio.mp4"),
                bandwidth = 128_000,
                mimeType = "audio/mp4",
                codecs = "mp4a.40.2",
                segmentBase =
                    BilibiliSegmentBaseData(
                        initialization = "0-99",
                        indexRange = "100-199",
                    ),
            )

        val mpdFile = File.createTempFile("bilibili_", ".mpd").apply { deleteOnExit() }
        BilibiliMpdGenerator.writeDashMpd(mpdFile, dash, video, audio)

        val mpd = mpdFile.readText()
        assertTrue(mpd.contains("<BaseURL"))
        assertTrue(mpd.contains(">https://a.example.com/video.mp4</BaseURL>"))
        assertTrue(mpd.contains(">https://b.example.com/video.mp4</BaseURL>"))
        assertTrue(mpd.contains(">https://a.example.com/audio.mp4</BaseURL>"))
        assertTrue(mpd.contains(">https://b.example.com/audio.mp4</BaseURL>"))
        assertTrue(mpd.contains("dvb:priority=\"1\""))
    }

    @Test
    fun writeDashMpdSkipsBlacklistedHost() {
        val dash =
            BilibiliDashData(
                duration = 100,
                minBufferTime = 1.5,
                video = emptyList(),
                audio = emptyList(),
            )
        val video =
            BilibiliDashMediaData(
                id = 64,
                baseUrl = "https://a.example.com/video.mp4",
                backupUrl = listOf("https://b.example.com/video.mp4"),
                bandwidth = 3_000_000,
                mimeType = "video/mp4",
                codecs = "avc1.640028",
            )

        val mpdFile = File.createTempFile("bilibili_", ".mpd").apply { deleteOnExit() }
        BilibiliMpdGenerator.writeDashMpd(
            outputFile = mpdFile,
            dash = dash,
            video = video,
            audio = null,
            cdnHostBlacklist = setOf("a.example.com"),
        )

        val mpd = mpdFile.readText()
        assertFalse(mpd.contains(">https://a.example.com/video.mp4</BaseURL>"))
        assertTrue(mpd.contains(">https://b.example.com/video.mp4</BaseURL>"))
    }
}
