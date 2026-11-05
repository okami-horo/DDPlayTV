package com.xyoye.player.kernel.impl.media3

import android.graphics.Bitmap
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import com.xyoye.player.kernel.inter.VideoPlayerEventListener
import com.xyoye.subtitle.MixedSubtitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Media3CueOutputTest {
    @Test
    fun mixedTextAndBitmapCues_preserveSpansPositionAndEmptyClears() {
        val text =
            SpannableString("Positioned subtitle").apply {
                setSpan(ForegroundColorSpan(0xFF00FF00.toInt()), 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        val textCue =
            Cue
                .Builder()
                .setText(text)
                .setPosition(0.25f)
                .setLine(0.2f, Cue.LINE_TYPE_FRACTION)
                .build()
        val bitmapCue =
            Cue
                .Builder()
                .setBitmap(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888))
                .setPosition(0.75f)
                .build()
        var output: MixedSubtitle? = null
        val kernel = Media3VideoPlayer(RuntimeEnvironment.getApplication())
        kernel.setPlayerEventListener(
            object : VideoPlayerEventListener by VideoPlayerEventListener.NO_OP {
                override fun onSubtitleTextOutput(subtitle: MixedSubtitle) {
                    output = subtitle
                }
            }
        )
        kernel.onCues(CueGroup(listOf(textCue, bitmapCue), 10))
        assertSame(textCue, output!!.cues!![0])
        assertSame(bitmapCue, output!!.cues!![1])
        assertEquals(0.25f, output!!.cues!![0].position)
        assertEquals(1, (output!!.cues!![0].text as Spanned).getSpans(0, text.length, ForegroundColorSpan::class.java).size)
        kernel.onCues(CueGroup(emptyList(), 20))
        assertEquals(emptyList<Cue>(), output!!.cues)
    }
}
