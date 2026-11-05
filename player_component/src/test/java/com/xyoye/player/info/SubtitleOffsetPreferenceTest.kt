package com.xyoye.player.info

import android.content.SharedPreferences
import com.tencent.mmkv.MMKV
import com.xyoye.common_component.config.SubtitleConfig
import com.xyoye.common_component.config.SubtitlePreferenceUpdater
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, shadows = [SubtitleOffsetPreferenceTest.Preferences::class])
class SubtitleOffsetPreferenceTest {
    @Before
    fun resetOffset() {
        Preferences.values.clear()
        PlayerInitializer.Subtitle.offsetPosition = 0
    }

    @Test
    fun persistedOffset_isTheValueUsedBySettingsAndMpvAfterInitialization() {
        SubtitleConfig.putSubtitleOffsetMs(1500)

        assertEquals(1500L, PlayerInitializer.Subtitle.offsetPosition)
        assertEquals(SubtitlePreferenceUpdater.currentOffset(), PlayerInitializer.Subtitle.offsetPosition)
    }

    @Test
    fun settingsAdjustment_isVisibleToBothSubtitleClocks() {
        SubtitlePreferenceUpdater.persistOffset(1500)
        PlayerInitializer.Subtitle.offsetPosition -= 500

        assertEquals(1000L, PlayerInitializer.Subtitle.offsetPosition)
        assertEquals(1000L, SubtitlePreferenceUpdater.currentOffset())
    }

    @Implements(value = MMKV::class, isInAndroidSdk = false)
    class Preferences {
        @RealObject
        private lateinit var preferences: MMKV

        @Implementation
        fun getLong(
            key: String,
            defaultValue: Long
        ): Long = values[key] ?: defaultValue

        @Implementation
        fun putLong(
            key: String,
            value: Long
        ): SharedPreferences.Editor {
            values[key] = value
            return preferences
        }

        @Implementation
        fun apply() = Unit

        companion object {
            val values = mutableMapOf<String, Long>()

            @JvmStatic
            @Implementation
            fun defaultMMKV(): MMKV = Shadow.newInstanceOf(MMKV::class.java)
        }
    }
}
