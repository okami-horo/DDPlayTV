package com.xyoye.player.kernel.impl.media3

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import com.xyoye.data_component.enums.TrackType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, qualifiers = "zh-rCN")
class Media3SubtitleTrackNameProviderTest {
    private val provider = Media3SubtitleTrackNameProvider(RuntimeEnvironment.getApplication().resources)

    @Test
    fun duplicateNames_areDistinguishedWithoutChangingTrackIdentityOrSelection() {
        val formats = TrackGroup("subtitles", format(null, "zh"), format(null, "zh"))
        val tracks =
            Tracks(listOf(Tracks.Group(formats, false, intArrayOf(C.FORMAT_HANDLED, C.FORMAT_HANDLED), booleanArrayOf(true, false))))
        val player =
            Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { _, method, _ ->
                if (method.name == "getCurrentTracks") tracks else throw AssertionError("Unexpected player call: ${method.name}")
            }
        val kernel = Media3VideoPlayer(RuntimeEnvironment.getApplication())
        Media3VideoPlayer::class.java
            .getDeclaredField("player")
            .apply { isAccessible = true }
            .set(kernel, player)

        val names = kernel.getTracks(TrackType.SUBTITLE)

        assertEquals(listOf("0-0", "0-1"), names.map { it.id })
        assertEquals(listOf(true, false), names.map { it.selected })
        assertEquals(2, names.map { it.name }.distinct().size)
        assertTrue(names.all { it.name.startsWith(provider.getTrackName(format(null, "zh"))) })
    }

    @Test
    fun chineseVariants_keepTheExplicitLabelInsteadOfCollapsingToChinese() {
        assertEquals("简体中文", provider.getTrackName(format("CHS", "zh")))
        assertEquals("繁体中文", provider.getTrackName(format("CHT", "zh")))
    }

    @Test
    fun descriptiveLabels_areRetainedAlongsideLanguage() {
        val name = provider.getTrackName(format("字幕组 中英双语", "zh"))
        assertTrue(name.contains("字幕组 中英双语"))
        assertTrue(name.contains("中文"))
    }

    @Test
    fun forcedAndCaptionFlags_remainVisibleWithAnExplicitLabel() {
        val format =
            format("CHS", "zh")
                .buildUpon()
                .setSelectionFlags(C.SELECTION_FLAG_FORCED)
                .setRoleFlags(C.ROLE_FLAG_CAPTION)
                .build()
        val name = provider.getTrackName(format)
        assertTrue(name.contains("简体中文"))
        assertTrue(name.contains("强制"))
        assertTrue(name.contains("字幕") || name.contains("听障"))
    }

    @Test
    fun missingLabels_useLanguageAndRetainScriptOrRegion() {
        val englishName = provider.getTrackName(format(null, "en"))
        assertTrue(englishName.isNotBlank())
        assertTrue(provider.getTrackName(format(null, "zh-Hans")).contains("简体"))
        assertTrue(provider.getTrackName(format(null, "zh-Hant")).contains("繁体"))
        assertEquals(englishName, provider.getTrackName(format("  ", "en")))
    }

    @Test
    fun undeterminedLanguage_keepsTheOriginalLabel() {
        assertEquals("自定义轨道", provider.getTrackName(format(" 自定义轨道 ", "und")))
    }

    private fun format(
        label: String?,
        language: String?
    ): Format =
        Format
            .Builder()
            .setSampleMimeType("text/x-ssa")
            .setLabel(label)
            .setLanguage(language)
            .build()
}
