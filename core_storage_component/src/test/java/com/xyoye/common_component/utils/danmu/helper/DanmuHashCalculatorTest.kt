package com.xyoye.common_component.utils.danmu.helper

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class DanmuHashCalculatorTest {
    @Test
    fun calculateIsSafeAcrossConcurrentCalls() {
        val payloads =
            List(32) { index ->
                ByteArray(1024 * 64) { byteIndex -> (index + byteIndex).toByte() }
            }
        val expected = payloads.map { md5(it) }
        val executor = Executors.newFixedThreadPool(8)

        val actual =
            executor.invokeAll(
                payloads.map { payload ->
                    Callable { DanmuHashCalculator.calculate(ByteArrayInputStream(payload)) }
                },
            ).map { it.get() }

        executor.shutdownNow()
        assertEquals(expected, actual)
    }

    private fun md5(bytes: ByteArray): String =
        MessageDigest
            .getInstance("MD5")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
