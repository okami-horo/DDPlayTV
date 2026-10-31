package com.xyoye.local_component.ui.metadata

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.xyoye.common_component.base.BaseViewModel
import com.xyoye.common_component.metadata.BindingTicket
import com.xyoye.common_component.metadata.MediaCandidate
import com.xyoye.common_component.metadata.MediaNameParser
import com.xyoye.common_component.metadata.MetadataCoordinator
import com.xyoye.common_component.playback.handoff.PlaybackFileReference
import com.xyoye.data_component.entity.MetadataEpisode
import com.xyoye.data_component.entity.MetadataWork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MetadataMatchViewModel : BaseViewModel() {
    val status = MutableLiveData("正在读取文件…")
    val cards = MutableLiveData<List<MetadataCard>>(emptyList())
    val saved = MutableLiveData(false)
    val stage = MutableLiveData("search")
    var ticket: BindingTicket? = null
        private set
    var work: MetadataWork? = null
        private set
    var episodes: List<MetadataEpisode> = emptyList()
        private set
    var firstIndex = 0
    var lastIndex = 0
    var kind = "movie"
    var query = ""
    var busy = false
        private set
    private var job: Job? = null
    private var page = 1
    private var candidates = emptyList<MediaCandidate>()
    private var initialized = false
    private var directoryPreview = emptyList<com.xyoye.common_component.metadata.DirectoryMapping>()

    fun initialize(
        assetId: Long,
        libraryId: Int,
        reference: PlaybackFileReference
    ) {
        if (initialized) return
        initialized = true
        launch {
            ticket = if (assetId > 0) MetadataCoordinator.prepare(assetId) else MetadataCoordinator.prepare(libraryId, reference)
            val parsed = MediaNameParser.parse(requireNotNull(ticket).asset.fileName, requireNotNull(ticket).asset.parentPath)
            kind = parsed.kind ?: "movie"
            query = parsed.title
            status.postValue("${requireNotNull(ticket).asset.fileName}\n选择作品后可确认季与集；已有绑定会保留到提交成功。")
            searchInternal(false)
        }
    }

    fun search(
        text: String,
        selectedKind: String,
        more: Boolean = false
    ) {
        query = text.trim()
        kind = selectedKind
        launch { searchInternal(more) }
    }

    private suspend fun searchInternal(more: Boolean) {
        val target = ticket ?: return
        stage.postValue("search")
        if (!more) {
            page = 1
            candidates = emptyList()
        } else {
            page++
        }
        status.postValue("正在搜索…")
        val parsed = MediaNameParser.parse(target.asset.fileName, target.asset.parentPath)
        val result = MetadataCoordinator.candidates(parsed, kind, query, page)
        candidates = (candidates + result.first).distinctBy { it.kind to it.title.id }
        cards.postValue(
            candidates.map {
                MetadataCard(
                    "candidate:${it.kind}:${it.title.id}",
                    it.title.displayTitle(),
                    "${it.title.date().take(4)} · ${if (it.kind == "tv") "剧集" else "电影"} · TMDB ${it.title.id}",
                    MetadataCoordinator.repository.poster(it.title.posterPath)
                )
            } + if (result.second) listOf(MetadataCard("more", "加载下一页")) else emptyList()
        )
        status.postValue(
            if (candidates.isEmpty()) "没有搜索结果。可修改关键词或切换电影 / 剧集后重试。" else "${target.asset.fileName}\n${candidates.size} 个候选，请核对名称与年份。"
        )
    }

    fun select(card: MetadataCard) {
        if (card.key == "more") {
            search(query, kind, true)
            return
        }
        launch {
            when {
                card.key.startsWith("candidate:") -> {
                    val candidate = candidates.first { "candidate:${it.kind}:${it.title.id}" == card.key }
                    work = MetadataCoordinator.repository.details(candidate.kind, candidate.title.id)
                    episodes = emptyList()
                    if (candidate.kind == "tv") {
                        showSeasons()
                    } else {
                        stage.postValue("confirm")
                        cards.postValue(emptyList())
                        status.postValue("${work?.title} · ${work?.releaseDate?.take(4)}\n${work?.overview.orEmpty()}\n确认后将锁定此文件的资料关联。")
                    }
                }
                card.key.startsWith("season:") -> {
                    val season = card.key.substringAfter(':').toInt()
                    episodes = MetadataCoordinator.repository.season(requireNotNull(work).tmdbId, season)
                    firstIndex = 0
                    lastIndex = 0
                    stage.postValue("episodes")
                    cards.postValue(
                        episodes.map {
                            MetadataCard("episode:${it.episodeNumber}", "第 ${it.episodeNumber} 集 · ${it.title}", it.overview, it.still)
                        }
                    )
                    status.postValue("${work?.title} · 第 $season 季\n选择单集，或用下方起止集按钮的左右键设置合并集。")
                }
                card.key.startsWith("episode:") -> {
                    firstIndex = episodes.indexOfFirst { it.episodeNumber == card.key.substringAfter(':').toInt() }.coerceAtLeast(0)
                    lastIndex = firstIndex
                    stage.postValue("episodes")
                }
                card.key == "series-only" -> {
                    episodes = emptyList()
                    stage.postValue("confirm")
                    cards.postValue(emptyList())
                    status.postValue("仅关联《${work?.title}》，文件保持待确认状态，稍后可补充季集。")
                }
            }
        }
    }

    private suspend fun showSeasons() {
        stage.postValue("seasons")
        cards.postValue(
            MetadataCoordinator.repository.dao.seasons(requireNotNull(work).tmdbId).map {
                MetadataCard(
                    "season:${it.seasonNumber}",
                    if (it.seasonNumber ==
                        0
                    ) {
                        "特别篇（第 0 季）"
                    } else {
                        "第 ${it.seasonNumber} 季 · ${it.title}"
                    },
                    it.airDate,
                    it.poster
                )
            } + MetadataCard("series-only", "仅关联作品，稍后确认单集")
        )
        status.postValue("${work?.title}\n按 TMDB 季度选择；连续集数不会自动换算。")
    }

    fun back(): Boolean {
        if (stage.value == "search") return false
        launch {
            if (stage.value == "episodes") showSeasons() else searchInternal(false)
        }
        return true
    }

    fun step(
        first: Boolean,
        delta: Int
    ) {
        if (episodes.isEmpty()) return
        if (first) {
            firstIndex = (firstIndex + delta).coerceIn(0, episodes.lastIndex)
            lastIndex = lastIndex.coerceAtLeast(firstIndex)
        } else {
            lastIndex = (lastIndex + delta).coerceIn(firstIndex, episodes.lastIndex)
        }
        stage.value = "episodes"
    }

    fun confirm() {
        val target = ticket ?: return
        val selected = work ?: return
        if (busy) return
        busy = true
        job?.cancel()
        val range = if (episodes.isEmpty()) emptyList() else episodes.subList(firstIndex, lastIndex + 1).toList()
        status.value = "正在保存…"
        val preview = directoryPreview.toList().takeIf { stage.value == "batch" }
        MetadataCoordinator.submit({
            if (preview != null) {
                MetadataCoordinator.repository.applyDirectory(selected, preview)
            } else {
                MetadataCoordinator.confirm(target, selected, range)
            }
        }) { result ->
            busy = false
            if (result.isSuccess) saved.value = true else status.value = metadataError(requireNotNull(result.exceptionOrNull()))
        }
    }

    fun previewDirectory() {
        val target = ticket ?: return
        val selected = work ?: return
        val first = episodes.getOrNull(firstIndex) ?: return
        launch {
            directoryPreview = MetadataCoordinator.repository.previewDirectory(target, selected, first.seasonNumber, first.episodeNumber)
            cards.postValue(
                directoryPreview.map {
                    MetadataCard(
                        "preview:${it.ticket.asset.assetId}",
                        it.ticket.asset.fileName,
                        it.conflict
                            ?: "第 ${first.seasonNumber} 季 · ${it.episodes.joinToString { episode -> episode.episodeNumber.toString() }} 集"
                    )
                }
            )
            stage.postValue("batch")
            status.postValue(
                "当前已索引目录 ${directoryPreview.size} 个文件：${directoryPreview.count {
                    it.conflict == null
                }} 个将关联，${directoryPreview.count { it.conflict != null }} 个冲突跳过。\n请检查映射；确认前不会修改关联。"
            )
        }
    }

    fun ignore() = mutate { MetadataCoordinator.repository.ignore(it) }

    fun refresh() = mutate { MetadataCoordinator.refresh(it) }

    private fun mutate(action: suspend (BindingTicket) -> Unit) {
        val target = ticket ?: return
        if (busy) return
        busy = true
        job?.cancel()
        MetadataCoordinator.submit({ action(target) }) { result ->
            busy = false
            if (result.isSuccess) saved.value = true else status.value = metadataError(requireNotNull(result.exceptionOrNull()))
        }
    }

    private fun launch(block: suspend () -> Unit) {
        if (busy) return
        job?.cancel()
        job =
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    block()
                } catch (
                    cancelled: CancellationException
                ) {
                    throw cancelled
                } catch (error: Exception) {
                    status.postValue(metadataError(error))
                }
            }
    }
}

fun metadataError(error: Throwable): String =
    when (MetadataCoordinator.failureCategory(error)) {
        "NOT_CONFIGURED" -> "尚未配置 TMDB 访问凭证，文件浏览与播放不受影响。"
        "AUTHENTICATION" -> "TMDB 凭证或访问权限无效，请检查应用配置。"
        "NETWORK", "SERVER" -> "TMDB 暂不可访问，请稍后重试；已有资料仍保留。"
        "RATE_LIMIT" -> "TMDB 请求过于频繁，请稍后重试。"
        "NOT_FOUND" -> "TMDB 条目已不可用，原关联仍保留，可手动更正。"
        "RESULT_EXPIRED" -> "来源、文件或关联已变更，请返回文件并重新打开。"
        "PERMISSION" -> "无法读取此文件，请恢复来源访问权限后重试。"
        else -> "操作失败，未报告保存成功。请检查来源连接后重试。"
    }
