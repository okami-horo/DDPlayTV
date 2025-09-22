package com.xyoye.common_component.utils.danmu.helper

import com.xyoye.common_component.extension.toHexString
import com.xyoye.common_component.utils.ErrorReportHelper
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.MessageDigest

/**
 * Created by xyoye on 2023/12/27
 * 读取文件前16M数据，用于计算匹配弹幕所需的Hash值
 */

object DanmuHashCalculator {

    // 匹配弹幕时，计算Hash需要的文件大小
    private const val HASH_CALCULATE_SIZE = 16 * 1024 * 1024

    // 计算Hash每次读取的数据大小
    private const val HASH_BUFFER_SIZE = 256 * 1024

    // MD5计算器
    private val MD5 = MessageDigest.getInstance("MD5")

    /**
     * 读取InputStream的前16M数据，生成hash值
     */
    fun calculate(inputStream: InputStream, bufferSize: Int = HASH_BUFFER_SIZE): String? {
        val buffer = ByteArray(bufferSize)

        var total = 0
        var current: Int
        var target = buffer.size

        return try {
            // 检查流是否已关闭或不可用
            if (inputStream.available() < 0) {
                return null
            }

            MD5.reset()
            while (
                inputStream.read(buffer, 0, target).also { current = it } != -1
                && total < HASH_CALCULATE_SIZE
            ) {
                MD5.update(buffer, 0, current)

                total += current
                target = minOf(HASH_CALCULATE_SIZE - total, bufferSize)
            }
            MD5.digest().toHexString()
        } catch (e: SocketException) {
            // 专门处理Socket异常，避免重复上报
            null
        } catch (e: SocketTimeoutException) {
            // 处理超时异常
            null
        } catch (e: InterruptedIOException) {
            // 处理中断异常
            null
        } catch (e: Exception) {
            ErrorReportHelper.postCatchedException(
                e,
                "DanmuHashCalculator.calculate",
                "计算弹幕哈希值失败"
            )
            null
        }
    }
}