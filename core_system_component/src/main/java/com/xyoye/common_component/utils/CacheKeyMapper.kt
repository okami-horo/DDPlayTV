package com.xyoye.common_component.utils

import com.xyoye.common_component.extension.toHexString
import java.security.MessageDigest

/**
 * 将业务侧的 key（可能是 URL/URI/路径等）映射为稳定且可落盘的安全文件名。
 *
 * 设计目标：
 * - 不依赖 key 的字符集/长度/是否包含路径分隔符
 * - 映射结果稳定（同 key 同结果）
 * - 结果不包含路径分隔符，避免 ENOENT/目录穿越等问题
 */
object CacheKeyMapper {
    private val MD5_PATTERN = Regex("^[0-9a-fA-F]{32}$")

    fun toSafeFileName(key: String): String {
        val trimmedKey = key.trim()
        if (trimmedKey.isEmpty()) {
            return "(invalid)"
        }

        // 保持历史上以 md5 作为 key 的场景不变，避免无意义的缓存失效
        if (MD5_PATTERN.matches(trimmedKey)) {
            return trimmedKey.lowercase()
        }

        return md5Hex(trimmedKey)
    }

    private fun md5Hex(input: String): String {
        val messageDigest = MessageDigest.getInstance("MD5")
        messageDigest.update(input.toByteArray())
        return messageDigest.digest().toHexString()
    }
}

