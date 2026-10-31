package com.xyoye.user_component.ui.activities.scan_manager

import android.view.Menu
import android.view.MenuItem
import com.alibaba.android.arouter.facade.annotation.Route
import com.xyoye.common_component.base.BaseActivity
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.focus.TvCategory
import com.xyoye.common_component.focus.TvCategoryPages
import com.xyoye.user_component.BR
import com.xyoye.user_component.R
import com.xyoye.user_component.databinding.ActivityScanManagerBinding
import com.xyoye.user_component.ui.dialog.VideoExtensionSupportSettingDialog
import com.xyoye.user_component.ui.fragment.scan_extend.ScanExtendFragment
import com.xyoye.user_component.ui.fragment.scan_filter.ScanFilterFragment

@Route(path = RouteTable.User.ScanManager)
class ScanManagerActivity : BaseActivity<ScanManagerViewModel, ActivityScanManagerBinding>() {
    private var extensionSettingItem: MenuItem? = null
    private var extensionSettingDialog: VideoExtensionSupportSettingDialog? = null
    private var tvPages: TvCategoryPages? = null
    private var initialFocusPending = true

    override fun initViewModel() =
        ViewModelInit(
            BR.viewModel,
            ScanManagerViewModel::class.java,
        )

    override fun getLayoutId() = R.layout.activity_scan_manager

    override fun initView() {
        title = "扫描目录管理"

        tvPages =
            TvCategoryPages(this, supportFragmentManager, dataBinding.tabLayout, dataBinding.viewpager, verticalNavigation = true) {
                extensionSettingItem?.isVisible = it == 0
            }.apply {
                submit(
                    listOf(
                        TvCategory("scan", "扫描") { ScanExtendFragment.newInstance() },
                        TvCategory("filter", "屏蔽") { ScanFilterFragment.newInstance() }
                    )
                )
            }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_scan_manager, menu)
        extensionSettingItem = menu.findItem(R.id.item_video_extension_support_setting)
        extensionSettingItem?.isVisible = (tvPages?.selectedIndex ?: 0) == 0
        return super.onCreateOptionsMenu(menu)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || !initialFocusPending) return
        initialFocusPending = false
        val pages = tvPages ?: return
        if (!dataBinding.root.isInTouchMode && !pages.content.hasFocus()) pages.requestNavigationFocus()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == extensionSettingItem?.itemId) {
            showScanSettingDialog()
            return true
        }

        return super.onOptionsItemSelected(item)
    }

    private fun showScanSettingDialog() {
        extensionSettingDialog?.dismiss()
        extensionSettingDialog =
            VideoExtensionSupportSettingDialog(this).also {
                it.show()
            }
    }
}
