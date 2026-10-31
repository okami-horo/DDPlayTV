package com.xyoye.local_component.ui.metadata

import androidx.core.view.isVisible
import coil.load
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.xyoye.common_component.base.BaseFragment
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.extension.collectAtStarted
import com.xyoye.common_component.extension.horizontal
import com.xyoye.common_component.focus.DpadFocusOwner
import com.xyoye.common_component.playback.handoff.PlaybackNavigation
import com.xyoye.common_component.playback.handoff.PlaybackSubmissionResult
import com.xyoye.common_component.playback.userMessage
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.local_component.BR
import com.xyoye.local_component.R
import com.xyoye.local_component.databinding.FragmentCinemaBinding

@Route(path = RouteTable.Local.CinemaFragment)
class CinemaFragment :
    BaseFragment<MetadataLibraryViewModel, FragmentCinemaBinding>(),
    DpadFocusOwner {
    private var current = MetadataLibraryState()
    private var heroKey: String? = null
    private var heroWork: com.xyoye.data_component.entity.MetadataWork? = null
    private val history =
        MetadataCardAdapter(continueWatching = true, click = { card ->
            current.history.find { "history:${it.id}" == card.key }?.let(viewModel::play)
        })

    override fun initViewModel() = ViewModelInit(BR.viewModel, MetadataLibraryViewModel::class.java)

    override fun getLayoutId() = R.layout.fragment_cinema

    override fun initView() {
        dataBinding.cinemaScroll.isFocusable = false
        androidx.lifecycle
            .ViewModelProvider(
                this
            )[com.xyoye.local_component.ui.fragment.media.MediaViewModel::class.java]
            .initLocalStorage()
        dataBinding.continueWatching.apply {
            layoutManager = horizontal()
            addItemDecoration(
                com.xyoye.common_component.utils.view
                    .ItemDecorationSpace((4 * resources.displayMetrics.density).toInt(), 0)
            )
            adapter = history
        }
        dataBinding.filterAll.setOnClickListener {
            parentFragmentManager.setFragmentResult(RouteTable.App.CinemaNavigation, androidx.core.os.bundleOf("page" to "library"))
        }
        dataBinding.heroPlay.setOnClickListener {
            val entry = current.history.firstOrNull()
            if (entry != null) {
                viewModel.play(entry)
            } else {
                if (heroWork != null) {
                    heroWork?.let { openCard(MetadataCard("${it.mediaKind}:${it.tmdbId}", it.title)) }
                } else {
                    parentFragmentManager.setFragmentResult(RouteTable.App.CinemaNavigation, androidx.core.os.bundleOf("page" to "library"))
                }
            }
        }
        viewModel.state.collectAtStarted(viewLifecycleOwner) {
            current = it
            render()
        }
        viewModel.playEvents.collectAtStarted(viewLifecycleOwner) { result ->
            when (result) {
                is PlaybackSubmissionResult.Accepted ->
                    ARouter
                        .getInstance()
                        .build(
                            RouteTable.Player.Player
                        ).withString(PlaybackNavigation.REQUEST_ID, result.requestId)
                        .navigation(requireActivity())
                is PlaybackSubmissionResult.Rejected -> ToastCenter.showError(result.failure.userMessage())
            }
        }
    }

    private fun render() {
        val binding = bindingOrNull ?: return
        val latest = current.history.firstOrNull()
        val latestAsset =
            latest?.let { entry ->
                current.assets.find { it.libraryId == entry.storageId && it.uniqueKey == entry.uniqueKey && it.contentGeneration == 1L }
            }
        val latestBinding = current.bindings.find { it.assetId == latestAsset?.assetId }
        val featured =
            if (latest != null) {
                current.works.find { it.mediaKind == latestBinding?.mediaKind && it.tmdbId == latestBinding?.tmdbId }
            } else {
                current.works.firstOrNull { "${it.mediaKind}:${it.tmdbId}" == heroKey } ?: current.works.firstOrNull()
            }
        val key = featured?.let { "${it.mediaKind}:${it.tmdbId}" }
        if (heroWork != featured || binding.heroTitle.text.isEmpty()) {
            heroKey = key
            heroWork = featured
            binding.backdrop.load(featured?.backdrop)
            binding.heroOverview.text = featured?.overview?.ifBlank { "保留每个文件，发现每部作品。" } ?: "连接本地与网络来源，开始你的大屏时光。"
        }
        binding.heroTitle.text = featured?.title ?: latest?.videoName ?: "每一部，都值得好好看"
        binding.heroLabel.text = if (latest != null) "DDPLAYTV · 继续观看" else "DDPLAYTV · 你的私人放映室"
        binding.heroMeta.text =
            featured?.let { "${it.releaseDate.take(4)}  ·  ${if (it.mediaKind == "tv") "剧集 / 番剧" else "电影"}" } ?: "本地与远程媒体"
        binding.heroPlay.text =
            when {
                latest != null -> "继续播放"
                featured != null -> "查看作品"
                else -> "浏览媒体库"
            }
        // History aggregation is display-only. Unverifiable old generations stay as file history.
        val seen = mutableSetOf<String>()
        val recent =
            current.history
                .filter { entry ->
                    val asset =
                        current.assets.find {
                            it.libraryId == entry.storageId &&
                                it.uniqueKey == entry.uniqueKey &&
                                it.contentGeneration == 1L
                        }
                    val media = current.bindings.find { it.assetId == asset?.assetId }
                    seen.add(if (media?.tmdbId != null) "${media.mediaKind}:${media.tmdbId}" else "history:${entry.id}")
                }.take(8)
                .map { entry ->
                    val asset =
                        current.assets.find {
                            it.libraryId == entry.storageId &&
                                it.uniqueKey == entry.uniqueKey &&
                                it.contentGeneration == 1L
                        }
                    val media = current.bindings.find { it.assetId == asset?.assetId }
                    val work = current.works.find { it.mediaKind == media?.mediaKind && it.tmdbId == media?.tmdbId }
                    MetadataCard(
                        "history:${entry.id}",
                        work?.title ?: entry.videoName,
                        "继续观看 · ${if (entry.videoDuration > 0) entry.videoPosition * 100 / entry.videoDuration else 0}%",
                        work?.backdrop ?: work?.poster,
                        if (entry.videoDuration > 0) (entry.videoPosition * 100 / entry.videoDuration).toInt() else 0
                    )
                }
        history.submitList(recent)
        binding.continueWatching.isVisible = recent.isNotEmpty()
        binding.continueTitle.isVisible = true
        binding.empty.isVisible = recent.isEmpty()
        listOf(binding.heroPlay, binding.filterAll).forEach {
            it.nextFocusDownId = if (recent.isNotEmpty()) binding.continueWatching.id else it.id
        }
    }

    private fun openCard(card: MetadataCard) {
        if (card.key.startsWith("asset:")) {
            correctCard(card)
            return
        }
        ARouter
            .getInstance()
            .build(
                RouteTable.Local.MetadataDetail
            ).withString("kind", card.key.substringBefore(':'))
            .withLong("tmdbId", card.key.substringAfter(':').toLong())
            .navigation()
    }

    private fun correctCard(card: MetadataCard) {
        if (!card.key.startsWith("asset:")) return
        ARouter
            .getInstance()
            .build(RouteTable.Local.MetadataMatch)
            .withLong("assetId", card.key.substringAfter(':').toLong())
            .navigation()
    }

    override fun requestDefaultDpadFocus(): Boolean {
        val binding = bindingOrNull ?: return false
        return (if (binding.heroPlay.isVisible) binding.heroPlay else binding.filterAll).requestFocus()
    }

    override fun onDestroyView() {
        bindingOrNull?.continueWatching?.adapter = null
        super.onDestroyView()
    }
}
