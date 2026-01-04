package com.xyoye.common_component.bilibili.cdn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BilibiliCdnStrategyTest {
    @Test
    fun resolveUrlsDeduplicatesAndFiltersBlank() {
        val resolved =
            BilibiliCdnStrategy.resolveUrls(
                baseUrl = "https://a.example.com/v",
                backupUrls = listOf("", "https://a.example.com/v", "https://b.example.com/v"),
            )

        assertEquals(listOf("https://a.example.com/v", "https://b.example.com/v"), resolved)
    }

    @Test
    fun resolveUrlsSortsByHeuristics() {
        val base = "http://upos-sz-mirrorcos-302.bilivideo.com/upgcxcode/foo?os=cos"
        val ali = "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/foo?os=ali"
        val cos = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/foo?os=cos"
        val mcdn = "https://upos-sz-mirrorcoso1.bilivideo.com/upgcxcode/foo?os=mcdn"

        val resolved =
            BilibiliCdnStrategy.resolveUrls(
                baseUrl = base,
                backupUrls = listOf(ali, mcdn, cos),
            )

        assertEquals(ali, resolved[0])
        assertEquals(cos, resolved[1])
        assertEquals(base, resolved[2])
        assertEquals(mcdn, resolved.last())
    }

    @Test
    fun resolveUrlsAppliesHostOverrideFirstAndKeepsOriginals() {
        val base = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/foo?os=cos"
        val backup = "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/foo?os=ali"

        val resolved =
            BilibiliCdnStrategy.resolveUrls(
                baseUrl = base,
                backupUrls = listOf(backup),
                options = BilibiliCdnStrategy.Options(hostOverride = "upos-sz-mirrorhw.bilivideo.com"),
            )

        assertEquals("https://upos-sz-mirrorhw.bilivideo.com/upgcxcode/foo?os=cos", resolved[0])
        assertEquals("https://upos-sz-mirrorhw.bilivideo.com/upgcxcode/foo?os=ali", resolved[1])
        assertTrue(resolved.contains(base))
        assertTrue(resolved.contains(backup))
    }
}
