package com.xyoye.common_component.utils

import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.xyoye.common_component.utils.moshi.EmptyArrayToNullAdapterFactory
import com.xyoye.common_component.utils.moshi.NullToEmptyStringAdapter
import com.xyoye.common_component.utils.moshi.NullToIntZeroAdapter
import com.xyoye.common_component.utils.moshi.NullToLongZeroAdapter
import java.io.IOException

/**
 * Created by xyoye on 2021/3/26.
 */

object JsonHelper {
    val MO_SHI: Moshi =
        Moshi
            .Builder()
            .add(EmptyArrayToNullAdapterFactory)
            .add(NullToEmptyStringAdapter)
            .add(NullToLongZeroAdapter)
            .add(NullToIntZeroAdapter)
            .add(KotlinJsonAdapterFactory())
            .build()

    inline fun <reified T> parseJson(jsonStr: String): T? {
        if (jsonStr.isEmpty()) {
            return null
        }

        try {
            val jsonAdapter = MO_SHI.adapter(T::class.java)
            return jsonAdapter.fromJson(jsonStr)
        } catch (e: IOException) {
            ErrorReportHelper.postCatchedException(
                e,
                "JsonHelper.parseJson",
                "JSON解析IO异常: $jsonStr",
            )
            e.printStackTrace()
        } catch (e: JsonDataException) {
            ErrorReportHelper.postCatchedException(
                e,
                "JsonHelper.parseJson",
                "JSON数据格式异常: $jsonStr",
            )
            e.printStackTrace()
        }
        return null
    }

    inline fun <reified T> parseJsonList(jsonStr: String): List<T> {
        if (jsonStr.isEmpty()) {
            return emptyList()
        }

        try {
            val type = Types.newParameterizedType(List::class.java, T::class.java)
            val adapter = MO_SHI.adapter<List<T>>(type)
            return adapter.fromJson(jsonStr) ?: emptyList()
        } catch (e: IOException) {
            ErrorReportHelper.postCatchedException(
                e,
                "JsonHelper.parseJsonList",
                "JSON列表解析IO异常: $jsonStr",
            )
            e.printStackTrace()
        } catch (e: JsonDataException) {
            ErrorReportHelper.postCatchedException(
                e,
                "JsonHelper.parseJsonList",
                "JSON列表数据格式异常: $jsonStr",
            )
            e.printStackTrace()
        }
        return emptyList()
    }

    fun parseJsonMap(jsonStr: String): Map<String, String> {
        if (jsonStr.isEmpty()) {
            return emptyMap()
        }

        try {
            val type =
                Types.newParameterizedType(Map::class.java, String::class.java, String::class.java)
            val adapter = MO_SHI.adapter<Map<String, String>>(type)
            return adapter.fromJson(jsonStr) ?: emptyMap()
        } catch (e: IOException) {
            ErrorReportHelper.postCatchedException(
                e,
                "JsonHelper.parseJsonMap",
                "JSON映射解析IO异常: $jsonStr",
            )
            e.printStackTrace()
        } catch (e: JsonDataException) {
            ErrorReportHelper.postCatchedException(
                e,
                "JsonHelper.parseJsonMap",
                "JSON映射数据格式异常: $jsonStr",
            )
            e.printStackTrace()
        }

        return emptyMap()
    }

    inline fun <reified T> toJson(t: T?): String? {
        t ?: return null

        try {
            val adapter = MO_SHI.adapter(T::class.java)
            return adapter.toJson(t)
        } catch (e: IOException) {
            ErrorReportHelper.postCatchedException(
                e,
                "JsonHelper.toJson",
                "对象转JSON IO异常",
            )
            e.printStackTrace()
        } catch (e: JsonDataException) {
            ErrorReportHelper.postCatchedException(
                e,
                "JsonHelper.toJson",
                "对象转JSON数据格式异常",
            )
            e.printStackTrace()
        }
        return null
    }
}
