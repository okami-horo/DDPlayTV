package com.xyoye.anime_component.ui.activities.anime_detail

import androidx.activity.addCallback
import com.alibaba.android.arouter.facade.annotation.Autowired
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.xyoye.anime_component.BR
import com.xyoye.anime_component.R
import com.xyoye.anime_component.databinding.ActivityAnimeDetailBinding
import com.xyoye.anime_component.ui.fragment.anime_episode.AnimeEpisodeFragment
import com.xyoye.anime_component.ui.fragment.anime_intro.AnimeIntroFragment
import com.xyoye.anime_component.ui.fragment.anime_recommend.AnimeRecommendFragment
import com.xyoye.anime_component.utils.loadAnimeCover
import com.xyoye.common_component.base.BaseActivity
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.extension.loadImage
import com.xyoye.common_component.extension.setTextColorRes
import com.xyoye.common_component.focus.TvCategory
import com.xyoye.common_component.focus.TvCategoryPages
import com.xyoye.common_component.utils.StatusBarStyle
import com.xyoye.data_component.bean.AnimeArgument
import com.xyoye.data_component.enums.AnimeDetailTab

@Route(path = RouteTable.Anime.AnimeDetail)
class AnimeDetailActivity : BaseActivity<AnimeDetailViewModel, ActivityAnimeDetailBinding>() {
    @Autowired
    @JvmField
    var animeArgument: AnimeArgument = AnimeArgument()

    // 默认的tab列表
    private val defaultTabs = AnimeDetailTab.values()

    // 返回事件拦截器
    private var backPressInterceptor: (() -> Boolean)? = null
    private var tvPages: TvCategoryPages? = null

    override fun initViewModel() =
        ViewModelInit(
            BR.viewModel,
            AnimeDetailViewModel::class.java,
        )

    override fun getLayoutId() = R.layout.activity_anime_detail

    override fun initStatusBar() {
        StatusBarStyle.applyTransparent(
            activity = this,
            darkFont = false,
        )
    }

    override fun initView() {
        ARouter.getInstance().inject(this)

        title = "番剧详情"

        initViewPager(defaultTabs)
        initListener()
        onBackPressedDispatcher.addCallback(this) {
            if (backPressInterceptor?.invoke() != true) finish()
        }

        updateUIbyAnime(animeArgument.title, animeArgument.imageUrl)

        viewModel.animeIdField.set(animeArgument.id.toString())
        viewModel.animeTitleField.set(animeArgument.title)
        viewModel.getAnimeDetail(animeArgument.id.toString())
    }

    private fun initViewPager(tabs: Array<AnimeDetailTab>) {
        val pages =
            tvPages ?: TvCategoryPages(this, supportFragmentManager, dataBinding.tabLayout, dataBinding.viewpager).also { tvPages = it }
        pages.submit(
            tabs.map { tab ->
                TvCategory(tab.name, tab.title) {
                    when (tab) {
                        AnimeDetailTab.INFO -> AnimeIntroFragment()
                        AnimeDetailTab.EPISODES -> AnimeEpisodeFragment()
                        AnimeDetailTab.RECOMMEND -> AnimeRecommendFragment()
                    }
                }
            }
        )
    }

    private fun initListener() {
        viewModel.followLiveData.observe(this) { followed ->
            if (followed) {
                dataBinding.followTv.text = "已追番"
                dataBinding.followTv.isSelected = true
                dataBinding.followTv.setTextColorRes(R.color.cinema_text)
            } else {
                dataBinding.followTv.text = "追番"
                dataBinding.followTv.isSelected = false
                dataBinding.followTv.setTextColorRes(R.color.cinema_text)
            }
        }

        viewModel.animeDetailLiveData.observe(this) {
            if (it.animeTitle != animeArgument.title || it.imageUrl != animeArgument.imageUrl) {
                updateUIbyAnime(it.animeTitle.orEmpty(), it.imageUrl.orEmpty())
            }

            // 如果没有相关推荐和相似番剧，移除推荐tab
            val dynamicTabs = defaultTabs.toMutableList()
            if (it.relateds.isEmpty() && it.similars.isEmpty()) {
                dynamicTabs.remove(AnimeDetailTab.RECOMMEND)
                initViewPager(dynamicTabs.toTypedArray())
            }
        }
    }

    /**
     * 根据番剧信息更新UI
     */
    private fun updateUIbyAnime(
        title: String,
        image: String
    ) {
        dataBinding.titleTv.text = title
        dataBinding.backgroundCoverIv.loadImage(image)
        dataBinding.coverIv.loadAnimeCover(image)
    }

    /**
     * 刷新番剧详情
     */
    fun refreshAnimeDetail() {
        viewModel.getAnimeDetail(animeArgument.id.toString())
    }

    /**
     * 注册返回事件拦截器
     */
    fun registerBackPressInterceptor(interceptor: () -> Boolean) {
        backPressInterceptor = interceptor
    }

    /**
     * 取消返回事件拦截器
     */
    fun unregisterBackPressInterceptor() {
        backPressInterceptor = null
    }
}
