package com.xyoye.user_component.ui.activities.setting_player

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.alibaba.android.arouter.facade.annotation.Route
import com.google.android.material.tabs.TabLayoutMediator
import com.xyoye.common_component.base.BaseActivity
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.extension.isTelevisionUiMode
import com.xyoye.common_component.focus.TvCategory
import com.xyoye.common_component.focus.TvCategoryPages
import com.xyoye.user_component.BR
import com.xyoye.user_component.R
import com.xyoye.user_component.databinding.ActivitySettingPlayerBinding
import com.xyoye.user_component.ui.fragment.DanmuSettingFragment
import com.xyoye.user_component.ui.fragment.PlayerSettingFragment
import com.xyoye.user_component.ui.fragment.SubtitleSettingFragment

@Route(path = RouteTable.User.SettingPlayer)
class SettingPlayerActivity : BaseActivity<SettingPlayerViewModel, ActivitySettingPlayerBinding>() {
    private val pageAdapter by lazy { SettingFragmentAdapter() }
    private var tvPages: TvCategoryPages? = null
    private var initialFocusPending = true

    override fun initViewModel() =
        ViewModelInit(
            BR.viewModel,
            SettingPlayerViewModel::class.java,
        )

    override fun getLayoutId() = R.layout.activity_setting_player

    override fun initView() {
        title = "播放器设置"

        if (isTelevisionUiMode()) {
            tvPages =
                TvCategoryPages(this, supportFragmentManager, dataBinding.tabLayout, dataBinding.viewpager).apply {
                    submit(
                        listOf("video", "danmu", "subtitle").mapIndexed { index, key ->
                            TvCategory(key, pageAdapter.getItemTitle(index)) { pageAdapter.createFragment(index) }
                        }
                    )
                }
        } else {
            dataBinding.viewpager.adapter = pageAdapter
            TabLayoutMediator(dataBinding.tabLayout, dataBinding.viewpager) { tab, position ->
                tab.text = pageAdapter.getItemTitle(position)
            }.attach()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || !initialFocusPending) return
        initialFocusPending = false
        val pages = tvPages ?: return
        if (!dataBinding.root.isInTouchMode && !pages.content.hasFocus()) pages.requestNavigationFocus()
    }

    inner class SettingFragmentAdapter : FragmentStateAdapter(this@SettingPlayerActivity) {
        private var titles = arrayOf("视频", "弹幕", "字幕")

        override fun getItemCount(): Int = titles.size

        override fun createFragment(position: Int): Fragment =
            when (position) {
                0 -> PlayerSettingFragment.newInstance()
                1 -> DanmuSettingFragment.newInstance()
                2 -> SubtitleSettingFragment.newInstance()
                else -> throw IllegalArgumentException()
            }

        fun getItemTitle(position: Int): String = titles.getOrNull(position).orEmpty()
    }
}
