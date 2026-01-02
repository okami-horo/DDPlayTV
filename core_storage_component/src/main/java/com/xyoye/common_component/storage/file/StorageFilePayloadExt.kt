package com.xyoye.common_component.storage.file

/**
 * 从 [StorageFile.getFile] 获取 payload 并进行类型安全的转换。
 *
 * 说明：
 * - [StorageFile.getFile] 的泛型在运行时会被擦除，调用点可能产生强制转换异常
 * - 这里用 reified 在调用点完成安全判断，避免 ClassCastException
 */
inline fun <reified T> StorageFile.payloadAs(): T? {
    val payload = getFile<Any?>()
    return payload as? T
}

