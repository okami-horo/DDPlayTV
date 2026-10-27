package com.xyoye.player_component.playback.session

import com.xyoye.common_component.playback.addon.PlaybackAddon
import com.xyoye.common_component.playback.addon.PlaybackEvent
import com.xyoye.common_component.playback.addon.PlaybackIdentity
import com.xyoye.common_component.playback.addon.PlaybackReleasableAddon
import com.xyoye.common_component.playback.addon.PlaybackReplacementIntent
import com.xyoye.common_component.playback.handoff.ExternalUriDescriptor
import com.xyoye.common_component.playback.handoff.PlaybackAction
import com.xyoye.common_component.playback.handoff.PlaybackDescriptor
import com.xyoye.common_component.playback.handoff.PlaybackLaunchOptions
import com.xyoye.common_component.playback.handoff.PlaybackRequestInput
import com.xyoye.common_component.playback.handoff.PlaybackRequestOrigin
import com.xyoye.common_component.playback.handoff.PlaybackSessionVersion
import com.xyoye.common_component.playback.handoff.PlaybackSourceView
import com.xyoye.common_component.source.handoff.PlaybackResolutionResult
import com.xyoye.common_component.source.handoff.ResolvedPlayback
import com.xyoye.common_component.source.media.StorageVideoSource
import com.xyoye.common_component.storage.file.impl.LinkStorageFile
import com.xyoye.common_component.storage.impl.LinkStorage
import com.xyoye.common_component.storage.transport.PlaybackTransportLease
import com.xyoye.common_component.storage.transport.PlaybackTransportType
import com.xyoye.data_component.bean.PlaybackProfile
import com.xyoye.data_component.bean.PlaybackProfileSource
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.entity.PlayHistoryEntity
import com.xyoye.data_component.enums.MediaType
import com.xyoye.data_component.enums.PlayerType
import com.xyoye.data_component.media3.entity.Media3Capability
import com.xyoye.data_component.media3.entity.Media3PlaybackState
import com.xyoye.data_component.media3.entity.Media3SourceType
import com.xyoye.player_component.playback.handoff.HandoffBlobStorage
import com.xyoye.player_component.playback.handoff.HandoffCipher
import com.xyoye.player_component.playback.handoff.HandoffStoreResult
import com.xyoye.player_component.playback.handoff.PlaybackHandoffCodec
import com.xyoye.player_component.playback.handoff.PlaybackHandoffStore
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.Assert.*
import java.util.Base64

internal class PlaybackSessionTestFixture(
    dispatcher: CoroutineDispatcher,
    storeDispatcher: CoroutineDispatcher = dispatcher
) {
    val blob = SessionTestBlob()
    val cipher = SessionTestCipher()
    val backend = SessionTestBackend()
    val leases = mutableListOf<SessionTestLease>()
    var now = 1000L
    var sequence = 0
    var addonThrows = false
    var addonReleases = 0
    var addonFactory: ((PlaybackIdentity) -> PlaybackAddon)? = null
    val store = PlaybackHandoffStore(blob, cipher, { now }, { "request-${++sequence}" }, storeDispatcher)
    var resolve: suspend (PlaybackDescriptor) -> PlaybackResolutionResult = { PlaybackResolutionResult.Success(resolved(it)) }
    var resolveReplacement: suspend (PlaybackDescriptor, PlaybackProfile, PlaybackReplacementIntent) -> PlaybackResolutionResult =
        { descriptor, _, _ -> PlaybackResolutionResult.Success(resolved(descriptor)) }
    val session =
        ActivePlaybackSession(store, backend, { resolve(it) }, dispatcher, dispatcher, { descriptor, profile, intent ->
            resolveReplacement(descriptor, profile, intent)
        })

    fun descriptor(name: String) = ExternalUriDescriptor("https://example.test/$name", Media3SourceType.STREAM)

    fun name(descriptor: PlaybackDescriptor) = (descriptor as ExternalUriDescriptor).uri.substringAfterLast('/')

    suspend fun submit(
        name: String,
        autoplay: Boolean = true
    ): String =
        (
            store.submit(
                PlaybackRequestInput(
                    descriptor(name),
                    PlaybackLaunchOptions(name, Media3SourceType.STREAM, autoplay = autoplay),
                    PlaybackRequestOrigin.EXTERNAL_INTENT
                )
            ) as HandoffStoreResult.Success
        ).value.requestId

    fun decode(value: String) = PlaybackHandoffCodec().decode(cipher.decrypt(value))

    fun state() =
        blob.data?.let(::decode) ?: com.xyoye.player_component.playback.handoff
            .PlaybackHandoffState()

    fun resolved(descriptor: PlaybackDescriptor): ResolvedPlayback {
        val url = (descriptor as ExternalUriDescriptor).uri
        val storage = LinkStorage(MediaLibraryEntity.STREAM.copy(url = url))
        val file = LinkStorageFile(storage, url)
        file.playHistory =
            PlayHistoryEntity(videoName = name(descriptor), url = url, mediaType = MediaType.STREAM_LINK, videoPosition = 123)
        val lease = SessionTestLease(url).also(leases::add)
        val addon =
            addonFactory?.invoke(
                PlaybackIdentity(
                    0,
                    file.uniqueKey(),
                    MediaType.STREAM_LINK,
                    url,
                    name(descriptor),
                    url
                )
            ) ?: object : PlaybackReleasableAddon {
                override val addonId = "fixture"

                override fun onEvent(event: PlaybackEvent) = Unit

                override fun onRelease() {
                    addonReleases++
                    if (addonThrows) error("fixture cleanup")
                }
            }
        val source =
            StorageVideoSource(
                url,
                file,
                listOf(file),
                null,
                null,
                null,
                PlaybackProfile(PlayerType.TYPE_EXO_PLAYER, PlaybackProfileSource.GLOBAL),
                lease,
                addon
            )
        lease.beforeRelease = { assertFalse("Released a source still borrowed by the backend", backend.isAttached(source)) }
        return ResolvedPlayback(
            source,
            descriptor,
            PlaybackSourceView(name(descriptor), file.uniqueKey(), MediaType.STREAM_LINK, name(descriptor), 0),
            listOf(descriptor)
        )
    }
}

