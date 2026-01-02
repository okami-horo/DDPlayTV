package com.xyoye.common_component.extension

import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.common_component.utils.PathHelper
import com.xyoye.data_component.enums.ResourceType
import java.io.File
import java.net.URLDecoder
import java.nio.charset.Charset

fun String?.toFile(): File? {
    if (this.isNullOrEmpty()) {
        return null
    }
    return File(this)
}

fun String?.toCoverFile(): File? {
    if (this.isNullOrEmpty()) {
        return null
    }
    return File(PathHelper.getVideoCoverDirectory(), this)
}

fun String.decodeUrl(charset: Charset = Charsets.UTF_8): String {
    if (isEmpty()) {
        return this
    }
    return try {
        URLDecoder.decode(this, charset.name())
    } catch (e: Exception) {
        ErrorReportHelper.postCatchedException(
            e,
            "StringStorageExt.decodeUrl",
            "URL解码失败: $this",
        )
        this
    }
}

fun String.formatFileName(): String = trim().replace("[*>/:\\\\?<|]".toRegex(), "_").replace(" ", "_")

inline fun String?.ifNullOrBlank(defaultValue: () -> String): String = if (this.isNullOrBlank()) defaultValue() else this

fun String?.resourceType(): ResourceType? =
    when {
        this.isNullOrEmpty() -> null
        this.startsWith("http", true) -> ResourceType.URL
        this.startsWith("/") || this.startsWith("file://") -> ResourceType.File
        this.startsWith("content") -> ResourceType.URI
        else -> null
    }
