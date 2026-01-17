package com.xyoye.storage_component.ui.activities.storage_file

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentTransaction
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.coroutineScope
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.alibaba.android.arouter.facade.annotation.Autowired
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.xyoye.common_component.base.BaseActivity
import com.xyoye.common_component.base.app.BaseApplication
import com.xyoye.common_component.bilibili.BilibiliPlaybackPreferencesStore
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.extension.horizontal
import com.xyoye.common_component.extension.requestIndexChildFocus
import com.xyoye.common_component.extension.setData
import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.common_component.storage.Storage
import com.xyoye.common_component.storage.StorageFactory
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.storage.impl.BilibiliStorage
import com.xyoye.common_component.storage.impl.FtpStorage
import com.xyoye.common_component.utils.SupervisorScope
import com.xyoye.common_component.utils.subtitle.SubtitleFontCacheHelper
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.common_component.weight.dialog.CommonDialog
import com.xyoye.data_component.bean.StorageFilePath
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.MediaType
import com.xyoye.storage_component.BR
import com.xyoye.storage_component.R
import com.xyoye.storage_component.databinding.ActivityStorageFileBinding
import com.xyoye.storage_component.ui.dialog.FontCacheProgressDialog
import com.xyoye.storage_component.ui.fragment.storage_file.StorageFileFragment
import com.xyoye.storage_component.ui.weight.StorageFileMenus
import com.xyoye.storage_component.utils.storage.StorageFilePathAdapter
import com.xyoye.storage_component.utils.storage.StorageFileStyleHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

@Route(path = RouteTable.Stream.StorageFile)
class StorageFileActivity : BaseActivity<StorageFileViewModel, ActivityStorageFileBinding>() {
    @Autowired
    @JvmField
    var storageLibrary: MediaLibraryEntity? = null

    lateinit var storage: Storage
        private set

    private data class RouteEntry(
        val path: StorageFilePath,
        var directory: StorageFile?,
        val fragment: StorageFileFragment,
        var directoryFilesSnapshot: List<StorageFile> = emptyList(),
        var displayVideoCount: Int = 0,
        var displayDirectoryCount: Int = 0
    )

    // 导航栈：当前页面/目录的单一事实源
    private val routeStack = mutableListOf<RouteEntry>()

    // 当前所处文件夹（由导航栈推导，避免状态不同步）
    val directory: StorageFile?
        get() = routeStack.lastOrNull()?.directory

    private val currentRoute: String
        get() = routeStack.lastOrNull()?.path?.route ?: "/"

    // 标题栏菜单管理器
    private var mMenus: StorageFileMenus? = null

    // 标题栏样式工具
    private val mToolbarStyleHelper: StorageFileStyleHelper by lazy {
        StorageFileStyleHelper(this, dataBinding)
    }

    companion object {
        private const val TAG = "StorageFileActivity"
        private const val REQUEST_CODE_BILIBILI_RISK_VERIFY = 3301
        internal const val REQUEST_CODE_OPEN115_REAUTH = 3302
    }

    var shareStorageFile: StorageFile? = null

    private var pendingRiskVerifyFile: StorageFile? = null
    private var riskVerifyInProgress: Boolean = false

    // private var locatingLastPlay = false
    // private var lastPlayHistory: PlayHistoryEntity? = null

    override fun initViewModel() =
        ViewModelInit(
            BR.viewModel,
            StorageFileViewModel::class.java,
        )

    override fun getLayoutId() = R.layout.activity_storage_file

    override fun onCreate(savedInstanceState: Bundle?) {
        ARouter.getInstance().inject(this)

        if (checkBundle().not()) {
            super.onCreate(savedInstanceState)
            finish()
            return
        }
        super.onCreate(savedInstanceState)
    }

    override fun initView() {
        mToolbarStyleHelper.observerChildScroll()
        title = storageLibrary?.displayName
        updateToolbarSubtitle(0, 0)

        initPathRv()
        initListener()
        // updateFloatingButtonStyle()
        openDirectory(null)
    }

    private fun initPathRv() {
        dataBinding.pathRv.apply {
            layoutManager = horizontal()

            adapter =
                StorageFilePathAdapter.build(this@StorageFileActivity) {
                    backToRouteFragment(it)
                }
        }
    }

