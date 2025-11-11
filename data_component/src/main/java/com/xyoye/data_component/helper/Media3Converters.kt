package com.xyoye.data_component.helper

import androidx.room.TypeConverter
import com.xyoye.data_component.entity.media3.DownloadRequiredAction
import com.xyoye.data_component.entity.media3.Media3RolloutSource

class Media3Converters {

    @TypeConverter
    fun rolloutSourceToString(source: Media3RolloutSource?): String? = source?.name

    @TypeConverter
    fun stringToRolloutSource(value: String?): Media3RolloutSource? {
        return value?.let { Media3RolloutSource.valueOf(it) }
    }

    @TypeConverter
    fun requiredActionToString(action: DownloadRequiredAction?): String? = action?.name

    @TypeConverter
    fun stringToRequiredAction(value: String?): DownloadRequiredAction? {
        return value?.let { DownloadRequiredAction.valueOf(it) }
    }

    @TypeConverter
    fun logsToString(logs: List<String>?): String? {
        if (logs.isNullOrEmpty()) {
            return null
        }
        return logs.joinToString(LOG_DELIMITER)
    }

    @TypeConverter
    fun stringToLogs(value: String?): List<String> {
        if (value.isNullOrEmpty()) {
            return emptyList()
        }
        return value.split(LOG_DELIMITER)
    }

    private companion object {
        const val LOG_DELIMITER = "\u0001"
    }
}
