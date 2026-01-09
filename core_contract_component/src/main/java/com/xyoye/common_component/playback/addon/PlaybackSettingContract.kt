package com.xyoye.common_component.playback.addon

data class PlaybackSettingSpec(
    val identity: PlaybackIdentity,
    val sections: List<Section>,
) {
    data class Section(
        val sectionId: String,
        val title: String,
        val items: List<Item>,
    )

    sealed class Item {
        data class SingleChoice(
            val settingId: String,
            val title: String,
            val options: List<Option>,
            val selectedOptionId: String?,
        ) : Item()
    }

    data class Option(
        val optionId: String,
        val label: String,
        val description: String? = null,
    )
}

data class PlaybackSettingUpdate(
    val settingId: String,
    val optionId: String,
)

interface PlaybackSettingsAddon : PlaybackAddon {
    suspend fun getSettingSpec(): Result<PlaybackSettingSpec?>
}

interface PlaybackPreferenceSwitchableAddon : PlaybackAddon {
    /**
     * 返回值语义：
     * - Result.success(playUrl)：表示需要播放器重建 Source，并以该 URL 重新起播（续播到 positionMs）。
     * - Result.success(null)：表示设置已保存，但本次不重启播放（例如非 Media3 内核：提示“重新播放后生效”）。
     * - Result.failure(e)：表示切换失败（用于上报/日志）。
     */
    suspend fun applySettingUpdate(
        update: PlaybackSettingUpdate,
        positionMs: Long,
    ): Result<String?>
}

