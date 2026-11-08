package com.xyoye.common_component.storage.open115.path

import com.xyoye.common_component.network.repository.Open115Repository
import com.xyoye.common_component.storage.impl.Open115Storage
import java.util.concurrent.ConcurrentHashMap

internal class Open115FolderInfoCache(
    private val repository: Open115Repository
) {
    private val parentIdCache = ConcurrentHashMap<String, String>()

    fun cacheParent(
        folderId: String,
        parentId: String
    ) {
        val folder = folderId.trim()
        val parent = parentId.trim()
        if (folder.isBlank() ||
            parent.isBlank() ||
            folder == Open115Storage.ROOT_CID ||
            folder == parent
        ) {
            return
        }
        parentIdCache[folder] = parent
    }

    suspend fun resolveBreadcrumbIds(folderId: String): List<String> {
        val folder = folderId.trim()
        require(folder.isNotBlank()) { "Missing 115 folder id" }
        if (folder == Open115Storage.ROOT_CID) {
            return emptyList()
        }

        val visited = HashSet<String>()
        val chain = mutableListOf<String>()
        var current = folder

        while (current != Open115Storage.ROOT_CID) {
            check(visited.add(current)) { "Cyclic 115 folder parent chain" }
            chain.add(current)

            val parent =
                parentIdCache[current]
                    ?: fetchParentId(current)
                        .also { parentIdCache.putIfAbsent(current, it) }
            current = parent
        }

        return chain.asReversed()
    }

    private suspend fun fetchParentId(folderId: String): String {
        val response = repository.folderGetInfo(fileId = folderId).getOrThrow()
        return response.data
            ?.parentId
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Missing parent in 115 folder response")
    }
}
