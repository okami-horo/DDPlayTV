package com.xyoye.common_component.bilibili

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BilibiliPlayurlPreferencesMapperTest {
    @Test
    fun webArchiveDashRequestsFullDashFormats() {
        val params =
            BilibiliPlayurlPreferencesMapper.archivePrimaryParams(
                preferences =
                    BilibiliPlaybackPreferences(
                        playMode = BilibiliPlayMode.AUTO,
                        preferredQualityQn = BilibiliQuality.QN_1080P.qn,
                    ),
                apiType = BilibiliApiType.WEB,
            )

        assertEquals(4048, params["fnval"])
        assertEquals(80, params["qn"])
        assertEquals("pc", params["platform"])
        assertFalse(params.containsKey("codecid"))
    }

    @Test
    fun webArchiveMp4KeepsCompatibilityMode() {
        val params =
            BilibiliPlayurlPreferencesMapper.archivePrimaryParams(
                preferences =
                    BilibiliPlaybackPreferences(
                        playMode = BilibiliPlayMode.MP4,
                        preferredQualityQn = BilibiliQuality.QN_1080P.qn,
                    ),
                apiType = BilibiliApiType.WEB,
            )

        assertEquals(1, params["fnval"])
        assertEquals(80, params["qn"])
        assertFalse(params.containsKey("fourk"))
    }

    @Test
    fun fourKStillRequiresExplicitFourkFlag() {
        val params =
            BilibiliPlayurlPreferencesMapper.archivePrimaryParams(
                preferences =
                    BilibiliPlaybackPreferences(
                        preferredQualityQn = BilibiliQuality.QN_4K.qn,
                        allow4k = true,
                    ),
                apiType = BilibiliApiType.WEB,
            )

        assertEquals(4048, params["fnval"])
        assertEquals(120, params["qn"])
        assertEquals(1, params["fourk"])
        assertFalse(params.containsKey("codecid"))
    }

    @Test
    fun explicitCodecStillAddsCodecid() {
        val params =
            BilibiliPlayurlPreferencesMapper.archivePrimaryParams(
                preferences =
                    BilibiliPlaybackPreferences(
                        preferredVideoCodec = BilibiliVideoCodec.AVC,
                    ),
                apiType = BilibiliApiType.WEB,
            )

        assertEquals(7, params["codecid"])
    }
}
