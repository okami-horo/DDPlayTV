package com.xyoye.common_component.bilibili.live.danmaku

import com.xyoye.common_component.bilibili.auth.BilibiliAuthStore
import com.xyoye.common_component.bilibili.net.BilibiliOkHttpClientFactory
import com.xyoye.common_component.bilibili.repository.BilibiliRepository
import com.xyoye.common_component.utils.ErrorReportHelper
import com.xyoye.data_component.data.bilibili.BilibiliLiveDanmuHost
import com.xyoye.data_component.data.bilibili.LiveDanmakuEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.pow
import kotlin.random.Random

class LiveDanmakuSocketClient(
    private val storageKey: String,
    private val roomId: Long,
    private val scope: CoroutineScope,
    private val listener: Listener
) {
    interface Listener {
        fun onStateChanged(state: LiveDanmakuState)

        fun onEvent(event: LiveDanmakuEvent)
    }

    sealed interface LiveDanmakuState {
        data object Connecting : LiveDanmakuState

        data class Connected(
            val host: String
        ) : LiveDanmakuState

        data class Reconnecting(
            val attempt: Int,
            val delayMs: Long
        ) : LiveDanmakuState

        data class Disconnected(
            val reason: String?
        ) : LiveDanmakuState

        data class Error(
            val message: String
        ) : LiveDanmakuState
    }

    private val repository = BilibiliRepository(storageKey)
    private val okHttpClient = BilibiliOkHttpClientFactory.create(storageKey)

    private val sequence = AtomicInteger(1)
    private val verified = AtomicBoolean(false)
    private val lastHeartbeatReplyAt = AtomicLong(0L)
    private val stopped = AtomicBoolean(false)

    private var connectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var decodeJob: Job? = null
    private var webSocket: WebSocket? = null

    private val binaryChannel =
        Channel<ByteArray>(
            capacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    fun start() {
        if (connectJob?.isActive == true) return
        stopped.set(false)
        connectJob =
            scope.launch(Dispatchers.IO) {
                val info =
                    repository
                        .liveDanmuInfo(roomId)
                        .getOrElse { throwable ->
                            listener.onStateChanged(LiveDanmakuState.Error(throwable.message ?: "获取弹幕参数失败"))
                            return@launch
                        }

                val resolvedRoomId = info.roomId
                val token = info.token
                val hosts = normalizeHosts(info.hostList)
                if (token.isBlank() || hosts.isEmpty()) {
                    listener.onStateChanged(LiveDanmakuState.Error("直播弹幕参数为空"))
                    return@launch
                }

                val auth = BilibiliAuthStore.read(storageKey)
                val uid = auth.mid ?: 0L

                var attempt = 0
                var hostIndex = 0

                while (isActive && !stopped.get()) {
                    val host = hosts[hostIndex % hosts.size]
                    val delayMs =
                        if (attempt <= 0) {
                            0L
                        } else {
                            calculateBackoffMs(attempt)
                        }

                    if (attempt <= 0) {
                        listener.onStateChanged(LiveDanmakuState.Connecting)
                    } else {
                        listener.onStateChanged(LiveDanmakuState.Reconnecting(attempt = attempt, delayMs = delayMs))
                        delay(delayMs)
                    }

                    val closedSignal = AtomicBoolean(false)
                    verified.set(false)
                    lastHeartbeatReplyAt.set(0L)

                    startDecodeLoop()

                    val request = Request.Builder().url(buildWssUrl(host)).build()
                    val ws =
                        okHttpClient.newWebSocket(
                            request,
                            object : WebSocketListener() {
                                override fun onOpen(
                                    webSocket: WebSocket,
                                    response: Response
                                ) {
                                    // auth within 5s
                                    val authJson =
                                        JSONObject()
                                            .put("uid", uid)
                                            .put("roomid", resolvedRoomId)
                                            .put("protover", LiveDanmakuPacketCodec.PROTOCOL_VER_ZLIB)
                                            .put("platform", "web")
                                            .put("type", 2)
                                            .put("key", token)
                                            .toString()

                                    val packet =
                                        LiveDanmakuPacketCodec.encode(
                                            operation = LiveDanmakuPacketCodec.OP_AUTH,
                                            protocolVer = LiveDanmakuPacketCodec.PROTOCOL_VER_HEARTBEAT,
                                            sequence = sequence.getAndIncrement(),
                                            body = authJson.toByteArray(Charsets.UTF_8),
                                        )
                                    webSocket.send(ByteString.of(*packet))
                                }

                                override fun onMessage(
                                    webSocket: WebSocket,
                                    bytes: ByteString
                                ) {
                                    binaryChannel.trySend(bytes.toByteArray())
                                }

                                override fun onMessage(
                                    webSocket: WebSocket,
                                    text: String
                                ) {
                                    // Keep compatibility: treat as JSON command if server sends text frames.
                                    val event = LiveDanmakuCommandParser.parseCommand(text) ?: return
                                    listener.onEvent(event)
                                }

                                override fun onClosing(
                                    webSocket: WebSocket,
                                    code: Int,
                                    reason: String
                                ) {
                                    webSocket.close(code, reason)
                                }

                                override fun onClosed(
                                    webSocket: WebSocket,
                                    code: Int,
                                    reason: String
                                ) {
                                    closedSignal.set(true)
                                    listener.onStateChanged(LiveDanmakuState.Disconnected(reason.ifBlank { null }))
                                }

                                override fun onFailure(
                                    webSocket: WebSocket,
                                    t: Throwable,
                                    response: Response?
                                ) {
                                    closedSignal.set(true)
                                    ErrorReportHelper.postCatchedExceptionWithContext(
                                        t,
                                        "LiveDanmakuSocketClient",
                                        "onFailure",
                                        "roomId=$roomId host=${host.host}",
                                    )
                                    listener.onStateChanged(
                                        LiveDanmakuState.Disconnected(
                                            reason = t.message ?: "WebSocket 连接失败",
                                        ),
                                    )
                                }
                            },
                        )

                    this@LiveDanmakuSocketClient.webSocket = ws

                    // Wait until closed (polling) to keep coroutine cancellable.
                    while (isActive && !stopped.get() && !closedSignal.get()) {
                        delay(300)
                    }

                    stopHeartbeat()
                    stopDecodeLoop()
                    this@LiveDanmakuSocketClient.webSocket = null

                    if (stopped.get()) {
                        break
                    }

                    attempt++
                    hostIndex++
                }
            }
    }

    fun stop() {
        stopped.set(true)
        stopHeartbeat()
        stopDecodeLoop()
        webSocket?.close(1000, "stop")
        webSocket = null
        connectJob?.cancel()
        connectJob = null
    }

    private fun startDecodeLoop() {
        if (decodeJob?.isActive == true) return
        decodeJob =
            scope.launch(Dispatchers.IO) {
                for (bytes in binaryChannel) {
                    handleBinary(bytes)
                }
            }
    }

    private fun stopDecodeLoop() {
        decodeJob?.cancel()
        decodeJob = null
        while (binaryChannel.tryReceive().getOrNull() != null) {
            // drain
        }
    }

    private fun handleBinary(bytes: ByteArray) {
        val packets = LiveDanmakuPacketCodec.decodeAll(bytes)
        packets.forEach { packet ->
            when (packet.operation) {
                LiveDanmakuPacketCodec.OP_AUTH_REPLY -> handleAuthReply(packet)
                LiveDanmakuPacketCodec.OP_HEARTBEAT_REPLY -> handleHeartbeatReply(packet)
                LiveDanmakuPacketCodec.OP_COMMAND -> handleCommand(packet)
            }
        }
    }

    private fun handleAuthReply(packet: LiveDanmakuPacket) {
        val json = packet.body.toString(Charsets.UTF_8)
        val code = runCatching { JSONObject(json).optInt("code", -1) }.getOrNull() ?: -1
        if (code == 0) {
            verified.set(true)
            lastHeartbeatReplyAt.set(System.currentTimeMillis())
            listener.onStateChanged(LiveDanmakuState.Connected(host = webSocketHost()))
            startHeartbeat()
        } else {
            webSocket?.close(1008, "auth failed")
        }
    }

    private fun handleHeartbeatReply(packet: LiveDanmakuPacket) {
        // body: uint32 popularity
        val body = packet.body
        if (body.size >= 4) {
            val value =
                ((body[0].toLong() and 0xFF) shl 24) or
                    ((body[1].toLong() and 0xFF) shl 16) or
                    ((body[2].toLong() and 0xFF) shl 8) or
                    (body[3].toLong() and 0xFF)
            listener.onEvent(LiveDanmakuEvent.Popularity(value = value))
        }
        lastHeartbeatReplyAt.set(System.currentTimeMillis())
    }

    private fun handleCommand(packet: LiveDanmakuPacket) {
        val json = packet.body.toString(Charsets.UTF_8)
        val event = LiveDanmakuCommandParser.parseCommand(json) ?: return
        listener.onEvent(event)
    }

    private fun startHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob =
            scope.launch(Dispatchers.IO) {
                while (isActive && !stopped.get()) {
                    if (!verified.get()) {
                        delay(200)
                        continue
                    }

                    val ws = webSocket ?: break
                    val heartbeat =
                        LiveDanmakuPacketCodec.encode(
                            operation = LiveDanmakuPacketCodec.OP_HEARTBEAT,
                            protocolVer = LiveDanmakuPacketCodec.PROTOCOL_VER_HEARTBEAT,
                            sequence = sequence.getAndIncrement(),
                            body = ByteArray(0),
                        )
                    ws.send(ByteString.of(*heartbeat))

                    val now = System.currentTimeMillis()
                    val last = lastHeartbeatReplyAt.get()
                    if (last > 0 && now - last > HEARTBEAT_TIMEOUT_MS) {
                        ws.close(1001, "heartbeat timeout")
                        break
                    }

                    delay(HEARTBEAT_INTERVAL_MS)
                }
            }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun buildWssUrl(host: BilibiliLiveDanmuHost): String = "wss://${host.host}:${host.wssPort}/sub"

    private fun normalizeHosts(hosts: List<BilibiliLiveDanmuHost>): List<BilibiliLiveDanmuHost> =
        hosts
            .filter { it.host.isNotBlank() && it.wssPort > 0 }
            .distinctBy { "${it.host}:${it.wssPort}" }

    private fun calculateBackoffMs(attempt: Int): Long {
        val capped = attempt.coerceIn(1, 6)
        val base = (1000.0 * 2.0.pow(capped - 1)).toLong().coerceAtMost(30_000L)
        val jitter = Random.nextLong(from = 0, until = 800)
        return (base + jitter).coerceAtMost(30_000L)
    }

    private fun webSocketHost(): String =
        runCatching {
            webSocket
                ?.request()
                ?.url
                ?.host
                .orEmpty()
        }.getOrDefault("")

    private companion object {
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        private const val HEARTBEAT_TIMEOUT_MS = 70_000L
    }
}
