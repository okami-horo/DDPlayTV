package com.xyoye.common_component.utils.moshi

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.xyoye.data_component.helper.moshi.EmptyArrayToNull
import java.lang.reflect.Type

object EmptyArrayToNullAdapterFactory : JsonAdapter.Factory {
    override fun create(
        type: Type,
        annotations: Set<Annotation>,
        moshi: Moshi
    ): JsonAdapter<*>? {
        val delegateAnnotations =
            annotations.filterNot { it.annotationClass.java == EmptyArrayToNull::class.java }.toSet()
        if (delegateAnnotations.size == annotations.size) {
            return null
        }

        val delegate = moshi.nextAdapter<Any>(this, type, delegateAnnotations)
        return EmptyArrayToNullJsonAdapter(delegate)
    }

    private class EmptyArrayToNullJsonAdapter<T>(
        private val delegate: JsonAdapter<T>
    ) : JsonAdapter<T>() {
        override fun fromJson(reader: JsonReader): T? =
            when (reader.peek()) {
                JsonReader.Token.BEGIN_ARRAY -> {
                    reader.beginArray()

                    val value =
                        if (!reader.hasNext()) {
                            null
                        } else if (reader.peek() == JsonReader.Token.BEGIN_OBJECT) {
                            delegate.fromJson(reader)
                        } else {
                            reader.skipValue()
                            null
                        }

                    while (reader.hasNext()) {
                        reader.skipValue()
                    }
                    reader.endArray()

                    value
                }

                else -> delegate.fromJson(reader)
            }

        override fun toJson(
            writer: JsonWriter,
            value: T?
        ) {
            delegate.toJson(writer, value)
        }
    }
}

