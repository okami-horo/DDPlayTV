package com.xyoye.common_component.bilibili.repository

import com.xyoye.common_component.bilibili.error.BilibiliException
import com.xyoye.data_component.data.bilibili.BilibiliDashData
import com.xyoye.data_component.data.bilibili.BilibiliDashMediaData
import com.xyoye.data_component.data.bilibili.BilibiliDurlData
import com.xyoye.data_component.data.bilibili.BilibiliLiveCodecData
import com.xyoye.data_component.data.bilibili.BilibiliLiveFormatData
import com.xyoye.data_component.data.bilibili.BilibiliLivePlayUrlData
import com.xyoye.data_component.data.bilibili.BilibiliLivePlayurlInfoData
import com.xyoye.data_component.data.bilibili.BilibiliLiveRoomPlayurlPayloadData
import com.xyoye.data_component.data.bilibili.BilibiliLiveRoomPlayInfoData
import com.xyoye.data_component.data.bilibili.BilibiliLiveStreamData
import com.xyoye.data_component.data.bilibili.BilibiliLiveUrlInfoData
import com.xyoye.data_component.data.bilibili.BilibiliPlayurlData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.InvocationTargetException

@RunWith(RobolectricTestRunner::class)
class BilibiliRepositoryCoreInternalLogicTest {
    private val repository = BilibiliRepositoryCore(storageKey = "internal-logic-test")

    @Test
    fun hasPlayableStreamReturnsTrueWhenDashVideoExists() {
        val playurlData =
            BilibiliPlayurlData(
                dash = BilibiliDashData(video = listOf(BilibiliDashMediaData())),
                durl = emptyList(),
            )

        val result = invokeBoolean("hasPlayableStream", playurlData)

        assertTrue(result)
    }

    @Test
    fun hasPlayableStreamReturnsTrueWhenDurlContainsPlayableUrl() {
        val playurlData =
            BilibiliPlayurlData(
                dash = null,
                durl = listOf(BilibiliDurlData(url = "https://example.com/video.mp4")),
            )

        val result = invokeBoolean("hasPlayableStream", playurlData)

        assertTrue(result)
    }

    @Test
    fun hasPlayableStreamReturnsFalseWhenBothDashAndDurlAreEmpty() {
        val playurlData = BilibiliPlayurlData(dash = BilibiliDashData(video = emptyList()), durl = emptyList())

        val result = invokeBoolean("hasPlayableStream", playurlData)

        assertFalse(result)
    }

    @Test
    fun ensurePlayableStreamThrowsRiskExceptionWhenVoucherExists() {
        val playurlData = BilibiliPlayurlData(vVoucher = "risk-voucher")

        val throwable =
            assertThrows(InvocationTargetException::class.java) {
                invokeAny("ensurePlayableStream", playurlData)
            }

        val cause = throwable.cause as BilibiliException
        assertEquals(-352, cause.code)
        assertEquals("risk-voucher", cause.verificationVoucher)
        assertFalse(cause.toString().contains("risk-voucher"))
        assertFalse(cause.bilibiliMessage.orEmpty().contains("risk-voucher"))
    }

    @Test
    fun ensurePlayableStreamThrowsGenericExceptionWhenStreamIsEmpty() {
        val playurlData = BilibiliPlayurlData(vVoucher = null)

        val throwable =
            assertThrows(InvocationTargetException::class.java) {
                invokeAny("ensurePlayableStream", playurlData)
            }

        val cause = throwable.cause as BilibiliException
        assertEquals(-1, cause.code)
    }

    @Test
    fun shouldTryHtml5PlayurlOnlyForMp4RiskFailures() {
        val mp4BaseParams = mapOf<String, Any?>("fnval" to 1)
        val dashBaseParams = mapOf<String, Any?>("fnval" to 16)

        val riskResult = Result.failure<BilibiliPlayurlData>(BilibiliException.from(code = -352, message = "risk"))
        val otherFailure = Result.failure<BilibiliPlayurlData>(BilibiliException.from(code = -1, message = "other"))

        assertTrue(invokeBoolean("shouldTryHtml5Playurl", mp4BaseParams, riskResult))
        assertFalse(invokeBoolean("shouldTryHtml5Playurl", dashBaseParams, riskResult))
        assertFalse(invokeBoolean("shouldTryHtml5Playurl", mp4BaseParams, otherFailure))
    }

    @Test
    fun extractRefreshCsrfParsesExpectedToken() {
        val html = "<html><div id=\"1-name\">csrf-token-value</div></html>"

        val parsed = invokeAny("extractRefreshCsrf", html) as String?
        val missing = invokeAny("extractRefreshCsrf", "<html></html>") as String?

        assertEquals("csrf-token-value", parsed)
        assertEquals(null, missing)
    }

    @Test
    fun normalizeCookieDomainRemovesLeadingDotAndKeepsFallback() {
        val normalized = invokeAny("normalizeCookieDomain", ".bilibili.com") as String
        val blankNormalized = invokeAny("normalizeCookieDomain", "   ") as String

        assertEquals("bilibili.com", normalized)
        assertEquals("   ", blankNormalized)
    }

    @Test
    fun buildGaiaActivatePayloadContainsRequiredFields() {
        val payload = invokeAny("buildGaiaActivatePayload") as String
        val bfe9 = Regex("\"bfe9\":\"([^\"]+)\"").find(payload)?.groupValues?.getOrNull(1)

        assertTrue(payload.contains("\"3064\":1"))
        assertTrue(payload.contains("\"39c8\":\"333.1387.fp.risk\""))
        assertEquals(50, bfe9?.length)
    }

