package com.xyoye.storage_component.ui.dialog

import com.xyoye.common_component.extension.setTextColorRes
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.PlayerType
import com.xyoye.storage_component.R
import com.xyoye.storage_component.databinding.LayoutPlayerTypeOverrideBinding

object PlayerTypeOverrideBinder {
    fun bind(
        binding: LayoutPlayerTypeOverrideBinding,
        library: MediaLibraryEntity,
    ) {
        val normalized = normalizeOverrideValue(library.playerTypeOverride)
        if (normalized != library.playerTypeOverride) {
            library.playerTypeOverride = normalized
        }
        render(binding, library.playerTypeOverride)

        binding.followGlobalTv.setOnClickListener {
            library.playerTypeOverride = 0
            render(binding, 0)
        }
        binding.media3Tv.setOnClickListener {
            val value = PlayerType.TYPE_EXO_PLAYER.value
            library.playerTypeOverride = value
            render(binding, value)
        }
        binding.vlcTv.setOnClickListener {
            val value = PlayerType.TYPE_VLC_PLAYER.value
            library.playerTypeOverride = value
            render(binding, value)
        }
        binding.mpvTv.setOnClickListener {
            val value = PlayerType.TYPE_MPV_PLAYER.value
            library.playerTypeOverride = value
            render(binding, value)
        }
    }

    private fun normalizeOverrideValue(value: Int): Int {
        if (value == 0) return 0
        val playerType = PlayerType.valueOf(value)
        return if (playerType.value == value) value else 0
    }

    private fun render(
        binding: LayoutPlayerTypeOverrideBinding,
        overrideValue: Int,
    ) {
        val normalized = normalizeOverrideValue(overrideValue)
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

