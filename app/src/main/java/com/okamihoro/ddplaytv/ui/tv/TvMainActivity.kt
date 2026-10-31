package com.okamihoro.ddplaytv.ui.tv

import android.os.Bundle
import android.view.KeyEvent
import androidx.fragment.app.Fragment
import com.alibaba.android.arouter.launcher.ARouter
import com.okamihoro.ddplaytv.R
import com.okamihoro.ddplaytv.databinding.ActivityTvMainBinding
import com.okamihoro.ddplaytv.databinding.ItemTvMainNavBinding
import com.okamihoro.ddplaytv.ui.shell.BaseShellActivity
import com.okamihoro.ddplaytv.ui.shell.ShellFragmentSwitcher
import com.rubensousa.dpadrecyclerview.DpadRecyclerView
import com.xyoye.common_component.adapter.addItem
import com.xyoye.common_component.adapter.buildAdapter
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.extension.horizontal
import com.xyoye.common_component.extension.setData

class TvMainActivity : BaseShellActivity<ActivityTvMainBinding>() {
    companion object {
        private const val TAG_FRAGMENT_MEDIA = "tag_fragment_tv_media"
        private const val TAG_FRAGMENT_CINEMA = "tag_fragment_tv_cinema"
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
    private var selectedSection: TvNavItem = TvNavItem.HOME

    private val focusCoordinator by lazy(LazyThreadSafetyMode.NONE) {
        TvShellFocusCoordinator(
            navRecyclerView = dataBinding.tvNavRv,
            selectedNavIndexProvider = { navItems.indexOf(selectedSection) },
            currentFragmentProvider = { fragmentSwitcher.currentFragment() },
        )
    }

    override fun getLayoutId() = R.layout.activity_tv_main

    override fun onCreate(savedInstanceState: Bundle?) {
        selectedSection = TvNavItem.entries.firstOrNull {
            it.name == savedInstanceState?.getString("tv_section") && it.isSection
        } ?: TvNavItem.HOME
        super.onCreate(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("tv_section", selectedSection.name)
        super.onSaveInstanceState(outState)
    }

    override fun initView() {
        initNav()
        dataBinding.fragmentContainer.navigationTarget = focusCoordinator::navigationTarget
        switchSection(selectedSection)
        focusCoordinator.requestNavFocus()

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
                if (dataBinding.fragmentContainer.hasFocus()) {
                    return focusCoordinator.requestNavFocus()
                }
                if (handleBackExit()) {
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun initNav() {
        dataBinding.tvNavRv.apply {
            layoutManager = horizontal()
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
            setFocusOutAllowed(false, false)
            setFocusOutSideAllowed(false, false)
            setOnKeyInterceptListener(
                object : DpadRecyclerView.OnKeyInterceptListener {
                    override fun onInterceptKeyEvent(event: KeyEvent): Boolean {
                        if (event.keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false
                        if (event.action == KeyEvent.ACTION_DOWN) focusCoordinator.requestContentFocus()
                        return true
                    }
                }
            )
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
            TvNavItem.HOME -> switchFragment(TAG_FRAGMENT_CINEMA, RouteTable.Local.CinemaFragment)
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
        HOME(R.string.navigation_cinema, true),
        MEDIA(R.string.navigation_media, true),
        SEARCH(R.string.navigation_search, false),
        PERSONAL(R.string.navigation_personal, true),
        SETTINGS(R.string.navigation_setting, false)
    }
}
