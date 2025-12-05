package com.xyoye.common_component.log

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志相关单测的基础类，提供默认常量与占位测试。
 */
open class LoggingTestBase {
    protected val defaultModule = "core"
    protected val defaultLevel = "DEBUG"
    protected val defaultTag = "LogTest"
    protected val defaultMessage = "log message"

    @Test
    fun placeholder() {
        // 保持测试套件可运行，后续具体测试继承并补充覆盖
        assertTrue(true)
    }
}
