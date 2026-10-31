package com.xyoye.anime_component.ui.activities

import com.xyoye.data_component.data.AnimeData

data class AnimeCollectionState(
    val items: List<AnimeData> = emptyList(),
    val keyword: String = "",
    val loading: Boolean = false,
    val error: String? = null
) {
    val visibleItems: List<AnimeData>
        get() = if (keyword.isEmpty()) items else items.filter { it.animeTitle?.contains(keyword, ignoreCase = true) == true }

    val summary: String
        get() =
            when {
                loading -> "正在加载…"
                error != null -> if (items.isEmpty()) "加载失败，请选择刷新重试。" else "刷新失败，已有内容已保留。"
                visibleItems.isEmpty() -> "0 部作品"
                else -> "${visibleItems.size} 部作品 · 确认查看详情"
            }

    val emptyMessage: String
        get() =
            when {
                loading -> "正在获取列表，请稍候。"
                error != null -> "加载失败\n$error\n可选择上方「刷新」重试。"
                keyword.isNotEmpty() -> "没有匹配的作品\n可清空搜索或调整关键词。"
                else -> "暂无内容\n可刷新列表或返回。"
            }

    val emptyTitle: String get() = emptyMessage.substringBefore('\n')
    val emptyDescription: String get() = emptyMessage.substringAfter('\n', "")
}
