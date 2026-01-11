package com.xyoye.common_component.bilibili.live.danmaku

import com.xyoye.data_component.data.bilibili.LiveDanmakuEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

@RunWith(RobolectricTestRunner::class)
class LiveDanmakuCodecTest {
    @Test
    fun encodeAndDecodeKeepsHeaderAndBody() {
        val body = "{\"cmd\":\"DANMU_MSG\"}".toByteArray(Charsets.UTF_8)
        val encoded =
            LiveDanmakuPacketCodec.encode(
                operation = LiveDanmakuPacketCodec.OP_COMMAND,
                protocolVer = LiveDanmakuPacketCodec.PROTOCOL_VER_NORMAL,
                sequence = 1,
                body = body,
            )

        val decoded = LiveDanmakuPacketCodec.decodeAll(encoded)
        assertEquals(1, decoded.size)
        val packet = decoded.first()
        assertEquals(LiveDanmakuPacketCodec.OP_COMMAND, packet.operation)
        assertEquals(LiveDanmakuPacketCodec.PROTOCOL_VER_NORMAL, packet.protocolVer)
        assertTrue(packet.body.contentEquals(body))
    }

    @Test
    fun decodeAllSplitsMultiplePackets() {
        val p1 =
            LiveDanmakuPacketCodec.encode(
                operation = LiveDanmakuPacketCodec.OP_HEARTBEAT,
                protocolVer = LiveDanmakuPacketCodec.PROTOCOL_VER_HEARTBEAT,
                sequence = 1,
                body = ByteArray(0),
            )
        val p2 =
            LiveDanmakuPacketCodec.encode(
                operation = LiveDanmakuPacketCodec.OP_HEARTBEAT,
                protocolVer = LiveDanmakuPacketCodec.PROTOCOL_VER_HEARTBEAT,
                sequence = 2,
                body = ByteArray(0),
            )
        val merged = p1 + p2

        val decoded = LiveDanmakuPacketCodec.decodeAll(merged)
        assertEquals(2, decoded.size)
        assertEquals(1, decoded[0].sequence)
        assertEquals(2, decoded[1].sequence)
    }

    @Test
    fun decodeAllInflatesZlibPackets() {
        val innerBody = "{\"cmd\":\"DANMU_MSG\"}".toByteArray(Charsets.UTF_8)
        val innerPacket =
            LiveDanmakuPacketCodec.encode(
                operation = LiveDanmakuPacketCodec.OP_COMMAND,
                protocolVer = LiveDanmakuPacketCodec.PROTOCOL_VER_NORMAL,
                sequence = 1,
                body = innerBody,
            )
        val compressed = deflateZlib(innerPacket)
        val outerPacket =
            LiveDanmakuPacketCodec.encode(
                operation = LiveDanmakuPacketCodec.OP_COMMAND,
                protocolVer = LiveDanmakuPacketCodec.PROTOCOL_VER_ZLIB,
                sequence = 1,
                body = compressed,
            )

        val decoded = LiveDanmakuPacketCodec.decodeAll(outerPacket)
        assertEquals(1, decoded.size)
        val packet = decoded.first()
        assertEquals(LiveDanmakuPacketCodec.OP_COMMAND, packet.operation)
        assertEquals(LiveDanmakuPacketCodec.PROTOCOL_VER_NORMAL, packet.protocolVer)
        assertTrue(packet.body.contentEquals(innerBody))
    }

    @Test
    fun parseDanmuMsgExtractsTextAndScore() {
        val json =
            // Minimal structure required by parser: info[0][1/3/4/15], info[1], info[2][0/1]
            """
            {
              "cmd": "DANMU_MSG",
              "info": [
                [0, 5, 25, 9920249, 1748266797000, 0, 0, "", 0, 0, 0, "", 0, "", "", { "extra": "{\"recommend_score\":3}" }],
                "白花300块[热]",
                [0, "*user", 0, 0, 0, 0, 0]
              ]
            }
            """.trimIndent()

        val event = LiveDanmakuCommandParser.parseCommand(json)
        assertNotNull(event)
        val danmaku = event as LiveDanmakuEvent.Danmaku
        assertEquals("白花300块[热]", danmaku.text)
        assertEquals(LiveDanmakuEvent.DanmakuMode.TOP, danmaku.mode)
        assertEquals(3, danmaku.recommendScore)
        assertEquals(0L, danmaku.userId)
        assertEquals("*user", danmaku.userName)
        assertEquals(1748266797000L, danmaku.timestampMs)
        assertEquals(0xFF000000.toInt() or 9920249, danmaku.color)
    }

    private fun deflateZlib(input: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(input)
        deflater.finish()
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            if (count <= 0) break
            output.write(buffer, 0, count)
        }
        deflater.end()
        return output.toByteArray()
    }
}
