package com.xyoye.common_component.playback.addon

data class PlaybackSettingSpec(
    val identity: PlaybackIdentity,
    val sections: List<Section>
) {
    data class Section(
        val sectionId: String,
        val title: String,
        val items: List<Item>
    )

    sealed class Item {
        data class SingleChoice(
            val settingId: String,
            val title: String,
            val options: List<Option>,
            val selectedOptionId: String?
        ) : Item()
    }

    data class Option(
        val optionId: String,
        val label: String,
        val description: String? = null
    )
}

data class PlaybackSettingUpdate(
    val settingId: String,
    val optionId: String
)

interface PlaybackSettingsAddon : PlaybackAddon {
    suspend fun getSettingSpec(): Result<PlaybackSettingSpec?>
}

interface PlaybackPreferenceSwitchableAddon : PlaybackAddon {
    /** Persist the preference independently, without changing the active transport. */
    suspend fun applySettingUpdate(
        update: PlaybackSettingUpdate,
        positionMs: Long
    ): Result<PlaybackReplacementDecision>
}
