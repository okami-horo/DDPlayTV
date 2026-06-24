package com.xyoye.common_component.utils.danmu.source

/**
 * Created by xyoye on 2024/1/14.
 */

interface DanmuSource {
    suspend fun hash(): String?

    suspend fun fileName(): String?

    suspend fun fileSize(): Long?

    suspend fun durationMs(): Long? = null
}
