package com.xyoye.player.kernel.impl.media3

import androidx.media3.common.util.UnstableApi
import com.xyoye.common_component.log.LogFacade
import com.xyoye.common_component.log.model.LogModule
import com.xyoye.player.subtitle.backend.EmbeddedSubtitleSink

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
    private val mediaFontLimit: Int = 32 * 1024 * 1024
) {
    private val lock = Any()
    private var mediaRevision = 0L
    private var decoderRevision = 0L
    private var target: EmbeddedSubtitleSink? = null
    private var trackOwner: Any? = null
    private var codecPrivate: ByteArray? = null

    private data class Sample(
        val data: ByteArray,
        val timeUs: Long,
        val durationUs: Long?
    )

    private val samples = mutableListOf<Sample>()
    private val fonts = mutableListOf<Pair<String, ByteArray>>()
    private var fontBytes = 0
    private var pendingFontBytes = 0

    fun beginMedia(): Media3FontAttachmentSink =
        synchronized(lock) {
            mediaRevision++
            trackOwner = null
            codecPrivate = null
            samples.clear()
            fonts.clear()
            fontBytes = 0
            pendingFontBytes = 0
            target?.onMediaChanged()
            fontSink(mediaRevision)
        }

    fun setSink(sink: EmbeddedSubtitleSink?) {
        synchronized(lock) {
            if (target !== sink) {
                target = sink
                sink?.onMediaChanged()
                fonts.forEach { (name, data) -> sink?.onFont(name, data) }
            }
            if (trackOwner != null) {
                sink?.onFormat(codecPrivate)
                samples.forEach { sink?.onSample(it.data, it.timeUs, it.durationUs) }
            }
        }
    }

    fun hasSink(): Boolean = synchronized(lock) { target != null }

    fun createDecoderSink(allowDetached: Boolean = false): EmbeddedSubtitleSink? =
        synchronized(lock) {
            if (target == null && !allowDetached) return@synchronized null
            val revision = mediaRevision
            val decoder = ++decoderRevision
            val owner = Any()
            object : EmbeddedSubtitleSink {
                override fun onFormat(codecPrivate: ByteArray?) {
                    synchronized(lock) {
                        if (revision != mediaRevision || decoder != decoderRevision) return
                        trackOwner = owner
                        this@Media3SubtitleInput.codecPrivate = codecPrivate
                        samples.clear()
                        target?.onFormat(codecPrivate)
                    }
                }

                override fun onSample(
                    data: ByteArray,
                    timeUs: Long,
                    durationUs: Long?
                ) {
                    synchronized(lock) {
                        if (revision != mediaRevision || decoder != decoderRevision || trackOwner !== owner) return
                        samples.add(Sample(data, timeUs, durationUs))
                        target?.onSample(data, timeUs, durationUs)
                    }
                }

                override fun onFlush() {
                    synchronized(lock) {
                        if (revision == mediaRevision && decoder == decoderRevision && trackOwner === owner) {
                            samples.clear()
                            target?.onFlush()
                        }
                    }
                }

                override fun onRelease() {
                    synchronized(lock) {
                        if (revision != mediaRevision || decoder != decoderRevision || trackOwner !== owner) return
                        trackOwner = null
                        codecPrivate = null
                        samples.clear()
                        target?.onRelease()
                    }
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
}