internal class SessionTestBackend : PlaybackSessionBackend {
    val applied = mutableListOf<String>()
    val attached = mutableSetOf<StorageVideoSource>()
    var current: StorageVideoSource? = null
    var queued: PreparedPlayback? = null
    var failAfterAttach = false
    var retainOld = false
    var failDetach = false
    var blockRemoval = false
    var rejectEnqueue = false
    var playbackProgress = PlaybackProgress(0, true)
    var backendState =
        BackendPlaybackState(
            Media3PlaybackState.PLAYING,
            setOf(Media3Capability.PLAY, Media3Capability.PAUSE, Media3Capability.SEEK)
        )
    val actions = mutableListOf<PlaybackAction>()
    var beforeApply: (PreparedPlayback, PlaybackSessionVersion) -> Unit = { _, _ -> }

    override fun apply(
        playback: PreparedPlayback,
        version: PlaybackSessionVersion,
        progress: PlaybackProgress
    ) {
        beforeApply(playback, version)
        if (!retainOld) attached.clear()
        current = playback.source
        playbackProgress = progress
        attached.add(playback.source)
        applied.add(playback.source.getVideoTitle())
        if (failAfterAttach) error("failure after attachment")
    }

    override fun currentSource() = current

    override fun isAttached(source: StorageVideoSource) = source in attached

    override fun progress() = playbackProgress

    override fun localState() = backendState

    override fun execute(action: PlaybackAction): Boolean {
        actions += action
        when (action) {
            PlaybackAction.Pause -> {
                playbackProgress = playbackProgress.copy(playWhenReady = false)
                backendState = backendState.copy(state = Media3PlaybackState.PAUSED)
            }
            PlaybackAction.Play -> {
                playbackProgress = playbackProgress.copy(playWhenReady = true)
                backendState = backendState.copy(state = Media3PlaybackState.PLAYING)
            }
            is PlaybackAction.Seek -> playbackProgress = playbackProgress.copy(positionMs = action.positionMs)
            else -> return false
        }
        return true
    }

    var constraints = PlaybackPositionConstraints()

    override fun positionConstraints(source: StorageVideoSource) = constraints

    override fun enqueue(
        playback: PreparedPlayback,
        expected: PlaybackSessionVersion
    ): Boolean {
        queued = playback
        attached.add(playback.source)
        return !rejectEnqueue
    }

    override fun reconcileCandidate(playback: PreparedPlayback): QueuedPlaybackStatus {
        if (current === playback.source) {
            queued = null
            return QueuedPlaybackStatus.CURRENT
        }
        if (blockRemoval) return QueuedPlaybackStatus.STILL_ATTACHED
        attached.remove(playback.source)
        queued = null
        return QueuedPlaybackStatus.DETACHED
    }

    fun transition() {
        current = queued!!.source
        playbackProgress = PlaybackProgress(0, true)
        attached.clear()
        attached.add(current!!)
    }

    override fun detach() {
        if (failDetach) error("detach failure")
        current = null
        queued = null
        attached.clear()
    }
}

internal class SessionTestLease(
    override val playUrl: String
) : PlaybackTransportLease {
    override val leaseId = "fixture"
    override val transportType = PlaybackTransportType.DIRECT
    var releases = 0
    var beforeRelease: () -> Unit = {}
    override val isReleased get() = releases > 0

    override fun release() {
        beforeRelease()
        releases++
    }
}

internal class SessionTestBlob : HandoffBlobStorage {
    @Volatile var data: String? = null

    @Volatile var failWrite = false
    var beforeWrite: (String?) -> Unit = {}

    override fun read() = data

    override fun write(value: String?): Boolean {
        if (failWrite) return false
        beforeWrite(value)
        data = value
        return true
    }
}

/** Store encryption itself has real AES-GCM coverage in PlaybackHandoffStoreTest. */
internal class SessionTestCipher : HandoffCipher {
    var fail = false

    override fun encrypt(plain: String): String? = if (fail) null else Base64.getEncoder().encodeToString(plain.toByteArray())

    override fun decrypt(encrypted: String): String = String(Base64.getDecoder().decode(encrypted))
}
