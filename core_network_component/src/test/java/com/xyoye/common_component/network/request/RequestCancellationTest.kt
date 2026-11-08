package com.xyoye.common_component.network.request

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import java.net.SocketTimeoutException

class RequestCancellationTest {
    @Test fun getAndPostPropagateCancellation() =
        runBlocking {
            for (post in listOf(false, true)) {
                val cancellation = CancellationException("cancelled")
                try {
                    if (post) {
                        Request().json("{}").doPost<String> { throw cancellation }
                    } else {
                        Request().doGet<String> { throw cancellation }
                    }
                    fail("Cancellation must propagate")
                } catch (actual: CancellationException) {
                    assertEquals(cancellation.message, actual.message)
                }
            }
        }

    @Test fun ordinaryNetworkFailureRetainsItsCause() =
        runBlocking {
            val timeout = SocketTimeoutException("timeout")
            val failure = Request().doGet<String> { throw timeout }.exceptionOrNull() as NetworkException
            assertEquals(1003, failure.code)
            assertSame(timeout, failure.cause)
        }
}