    @Test
    fun encryptCorrespondPathProducesHexAndIsRandomizedByOaep() {
        val first = invokeAny("encryptCorrespondPath", 1700000000000L) as String
        val second = invokeAny("encryptCorrespondPath", 1700000000000L) as String

        assertTrue(first.matches(Regex("^[0-9a-f]+$")))
        assertTrue(second.matches(Regex("^[0-9a-f]+$")))
        assertNotEquals(first, second)
    }

    @Test
    fun isRiskControlErrorMatchesKnownRiskCodes() {
        val risk = BilibiliException.from(code = -412, message = "risk")
        val notRisk = BilibiliException.from(code = -1, message = "other")

        assertTrue(invokeBoolean("isRiskControlError", risk))
        assertFalse(invokeBoolean("isRiskControlError", notRisk))
    }

    @Test
    fun buildLivePlayUrlDataSelectsRequestedQualityAndCombinesUrls() {
        val data =
            liveRoomPlayInfoData(
                BilibiliLiveCodecData(
                    currentQn = 400,
                    baseUrl = "/live/blue.flv",
                    urlInfo = listOf(BilibiliLiveUrlInfoData(host = "https://blue.example.com", extra = "?token=blue")),
                ),
                BilibiliLiveCodecData(
                    currentQn = 10000,
                    baseUrl = "/live/original.flv",
                    urlInfo =
                        listOf(
                            BilibiliLiveUrlInfoData(host = "https://origin-a.example.com", extra = "?token=origin"),
                            BilibiliLiveUrlInfoData(host = "https://origin-b.example.com/", extra = "?token=origin"),
                        ),
                ),
            )

        val result = invokeAny("buildLivePlayUrlData", data, 10000) as BilibiliLivePlayUrlData

        assertEquals("https://origin-a.example.com/live/original.flv?token=origin", result.durl.first().url)
        assertEquals(listOf("https://origin-b.example.com/live/original.flv?token=origin"), result.durl.first().backupUrl)
    }

    @Test
    fun buildLivePlayUrlDataFallsBackToClosestLowerQuality() {
        val data =
            liveRoomPlayInfoData(
                BilibiliLiveCodecData(
                    currentQn = 150,
                    baseUrl = "/live/high.flv",
                    urlInfo = listOf(BilibiliLiveUrlInfoData(host = "https://high.example.com", extra = "?token=high")),
                ),
                BilibiliLiveCodecData(
                    currentQn = 400,
                    baseUrl = "/live/blue.flv",
                    urlInfo = listOf(BilibiliLiveUrlInfoData(host = "https://blue.example.com", extra = "?token=blue")),
                ),
            )

        val result = invokeAny("buildLivePlayUrlData", data, 10000) as BilibiliLivePlayUrlData

        assertEquals("https://blue.example.com/live/blue.flv?token=blue", result.durl.first().url)
    }

    @Test
    fun buildLivePlayUrlDataThrowsWhenLiveRoomIsClosed() {
        val data = BilibiliLiveRoomPlayInfoData(liveStatus = 0)

        val throwable =
            assertThrows(InvocationTargetException::class.java) {
                invokeAny("buildLivePlayUrlData", data, 10000)
            }

        val cause = throwable.cause as BilibiliException
        assertEquals(-1, cause.code)
    }

    private fun liveRoomPlayInfoData(vararg codecs: BilibiliLiveCodecData): BilibiliLiveRoomPlayInfoData =
        BilibiliLiveRoomPlayInfoData(
            liveStatus = 1,
            playurlInfo =
                BilibiliLivePlayurlInfoData(
                    playurl =
                        BilibiliLiveRoomPlayurlPayloadData(
                            stream =
                                listOf(
                                    BilibiliLiveStreamData(
                                        format =
                                            listOf(
                                                BilibiliLiveFormatData(codec = codecs.toList()),
                                            ),
                                    ),
                                ),
                        ),
                ),
        )

    private fun invokeAny(
        methodName: String,
        vararg args: Any
    ): Any? {
        val paramTypes = args.map { toJavaType(it) }.toTypedArray()
        val invokeArgs = args.map { toInvokeArg(it) }.toTypedArray()
        val method = repository::class.java.getDeclaredMethod(methodName, *paramTypes)
        method.isAccessible = true
        return method.invoke(repository, *invokeArgs)
    }

    private fun invokeBoolean(
        methodName: String,
        vararg args: Any
    ): Boolean = invokeAny(methodName, *args) as Boolean

    private fun toInvokeArg(value: Any): Any =
        when (value) {
            is Result<*> -> {
                val unbox = value.javaClass.getDeclaredMethod("unbox-impl")
                unbox.isAccessible = true
                requireNotNull(unbox.invoke(value)) { "Unboxed Result value is null" }
            }
            else -> value
        }

    private fun toJavaType(value: Any): Class<*> =
        when (value) {
            is Int -> Int::class.javaPrimitiveType!!
            is Long -> Long::class.javaPrimitiveType!!
            is Boolean -> Boolean::class.javaPrimitiveType!!
            is Map<*, *> -> Map::class.java
            is Result<*> -> Any::class.java
            else -> value.javaClass
        }
}
