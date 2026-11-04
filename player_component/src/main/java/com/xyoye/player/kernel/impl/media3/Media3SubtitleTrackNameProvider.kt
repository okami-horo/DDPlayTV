package com.xyoye.player.kernel.impl.media3

import android.content.res.Resources
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.DefaultTrackNameProvider
import androidx.media3.ui.TrackNameProvider
import com.xyoye.player_component.R
import androidx.media3.ui.R as Media3R

/** Keeps subtitle titles that Media3's language-first default would discard. */
@UnstableApi
internal class Media3SubtitleTrackNameProvider(
    private val resources: Resources
) : TrackNameProvider {
    private val fallback = DefaultTrackNameProvider(resources)

    override fun getTrackName(format: Format): String {
        val label = format.label?.trim().orEmpty()
        val chineseVariant =
            when {
                label.equals("CHS", true) -> resources.getString(R.string.subtitle_language_simplified)
                label.equals("CHT", true) -> resources.getString(R.string.subtitle_language_traditional)
                else -> null
            }
        val languageName =
            fallback.getTrackName(
                format
                    .buildUpon()
                    .setLabel(null)
                    .setRoleFlags(0)
                    .build()
            )
        val names = mutableListOf(chineseVariant ?: label.ifEmpty { languageName })
        if (chineseVariant == null &&
            label.isNotEmpty() &&
            !format.language.isNullOrBlank() &&
            format.language != C.LANGUAGE_UNDETERMINED &&
            languageName != label
        ) {
            names.add(languageName)
        }
        if (format.roleFlags and C.ROLE_FLAG_ALTERNATE != 0) {
            names.add(resources.getString(Media3R.string.exo_track_role_alternate))
        }
        if (format.roleFlags and C.ROLE_FLAG_SUPPLEMENTARY != 0) {
            names.add(resources.getString(Media3R.string.exo_track_role_supplementary))
        }
        if (format.roleFlags and C.ROLE_FLAG_COMMENTARY != 0) {
            names.add(resources.getString(Media3R.string.exo_track_role_commentary))
        }
        if (format.roleFlags and (C.ROLE_FLAG_CAPTION or C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND) != 0) {
            names.add(resources.getString(Media3R.string.exo_track_role_closed_captions))
        }
        if (format.selectionFlags and C.SELECTION_FLAG_FORCED != 0) {
            names.add(resources.getString(R.string.subtitle_track_forced))
        }
        return names.joinToString(" · ")
    }
}
