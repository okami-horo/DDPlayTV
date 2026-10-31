package com.xyoye.anime_component.ui.fragment.anime_recommend

import androidx.fragment.app.viewModels
import com.xyoye.anime_component.BR
import com.xyoye.anime_component.R
import com.xyoye.anime_component.databinding.FragmentAnimeRecommendBinding
import com.xyoye.anime_component.databinding.ItemAnimeCinemaBinding
import com.xyoye.anime_component.databinding.ItemAnimeSectionBinding
import com.xyoye.anime_component.ui.activities.anime_detail.AnimeDetailViewModel
import com.xyoye.anime_component.ui.adapter.AnimeCinemaAdapter
import com.xyoye.common_component.adapter.addItem
import com.xyoye.common_component.adapter.buildAdapter
import com.xyoye.common_component.adapter.setupDiffUtil
import com.xyoye.common_component.base.BaseFragment
import com.xyoye.common_component.extension.grid
import com.xyoye.common_component.extension.setData
import com.xyoye.data_component.data.AnimeData

class AnimeRecommendFragment : BaseFragment<AnimeRecommendFragmentViewModel, FragmentAnimeRecommendBinding>() {
    private val parentViewModel: AnimeDetailViewModel by viewModels(ownerProducer = { mAttachActivity })

    private data class Recommendation(
        val anime: AnimeData,
        val showStatus: Boolean
    )

    override fun initViewModel() =
        ViewModelInit(
            BR.viewModel,
            AnimeRecommendFragmentViewModel::class.java,
        )

    override fun getLayoutId() = R.layout.fragment_anime_recommend

    override fun initView() {
        initRv()

        initObserver()
    }

    private fun initRv() {
        val recommendations =
            buildAdapter {
                setupDiffUtil {
                    areItemsTheSame { old, new ->
                        if (old is Recommendation && new is Recommendation) {
                            old.anime.animeId == new.anime.animeId && old.showStatus == new.showStatus
                        } else {
                            old == new
                        }
                    }
                }
                addItem<String, ItemAnimeSectionBinding>(R.layout.item_anime_section) {
                    initView { title, _, _ -> itemBinding.title.text = title }
                }
                addItem<Recommendation, ItemAnimeCinemaBinding>(R.layout.item_anime_cinema) {
                    initView { data, _, _ ->
                        AnimeCinemaAdapter.bind(mAttachActivity, itemBinding, data.anime, data.showStatus)
                    }
                }
            }
        dataBinding.recommendRv.apply {
            layoutManager = grid(3) { position -> if (recommendations.items.getOrNull(position) is String) 3 else 1 }
            adapter = recommendations
        }
    }

    private fun initObserver() {
        parentViewModel.animeDetailLiveData.observe(viewLifecycleOwner) {
            viewModel.setBangumiData(it)
            dataBinding.recommendRv.setData(
                buildList {
                    if (it.relateds.isNotEmpty()) {
                        add("相关作品")
                        addAll(it.relateds.map { anime -> Recommendation(anime, false) })
                    }
                    if (it.similars.isNotEmpty()) {
                        add("更多推荐")
                        addAll(it.similars.map { anime -> Recommendation(anime, true) })
                    }
                }
            )
        }
    }
}
