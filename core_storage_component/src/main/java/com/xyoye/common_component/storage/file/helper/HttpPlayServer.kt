package com.xyoye.common_component.storage.file.helper

import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.common_component.network.RetrofitManager
import com.xyoye.common_component.network.config.Api
import com.xyoye.common_component.network.helper.AgentInterceptor
import com.xyoye.common_component.network.helper.OkHttpTlsConfigurer
import com.xyoye.common_component.network.helper.OkHttpTlsPolicy
import com.xyoye.common_component.network.helper.RedirectAuthorizationInterceptor
import com.xyoye.common_component.network.helper.UnsafeTlsApi
import com.xyoye.common_component.network.service.ExtendedService
import com.xyoye.common_component.storage.transport.HttpPlaybackTransport
import com.xyoye.common_component.storage.transport.PlaybackTransportLease
import com.xyoye.common_component.storage.transport.TransportAcquisitionException
import com.xyoye.common_component.storage.transport.TransportAcquisitionFailure
import com.xyoye.common_component.storage.transport.requireUnownedPlaybackUrl
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.net.URI
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** One loopback listener; each URL identifies a separately owned upstream context. */
class HttpPlayServer internal constructor(
    private val serviceOverride: ((UpstreamTlsPolicy) -> ExtendedService)? = null,
    listenPort: Int = 0
) : NanoHTTPD("127.0.0.1", listenPort) {
    private val startMutex = Mutex()
    private val contexts = ConcurrentHashMap<String, HttpUpstreamContext>()

    companion object {
        private const val DEFAULT_BINARY_CONTENT_TYPE = "application/octet-stream"

        @JvmStatic
        fun getInstance(): HttpPlayServer = Holder.instance
    }

    private object Holder {
        val instance: HttpPlayServer by lazy { HttpPlayServer() }
    }

    enum class UpstreamTlsPolicy {
        /**
         * 严格 TLS：不忽略证书校验、不忽略主机名校验（推荐；WebDAV 默认）。
         */
        STRICT,

        /**
         * 不安全 TLS：信任所有证书 + 忽略主机名校验（必须由用户显式开启）。
         */
        UNSAFE_TRUST_ALL
    }

    data class UpstreamSource(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        val contentType: String = DEFAULT_BINARY_CONTENT_TYPE,
        val contentLength: Long = -1L,
        val tlsPolicy: UpstreamTlsPolicy = UpstreamTlsPolicy.STRICT
    )

    private val strictClient: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .addInterceptor(AgentInterceptor())
            .addNetworkInterceptor(RedirectAuthorizationInterceptor())
            .build()
    }

    @OptIn(UnsafeTlsApi::class)
    private val unsafeTrustAllClient: OkHttpClient by lazy {
        OkHttpTlsConfigurer
            .apply(strictClient.newBuilder(), OkHttpTlsPolicy.UnsafeTrustAll)
            .build()
    }

    private val strictService: ExtendedService by lazy {
        RetrofitManager.createService(Api.PLACEHOLDER, strictClient, ExtendedService::class.java)
    }

    private val unsafeTrustAllService: ExtendedService by lazy {
        RetrofitManager.createService(Api.PLACEHOLDER, unsafeTrustAllClient, ExtendedService::class.java)
    }

    private fun selectExtendedService(tlsPolicy: UpstreamTlsPolicy): ExtendedService =
        when (tlsPolicy) {
            UpstreamTlsPolicy.STRICT -> strictService
            UpstreamTlsPolicy.UNSAFE_TRUST_ALL -> unsafeTrustAllService
        }

    override fun serve(session: IHTTPSession): Response {
        val leaseId = leaseIdFromPath(session.uri)
        val context = leaseId?.let { contexts[it] }
        return context?.serve(session) ?: newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "")
    }

    suspend fun acquireTransport(
        source: UpstreamSource,
        prePlayRangeMinIntervalMs: Long = 1000,
        fileName: String = "video",
        onRangeUnsupported: (() -> UpstreamSource?)? = null
    ): PlaybackTransportLease {
        requireUnownedPlaybackUrl(source.url)
        if (isLocalServerUrl(source.url)) throw TransportAcquisitionException(TransportAcquisitionFailure.UnsupportedTransport)
        if (!startSync()) throw TransportAcquisitionException(TransportAcquisitionFailure.ServerStartFailed)
        currentCoroutineContext().ensureActive()
        return register(source, prePlayRangeMinIntervalMs, fileName, onRangeUnsupported)
    }

    private fun register(
        source: UpstreamSource,
        rangeMinIntervalMs: Long,
        fileName: String,
        onRangeUnsupported: (() -> UpstreamSource?)?
    ): PlaybackTransportLease {
        val leaseId = UUID.randomUUID().toString()
        val context =
            HttpUpstreamContext(
                leaseId,
                source,
                rangeMinIntervalMs,
                onRangeUnsupported,
                serviceOverride ?: ::selectExtendedService
            )
        val encodedName = URLEncoder.encode(fileName.ifBlank { "video" }, "UTF-8")
        val url = "http://127.0.0.1:$listeningPort/lease/$leaseId/$encodedName"
        val lease =
            HttpPlaybackTransport(leaseId, url) {
                contexts.remove(leaseId, context)
                context.retire()
            }
        contexts[leaseId] = context
        return lease
    }

    fun setSeekEnabled(
        url: String,
        enabled: Boolean
    ): Boolean {
        val context = contextForUrl(url) ?: return false
        context.setSeekEnabled(enabled)
        return true
    }

    fun isServingUrl(url: String): Boolean = contextForUrl(url) != null

    internal fun isLocalServerUrl(url: String): Boolean =
        runCatching {
            val uri = URI(url)
            uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port == listeningPort
        }.getOrDefault(false)

    private fun contextForUrl(url: String): HttpUpstreamContext? {
        if (!isLocalServerUrl(url)) return null
        val path = runCatching { URI(url).rawPath }.getOrNull() ?: return null
        return leaseIdFromPath(path)?.let { contexts[it] }
    }

    private fun leaseIdFromPath(path: String): String? {
        val parts = path.split('/')
        return parts.getOrNull(2)?.takeIf {
            parts.getOrNull(1) == "lease" && parts.size >= 4 && it.isNotBlank()
        }
    }

    suspend fun startSync(timeoutMs: Long = 5000): Boolean =
        startMutex.withLock {
            if (wasStarted()) return@withLock true
            try {
                start()
                val started = awaitCondition(timeoutMs = timeoutMs) { wasStarted() }
                if (!started) stop()
                started
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                LogFacade.w(LogModule.STORAGE, "HttpPlayServer", "Playback server start failed")
                false
            }
        }

    override fun stop() {
        super.stop()
        val retired = contexts.values.toList()
        contexts.clear()
        retired.forEach { it.retire() }
    }
}
