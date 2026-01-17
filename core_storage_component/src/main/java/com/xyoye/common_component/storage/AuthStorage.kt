package com.xyoye.common_component.storage

import com.xyoye.common_component.storage.file.StorageFile

/**
 * 需要登录/授权的 Storage 抽象。
 *
 * UI 侧可基于 [requiresLogin]/[isConnected] 决定是否展示“扫码登录/授权”等入口。
 */
interface AuthStorage {
    fun isConnected(): Boolean

    fun requiresLogin(directory: StorageFile?): Boolean

    fun loginActionText(directory: StorageFile?): String
}

