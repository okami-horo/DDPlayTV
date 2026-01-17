package com.xyoye.common_component.bilibili.live.danmaku

import com.xyoye.common_component.utils.ErrorReportHelper
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.DataFormatException
import java.util.zip.Inflater

object LiveDanmakuPacketCodec {
    private const val HEADER_SIZE = 16

    // protocolVer
    const val PROTOCOL_VER_NORMAL = 0
    const val PROTOCOL_VER_HEARTBEAT = 1
    const val PROTOCOL_VER_ZLIB = 2
    const val PROTOCOL_VER_BROTLI = 3

    // operation
    const val OP_HEARTBEAT = 2
    const val OP_HEARTBEAT_REPLY = 3
    const val OP_COMMAND = 5
    const val OP_AUTH = 7
    const val OP_AUTH_REPLY = 8

    fun encode(
        operation: Int,
        protocolVer: Int,
        sequence: Int,
        body: ByteArray
    ): ByteArray {
        val headerLen = HEADER_SIZE
        val packetLen = headerLen + body.size

        val buffer =
            ByteBuffer
                .allocate(packetLen)
                .order(ByteOrder.BIG_ENDIAN)

        buffer.putInt(packetLen)
        buffer.putShort(headerLen.toShort())
        buffer.putShort(protocolVer.toShort())
        buffer.putInt(operation)
        buffer.putInt(sequence)
        buffer.put(body)
        return buffer.array()
    }

    fun decodeAll(packetBytes: ByteArray): List<LiveDanmakuPacket> = decodeAllInternal(packetBytes, depth = 0)

    private fun decodeAllInternal(
        packetBytes: ByteArray,
        depth: Int
    ): List<LiveDanmakuPacket> {
        if (depth > 2) return emptyList()
        val packets = decodeRaw(packetBytes)
        if (packets.isEmpty()) return emptyList()

        val flattened = ArrayList<LiveDanmakuPacket>(packets.size)
        packets.forEach { packet ->
            when (packet.protocolVer) {
                PROTOCOL_VER_ZLIB -> {
                    val inflated = inflateZlibOrNull(packet.body) ?: return@forEach
                    flattened.addAll(decodeAllInternal(inflated, depth = depth + 1))
                }

                PROTOCOL_VER_BROTLI -> {
                    // MVP: avoid brotli dependency by using protover=2; ignore unsupported packets safely.
                }

                else -> flattened.add(packet)
            }
        }
        return flattened
    }

    private fun decodeRaw(packetBytes: ByteArray): List<LiveDanmakuPacket> {
        if (packetBytes.size < HEADER_SIZE) return emptyList()

        val result = ArrayList<LiveDanmakuPacket>()
        var offset = 0

        while (offset + HEADER_SIZE <= packetBytes.size) {
            val header =
                ByteBuffer
                    .wrap(packetBytes, offset, HEADER_SIZE)
                    .order(ByteOrder.BIG_ENDIAN)

            val packetLen = header.int
            val headerLen = header.short.toInt() and 0xFFFF
            val protocolVer = header.short.toInt() and 0xFFFF
            val operation = header.int
            val sequence = header.int

            if (packetLen <= 0 || headerLen <= 0 || offset + packetLen > packetBytes.size) {
                break
            }
            val bodyLen = packetLen - headerLen
            if (bodyLen < 0 || offset + headerLen + bodyLen > packetBytes.size) {
                break
            }
            val body =
                if (bodyLen == 0) {
                    ByteArray(0)
                } else {
                    packetBytes.copyOfRange(offset + headerLen, offset + headerLen + bodyLen)
                }

            result.add(
                LiveDanmakuPacket(
                    packetLen = packetLen,
                    headerLen = headerLen,
                    protocolVer = protocolVer,
                    operation = operation,
                    sequence = sequence,
                    body = body,
                ),
            )
            offset += packetLen
        }

        return result
    }

    private fun inflateZlibOrNull(input: ByteArray): ByteArray? {
        if (input.isEmpty()) return ByteArray(0)
        val inflater = Inflater()
        inflater.setInput(input)

        val outputStream = ByteArrayOutputStream(input.size.coerceAtLeast(256))
        val buffer = ByteArray(4096)
        try {
            while (!inflater.finished() && !inflater.needsInput()) {
                val count = inflater.inflate(buffer)
                if (count <= 0) break
                outputStream.write(buffer, 0, count)
            }
            return outputStream.toByteArray()
        } catch (e: DataFormatException) {
            ErrorReportHelper.postCatchedException(
                e,
                "LiveDanmakuPacketCodec.inflateZlibOrNull",
                "zlib 解压失败",
            )
            return null
        } finally {
            inflater.end()
            outputStream.close()
        }
    }
}
