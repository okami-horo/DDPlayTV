package com.xyoye.common_component.bilibili.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job

/** Tracks only this session/addon's operations without cancelling the caller's scope. */
internal class BilibiliPlaybackWork {
    private val jobs = mutableSetOf<Job>()
    private var closed = false

    suspend fun <T> run(block: suspend () -> T): T =
        coroutineScope {
            val job = coroutineContext.job
            synchronized(jobs) {
                if (closed) throw CancellationException("Playback owner retired")
                jobs.add(job)
            }
            try {
                val result = block()
                coroutineContext.ensureActive()
                result
            } finally {
                synchronized(jobs) { jobs.remove(job) }
            }
        }

    fun close() {
        val pending =
            synchronized(jobs) {
                closed = true
                jobs.toList().also { jobs.clear() }
            }
        pending.forEach { it.cancel() }
    }
}
