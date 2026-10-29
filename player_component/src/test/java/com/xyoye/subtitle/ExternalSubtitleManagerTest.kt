package com.xyoye.subtitle

import com.xyoye.common_component.enums.SubtitleRendererBackend
import com.xyoye.data_component.enums.SurfaceType
import com.xyoye.player.subtitle.backend.SubtitleRenderEnvironment
import com.xyoye.player.subtitle.backend.SubtitleRenderer
import com.xyoye.player.subtitle.backend.SubtitleRendererRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ExternalSubtitleManagerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        SubtitleRendererRegistry.unregister(SubtitleRendererRegistry.current())
        Dispatchers.resetMain()
    }

    @Test
    fun ordinaryTextSubtitle_doesNotReportUnsupportedLibassFormat() =
        runBlocking {
            SubtitleRendererRegistry.register(RecordingRenderer())
            val file =
                temporaryFolder.newFile("subtitle.srt").apply {
                    writeText("1\n00:00:00,000 --> 00:00:03,000\nHello\n\n")
                }
            var unsupportedCount = 0
            val manager = ExternalSubtitleManager { _, _ -> unsupportedCount++ }

            assertTrue(manager.loadSubtitle(file.absolutePath))
            assertEquals(0, unsupportedCount)
            assertTrue(manager.getSubtitle(1000) != null)
        }

    @Test
    fun release_clearsTheRendererThatLoadedTheTrack() =
        runBlocking {
            val original = RecordingRenderer()
            val replacement = RecordingRenderer()
            SubtitleRendererRegistry.register(original)
            val manager = ExternalSubtitleManager()
            assertTrue(manager.loadSubtitle(temporaryFolder.newFile("subtitle.ass").absolutePath))
            SubtitleRendererRegistry.register(replacement)

            manager.release()

            assertEquals(1, original.clearCount)
            assertEquals(0, replacement.clearCount)
        }

    private class RecordingRenderer : SubtitleRenderer {
        override val backend = SubtitleRendererBackend.LIBASS
        var clearCount = 0

        override fun bind(environment: SubtitleRenderEnvironment) = Unit

        override fun release() = Unit

        override fun render(subtitle: MixedSubtitle) = false

        override fun onSurfaceTypeChanged(surfaceType: SurfaceType) = Unit

        override fun supportsExternalTrack(extension: String) = extension == "ass"

        override suspend fun loadExternalSubtitle(path: String) = true

        override fun clearExternalSubtitle() {
            clearCount++
        }
    }
}
