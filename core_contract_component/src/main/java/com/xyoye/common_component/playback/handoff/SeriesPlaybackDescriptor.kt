package com.xyoye.common_component.playback.handoff

import com.xyoye.data_component.enums.MediaType

/** A frozen catalog selection. Only the active session may advance its playback cursor. */
data class SeriesPlaybackDescriptor(
    val seriesId: Long,
    val entries: List<SeriesQueueEntry>,
    val selectedEpisode: Int,
    val preferredLibraryId: Int,
    val selectedFile: SeriesQueueFile? = null
) : PlaybackDescriptor() {
    init {
        require(entries.isNotEmpty() && selectedEpisode in entries.indices)
        require(entries.map { it.seasonNumber to it.episodeNumber }.distinct().size == entries.size)
        require(entries.all { (it.seasonNumber == 0) == (entries[selectedEpisode].seasonNumber == 0) })
        require(selectedFile == null || selectedFile in entries[selectedEpisode].files)
    }

    /** Versions stay inside an episode; a merged physical file occupies one playback position. */
    fun queue(): List<SeriesPlaybackDescriptor> {
        val selected = selectedFile
        val planned =
            entries.map { entry ->
                selected?.takeIf { it in entry.files }
                    ?: entry.files.filter { it.libraryId == preferredLibraryId }.singleOrNull()
            }
        val retained = mutableSetOf<Long>()
        return entries.indices.mapNotNull { index ->
            val file = planned[index]
            if (file != null) {
                if (file == selected && index != selectedEpisode || !retained.add(file.assetId)) return@mapNotNull null
            }
            copy(selectedEpisode = index, selectedFile = selected.takeIf { index == selectedEpisode })
        }
    }

    fun displayTitle(): String {
        val file =
            selectedFile ?: entries[selectedEpisode].files.filter { it.libraryId == preferredLibraryId }.singleOrNull()
                ?: return entries[selectedEpisode].title
        val covered = entries.filter { file in it.files }
        return if (covered.size == 1) covered.single().title else covered.joinToString(" / ") { it.title } + "（合并集）"
    }
}

data class SeriesQueueEntry(
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String,
    val files: List<SeriesQueueFile>
) {
    init {
        require(seasonNumber >= 0 && episodeNumber > 0 && files.isNotEmpty())
        require(files.map { it.assetId }.distinct().size == files.size)
    }

    override fun toString(): String = "SeriesQueueEntry(files=${files.size})"
}

data class SeriesQueueFile(
    val assetId: Long,
    val assetRevision: Long,
    val libraryId: Int,
    val contentGeneration: Long,
    val mediaType: MediaType,
    val file: PlaybackFileReference,
    val sourceName: String
) {
    override fun toString(): String = "SeriesQueueFile"
}
