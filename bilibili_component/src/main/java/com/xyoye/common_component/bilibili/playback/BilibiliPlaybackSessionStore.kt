package com.xyoye.common_component.bilibili.playback

import java.util.concurrent.ConcurrentHashMap

object BilibiliPlaybackSessionStore {
    data class Key(
        val storageId: Int,
        val uniqueKey: String,
    )

    private val sessions = ConcurrentHashMap<Key, BilibiliPlaybackSession>()

    fun put(session: BilibiliPlaybackSession) {
        sessions[Key(session.storageId, session.uniqueKey)] = session
    }

    fun get(
        storageId: Int,
        uniqueKey: String,
    ): BilibiliPlaybackSession? = sessions[Key(storageId, uniqueKey)]

    fun remove(
        storageId: Int,
        uniqueKey: String,
    ) {
        sessions.remove(Key(storageId, uniqueKey))
        BilibiliPlaybackHeartbeat.clear(storageId, uniqueKey)
    }

    fun clearStorage(storageId: Int) {
        val iterator = sessions.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key.storageId == storageId) {
                iterator.remove()
            }
        }
        BilibiliPlaybackHeartbeat.clearStorage(storageId)
    }

    fun clear() {
        sessions.clear()
        BilibiliPlaybackHeartbeat.clearAll()
    }
}
