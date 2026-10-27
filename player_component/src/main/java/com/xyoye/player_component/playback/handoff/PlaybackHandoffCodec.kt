package com.xyoye.player_component.playback.handoff

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.xyoye.common_component.playback.handoff.ExternalUriDescriptor
import com.xyoye.common_component.playback.handoff.PlayHistoryDescriptor
import com.xyoye.common_component.playback.handoff.PlaybackDescriptor
import com.xyoye.common_component.playback.handoff.PlaybackRequestInput
import com.xyoye.common_component.playback.handoff.ScreencastDescriptor
import com.xyoye.common_component.playback.handoff.StoredLibraryFileDescriptor
import com.xyoye.common_component.playback.handoff.TorrentDescriptor
import okio.Buffer

/** Direct adapters only: generic JSON helpers log parse failures and must not see this plaintext. */
internal class PlaybackHandoffCodec {
    private val moshi =
        Moshi
            .Builder()
            .add(DescriptorAdapterFactory)
            .addLast(KotlinJsonAdapterFactory())
            .build()
    private val stateAdapter = moshi.adapter(PlaybackHandoffState::class.java)
    private val inputAdapter = moshi.adapter(PlaybackRequestInput::class.java)
    private val targetAdapter = moshi.adapter(PlaybackSnapshotTarget::class.java)

    fun encode(state: PlaybackHandoffState): String = stateAdapter.toJson(state)

    fun decode(json: String): PlaybackHandoffState {
        val reader = JsonReader.of(Buffer().writeUtf8(json))
        var schema: Int? = null
        reader.beginObject()
        while (reader.hasNext()) {
            if (reader.nextName() == "schemaVersion") schema = reader.nextInt() else reader.skipValue()
        }
        reader.endObject()
        if (schema == null) throw HandoffCodecException(HandoffStoreFailureKind.INVALID_PAYLOAD)
        if (schema != 1) throw HandoffCodecException(HandoffStoreFailureKind.UNSUPPORTED_SCHEMA)
        val state = stateAdapter.fromJson(json) ?: throw HandoffCodecException(HandoffStoreFailureKind.INVALID_PAYLOAD)
        if (state.verification != null && state.verification.requestId != state.pending?.requestId) {
            throw HandoffCodecException(HandoffStoreFailureKind.INVALID_PAYLOAD)
        }
        return state
    }

    fun copy(input: PlaybackRequestInput): PlaybackRequestInput = requireNotNull(inputAdapter.fromJson(inputAdapter.toJson(input)))

    fun copy(target: PlaybackSnapshotTarget): PlaybackSnapshotTarget = requireNotNull(targetAdapter.fromJson(targetAdapter.toJson(target)))

    private object DescriptorAdapterFactory : JsonAdapter.Factory {
        private val types =
            mapOf(
                "library" to StoredLibraryFileDescriptor::class.java,
                "history" to PlayHistoryDescriptor::class.java,
                "torrent" to TorrentDescriptor::class.java,
                "external" to ExternalUriDescriptor::class.java,
                "screencast" to ScreencastDescriptor::class.java
            )

        override fun create(
            type: java.lang.reflect.Type,
            annotations: Set<Annotation>,
            moshi: Moshi
        ): JsonAdapter<*>? {
            if (type != PlaybackDescriptor::class.java || annotations.isNotEmpty()) return null
            return object : JsonAdapter<PlaybackDescriptor>() {
                override fun toJson(
                    writer: JsonWriter,
                    value: PlaybackDescriptor?
                ) {
                    val descriptor = requireNotNull(value)
                    val entry =
                        types.entries.firstOrNull { it.value == descriptor.javaClass }
                            ?: throw JsonDataException("Unsupported playback descriptor")
                    writer
                        .beginObject()
                        .name("kind")
                        .value(entry.key)
                        .name("value")
                    moshi.adapter<Any>(entry.value).toJson(writer, descriptor)
                    writer.endObject()
                }

                override fun fromJson(reader: JsonReader): PlaybackDescriptor? {
                    val envelope = reader.readJsonValue() as? Map<*, *> ?: throw JsonDataException("Invalid playback descriptor")
                    val subtype = types[envelope["kind"]] ?: throw JsonDataException("Unsupported playback descriptor")
                    return moshi.adapter<Any>(subtype).fromJsonValue(envelope["value"]) as? PlaybackDescriptor
                        ?: throw JsonDataException("Missing playback descriptor")
                }
            }.nullSafe()
        }
    }
}

internal class HandoffCodecException(
    val kind: HandoffStoreFailureKind
) : Exception(kind.name)
