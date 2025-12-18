package com.xyoye.common_component.log.model

/**
 * 模块内的子标签，用于细化日志来源。
 */
class LogTag(
    val module: LogModule,
    rawValue: String
) {
    val value: String = rawValue.trim()

    init {
        require(value.isNotBlank()) { "LogTag value must not be blank" }
        require(value.length <= MAX_TAG_LENGTH) { "LogTag length must be <= $MAX_TAG_LENGTH" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LogTag) return false

        return module == other.module && value == other.value
    }

    override fun hashCode(): Int {
        var result = module.hashCode()
        result = 31 * result + value.hashCode()
        return result
    }

    override fun toString(): String = "LogTag(module=$module, value=$value)"

    companion object {
        const val MAX_TAG_LENGTH = 32
    }
}
