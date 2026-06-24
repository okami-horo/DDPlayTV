package com.okamihoro.ddplaytv.ui.tv

import android.view.KeyEvent
import androidx.fragment.app.Fragment
import com.alibaba.android.arouter.launcher.ARouter
import com.okamihoro.ddplaytv.R
import com.okamihoro.ddplaytv.databinding.ActivityTvMainBinding
import com.okamihoro.ddplaytv.databinding.ItemTvMainNavBinding
import com.okamihoro.ddplaytv.ui.shell.BaseShellActivity
import com.okamihoro.ddplaytv.ui.shell.ShellFragmentSwitcher
import com.xyoye.common_component.adapter.addItem
import com.xyoye.common_component.adapter.buildAdapter
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.extension.findAndRemoveFragment
import com.xyoye.common_component.extension.setData
import com.xyoye.common_component.extension.vertical

class TvMainActivity : BaseShellActivity<ActivityTvMainBinding>() {
    companion object {
        private const val TAG_FRAGMENT_MEDIA = "tag_fragment_tv_media"
        private const val TAG_FRAGMENT_PERSONAL = "tag_fragment_tv_personal"
    }

    private val fragmentSwitcher by lazy {
        ShellFragmentSwitcher(
            fragmentManager = supportFragmentManager,
            containerId = R.id.fragment_container,
            fragmentProvider = ::getFragment,
        )
    }

    private val navItems = TvNavItem.entries.toList()
    private var selectedSection: TvNavItem = TvNavItem.MEDIA

    private val focusCoordinator by lazy(LazyThreadSafetyMode.NONE) {
        TvShellFocusCoordinator(
            navRecyclerView = dataBinding.tvNavRv,
            contentRoot = dataBinding.fragmentContainer,
            selectedNavIndexProvider = { navItems.indexOf(selectedSection) },
            currentFragmentProvider = { fragmentSwitcher.currentFragment() },
        )
    }

    override fun getLayoutId() = R.layout.activity_tv_main

    override fun initView() {
        supportFragmentManager.findAndRemoveFragment(
            TAG_FRAGMENT_MEDIA,
            TAG_FRAGMENT_PERSONAL,
        )

        initNav()

        // 默认显示媒体库页面
        switchSection(TvNavItem.MEDIA)
        dataBinding.tvNavRv.post {
            focusCoordinator.requestNavFocus()
        }

        initShell()
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent?
    ): Boolean {
        if (focusCoordinator.onContentKeyDown(keyCode)) {
            return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_SETTINGS -> {
                ARouter.getInstance().build(RouteTable.User.SettingApp).navigation()
                return true
            }

            KeyEvent.KEYCODE_BACK -> {
                if (handleBackExit()) {
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun initNav() {
        dataBinding.tvNavRv.apply {
            layoutManager = vertical()
            adapter =
                buildAdapter {
                    addItem<TvNavItem, ItemTvMainNavBinding>(R.layout.item_tv_main_nav) {
                        initView { data, position, _ ->
                            itemBinding.titleTv.setText(data.titleRes)
                            itemBinding.root.isSelected = data == selectedSection
                            itemBinding.root.setOnClickListener {
                                handleNavClick(data, position)
                            }
                        }
                    }
                }
            setData(navItems)
            setOnKeyListener { _, keyCode, event ->
                if (event?.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                focusCoordinator.onNavKeyDown(keyCode)
            }
        }
    }

    private fun handleNavClick(
        item: TvNavItem,
        position: Int
    ) {
        if (item.isSection) {
            focusCoordinator.saveContentFocus()
            val oldIndex = navItems.indexOf(selectedSection)
            selectedSection = item
            dataBinding.tvNavRv.adapter?.notifyItemChanged(oldIndex)
            dataBinding.tvNavRv.adapter?.notifyItemChanged(position)
            switchSection(item)
            return
        }

        when (item) {
            TvNavItem.SEARCH -> {
                ARouter.getInstance().build(RouteTable.Anime.Search).navigation()
            }

            TvNavItem.SETTINGS -> {
                ARouter.getInstance().build(RouteTable.User.SettingApp).navigation()
            }

            else -> {
            }
        }
    }

    private fun switchSection(section: TvNavItem) {
        when (section) {
            TvNavItem.MEDIA -> {
                switchFragment(
                    tag = TAG_FRAGMENT_MEDIA,
                    fragmentPath = RouteTable.Local.MediaFragment,
                )
            }

            TvNavItem.PERSONAL -> {
                switchFragment(
                    tag = TAG_FRAGMENT_PERSONAL,
                    fragmentPath = RouteTable.User.PersonalFragment,
                )
            }

            else -> {
            }
        }
    }

    private fun switchFragment(
        tag: String,
        fragmentPath: String
    ) {
        fragmentSwitcher.switchFragment(tag, fragmentPath)
    }

    private fun getFragment(path: String) =
        ARouter
            .getInstance()
            .build(path)
            .navigation() as Fragment?

    private enum class TvNavItem(
        val titleRes: Int,
        val isSection: Boolean
    ) {
        MEDIA(R.string.navigation_media, true),
        SEARCH(R.string.navigation_search, false),
        PERSONAL(R.string.navigation_personal, true),
        SETTINGS(R.string.navigation_setting, false)
    }
}
