package com.xyoye.common_component.utils

/**
 * Created by XYJ on 2021/2/15.
 */

object MagnetUtils {
    fun getMagnetHash(magnetLink: String?): String {
        return try {
            if (magnetLink.isNullOrEmpty()) {
                ErrorReportHelper.postException(
                    "Empty magnet link provided",
                    "MagnetUtils",
                    RuntimeException("magnetLink is null or empty"),
                )
                return ""
            }

            // 头部
            if (!magnetLink.startsWith("magnet:?xt=urn:btih:", true)) {
                ErrorReportHelper.postException(
                    "Invalid magnet link format",
                    "MagnetUtils",
                    RuntimeException("Magnet link does not start with expected prefix: $magnetLink"),
                )
                return ""
            }
            var magnet = magnetLink.substring(20)

            // 尾部tracker信息
            val extraIndex = magnet.indexOf("&")
            if (extraIndex != -1) {
                magnet = magnet.substring(0, extraIndex)
            }

            val result =
                when (magnet.length) {
                    // SHA1(40位)
                    40 -> magnet.uppercase()
                    // MD5(32位)
                    32 -> magnet.uppercase()
                    else -> {
                        ErrorReportHelper.postException(
                            "Invalid magnet hash length",
                            "MagnetUtils",
                            RuntimeException("Hash length: ${magnet.length}, expected 32 or 40. Magnet: $magnetLink"),
                        )
                        ""
                    }
                }
            result
        } catch (e: Exception) {
            ErrorReportHelper.postCatchedExceptionWithContext(
                e,
                "MagnetUtils",
                "getMagnetHash",
                "磁链: $magnetLink",
            )
            ""
        }
    }
}
