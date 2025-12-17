package com.xyoye.common_component.config

import androidx.appcompat.app.AppCompatDelegate
import com.xyoye.common_component.network.config.Api
import com.xyoye.common_component.utils.meida.VideoExtension
import com.xyoye.data_component.enums.HistorySort
import com.xyoye.data_component.enums.StorageSort
import com.xyoye.mmkv_annotation.MMKVFiled
import com.xyoye.mmkv_annotation.MMKVKotlinClass

@MMKVKotlinClass(className = "AppConfig")
object AppConfigTable {
    // 是否展示欢迎页
    @MMKVFiled
    const val showSplashAnimation = false

    // 缓存路径
    @MMKVFiled
    val cachePath = DefaultConfig.DEFAULT_CACHE_PATH

    // 是否展示隐藏文件
    @MMKVFiled
    var showHiddenFile = false

    // 是否展示FTP播放视频提示
    @MMKVFiled
    var showFTPVideoTips = true

    // 磁链搜索节点
    @MMKVFiled
    var magnetResDomain: String? = null

    // 最后一次更新云屏蔽信息的时间
    @MMKVFiled
    var cloudBlockUpdateTime: Long = 0

    // 深色模式状态
    @MMKVFiled
    var darkMode: Int = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM

    // 常用目录1
    @MMKVFiled
    var commonlyFolder1: String? = null

    // 常用目录2
    @MMKVFiled
    var commonlyFolder2: String? = null

    // 上次打开目录
    @MMKVFiled
    var lastOpenFolder: String? = null

    // 上次打开目录开关
    @MMKVFiled
    var lastOpenFolderEnable: Boolean = true

    // 上次搜索弹幕记录
    @MMKVFiled
    var lastSearchDanmuJson: String? = null

    // 文件排序类型
    @MMKVFiled
    var storageSortType: Int = StorageSort.NAME.value

    // 文件排序升序
    @MMKVFiled
    var storageSortAsc: Boolean = true

    // 文件排序文件夹优先
    @MMKVFiled
    var storageSortDirectoryFirst: Boolean = true

    // 播放历史排序类型
    @MMKVFiled
    var historySortType: Int = HistorySort.TIME.value

    // 播放历史排序升序
    @MMKVFiled
    var historySortAsc: Boolean = false

    // 是否启用备用域名
    @MMKVFiled
    var backupDomainEnable: Boolean = false

    // 备用域名地址
    @MMKVFiled
    var backupDomain: String = Api.DAN_DAN_SPARE

    // 支持的视频后缀
    @MMKVFiled
    var supportVideoExtension: String = VideoExtension.supportText

    // Jsoup的User-Agent
    @MMKVFiled
    var jsoupUserAgent: String = DefaultConfig.DEFAULT_JSOUP_USER_AGENT
}
