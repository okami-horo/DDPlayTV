package com.xyoye.storage_component.ui.activities.storage_file

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentTransaction
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.alibaba.android.arouter.facade.annotation.Autowired
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.rubensousa.dpadrecyclerview.DpadRecyclerView
import com.xyoye.common_component.base.BaseActivity
import com.xyoye.common_component.base.app.BaseApplication
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.extension.horizontal
import com.xyoye.common_component.extension.setData
import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.common_component.playback.handoff.PlaybackNavigation
import com.xyoye.common_component.playback.handoff.PlaybackSubmissionResult
import com.xyoye.common_component.playback.userMessage
import com.xyoye.common_component.storage.Storage
import com.xyoye.common_component.storage.StorageFactory
import com.xyoye.common_component.storage.file.StorageFile
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

@Route(path = RouteTable.Stream.StorageFile)
class StorageFileActivity : BaseActivity<StorageFileViewModel, ActivityStorageFileBinding>() {
    @Autowired
    @JvmField
    var storageLibrary: MediaLibraryEntity? = null

    lateinit var storage: Storage
        private set

    internal val storageOperations = Mutex()

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
    private var restoredRoutePaths = emptyList<String>()
    private var restoredRouteNames = emptyList<String>()

    // 当前所处文件夹（由导航栈推导，避免状态不同步）
    val directory: StorageFile?
        get() = routeStack.lastOrNull()?.directory

    private val currentRoute: String
        get() = routeStack.lastOrNull()?.path?.route ?: "/"

    // 标题栏菜单管理器
    private var mMenus: StorageFileMenus? = null

    companion object {
        private const val TAG = "StorageFileActivity"
        internal const val REQUEST_CODE_OPEN115_REAUTH = 3302
        internal const val REQUEST_CODE_CLOUD115_REAUTH = 3303

        internal fun shouldRefreshAfterReauth(
            requestCode: Int,
            resultCode: Int
        ): Boolean =
            resultCode == Activity.RESULT_OK &&
                (
                    requestCode == REQUEST_CODE_OPEN115_REAUTH ||
                        requestCode == REQUEST_CODE_CLOUD115_REAUTH
                )
    }

    var shareStorageFile: StorageFile? = null

    override fun initViewModel() =
        ViewModelInit(
            BR.viewModel,
            StorageFileViewModel::class.java
        )

    override fun getLayoutId() = R.layout.activity_storage_file

