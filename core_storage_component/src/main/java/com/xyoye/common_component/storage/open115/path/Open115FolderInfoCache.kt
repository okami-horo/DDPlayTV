package com.xyoye.common_component.storage.open115.path

import com.xyoye.common_component.network.repository.Open115Repository
import com.xyoye.common_component.storage.impl.Open115Storage
import java.util.concurrent.ConcurrentHashMap

internal class Open115FolderInfoCache(
    private val repository: Open115Repository
) {
    private val parentIdCache = ConcurrentHashMap<String, String>()
    private val breadcrumbIdCache = ConcurrentHashMap<String, List<String>>()

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
        if (folder.isBlank() || folder == Open115Storage.ROOT_CID) {
            return emptyList()
        }

        breadcrumbIdCache[folder]?.let { return it }

        val visited = HashSet<String>()
        val chain = mutableListOf<String>()
        var current = folder

        while (current != Open115Storage.ROOT_CID && visited.add(current)) {
            chain.add(current)

            val parent =
                parentIdCache[current]
                    ?: runCatching { fetchParentId(current) }
                        .getOrDefault(Open115Storage.ROOT_CID)
                        .also { parentIdCache.putIfAbsent(current, it) }
            current = parent
        }

        val breadcrumb = chain.asReversed()
        for (index in breadcrumb.indices) {
            val id = breadcrumb[index]
            breadcrumbIdCache.putIfAbsent(id, breadcrumb.subList(0, index + 1).toList())
        }

        return breadcrumbIdCache[folder].orEmpty()
    }

    private suspend fun fetchParentId(folderId: String): String {
        val response = repository.folderGetInfo(fileId = folderId).getOrThrow()
        return response.data?.parentId?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: Open115Storage.ROOT_CID
    }
}