    private fun initListener() {
        mToolbar?.setNavigationOnClickListener {
            if (popFragment().not()) {
                finish()
            }
        }

        // 定位上次播放目录功能暂时关闭
        // dataBinding.quicklyPlayBt.setOnClickListener {
        //     viewModel.locateLastPlay(storage)
        // }

        // dataBinding.quicklyPlayBt.setOnFocusChangeListener { _, _ ->
        //     updateFloatingButtonStyle()
        // }

        dataBinding.pathRv.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                val lastIndex =
                    dataBinding.pathRv.adapter?.itemCount?.let { count ->
                        if (count > 0) count - 1 else RecyclerView.NO_POSITION
                    } ?: RecyclerView.NO_POSITION
                if (lastIndex != RecyclerView.NO_POSITION) {
                    LogFacade.d(
                        LogModule.STORAGE,
                        TAG,
                        "pathRv focus gained, request focus index=$lastIndex count=${dataBinding.pathRv.adapter?.itemCount ?: 0}",
                    )
                    dataBinding.pathRv.requestIndexChildFocus(lastIndex)
                }
            }
        }

        // 系统无法正确分发快速播放按钮的焦点，需要手动分发
        // dataBinding.quicklyPlayBt.setOnKeyListener(object : View.OnKeyListener {
        //     override fun onKey(v: View?, keyCode: Int, event: KeyEvent?): Boolean {
        //         if (event?.action != KeyEvent.ACTION_DOWN || v?.isFocused != true) {
        //             return false
        //         }
        //         if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
        //             LogFacade.i(LogModule.STORAGE, TAG, "quickPlay key up/left -> dispatch reversed")
        //             dispatchFocus(reversed = true)
        //             return true
        //         } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
        //             LogFacade.i(LogModule.STORAGE, TAG, "quickPlay key down/right -> dispatch forward")
        //             dispatchFocus()
        //             return true
        //         }
        //         return false
        //     }
        // })

        viewModel.playLiveData.observe(this) {
            ARouter
                .getInstance()
                .build(RouteTable.Player.Player)
                .navigation()
        }

        viewModel.bilibiliRiskVerifyLiveData.observe(this) { payload ->
            if (riskVerifyInProgress) {
                return@observe
            }
            riskVerifyInProgress = true
            pendingRiskVerifyFile = payload.file
            showBilibiliRiskVerifyDialog(payload.vVoucher)
        }
        // viewModel.locateLastPlayLiveData.observe(this) {
        //     startLocateToHistory(it)
        // }

        /*
         * TV adaptation: 投屏发送链路关闭，保留原逻辑以便后续按 flavor 恢复
         * viewModel.castLiveData.observe(this) {
         *     ARouter.getInstance()
         *         .navigation(ScreencastProvideService::class.java)
         *         .startService(this, it)
         * }
         *
         * viewModel.selectDeviceLiveData.observe(this) {
         *     showSelectDeviceDialog(it.first, it.second)
         * }
         */

        if (storage is FtpStorage) {
            lifecycle.coroutineScope.launchWhenResumed {
                withContext(Dispatchers.IO) {
                    (storage as FtpStorage).completePending()
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        mMenus =
            StorageFileMenus.inflater(this, menu).apply {
                onSearchTextChanged { onSearchTextChanged(it) }
                onSortTypeChanged { onSortOptionChanged() }
            }
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        mMenus?.onOptionsItemSelected(item)
        return super.onOptionsItemSelected(item)
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        if (requestCode == REQUEST_CODE_BILIBILI_RISK_VERIFY) {
            riskVerifyInProgress = false
            val file = pendingRiskVerifyFile
            pendingRiskVerifyFile = null

            if (resultCode == RESULT_OK && file != null) {
                openFile(file)
            }
        }
        if (requestCode == REQUEST_CODE_OPEN115_REAUTH) {
            if (resultCode == RESULT_OK) {
                routeStack.lastOrNull()?.fragment?.triggerTvRefresh()
            }
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent?
    ): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && mMenus?.handleBackPressed() == true) {
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && popFragment()) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (handleTvBilibiliPagedRefreshKey(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        shareStorageFile = null
        if (this::storage.isInitialized) {
            SupervisorScope.IO.launch {
                storage.close()
            }
        }
        super.onDestroy()
    }

    private fun checkBundle(): Boolean {
        storageLibrary
            ?: return false
        val storage =
            StorageFactory.createStorage(storageLibrary!!)
                ?: return false

        this.storage = storage
        return true
    }

    private fun pushFragment(
        path: StorageFilePath,
        directory: StorageFile?
    ) {
        val fragment = StorageFileFragment.newInstance()
        val previousFragment = routeStack.lastOrNull()?.fragment
        routeStack.add(RouteEntry(path = path, directory = directory, fragment = fragment))
        LogFacade.d(LogModule.STORAGE, TAG, "pushFragment route=${path.route} size=${routeStack.size}")

        supportFragmentManager.beginTransaction().apply {
            // 添加前的最后一个Fragment，设置为STARTED状态
            previousFragment?.let { setMaxLifecycle(it, Lifecycle.State.STARTED) }

            setCustomAnimations(R.anim.fragment_fade_in, R.anim.fragment_fade_out)
            setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
            add(dataBinding.fragmentContainer.id, fragment, path.route)

            // 当前添加的Fragment，设置为RESUMED状态
            setMaxLifecycle(fragment, Lifecycle.State.RESUMED)
            commit()
        }

        onDisplayFragmentChanged()
    }

    private fun handleTvBilibiliPagedRefreshKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (event.repeatCount != 0) return false

        val library = storage.library
        if (library.mediaType != MediaType.BILIBILI_STORAGE) return false
        if (!BilibiliStorage.isBilibiliPagedDirectoryPath(currentRoute)) return false

        val isRefreshKey =
            when (event.keyCode) {
                KeyEvent.KEYCODE_MENU -> true
                // 部分 TV 遥控器“菜单”键会被映射为 Settings
                KeyEvent.KEYCODE_SETTINGS -> true
                else -> false
            }
        if (!isRefreshKey) return false

        val fragment = routeStack.lastOrNull()?.fragment
        if (fragment == null) {
            LogFacade.w(LogModule.STORAGE, TAG, "bilibili paged refresh ignored, fragment null keyCode=${event.keyCode}")
            return false
        }

        fragment.triggerTvRefresh()
        return true
    }

    private fun popFragment(): Boolean {
        if (routeStack.size <= 1) {
            LogFacade.d(LogModule.STORAGE, TAG, "popFragment ignored, only root fragment left size=${routeStack.size}")
            return false
        }
        val removedEntry = routeStack.removeAt(routeStack.lastIndex)
        LogFacade.d(LogModule.STORAGE, TAG, "popFragment route=${removedEntry.path.route} remain=${routeStack.size}")
        syncStorageToCurrentRoute()
        removeFragment(listOf(removedEntry.fragment))
        onDisplayFragmentChanged()
        return true
    }

    private fun syncStorageToCurrentRoute() {
        val entry = routeStack.lastOrNull() ?: return
        storage.directory = entry.directory
        storage.directoryFiles = entry.directoryFilesSnapshot
        updateToolbarSubtitle(entry.displayVideoCount, entry.displayDirectoryCount)
    }

    private fun backToRouteFragment(target: StorageFilePath) {
        val targetIndex = routeStack.indexOfFirst { it.path == target }
        if (targetIndex == -1) {
            LogFacade.w(LogModule.STORAGE, TAG, "backToRouteFragment ignored, target not found route=${target.route}")
            return
        }
        if (targetIndex == routeStack.lastIndex) {
            return
        }

        val removedEntries =
            routeStack
                .subList(targetIndex + 1, routeStack.size)
                .toList()
        routeStack.subList(targetIndex + 1, routeStack.size).clear()

        val fragments: List<Fragment> = removedEntries.map { it.fragment }
        LogFacade.d(
            LogModule.STORAGE,
            TAG,
            "backToRouteFragment target=${target.route} remove=${fragments.size} remain=${routeStack.size}",
        )
        syncStorageToCurrentRoute()
        removeFragment(fragments)
        onDisplayFragmentChanged()
    }

    private fun removeFragment(fragments: List<Fragment>) {
        val resumedFragment = routeStack.lastOrNull()?.fragment
        supportFragmentManager.beginTransaction().apply {
            setCustomAnimations(R.anim.fragment_fade_in, R.anim.fragment_fade_out)
            setTransition(FragmentTransaction.TRANSIT_FRAGMENT_CLOSE)

            fragments.forEach {
                remove(it)
                // 当前移除的Fragment，设置为CREATED状态
                setMaxLifecycle(it, Lifecycle.State.CREATED)
            }

            // 以导航栈为准：最后一个Fragment，设置为RESUMED状态
            if (resumedFragment != null && resumedFragment !in fragments) {
                setMaxLifecycle(resumedFragment, Lifecycle.State.RESUMED)
            }
            commit()
        }
    }

    private fun onDisplayFragmentChanged() {
        val newPathData = StorageFilePathAdapter.buildPathData(routeStack.map { it.path })
        LogFacade.d(LogModule.STORAGE, TAG, "onDisplayFragmentChanged pathSize=${newPathData.size}")
        dataBinding.pathRv.setData(newPathData)
        dataBinding.pathRv.post {
            dataBinding.pathRv.smoothScrollToPosition(newPathData.size - 1)
            LogFacade.d(LogModule.STORAGE, TAG, "pathRv smoothScrollToPosition index=${newPathData.size - 1}")
        }
    }

    /*
     * TV adaptation: Sender 设备选择入口关闭，保留原实现方便迁移至非 TV flavor
    private fun showSelectDeviceDialog(file: StorageFile, devices: List<MediaLibraryEntity>) {
        val drawable = com.xyoye.core_ui_component.R.drawable.ic_screencast_device
        val actionData = devices.map {
            SheetActionBean(it.id, it.displayName, drawable, it.url)
        }
        BottomActionDialog(
            title = "选择投屏设备",
            activity = this,
            actionData = actionData
        ) { action ->
            devices.firstOrNull { it.id == action.actionId }?.let {
                viewModel.castItem(file, it)
            }
            return@BottomActionDialog true
        }.show()
    }
     */

    /**
     * 更新标题栏副标题
     */
    private fun updateToolbarSubtitle(
        videoCount: Int,
        directoryCount: Int
    ) {
        supportActionBar?.subtitle =
            when {
                videoCount == 0 && directoryCount == 0 -> {
                    "0视频"
                }

                directoryCount == 0 -> {
                    "${videoCount}视频"
                }

                videoCount == 0 -> {
                    "${directoryCount}文件夹"
                }

                else -> {
                    "${videoCount}视频  ${directoryCount}文件夹"
                }
            }
    }

    private fun showBilibiliRiskVerifyDialog(vVoucher: String) {
        val storageKey = BilibiliPlaybackPreferencesStore.storageKey(storage.library)

        CommonDialog
            .Builder(this)
            .apply {
                tips = "B站风控验证"
                content = "检测到B站风控，需要完成验证码验证后才能继续播放。\n\nv_voucher：$vVoucher"
                addNegative("取消") { dialog ->
                    dialog.dismiss()
                    riskVerifyInProgress = false
                    pendingRiskVerifyFile = null
                }
                addPositive("去验证") { dialog ->
                    dialog.dismiss()
                    ARouter
                        .getInstance()
                        .build(RouteTable.User.BilibiliRiskVerify)
                        .withString("storageKey", storageKey)
                        .withString("vVoucher", vVoucher)
                        .navigation(this@StorageFileActivity, REQUEST_CODE_BILIBILI_RISK_VERIFY)
                }
            }.build()
            .show()
    }

    /**
     * 搜索文案
     */
    private fun onSearchTextChanged(text: String) {
        routeStack.lastOrNull()?.fragment?.search(text)
    }

    /**
     * 改变文件排序
     */
    private fun onSortOptionChanged() {
        routeStack.map { it.fragment }.onEach { it.sort() }
    }

//    /**
//     * 根据焦点状态修改悬浮按钮样式
//     */
//    private fun updateFloatingButtonStyle() {
//        val floatingButton = dataBinding.quicklyPlayBt
//        val shapeAppearanceRes = if (floatingButton.isFocused)
//            R.style.ShapeAppearance_DanDanPlay_FloatingButton_Focused
//        else
//            R.style.ShapeAppearance_DanDanPlay_FloatingButton
//        floatingButton.shapeAppearanceModel = ShapeAppearanceModel.builder(
//            this, 0, shapeAppearanceRes
//        ).build()
//    }

    /**
     * 分发焦点到最后一个Fragment
     */
    fun dispatchFocus(reversed: Boolean = false) {
        routeStack.lastOrNull()?.fragment?.requestFocus(reversed)
    }

//    fun onDirectoryDataLoaded(fragment: StorageFileFragment, files: List<StorageFile>) {
//        if (!locatingLastPlay) {
//            return
//        }
//        val latestFragment = mRouteFragmentMap.values.lastOrNull()
//        if (fragment != latestFragment) {
//            return
//        }
//        val targetHistory = lastPlayHistory ?: run {
//            stopLocateLastPlay(false)
//            return
//        }
//        val targetUniqueKey = targetHistory.uniqueKey
//        val targetFile = files.firstOrNull { file ->
//            file.isFile() && file.playHistory?.uniqueKey == targetUniqueKey
//        }
//        if (targetFile != null) {
//            fragment.focusFile(targetUniqueKey)
//            ToastCenter.showOriginalToast("已定位到上次观看的视频")
//            stopLocateLastPlay(true)
//            return
//        }
//        val targetDirectory = files.firstOrNull { file ->
//            file.isDirectory() && file.playHistory?.isLastPlay == true
//        }
//        if (targetDirectory != null) {
//            openDirectory(targetDirectory)
//            return
//        }
//        ToastCenter.showError("定位失败：找不到上次观看记录")
//        stopLocateLastPlay(false)
//    }

//    fun isLocatingLastPlay() = locatingLastPlay

//    private fun startLocateToHistory(history: PlayHistoryEntity) {
//        lastPlayHistory = history
//        locatingLastPlay = true
//        if (mRouteFragmentMap.isEmpty()) {
//            openDirectory(null)
//            return
//        }
//        val rootPath = mRouteFragmentMap.keys.firstOrNull()
//        if (rootPath != null) {
//            backToRouteFragment(rootPath)
//        }
//        mRouteFragmentMap.values.lastOrNull()?.reloadDirectory(refresh = true)
//    }

//    private fun stopLocateLastPlay(success: Boolean) {
//        locatingLastPlay = false
//        lastPlayHistory = null
//        if (!success) {
//            dataBinding.quicklyPlayBt.requestFocus()
//        }
//    }

    @Suppress("UNUSED_PARAMETER")
    fun onDirectoryDataLoaded(
        fragment: StorageFileFragment,
        files: List<StorageFile>
    ) {
        // 定位上次播放目录功能已关闭
    }

    fun isLocatingLastPlay() = false

    fun openDirectory(file: StorageFile?) {
        val route = file?.filePath() ?: "/"
        val name = file?.fileName() ?: "根目录"
        pushFragment(StorageFilePath(name, route), directory = file)
    }

    fun onDirectoryOpened(
        fragment: StorageFileFragment,
        displayFiles: List<StorageFile>
    ) {
        val entry =
            routeStack.firstOrNull { it.fragment == fragment }
                ?: run {
                    LogFacade.w(LogModule.STORAGE, TAG, "onDirectoryOpened ignored, entry not found")
                    return
                }

        val storageDirectory = storage.directory
        val directoryMatchesRoute = storageDirectory?.filePath() == entry.path.route
        if (!directoryMatchesRoute) {
            val message =
                "onDirectoryOpened directory mismatch, route=${entry.path.route}, storage=${storageDirectory?.filePath()}"
            if (entry.path.route == "/") {
                LogFacade.d(LogModule.STORAGE, TAG, message)
            } else {
                LogFacade.w(LogModule.STORAGE, TAG, message)
            }
        }
        if (storageDirectory != null) {
            entry.directory = storageDirectory
        }
        entry.directoryFilesSnapshot = storage.directoryFiles

        entry.displayVideoCount = displayFiles.count { it.isFile() }
        entry.displayDirectoryCount = displayFiles.count { it.isDirectory() }

        if (routeStack.lastOrNull()?.fragment == fragment) {
            updateToolbarSubtitle(entry.displayVideoCount, entry.displayDirectoryCount)
        }
    }

    fun openFile(file: StorageFile) {
        lifecycleScope.launch {
            if (prepareRemoteFontsIfNeeded(file)) {
                viewModel.playItem(file)
            }
        }
    }

    private suspend fun prepareRemoteFontsIfNeeded(file: StorageFile): Boolean {
        if (file.playHistory != null || file.isFile().not()) {
            return true
        }
        val mediaType = storage.library.mediaType
        if (mediaType != MediaType.ALSIT_STORAGE && mediaType != MediaType.WEBDAV_SERVER) {
            return true
        }
        val fontDirectory =
            SubtitleFontCacheHelper.findFontDirectory(storage.directoryFiles)
                ?: return true

        val fontFiles =
            withContext(Dispatchers.IO) {
                SubtitleFontCacheHelper
                    .listFontFiles(storage, fontDirectory)
                    .let { SubtitleFontCacheHelper.filterFontFiles(it) }
            }
        if (fontFiles.isEmpty()) {
            ToastCenter.showWarning(getString(R.string.text_font_cache_empty))
            return true
        }

        val fontCacheDir =
            SubtitleFontCacheHelper.ensureFontDirectory(BaseApplication.getAppContext())
        if (fontCacheDir == null || fontCacheDir.exists().not()) {
            ToastCenter.showError(getString(R.string.text_font_cache_dir_error))
            return true
        }

        val cachedCountStart =
            fontFiles.count {
                SubtitleFontCacheHelper.isFontCached(fontCacheDir, it.fileName())
            }
        val pendingFonts =
            fontFiles.filterNot {
                SubtitleFontCacheHelper.isFontCached(fontCacheDir, it.fileName())
            }
        if (pendingFonts.isEmpty()) {
            return true
        }

        if (!requestFontCacheConfirm()) {
            return true
        }

        val progressDialog = FontCacheProgressDialog(this)
        withContext(Dispatchers.Main) {
            progressDialog.update(fontFiles.size, cachedCountStart)
            progressDialog.show()
        }

        var cachedCount = cachedCountStart
        try {
            withContext(Dispatchers.IO) {
                pendingFonts.forEach { fontFile ->
                    val fontName = fontFile.fileName()
                    val stream = storage.openFile(fontFile)
                    val success =
                        stream != null &&
                            SubtitleFontCacheHelper.cacheFontFile(
                                fontCacheDir,
                                fontName,
                                stream,
                            )
                    if (success) {
                        cachedCount++
                        withContext(Dispatchers.Main) {
                            progressDialog.update(fontFiles.size, cachedCount)
                        }
                    }
                }
            }
        } finally {
            withContext(Dispatchers.Main) {
                if (progressDialog.isShowing) {
                    progressDialog.dismiss()
                }
            }
        }
        return true
    }

    private suspend fun requestFontCacheConfirm(): Boolean =
        suspendCancellableCoroutine { continuation ->
            var decided = false
            val dialog =
                CommonDialog
                    .Builder(this)
                    .apply {
                        content = getString(R.string.text_font_cache_prompt)
                        addNegative("否") {
                            decided = true
                            if (continuation.isActive) {
                                continuation.resume(false)
                            }
                            it.dismiss()
                        }
                        addPositive("是") {
                            decided = true
                            if (continuation.isActive) {
                                continuation.resume(true)
                            }
                            it.dismiss()
                        }
                        doOnDismiss {
                            if (!decided && continuation.isActive) {
                                continuation.resume(false)
                            }
                        }
                    }.build()
            dialog.show()
            continuation.invokeOnCancellation { dialog.dismiss() }
        }

//    fun castFile(file: StorageFile) {
//        viewModel.castItem(file)
//    }
}
