package com.xyoye.common_component.storage.file.helper

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoroutineAwaiterTest {
    @Test
    fun awaitCondition_returnsTrue_whenConditionMet() =
        runBlocking {
            var ready = false
            launch {
                delay(20)
                ready = true
            }

            val result =
                awaitCondition(
                    timeoutMs = 500,
                    checkIntervalMs = 5,
                ) {
                    ready
                }

            assertTrue(result)
        }

    @Test
    fun awaitCondition_returnsFalse_onTimeout() =
        runBlocking {
            val result =
                awaitCondition(
                    timeoutMs = 50,
                    checkIntervalMs = 5,
                ) {
                    false
                }

            assertFalse(result)
        }
}
