package com.xyoye.player.kernel.impl.media3

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.model.LogModule
import java.nio.ByteBuffer

/**
 * 简易比特流修补：
 * - H.264：若 csd-0/1 以 `length-prefixed`（avcC）开头，则转换为 Annex-B（0x00000001 起始码）。
 * - HEVC：保持 hvcC，不强转（大多数硬解能直接消费 hvcC）；如后续需要可在此扩展。
 *
 * 说明：Media3 绝大多数 Extractor 已会填好 csd，以下转换仅针对少数“非规范 mp4/mkv”场景。
 */
@UnstableApi
object Media3BitstreamRewriter {

    private val START_CODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)

    fun rewrite(format: Format): Format {
        if (format.initializationData.isNullOrEmpty()) return format

        return when (format.sampleMimeType) {
            MimeTypes.VIDEO_H264 -> format.copyWithInitialisationData(
                format.initializationData.mapIndexed { index, data ->
                    if (index <= 1 && data.hasLengthPrefix()) lengthPrefixedToAnnexB(data) else data
                }
            )
            else -> format
        }
    }

    private fun lengthPrefixedToAnnexB(src: ByteArray): ByteArray {
        return try {
            val buffer = ByteBuffer.wrap(src)
            val out = ArrayList<Byte>()
            while (buffer.remaining() > 4) {
                val len = buffer.int
                if (len <= 0 || len > buffer.remaining()) break
                out.addAll(START_CODE.toList())
                val slice = ByteArray(len)
                buffer.get(slice)
                out.addAll(slice.toList())
            }
            if (out.isEmpty()) src else out.toByteArray()
        } catch (e: Exception) {
            LogFacade.w(LogModule.PLAYER, TAG, "AnnexB rewrite failed, fallback to original: ${e.message}")
            src
        }
    }

    private fun ByteArray.hasLengthPrefix(): Boolean {
        if (size < 4) return false
        // 粗略检测：若前 4 字节非起始码且长度值不超出范围，视为 length-prefixed
        if (this[0] == 0.toByte() && this[1] == 0.toByte() && this[2] == 0.toByte() && this[3] == 1.toByte()) {
            return false
        }
        val len = ((this[0].toInt() and 0xFF) shl 24) or
            ((this[1].toInt() and 0xFF) shl 16) or
            ((this[2].toInt() and 0xFF) shl 8) or
            (this[3].toInt() and 0xFF)
        return len in 1 until size
    }

    private fun Format.copyWithInitialisationData(data: List<ByteArray>): Format {
        return buildUpon().setInitializationData(data).build()
    }

    private const val TAG = "Media3BitstreamRewriter"
}
