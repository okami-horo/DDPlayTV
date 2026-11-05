package com.xyoye.player.kernel.impl.media3

import android.net.Uri
import com.xyoye.data_component.bean.VideoTrackBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Media3ExternalSubtitleTest {
    @Test
    fun pathAndFileUri_shareAnIdentityWithoutReadingTheFile() {
        val file = File("/storage/emulated/0/Subtitles/../Subtitles/字幕.srt")
        val path = VideoTrackBean.subtitle(file.path)
        val uri = VideoTrackBean.subtitle(Uri.fromFile(file.normalize()).toString())

        assertEquals(Media3ExternalSubtitle.id(path), Media3ExternalSubtitle.id(uri))
        assertEquals(Media3ExternalSubtitle.id(path), Media3ExternalSubtitle.id(path.copy(name = "Different label")))
    }

    @Test
    fun contentIdentity_preservesProviderAndDocumentIdRatherThanDisplayName() {
        val first = VideoTrackBean.subtitle("content://subtitles/document/one.srt")
        val second = VideoTrackBean.subtitle("content://subtitles/document/two.srt").copy(name = first.name)
        val anotherProvider = VideoTrackBean.subtitle("content://another/document/one.srt")

        assertEquals(Media3ExternalSubtitle.id(first), Media3ExternalSubtitle.id(first.copy()))
        assertNotEquals(Media3ExternalSubtitle.id(first), Media3ExternalSubtitle.id(second))
        assertNotEquals(Media3ExternalSubtitle.id(first), Media3ExternalSubtitle.id(anotherProvider))
    }
}
