package com.okamihoro.ddplaytv.ui.tv

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.fragment.app.Fragment
import com.alibaba.android.arouter.facade.annotation.Route
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
import com.xyoye.common_component.focus.DpadFocusOwner

@Route(path = RouteTable.App.TvMain)
class TvMainActivity : BaseShellActivity<ActivityTvMainBinding>() {
    companion object {
        private const val TAG_FRAGMENT_MEDIA = "tag_fragment_tv_media"
        private const val TAG_FRAGMENT_CINEMA = "tag_fragment_tv_cinema"
        private const val TAG_FRAGMENT_SETTINGS = "tag_fragment_tv_settings"
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
        supportFragmentManager.setFragmentResultListener(RouteTable.App.CinemaNavigation, this) { _, result ->
            val target = if (result.getString("page") == "settings") TvNavItem.SETTINGS else TvNavItem.MEDIA
            handleNavClick(target, navItems.indexOf(target))
            focusCoordinator.requestContentFocus()
        }
        dataBinding.fragmentContainer.navigationTarget = focusCoordinator::navigationTarget
        switchSection(selectedSection)
        focusCoordinator.requestNavFocus()

        initShell()
        navigateFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        navigateFromIntent(intent)
    }

    private fun navigateFromIntent(intent: Intent) {
        val page = intent.getStringExtra(RouteTable.App.CinemaNavigation) ?: return
        intent.removeExtra(RouteTable.App.CinemaNavigation)
        val target = if (page == "settings") TvNavItem.SETTINGS else TvNavItem.HOME
        handleNavClick(target, navItems.indexOf(target))
        focusCoordinator.requestContentFocus()
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent?
    ): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_SETTINGS -> {
                handleNavClick(TvNavItem.SETTINGS, navItems.indexOf(TvNavItem.SETTINGS))
                focusCoordinator.requestContentFocus()
                return true
            }

            KeyEvent.KEYCODE_BACK -> {
                if ((fragmentSwitcher.currentFragment() as? DpadFocusOwner)?.handleDpadBack() == true) return true
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
            addItemDecoration(
                com.xyoye.common_component.utils.view
                    .ItemDecorationSpace((5 * resources.displayMetrics.density).toInt(), 0)
            )
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
                if (supportFragmentManager.findFragmentByTag("cinema_search") == null) {
                    (ARouter.getInstance().build(RouteTable.Local.MetadataSearch).navigation() as? androidx.fragment.app.DialogFragment)
                        ?.show(supportFragmentManager, "cinema_search")
                }
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

            TvNavItem.SETTINGS -> {
                switchFragment(
                    tag = TAG_FRAGMENT_SETTINGS,
                    fragmentPath = RouteTable.User.SettingsFragment,
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
        MEDIA(R.string.navigation_sources, true),
        SEARCH(R.string.navigation_search, false),
        SETTINGS(R.string.navigation_setting, true)
    }
}
