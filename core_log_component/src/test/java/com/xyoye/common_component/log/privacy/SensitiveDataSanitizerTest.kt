package com.xyoye.common_component.log.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SensitiveDataSanitizerTest {
    @Test
    fun verificationJsonAndFormBodiesDoNotExposeChallengeOrAnswers() {
        val fields = listOf("v_voucher", "gt", "challenge", "validate", "seccode", "grisk_id", "token", "csrf")
        val json =
            fields.joinToString(prefix = "{\"code\":-352,\"data\":{", postfix = "}}") {
                "\"$it\":\"private-$it\""
            }
        val form = fields.joinToString("&") { "$it=private-$it" } + "&code=0"

        val safeJson = SensitiveDataSanitizer.sanitizeFreeText(json)
        val safeForm = SensitiveDataSanitizer.sanitizeFreeText(form)

        fields.forEach {
            assertFalse(it, safeJson.contains("private-$it"))
            assertFalse(it, safeForm.contains("private-$it"))
        }
        assertTrue(safeJson.contains("\"code\":-352"))
        assertTrue(safeForm.contains("code=0"))
    }

    @Test
    fun verificationContextAndParametersAreRedacted() {
        val fields = listOf("V_VOUCHER", "gt", "challenge", "validate", "seccode", "grisk_id", "token", "csrf")
        val context = fields.associateWith { "private-$it" } + ("code" to "-352")

        val safeContext = SensitiveDataSanitizer.sanitizeContext(context)
        val safeParams = SensitiveDataSanitizer.sanitizeParams(context)

        fields.forEach {
            assertEquals(it, "<redacted>", safeContext[it])
            assertEquals(it, "<redacted>", safeParams[it])
        }
        assertEquals("-352", safeContext["code"])
        assertEquals("-352", safeParams["code"])
    }

    @Test
    fun sanitizeUrlStripsQueryAndFragment() {
        val raw = "https://example.com/a/b.m3u8?token=abc&sign=xyz#frag"
        val safe = SensitiveDataSanitizer.sanitizeUrl(raw, SensitiveDataSanitizer.UrlMode.SAFE)
        assertEquals("https://example.com/a/b.m3u8", safe)
        assertFalse(safe.contains("?"))
        assertFalse(safe.contains("#"))
        assertFalse(safe.contains("abc"))
        assertFalse(safe.contains("xyz"))
    }

    @Test
    fun sanitizeMagnetKeepsOnlyBtihHash() {
        val raw = "magnet:?xt=urn:btih:0123456789ABCDEF&dn=name&tr=udp://tracker"
        val safe = SensitiveDataSanitizer.sanitizeMagnet(raw)
        assertTrue(safe.startsWith("magnet:btih="))
        assertTrue(safe.contains("0123456789ABCDEF"))
        assertFalse(safe.contains("dn="))
        assertFalse(safe.contains("tr="))
    }

    @Test
    fun sanitizeContextRedactsSensitiveKeys() {
        val ctx =
            mapOf(
                "Authorization" to "Bearer secret-token",
                "cookie" to "SESSDATA=abc; bili_jct=def",
                "path" to "/storage/emulated/0/Movies/private.mp4",
                "url" to "https://a.com/v.m3u8?access_token=aaa"
            )
        val safe = SensitiveDataSanitizer.sanitizeContext(ctx)
        assertEquals("<redacted>", safe.getValue("Authorization"))
        assertEquals("<redacted>", safe.getValue("cookie"))
        assertTrue(safe.getValue("path").startsWith("private.mp4#"))
        assertEquals("https://a.com/v.m3u8", safe.getValue("url"))
    }

    @Test
    fun sanitizeFreeTextRedactsBearerAndQueryTokens() {
        val raw = "Authorization: Bearer abcdef token=zzz https://h.com/p?q=1&access_token=aaa"
        val safe = SensitiveDataSanitizer.sanitizeFreeText(raw)
        assertFalse(safe.contains("abcdef"))
        assertFalse(safe.contains("zzz"))
        assertFalse(safe.contains("access_token=aaa"))
        assertTrue(safe.contains("<redacted>"))
        assertTrue(safe.contains("https://h.com/p"))
    }
}
