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
    private var filter = "all"
    private var current = MetadataLibraryState()
    private var heroKey: String? = null
    private var heroWork: com.xyoye.data_component.entity.MetadataWork? = null
    private val works = MetadataCardAdapter(posters = true, click = ::openCard, longClick = ::correctCard)
    private val history =
        MetadataCardAdapter(compact = true, click = { card ->
            current.history.find { "history:${it.id}" == card.key }?.let(viewModel::play)
        })

    override fun initViewModel() = ViewModelInit(BR.viewModel, MetadataLibraryViewModel::class.java)

    override fun getLayoutId() = R.layout.fragment_cinema

    override fun initView() {
        androidx.lifecycle
            .ViewModelProvider(
                this
            )[com.xyoye.local_component.ui.fragment.media.MediaViewModel::class.java]
            .initLocalStorage()
        dataBinding.works.apply {
            layoutManager = horizontal()
            adapter = works
            setFocusOutAllowed(true, true)
        }
        dataBinding.continueWatching.apply {
            layoutManager = horizontal()
            adapter = history
        }
        dataBinding.filterAll.setOnClickListener { ARouter.getInstance().build(RouteTable.Local.MetadataLibrary).navigation() }
        dataBinding.filterMovie.setOnClickListener {
            filter = "movie"
            render()
        }
        dataBinding.filterTv.setOnClickListener {
            filter = "tv"
            render()
        }
        dataBinding.filterUnmatched.setOnClickListener {
            filter = "unmatched"
            render()
        }
        dataBinding.tasks.setOnClickListener { ARouter.getInstance().build(RouteTable.User.MetadataSettings).navigation() }
        dataBinding.browseFiles.setOnClickListener {
            com.xyoye.common_component.application.DanDanPlay.permission.storage.request(this) {
                onGranted {
                    ARouter
                        .getInstance()
                        .build(
                            RouteTable.Stream.StorageFile
                        ).withParcelable("storageLibrary", com.xyoye.data_component.entity.MediaLibraryEntity.LOCAL)
                        .navigation()
                }
                onDenied { ToastCenter.showError("获取文件读取权限失败") }
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
        val cards =
            if (filter == "unmatched") {
                current.assets.filter { asset -> current.bindings.find { it.assetId == asset.assetId }?.state != "MATCHED" }.map {
                    MetadataCard("asset:${it.assetId}", it.fileName, if (it.available) "识别 / 更正资料" else "文件暂不可用")
                }
            } else {
                current.works.filter { filter == "all" || it.mediaKind == filter }.map {
                    MetadataCard(
                        "${it.mediaKind}:${it.tmdbId}",
                        it.title,
                        "${it.releaseDate.take(4)} · ${if (it.mediaKind == "tv") "剧集" else "电影"}",
                        it.poster
                    )
                }
            }
        works.submitList(cards)
        binding.empty.text = if (filter == "unmatched") "没有待识别文件。可从来源目录手动扫描入库。" else "还没有已入库作品。先浏览来源文件，再选择“识别 / 更正资料”。"
        binding.empty.isVisible = cards.isEmpty()
        binding.works.isVisible = cards.isNotEmpty()
        val featured = current.works.firstOrNull { "${it.mediaKind}:${it.tmdbId}" == heroKey } ?: current.works.firstOrNull()
        val key = featured?.let { "${it.mediaKind}:${it.tmdbId}" }
        if (heroWork != featured || binding.heroTitle.text.isEmpty()) {
            heroKey = key
            heroWork = featured
            binding.backdrop.load(featured?.backdrop)
            binding.heroTitle.text = featured?.title ?: "你的私人放映室"
            binding.heroOverview.text = featured?.overview?.ifBlank { "保留每个文件，发现每部作品。" } ?: "本地与网络来源，汇集于此。资料未配置时仍可浏览和播放文件。"
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
                        work?.poster
                    )
                }
        history.submitList(recent)
        binding.continueWatching.isVisible = recent.isNotEmpty()
        binding.continueTitle.isVisible = recent.isNotEmpty()
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

    override fun requestDefaultDpadFocus(): Boolean = bindingOrNull?.filterAll?.requestFocus() == true

    override fun onDestroyView() {
        bindingOrNull?.works?.adapter = null
        bindingOrNull?.continueWatching?.adapter = null
        super.onDestroyView()
    }
}
