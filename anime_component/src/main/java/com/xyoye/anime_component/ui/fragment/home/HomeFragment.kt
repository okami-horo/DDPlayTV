package com.xyoye.anime_component.ui.fragment.home

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.google.android.material.tabs.TabLayoutMediator
import com.xyoye.anime_component.BR
import com.xyoye.anime_component.R
import com.xyoye.anime_component.databinding.FragmentHomeBinding
import com.xyoye.anime_component.ui.adapter.HomeBannerAdapter
import com.xyoye.anime_component.ui.fragment.home_page.HomePageFragment
import com.xyoye.common_component.base.BaseFragment
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.utils.dp2px
import com.xyoye.data_component.data.BangumiAnimeData
import com.youth.banner.config.BannerConfig
import com.youth.banner.config.IndicatorConfig
import com.youth.banner.indicator.CircleIndicator
import java.util.*

/**
 * Created by xyoye on 2020/7/28.
 */

@Route(path = RouteTable.Anime.HomeFragment)
class HomeFragment : BaseFragment<HomeFragmentViewModel, FragmentHomeBinding>() {
    private var tabLayoutMediator: TabLayoutMediator? = null

    override fun initViewModel() =
        ViewModelInit(
            BR.viewModel,
            HomeFragmentViewModel::class.java,
        )

    override fun getLayoutId() = R.layout.fragment_home

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
        tabLayoutMediator?.detach()
        tabLayoutMediator = null
        super.onDestroyView()
    }

    private fun initViewModelObserve() {
        viewModel.bannersLiveData.observe(this) {
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

        viewModel.weeklyAnimeLiveData.observe(this) {
            val pageAdapter = HomePageAdapter(it)

            dataBinding.viewpager.apply {
                adapter = pageAdapter
                offscreenPageLimit = 2
            }

            tabLayoutMediator?.detach()
            tabLayoutMediator =
                TabLayoutMediator(dataBinding.tabLayout, dataBinding.viewpager) { tab, position ->
                    tab.text = pageAdapter.getItemTitle(position)
                }.also { mediator ->
                    mediator.attach()
                }

            dataBinding.viewpager.post {
                dataBinding.viewpager.currentItem = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1
            }
        }
    }

    inner class HomePageAdapter(
        private val weeklyAnimeData: Array<BangumiAnimeData>
    ) : FragmentStateAdapter(this@HomeFragment) {
        override fun getItemCount(): Int = weeklyAnimeData.size

        override fun createFragment(position: Int): Fragment = HomePageFragment.newInstance(weeklyAnimeData[position])

        fun getItemTitle(position: Int): String = viewModel.tabTitles.getOrNull(position).orEmpty()
    }
}
