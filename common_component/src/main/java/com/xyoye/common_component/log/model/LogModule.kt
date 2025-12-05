package com.xyoye.common_component.log.model

/**
 * 日志所属模块定义，统一使用预设枚举避免自由文本 Tag。
 */
enum class LogModule(
    val code: String,
    val displayName: String,
    val category: String? = null
) {
    CORE("core", "核心", "system"),
    PLAYER("player", "播放器", "media"),
    DOWNLOAD("download", "下载", "transfer"),
    USER("user", "用户", "account"),
    ANIME("anime", "番剧", "content"),
    LOCAL("local", "本地媒体", "content"),
    STORAGE("storage", "存储", "system"),
    NETWORK("network", "网络", "system"),
    SUBTITLE("subtitle", "字幕", "media"),
    UNKNOWN("unknown", "未知", "system");

    companion object {
        private val codeMap = values().associateBy { it.code.lowercase() }

        fun fromCode(code: String?): LogModule {
            if (code.isNullOrBlank()) return UNKNOWN
            return codeMap[code.lowercase()] ?: UNKNOWN
        }

        /**
         * 根据包名或调用上下文推导模块，无法推导时返回 UNKNOWN。
         */
        fun fromPackage(packageName: String?): LogModule {
            if (packageName.isNullOrBlank()) return UNKNOWN
            val normalized = packageName.lowercase()
            return when {
                normalized.contains("player") -> PLAYER
                normalized.contains("download") -> DOWNLOAD
                normalized.contains("user") -> USER
                normalized.contains("anime") -> ANIME
                normalized.contains("local") -> LOCAL
                normalized.contains("storage") -> STORAGE
                normalized.contains("network") || normalized.contains("net") -> NETWORK
                normalized.contains("subtitle") || normalized.contains("danmaku") -> SUBTITLE
                normalized.contains("core") || normalized.contains("common_component") -> CORE
                else -> UNKNOWN
            }
        }
    }
}
