package com.xyoye.common_component.log.model

/**
 * 本地日志文件元信息，仅允许 log.txt / log_old.txt。
 */
data class LogFileMeta(
    val fileName: String,
    val path: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val sessionStartTime: Long? = null,
    val readable: Boolean = true
) {
    init {
        require(fileName in ALLOWED_FILE_NAMES) { "Unexpected log file name: $fileName" }
        require(path.isNotBlank()) { "Log file path must not be blank" }
        require(sizeBytes >= 0) { "Log file size must be non-negative" }
    }

    companion object {
        val ALLOWED_FILE_NAMES = setOf("log.txt", "log_old.txt")
    }
}

/**
 * 日志包 / 采集快照，用于未来导出能力。
 */
data class LogPackage(
    val id: String,
    val createdAt: Long,
    val files: List<LogFileMeta>,
    val appVersion: String,
    val buildType: String,
    val deviceInfo: Map<String, String> = emptyMap(),
    val environment: String = "",
    val notes: String? = null
) {
    init {
        require(id.isNotBlank()) { "LogPackage id must not be blank" }
        require(createdAt > 0) { "createdAt must be positive" }
    }
}

data class LogExportRequest(
    val includeOldLog: Boolean = true,
    val compress: Boolean = true,
    val stripDebugLinesBelowLevel: LogLevel? = null
)

data class LogExportResult(
    val success: Boolean,
    val `package`: LogPackage? = null,
    val errorMessage: String? = null
) {
    init {
        if (success) {
            require(`package` != null) { "LogExportResult.package must be set when success is true" }
        }
    }
}
