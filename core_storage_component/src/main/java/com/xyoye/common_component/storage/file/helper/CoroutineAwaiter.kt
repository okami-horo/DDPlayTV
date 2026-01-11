package com.xyoye.common_component.storage.file.helper

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/**
 * Await until [condition] becomes true.
 *
 * Threading model:
 * - This is a suspend function and MUST be called from a coroutine.
 * - It is safe to call from Main dispatcher (it uses [delay] instead of busy loop).
 */
internal suspend fun awaitCondition(
    timeoutMs: Long,
    checkIntervalMs: Long = 10L,
    condition: () -> Boolean
): Boolean {
    if (condition()) return true
    val timeout = timeoutMs.coerceAtLeast(0L)
    if (timeout == 0L) return condition()

    val interval = checkIntervalMs.coerceAtLeast(1L)
    return try {
        withTimeout(timeout) {
            while (!condition()) {
                delay(interval)
            }
            true
        }
    } catch (_: TimeoutCancellationException) {
        false
    }
}
