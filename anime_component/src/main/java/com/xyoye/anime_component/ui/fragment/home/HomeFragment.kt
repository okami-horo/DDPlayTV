package com.xyoye.anime_component.ui.fragment.home

import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.xyoye.anime_component.BR
import com.xyoye.anime_component.R
import com.xyoye.anime_component.databinding.FragmentHomeBinding
import com.xyoye.anime_component.ui.adapter.HomeBannerAdapter
import com.xyoye.anime_component.ui.fragment.home_page.HomePageFragment
import com.xyoye.common_component.base.BaseFragment
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.focus.DpadFocusOwner
import com.xyoye.common_component.focus.TvCategory
import com.xyoye.common_component.focus.TvCategoryPages
import com.xyoye.common_component.utils.dp2px
import com.youth.banner.config.BannerConfig
import com.youth.banner.config.IndicatorConfig
import com.youth.banner.indicator.CircleIndicator
import java.util.*

/**
 * Created by xyoye on 2020/7/28.
 */

@Route(path = RouteTable.Anime.HomeFragment)
class HomeFragment :
    BaseFragment<HomeFragmentViewModel, FragmentHomeBinding>(),
    DpadFocusOwner {
    private var tvPages: TvCategoryPages? = null

    override fun initViewModel() =
        ViewModelInit(
            BR.viewModel,
            HomeFragmentViewModel::class.java,
        )

    override fun getLayoutId() = R.layout.fragment_home

    override fun requestDefaultDpadFocus(): Boolean = tvPages?.requestNavigationFocus() ?: dataBinding.searchLl.requestFocus()

    override fun initView() {
        dataBinding.searchLl.setOnClickListener {
            ARouter
                .getInstance()
                .build(RouteTable.Anime.Search)
                .navigation()
        }
        dataBinding.seasonLl.setOnClickListener {
            ARouter
                .getInstance()
                .build(RouteTable.Anime.AnimeSeason)
                .navigation()
        }

        initViewModelObserve()
        viewModel.getBanners()
        viewModel.getWeeklyAnime()
    }

    override fun onDestroyView() {
        tvPages = null
        super.onDestroyView()
    }

    private fun initViewModelObserve() {
        viewModel.bannersLiveData.observe(viewLifecycleOwner) {
            dataBinding.banner.apply {
                setAdapter(HomeBannerAdapter(it.banners))
                indicator = CircleIndicator(mAttachActivity)
                setIndicatorGravity(IndicatorConfig.Direction.RIGHT)
                setIndicatorMargins(
                    IndicatorConfig.Margins(
                        0,
                        0,
                        BannerConfig.INDICATOR_MARGIN,
                        dp2px(12),
                    ),
                )
            }
        }

        viewModel.weeklyAnimeLiveData.observe(viewLifecycleOwner) {
            val pages =
                tvPages
                    ?: TvCategoryPages(this, childFragmentManager, dataBinding.tabLayout, dataBinding.viewpager).also { tvPages = it }
            pages.submit(
                it.mapIndexed { index, anime ->
                    TvCategory("weekday_$index", viewModel.tabTitles.getOrNull(index).orEmpty()) { HomePageFragment.newInstance(anime) }
                },
                Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1
            )
        }
    }
}