    override fun onCreate(savedInstanceState: Bundle?) {
        ARouter.getInstance().inject(this)
        restoredRoutePaths = savedInstanceState?.getStringArrayList("directory_paths").orEmpty()
        restoredRouteNames = savedInstanceState?.getStringArrayList("directory_names").orEmpty()

        if (checkBundle().not()) {
            super.onCreate(savedInstanceState)
            finish()
            return
        }
        super.onCreate(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("directory_paths", ArrayList(routeStack.map { it.path.route }))
        outState.putStringArrayList("directory_names", ArrayList(routeStack.map { it.path.name }))
        super.onSaveInstanceState(outState)
    }

    override fun initView() {
        dataBinding.sourceTitle.text = storageLibrary?.displayName
        mMenus = StorageFileMenus(this, ::onSearchTextChanged, ::onSortOptionChanged)
        dataBinding.browserNav1.isSelected = true
        dataBinding.browserNav0.setOnClickListener { openShell("home") }
        dataBinding.browserNav1.setOnClickListener { finish() }
        dataBinding.browserNav2.setOnClickListener {
            if (supportFragmentManager.findFragmentByTag("cinema_search") == null) {
                (ARouter.getInstance().build(RouteTable.Local.MetadataSearch).navigation() as? DialogFragment)
                    ?.show(supportFragmentManager, "cinema_search")
            }
        }
        dataBinding.browserNav3.setOnClickListener { openShell("settings") }
        dataBinding.browserBack.setOnClickListener { backDirectory() }
        dataBinding.browserSearch.setOnClickListener {
            mMenus?.search(
                routeStack
                    .lastOrNull()
                    ?.fragment
                    ?.query
                    .orEmpty(),
                it
            )
        }
        dataBinding.browserSort.text = mMenus?.sortLabel()
        dataBinding.browserSort.setOnClickListener { mMenus?.sort(it) }
        dataBinding.browserRefresh.setOnClickListener { routeStack.lastOrNull()?.fragment?.triggerTvRefresh() }
        dataBinding.browserTools.isVisible =
            com.xyoye.common_component.storage.catalog.CatalogSourceReader
                .supports(storage.library.mediaType)
        dataBinding.browserTools.contentDescription = "扫描此目录资料"
        dataBinding.browserTools.setOnClickListener { mMenus?.scan() }

        initPathRv()
        initListener()
        if (restoredRoutePaths.isEmpty()) {
            openDirectory(null)
        } else {
            val transaction = supportFragmentManager.beginTransaction().setReorderingAllowed(true)
            restoredRoutePaths.forEachIndexed { index, path ->
                val fragment =
                    supportFragmentManager.findFragmentByTag(path) as? StorageFileFragment
                        ?: StorageFileFragment.newInstance(path)
                routeStack.add(RouteEntry(StorageFilePath(restoredRouteNames.getOrElse(index) { path }, path), null, fragment))
                if (!fragment.isAdded) transaction.add(dataBinding.fragmentContainer.id, fragment, path)
                if (index == restoredRoutePaths.lastIndex) {
                    transaction.show(fragment).setMaxLifecycle(fragment, Lifecycle.State.RESUMED)
                } else {
                    transaction.hide(fragment).setMaxLifecycle(fragment, Lifecycle.State.STARTED)
                }
            }
            transaction.commitNow()
            onDisplayFragmentChanged()
        }
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

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.playEvents.collect { result ->
                    when (result) {
                        is PlaybackSubmissionResult.Accepted ->
                            ARouter
                                .getInstance()
                                .build(RouteTable.Player.Player)
                                .withString(PlaybackNavigation.REQUEST_ID, result.requestId)
                                .navigation(this@StorageFileActivity)
                        is PlaybackSubmissionResult.Rejected -> ToastCenter.showError(result.failure.userMessage())
                    }
                }
            }
        }
        if (storage is FtpStorage) {
            val ftpStorage = storage as FtpStorage
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                lifecycleScope.launch(Dispatchers.IO) {
                    ftpStorage.completePending()
                }
            } else {
                lifecycle.addObserver(
                    object : LifecycleEventObserver {
                        override fun onStateChanged(
                            source: androidx.lifecycle.LifecycleOwner,
                            event: Lifecycle.Event
                        ) {
                            if (event != Lifecycle.Event.ON_RESUME) {
                                return
                            }
                            source.lifecycle.removeObserver(this)
                            lifecycleScope.launch(Dispatchers.IO) {
                                ftpStorage.completePending()
                            }
                        }
                    }
                )
            }
        }
    }

    private fun openShell(page: String) {
        ARouter
            .getInstance()
            .build(RouteTable.App.TvMain)
            .withString(RouteTable.App.CinemaNavigation, page)
            .withFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .navigation(this)
        finish()
    }

    fun backDirectory() {
        if (!popFragment()) finish()
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        when {
            shouldRefreshAfterReauth(requestCode, resultCode) -> {
                routeStack.lastOrNull()?.fragment?.triggerTvRefresh()
            }
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_MENU || event.keyCode == KeyEvent.KEYCODE_SETTINGS) {
            // AppCompat handles MENU before dispatching to the focused list.
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                val fragment = routeStack.lastOrNull()?.fragment
                if (event.keyCode == KeyEvent.KEYCODE_SETTINGS || fragment?.showFocusedActions() != true) fragment?.triggerTvRefresh()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent?
    ): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && popFragment()) {
            return true
        }
        return super.onKeyDown(keyCode, event)
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
        val fragment = StorageFileFragment.newInstance(path.route)
        val previousFragment = routeStack.lastOrNull()?.fragment
        routeStack.add(RouteEntry(path = path, directory = directory, fragment = fragment))
        LogFacade.d(LogModule.STORAGE, TAG, "pushFragment route=${path.route} size=${routeStack.size}")

        supportFragmentManager.beginTransaction().apply {
            // 添加前的最后一个Fragment，设置为STARTED状态
            previousFragment?.let { hide(it).setMaxLifecycle(it, Lifecycle.State.STARTED) }

            setCustomAnimations(R.anim.fragment_fade_in, R.anim.fragment_fade_out)
            setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
            add(dataBinding.fragmentContainer.id, fragment, path.route)

            // 当前添加的Fragment，设置为RESUMED状态
            setMaxLifecycle(fragment, Lifecycle.State.RESUMED)
            commit()
        }

        onDisplayFragmentChanged()
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
            "backToRouteFragment target=${target.route} remove=${fragments.size} remain=${routeStack.size}"
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
                show(resumedFragment).setMaxLifecycle(resumedFragment, Lifecycle.State.RESUMED)
                runOnCommit { resumedFragment.requestFocus() }
            }
            commit()
        }
    }

    private fun onDisplayFragmentChanged() {
        val newPathData = StorageFilePathAdapter.buildPathData(routeStack.map { it.path })
        LogFacade.d(LogModule.STORAGE, TAG, "onDisplayFragmentChanged pathSize=${newPathData.size}")
        dataBinding.pathRv.setData(newPathData)
        val pathList = dataBinding.pathRv
        if (pathList is DpadRecyclerView) {
            pathList.setSelectedPosition(newPathData.lastIndex)
        } else {
            pathList.smoothScrollToPosition(newPathData.lastIndex)
        }
    }

    /**
     * 更新标题栏副标题
     */
    private fun updateToolbarSubtitle(
        videoCount: Int,
        directoryCount: Int
    ) {
        dataBinding.directoryCount.text =
            when {
                videoCount == 0 && directoryCount == 0 -> {
                    "0 个项目"
                }

                directoryCount == 0 -> {
                    "$videoCount 个视频"
                }

                videoCount == 0 -> {
                    "$directoryCount 个文件夹"
                }

                else -> {
                    "$directoryCount 个文件夹 · $videoCount 个视频"
                }
            }
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
        dataBinding.browserSort.text = mMenus?.sortLabel()
        routeStack.map { it.fragment }.onEach { it.sort() }
    }

    /**
     * 分发焦点到最后一个Fragment
     */
    fun showBrowserStatus(
        fragment: StorageFileFragment,
        text: String,
        retry: (() -> Unit)?
    ) {
        if (!isCurrentDirectory(fragment)) return
        dataBinding.browserStatus.apply {
            this.text = text
            isFocusable = retry != null
            isClickable = retry != null
            setBackgroundResource(if (retry != null) R.drawable.cinema_category else android.R.color.transparent)
            setOnClickListener { retry?.invoke() }
        }
    }

    fun showDirectoryLoading(fragment: StorageFileFragment) {
        if (isCurrentDirectory(fragment)) dataBinding.directoryCount.text = ""
    }

    fun dispatchFocus() {
        routeStack.lastOrNull()?.fragment?.requestFocus()
    }

    fun isCurrentDirectory(fragment: StorageFileFragment): Boolean = routeStack.lastOrNull()?.fragment === fragment

    fun directoryFor(fragment: StorageFileFragment): StorageFile? = routeStack.firstOrNull { it.fragment === fragment }?.directory

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
        requestDirectory: StorageFile?,
        directoryFiles: List<StorageFile>,
        displayFiles: List<StorageFile>
    ) {
        val entry =
            routeStack.firstOrNull { it.fragment == fragment }
                ?: run {
                    LogFacade.w(LogModule.STORAGE, TAG, "onDirectoryOpened ignored, entry not found")
                    return
                }

        if (requestDirectory == null || requestDirectory.storage !== storage) return
        if (entry.path.route != "/" && requestDirectory.filePath() != entry.path.route) return
        entry.directory = requestDirectory
        entry.directoryFilesSnapshot = directoryFiles

        entry.displayVideoCount = displayFiles.count { it.isFile() }
        entry.displayDirectoryCount = displayFiles.count { it.isDirectory() }

        if (routeStack.lastOrNull()?.fragment == fragment) {
            updateToolbarSubtitle(entry.displayVideoCount, entry.displayDirectoryCount)
        }
    }

    fun openFile(file: StorageFile) {
        val entry = routeStack.lastOrNull() ?: return
        lifecycleScope.launch {
            storageOperations.withLock {
                if (routeStack.lastOrNull() !== entry) return@withLock
                storage.directory = entry.directory
                storage.directoryFiles = entry.directoryFilesSnapshot
                if (prepareRemoteFontsIfNeeded(file) && routeStack.lastOrNull() === entry) {
                    viewModel.playItem(file)
                }
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
                                stream
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
}
