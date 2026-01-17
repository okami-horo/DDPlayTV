package com.xyoye.common_component.bilibili.playback

import java.util.concurrent.ConcurrentHashMap

object BilibiliLivePlaybackSessionStore {
    data class Key(
        val storageId: Int,
        val uniqueKey: String
    )

    private val sessions = ConcurrentHashMap<Key, BilibiliLivePlaybackSession>()

    fun put(session: BilibiliLivePlaybackSession) {
        sessions[Key(session.storageId, session.uniqueKey)] = session
    }

    fun get(
        storageId: Int,
        uniqueKey: String
    ): BilibiliLivePlaybackSession? = sessions[Key(storageId, uniqueKey)]

    fun remove(
        storageId: Int,
        uniqueKey: String
    ) {
        sessions.remove(Key(storageId, uniqueKey))
    }

    fun clearStorage(storageId: Int) {
        val iterator = sessions.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key.storageId == storageId) {
                iterator.remove()
            }
        }
    }

    fun clear() {
        sessions.clear()
    }
}

