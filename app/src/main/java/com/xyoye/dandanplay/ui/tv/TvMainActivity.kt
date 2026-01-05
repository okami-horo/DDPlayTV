package com.xyoye.dandanplay.ui.tv

import android.view.KeyEvent
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentTransaction
import com.alibaba.android.arouter.launcher.ARouter
import com.xyoye.common_component.adapter.addItem
import com.xyoye.common_component.adapter.buildAdapter
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.extension.findAndRemoveFragment
import com.xyoye.common_component.extension.hideFragment
import com.xyoye.common_component.extension.requestIndexChildFocus
import com.xyoye.common_component.extension.setData
import com.xyoye.common_component.extension.showFragment
import com.xyoye.common_component.extension.vertical
import com.xyoye.dandanplay.R
import com.xyoye.dandanplay.databinding.ActivityTvMainBinding
import com.xyoye.dandanplay.databinding.ItemTvMainNavBinding
import com.xyoye.dandanplay.ui.shell.BaseShellActivity

class TvMainActivity :
    BaseShellActivity<ActivityTvMainBinding>() {
    companion object {
        private const val TAG_FRAGMENT_MEDIA = "tag_fragment_tv_media"
        private const val TAG_FRAGMENT_PERSONAL = "tag_fragment_tv_personal"
    }

    private lateinit var previousFragment: Fragment
    private var fragmentTag = ""

    private val navItems = TvNavItem.entries.toList()
    private var selectedSection: TvNavItem = TvNavItem.MEDIA

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
            dataBinding.tvNavRv.requestIndexChildFocus(navItems.indexOf(selectedSection))
        }

        initShell()
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent?
    ): Boolean {
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
        }
    }

    private fun handleNavClick(
        item: TvNavItem,
        position: Int
    ) {
        if (item.isSection) {
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
        // 重复打开当前页面，不进行任何操作
        if (tag == fragmentTag) {
            return
        }

        // 隐藏上一个布局，fragmentTag不为空代表上一个布局已存在
        if (fragmentTag.isNotEmpty()) {
            supportFragmentManager.hideFragment(previousFragment)
        }

        val fragment = supportFragmentManager.findFragmentByTag(tag)
        if (fragment == null) {
            getFragment(fragmentPath)?.also {
                addFragment(it, tag)
                previousFragment = it
                fragmentTag = tag
            }
        } else {
            supportFragmentManager.showFragment(fragment)
            previousFragment = fragment
            fragmentTag = tag
        }
    }

    private fun addFragment(
        fragment: Fragment,
        tag: String
    ) {
        supportFragmentManager
            .beginTransaction()
            .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
            .add(R.id.fragment_container, fragment, tag)
            .commit()
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
        SETTINGS(R.string.navigation_setting, false),
    }
}
