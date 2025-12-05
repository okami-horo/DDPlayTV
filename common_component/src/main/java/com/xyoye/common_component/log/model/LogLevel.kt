package com.xyoye.common_component.log.model

/**
 * 日志级别，遵循 FR-013：默认仅 INFO/ERROR 输出，DEBUG 需显式开启。
 */
enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR
}
