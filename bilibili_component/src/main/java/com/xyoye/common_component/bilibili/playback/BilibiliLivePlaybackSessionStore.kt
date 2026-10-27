package com.xyoye.common_component.bilibili.playback

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object BilibiliLivePlaybackSessionStore {
    data class Key(
        val storageId: Int,
        val uniqueKey: String,
        val token: String
    )

    private val sessions = ConcurrentHashMap<Key, BilibiliLivePlaybackSession>()

    fun put(session: BilibiliLivePlaybackSession): String {
        val token = UUID.randomUUID().toString()
        sessions[Key(session.storageId, session.uniqueKey, token)] = session
        return token
    }

    fun get(
        storageId: Int,
        uniqueKey: String,
        token: String
    ): BilibiliLivePlaybackSession? = sessions[Key(storageId, uniqueKey, token)]

    fun remove(
        storageId: Int,
        uniqueKey: String,
        token: String
    ) {
        val session = sessions.remove(Key(storageId, uniqueKey, token))

        session?.close()
    }

    fun clearStorage(storageId: Int) {
        sessions.keys.filter { it.storageId == storageId }.forEach {
            remove(it.storageId, it.uniqueKey, it.token)
        }
    }

    fun clear() {
        sessions.keys.forEach { remove(it.storageId, it.uniqueKey, it.token) }
    }
}
