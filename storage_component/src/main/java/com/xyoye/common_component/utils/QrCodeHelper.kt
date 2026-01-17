package com.xyoye.common_component.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.huawei.hms.hmsscankit.ScanUtil
import com.huawei.hms.ml.scan.HmsBuildBitmapOption
import com.huawei.hms.ml.scan.HmsScan

object QrCodeHelper {
    fun createQrCode(
        context: Context,
        content: String,
        sizePx: Int,
        logoResId: Int? = null,
        bitmapColor: Int? = null,
        errorContext: String? = null
    ): Bitmap? {
        try {
            val logo =
                logoResId?.let {
                    BitmapFactory.decodeResource(context.resources, it)
                }
            val options =
                HmsBuildBitmapOption
                    .Creator()
                    .apply {
                        if (logo != null) {
                            setQRLogoBitmap(logo)
                        }
                        if (bitmapColor != null) {
                            setBitmapColor(bitmapColor)
                        }
                    }.create()

            return ScanUtil.buildBitmap(
                content,
                HmsScan.QRCODE_SCAN_TYPE,
                sizePx,
                sizePx,
                options,
            )
        } catch (e: Exception) {
            ErrorReportHelper.postCatchedExceptionWithContext(
                e,
                "QrCodeHelper",
                "createQrCode",
                (errorContext ?: "生成二维码失败") + "，content长度=${content.length}",
            )
            e.printStackTrace()
        }
        return null
    }
}
