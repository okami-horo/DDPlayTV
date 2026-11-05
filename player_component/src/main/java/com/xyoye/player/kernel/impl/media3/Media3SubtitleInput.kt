package com.xyoye.player.kernel.impl.media3

import androidx.media3.common.util.UnstableApi
import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.player.subtitle.backend.EmbeddedSubtitleSink
import java.util.WeakHashMap

interface Media3FontAttachmentSink {
    fun reserve(size: Int): Boolean

    fun cancel(size: Int)

    fun onFont(
        name: String,
        data: ByteArray
    )
}

/** Owns cached fonts and input generations for one Media3 source, independently of its View. */
@UnstableApi
internal class Media3SubtitleInput(
    private val singleFontLimit: Int = 16 * 1024 * 1024,
    private val mediaFontLimit: Int = 32 * 1024 * 1024,
    val requestHeaders: Map<String, String>? = null,
    val cacheEnabled: Boolean = false
) {
    private val lock = Any()
    private var mediaRevision = 0L
    private var target: EmbeddedSubtitleSink? = null
    private val tracks = mutableMapOf<String, Track>()
    private var selectedId: String? = null
    private var periodPositionUs = 0L

    private data class Sample(
        val data: ByteArray,
        val timeUs: Long,
        val durationUs: Long?
    )

    private val fonts = mutableListOf<Pair<String, ByteArray>>()
    private var fontBytes = 0
    private var pendingFontBytes = 0

    fun beginMedia(): Media3FontAttachmentSink =
        synchronized(lock) {
            mediaRevision++
            tracks.clear()
            synchronized(trackInputs) { trackInputs[this]?.clear() }
            selectedId = null
            periodPositionUs = 0L
            fonts.clear()
            fontBytes = 0
            pendingFontBytes = 0
            target?.onMediaChanged()
            fontSink(mediaRevision)
        }

    fun setSink(sink: EmbeddedSubtitleSink?) {
        synchronized(lock) {
            if (target === sink) return
            target = sink
            sink?.onMediaChanged()
            fonts.forEach { (name, data) -> sink?.onFont(name, data) }
            replayTrack()
        }
    }

    fun hasSink(): Boolean = synchronized(lock) { target != null }

    /** Extraction can run ahead of playback. Only the renderer's selected track reaches the View. */
    fun createTrack(id: String): EmbeddedSubtitleSink =
        synchronized(lock) {
            synchronized(trackInputs) { trackInputs.getOrPut(this) { mutableSetOf() }.add(id) }
            Track(id, mediaRevision).also { tracks[id] = it }
        }

    fun refreshSelectedTrack() {
        synchronized(lock) {
            if (selectedId == null) return
            target?.onFlush()
            replayTrack()
        }
    }

    fun selectTrack(
        id: String?,
        positionInWindowUs: Long = 0L
    ) {
        synchronized(lock) {
            if (selectedId == id && periodPositionUs == positionInWindowUs) return
            val hadSelection = selectedId != null
            selectedId = id
            periodPositionUs = positionInWindowUs
            if (hadSelection) target?.onRelease()
            replayTrack()
        }
    }

    private fun replayTrack() {
        val track = tracks[selectedId] ?: return
        if (!track.hasFormat) return
        target?.onFormat(track.codecPrivate)
        track.document?.let { target?.onDocument(it, periodPositionUs + track.documentOffsetUs) }
        track.samples.forEach { target?.onSample(it.data, it.timeUs + periodPositionUs, it.durationUs) }
    }

    private inner class Track(
        val id: String,
        val revision: Long
    ) : EmbeddedSubtitleSink {
        var codecPrivate: ByteArray? = null
        var hasFormat = false
        var document: ByteArray? = null
        var documentOffsetUs = 0L
        val samples = mutableListOf<Sample>()

        private fun isCurrent(): Boolean = revision == mediaRevision && tracks[id] === this

        override fun onFormat(codecPrivate: ByteArray?) {
            synchronized(lock) {
                if (!isCurrent()) return
                this.codecPrivate = codecPrivate?.copyOf()
                hasFormat = true
                document = null
                samples.clear()
                if (selectedId == id) target?.onFormat(this.codecPrivate)
            }
        }

        override fun onDocument(
            data: ByteArray,
            timeOffsetUs: Long
        ) {
            synchronized(lock) {
                if (!isCurrent()) return
                document = data.copyOf()
                documentOffsetUs = timeOffsetUs
                if (selectedId == id) target?.onDocument(document!!, timeOffsetUs + periodPositionUs)
            }
        }

        override fun onSample(
            data: ByteArray,
            timeUs: Long,
            durationUs: Long?
        ) {
            synchronized(lock) {
                if (!isCurrent()) return
                val sample = Sample(data.copyOf(), timeUs, durationUs)
                samples.add(sample)
                if (selectedId == id) target?.onSample(sample.data, timeUs + periodPositionUs, durationUs)
            }
        }

        override fun onFlush() {
            synchronized(lock) {
                if (!isCurrent()) return
                samples.clear()
                if (selectedId == id) target?.onFlush()
            }
        }

        override fun onRelease() {
            synchronized(lock) {
                if (!isCurrent()) return
                tracks.remove(id)
                synchronized(trackInputs) { trackInputs[this@Media3SubtitleInput]?.remove(id) }
                if (selectedId == id) target?.onRelease()
            }
        }
    }

    private fun fontSink(revision: Long): Media3FontAttachmentSink =
        object : Media3FontAttachmentSink {
            override fun reserve(size: Int): Boolean =
                synchronized(lock) {
                    if (revision != mediaRevision) return@synchronized false
                    if (size <= 0 || size > singleFontLimit || size > mediaFontLimit - fontBytes - pendingFontBytes) {
                        LogFacade.w(
                            LogModule.PLAYER,
                            "Media3SubtitleInput",
                            "font skipped: budget size=$size retained=$fontBytes pending=$pendingFontBytes"
                        )
                        return@synchronized false
                    }
                    pendingFontBytes += size
                    true
                }

            override fun cancel(size: Int) {
                synchronized(lock) {
                    if (revision == mediaRevision) pendingFontBytes -= size
                }
            }

            override fun onFont(
                name: String,
                data: ByteArray
            ) {
                synchronized(lock) {
                    if (revision != mediaRevision) return
                    pendingFontBytes -= data.size
                    if (fonts.any { it.first == name && it.second.contentEquals(data) }) return
                    fonts.add(name to data)
                    fontBytes += data.size
                    target?.onFont(name, data)
                }
            }
        }

    companion object {
        // ConcatenatingMediaSource2 exposes a synthetic window item without the child's tag.
        // Weak ownership lets a renderer resolve the actual extracted track without retaining sources.
        private val trackInputs = WeakHashMap<Media3SubtitleInput, MutableSet<String>>()

        fun findTrackInput(id: String?): Media3SubtitleInput? =
            synchronized(trackInputs) {
                if (id == null) {
                    null
                } else {
                    trackInputs.entries
                        .filter { id in it.value }
                        .singleOrNull()
                        ?.key
                }
            }
    }
}
