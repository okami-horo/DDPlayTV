package com.xyoye.storage_component.ui.dialog

import androidx.core.view.isVisible
import com.xyoye.common_component.extension.setTextColorRes
import com.xyoye.common_component.storage.StorageFactory
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.PlayerType
import com.xyoye.storage_component.R
import com.xyoye.storage_component.databinding.LayoutPlayerTypeOverrideBinding

object PlayerTypeOverrideBinder {
    fun bind(
        binding: LayoutPlayerTypeOverrideBinding,
        library: MediaLibraryEntity,
    ) {
        val (supportedPlayerTypes, preferredPlayerType) = resolvePolicy(library)
        val allowFollowGlobal = supportedPlayerTypes.size > 1

        binding.followGlobalTv.isVisible = allowFollowGlobal
        binding.media3Tv.isVisible = supportedPlayerTypes.contains(PlayerType.TYPE_EXO_PLAYER)
        binding.vlcTv.isVisible = supportedPlayerTypes.contains(PlayerType.TYPE_VLC_PLAYER)
        binding.mpvTv.isVisible = supportedPlayerTypes.contains(PlayerType.TYPE_MPV_PLAYER)

        updateFixedLabelsIfNeeded(binding, supportedPlayerTypes, allowFollowGlobal)

        val normalized =
            normalizeOverrideValue(
                value = library.playerTypeOverride,
                allowFollowGlobal = allowFollowGlobal,
                supportedPlayerTypes = supportedPlayerTypes,
                preferredPlayerType = preferredPlayerType,
            )
        if (normalized != library.playerTypeOverride) {
            library.playerTypeOverride = normalized
        }
        render(binding, library.playerTypeOverride, allowFollowGlobal, supportedPlayerTypes, preferredPlayerType)

        binding.followGlobalTv.setOnClickListener {
            if (!allowFollowGlobal) return@setOnClickListener
            library.playerTypeOverride = 0
            render(binding, 0, allowFollowGlobal, supportedPlayerTypes, preferredPlayerType)
        }
        binding.media3Tv.setOnClickListener {
            if (!supportedPlayerTypes.contains(PlayerType.TYPE_EXO_PLAYER)) return@setOnClickListener
            val value = PlayerType.TYPE_EXO_PLAYER.value
            library.playerTypeOverride = value
            render(binding, value, allowFollowGlobal, supportedPlayerTypes, preferredPlayerType)
        }
        binding.vlcTv.setOnClickListener {
            if (!supportedPlayerTypes.contains(PlayerType.TYPE_VLC_PLAYER)) return@setOnClickListener
            val value = PlayerType.TYPE_VLC_PLAYER.value
            library.playerTypeOverride = value
            render(binding, value, allowFollowGlobal, supportedPlayerTypes, preferredPlayerType)
        }
        binding.mpvTv.setOnClickListener {
            if (!supportedPlayerTypes.contains(PlayerType.TYPE_MPV_PLAYER)) return@setOnClickListener
            val value = PlayerType.TYPE_MPV_PLAYER.value
            library.playerTypeOverride = value
            render(binding, value, allowFollowGlobal, supportedPlayerTypes, preferredPlayerType)
        }
    }

    private fun resolvePolicy(library: MediaLibraryEntity): Pair<Set<PlayerType>, PlayerType> {
        val storage = StorageFactory.createStorage(library)
        val supported = storage?.supportedPlayerTypes()?.takeIf { it.isNotEmpty() } ?: PlayerType.values().toSet()
        val preferred = storage?.preferredPlayerType() ?: PlayerType.TYPE_EXO_PLAYER
        storage?.close()
        return supported to preferred
    }

    private fun updateFixedLabelsIfNeeded(
        binding: LayoutPlayerTypeOverrideBinding,
        supportedPlayerTypes: Set<PlayerType>,
        allowFollowGlobal: Boolean,
    ) {
        val fixedSuffix = binding.root.context.getString(R.string.text_player_fixed_suffix)
        val isFixed = !allowFollowGlobal && supportedPlayerTypes.size == 1

        if (isFixed) {
            if (supportedPlayerTypes.contains(PlayerType.TYPE_EXO_PLAYER)) {
                binding.media3Tv.text = binding.media3Tv.context.getString(R.string.text_player_media3) + fixedSuffix
            }
            if (supportedPlayerTypes.contains(PlayerType.TYPE_VLC_PLAYER)) {
                binding.vlcTv.text = binding.vlcTv.context.getString(R.string.text_player_vlc) + fixedSuffix
            }
            if (supportedPlayerTypes.contains(PlayerType.TYPE_MPV_PLAYER)) {
                binding.mpvTv.text = binding.mpvTv.context.getString(R.string.text_player_mpv) + fixedSuffix
            }
            return
        }

        binding.media3Tv.setText(R.string.text_player_media3)
        binding.vlcTv.setText(R.string.text_player_vlc)
        binding.mpvTv.setText(R.string.text_player_mpv)
    }

    private fun preferredOverrideValue(
        supportedPlayerTypes: Set<PlayerType>,
        preferredPlayerType: PlayerType,
    ): Int {
        val supported = supportedPlayerTypes.ifEmpty { setOf(preferredPlayerType) }
        val preferred = preferredPlayerType.takeIf { it in supported } ?: supported.first()
        return preferred.value
    }

    private fun normalizeOverrideValue(
        value: Int,
        allowFollowGlobal: Boolean,
        supportedPlayerTypes: Set<PlayerType>,
        preferredPlayerType: PlayerType,
    ): Int {
        if (value == 0) {
            return if (allowFollowGlobal) 0 else preferredOverrideValue(supportedPlayerTypes, preferredPlayerType)
        }

        val playerType = PlayerType.valueOf(value)
        if (playerType.value != value) {
            return if (allowFollowGlobal) 0 else preferredOverrideValue(supportedPlayerTypes, preferredPlayerType)
        }

        if (supportedPlayerTypes.contains(playerType)) {
            return value
        }

        return if (allowFollowGlobal) 0 else preferredOverrideValue(supportedPlayerTypes, preferredPlayerType)
    }

    private fun render(
        binding: LayoutPlayerTypeOverrideBinding,
        overrideValue: Int,
        allowFollowGlobal: Boolean,
        supportedPlayerTypes: Set<PlayerType>,
        preferredPlayerType: PlayerType,
    ) {
        val normalized =
            normalizeOverrideValue(
                value = overrideValue,
                allowFollowGlobal = allowFollowGlobal,
                supportedPlayerTypes = supportedPlayerTypes,
                preferredPlayerType = preferredPlayerType,
            )
        val followGlobalSelected = normalized == 0
        val media3Selected = normalized == PlayerType.TYPE_EXO_PLAYER.value
        val vlcSelected = normalized == PlayerType.TYPE_VLC_PLAYER.value
        val mpvSelected = normalized == PlayerType.TYPE_MPV_PLAYER.value

        binding.followGlobalTv.isSelected = followGlobalSelected
        binding.followGlobalTv.setTextColorRes(if (followGlobalSelected) R.color.text_white else R.color.text_black)

        binding.media3Tv.isSelected = media3Selected
        binding.media3Tv.setTextColorRes(if (media3Selected) R.color.text_white else R.color.text_black)

        binding.vlcTv.isSelected = vlcSelected
        binding.vlcTv.setTextColorRes(if (vlcSelected) R.color.text_white else R.color.text_black)

        binding.mpvTv.isSelected = mpvSelected
        binding.mpvTv.setTextColorRes(if (mpvSelected) R.color.text_white else R.color.text_black)
    }
}
