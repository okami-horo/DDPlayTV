package com.xyoye.common_component.playback.handoff

import com.xyoye.data_component.enums.MediaType
import org.junit.Assert.*
import org.junit.Test

class SeriesPlaybackDescriptorTest {
    @Test fun versionsStayWithinTheirEpisodeAndLargeQueuesKeepTheSelectedCursor() {
        val entries = (1..260).map { number -> entry(number, listOf(file(number.toLong()), file(number + 1000L, 2))) }
        val descriptor = SeriesPlaybackDescriptor(7, entries, 230, 1, entries[230].files.first())
        val queue = descriptor.queue()
        assertEquals(260, queue.size)
        assertEquals(230, queue[230].selectedEpisode)
        assertEquals(descriptor.selectedFile, queue[230].selectedFile)
        assertNull(queue[231].selectedFile)
        assertEquals(2, queue[231].entries[231].files.size)
    }

    @Test fun selectingTheSecondLogicalEpisodeOfAMergedFileRetainsThatCursorOnly() {
        val merged = file(1)
        val entries = listOf(entry(1, listOf(merged)), entry(2, listOf(merged)), entry(3, listOf(file(3))))
        val descriptor = SeriesPlaybackDescriptor(7, entries, 1, 1, merged)
        assertEquals(listOf(1, 2), descriptor.queue().map { it.selectedEpisode })
        assertEquals("E1 / E2（合并集）", descriptor.displayTitle())
        assertEquals(merged, descriptor.queue().first().selectedFile)
    }

    @Test fun futureMergedFilesOccupyOnePositionAndRetainTheirCoverageTitle() {
        val merged = file(2)
        val entries = listOf(entry(1, listOf(file(1))), entry(2, listOf(merged)), entry(3, listOf(merged)))
        val descriptor = SeriesPlaybackDescriptor(7, entries, 0, 1, entries.first().files.first())
        assertEquals(listOf(0, 1), descriptor.queue().map { it.selectedEpisode })
        assertEquals("E2 / E3（合并集）", descriptor.queue()[1].displayTitle())
    }

    @Test fun ambiguousVersionsAreNotCollapsedBeforeTheUserChooses() {
        val merged = file(2)
        val entries = listOf(entry(1, listOf(file(1))), entry(2, listOf(merged, file(3))), entry(3, listOf(merged)))
        val descriptor = SeriesPlaybackDescriptor(7, entries, 0, 1, entries.first().files.first())
        assertEquals(3, descriptor.queue().size)
        val chosen = descriptor.copy(selectedEpisode = 1, selectedFile = merged)
        assertEquals(listOf(0, 1), chosen.queue().map { it.selectedEpisode })
    }

    @Test fun invalidCursorForeignFileDuplicateEpisodesAndMixedSpecialsAreRejected() {
        val entries = listOf(entry(1, listOf(file(1))))
        assertThrows(IllegalArgumentException::class.java) { SeriesPlaybackDescriptor(7, entries, 1, 1) }
        assertThrows(IllegalArgumentException::class.java) { SeriesPlaybackDescriptor(7, entries, 0, 1, file(9)) }
        assertThrows(IllegalArgumentException::class.java) { SeriesPlaybackDescriptor(7, entries + entries, 0, 1) }
        assertThrows(IllegalArgumentException::class.java) {
            SeriesPlaybackDescriptor(7, entries + entries.first().copy(seasonNumber = 0), 0, 1)
        }
    }

    @Test fun diagnosticStringsKeepPrivatePathsAndSourceNamesOut() {
        val secret = "private-secret"
        val file = file(1).copy(file = PlaybackFileReference(secret, secret, protocolReference = secret), sourceName = secret)
        val entry = entry(1, listOf(file)).copy(title = secret)
        val descriptor = SeriesPlaybackDescriptor(7, listOf(entry), 0, 1, file)
        listOf(file, entry, descriptor, PlaybackPreparationFailure.VersionSelectionRequired(descriptor, listOf(file)))
            .forEach { assertFalse(it.toString().contains(secret)) }
    }

    private fun entry(
        number: Int,
        files: List<SeriesQueueFile>
    ) = SeriesQueueEntry(1, number, "E$number", files)

    private fun file(
        id: Long,
        library: Int = 1
    ) = SeriesQueueFile(id, 1, library, 1, MediaType.SMB_SERVER, PlaybackFileReference("$library:$id", "/$id.mkv"), "Source $library")
}
