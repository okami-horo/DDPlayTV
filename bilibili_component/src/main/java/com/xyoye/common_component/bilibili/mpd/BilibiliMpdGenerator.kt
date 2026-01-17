package com.xyoye.common_component.bilibili.mpd

import com.xyoye.common_component.bilibili.cdn.BilibiliCdnStrategy
import com.xyoye.data_component.data.bilibili.BilibiliDashData
import com.xyoye.data_component.data.bilibili.BilibiliDashMediaData
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File

object BilibiliMpdGenerator {
    fun writeDashMpd(
        outputFile: File,
        dash: BilibiliDashData,
        videos: List<BilibiliDashMediaData>,
        audios: List<BilibiliDashMediaData> = emptyList(),
        cdnHostOverride: String? = null,
        cdnHostBlacklist: Set<String> = emptySet()
    ): File {
        if (outputFile.parentFile?.exists() != true) {
            outputFile.parentFile?.mkdirs()
        }
        val mpd = buildMpd(dash, videos, audios, cdnHostOverride, cdnHostBlacklist)
        outputFile.writeText(mpd)
        return outputFile
    }

    fun writeDashMpd(
        outputFile: File,
        dash: BilibiliDashData,
        video: BilibiliDashMediaData,
        audio: BilibiliDashMediaData?,
        cdnHostOverride: String? = null,
        cdnHostBlacklist: Set<String> = emptySet()
    ): File =
        writeDashMpd(
            outputFile = outputFile,
            dash = dash,
            videos = listOf(video),
            audios = audio?.let(::listOf).orEmpty(),
            cdnHostOverride = cdnHostOverride,
            cdnHostBlacklist = cdnHostBlacklist,
        )

    private fun buildMpd(
        dash: BilibiliDashData,
        videos: List<BilibiliDashMediaData>,
        audios: List<BilibiliDashMediaData>,
        cdnHostOverride: String?,
        cdnHostBlacklist: Set<String>
    ): String {
        val duration = dash.duration.takeIf { it > 0 } ?: 0
        val mpdDuration = "PT${duration}S"
        val minBufferTime = dash.minBufferTime?.takeIf { it > 0 } ?: 1.5
        val videoMimeType = videos.firstNotNullOfOrNull { it.mimeType?.takeIf(String::isNotBlank) }
        val audioMimeType = audios.firstNotNullOfOrNull { it.mimeType?.takeIf(String::isNotBlank) }

        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append(
                "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" " +
                    "xmlns:dvb=\"urn:dvb:dash:profile:dvb-dash:2014\" " +
                    "type=\"static\" " +
                    "profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011,urn:dvb:dash:profile:dvb-dash:2014\" " +
                    "mediaPresentationDuration=\"$mpdDuration\" " +
                    "minBufferTime=\"PT${minBufferTime}S\">\n",
            )
            append("  <Period>\n")

            if (videos.isNotEmpty()) {
                append("    <AdaptationSet contentType=\"video\"")
                if (!videoMimeType.isNullOrBlank()) {
                    append(" mimeType=\"${escape(videoMimeType)}\"")
                }
                append(">\n")
                val used = hashSetOf<String>()
                videos.forEach { video ->
                    val representationId = buildUniqueRepresentationId("v", video, used)
                    appendRepresentation(representationId, video, cdnHostOverride, cdnHostBlacklist)
                }
                append("    </AdaptationSet>\n")
            }

            if (audios.isNotEmpty()) {
                append("    <AdaptationSet contentType=\"audio\"")
                if (!audioMimeType.isNullOrBlank()) {
                    append(" mimeType=\"${escape(audioMimeType)}\"")
                }
                append(">\n")
                val used = hashSetOf<String>()
                audios.forEach { audio ->
                    val representationId = buildUniqueRepresentationId("a", audio, used)
                    appendRepresentation(representationId, audio, cdnHostOverride, cdnHostBlacklist)
                }
                append("    </AdaptationSet>\n")
            }

            append("  </Period>\n")
            append("</MPD>\n")
        }
    }

    private fun buildUniqueRepresentationId(
        prefix: String,
        media: BilibiliDashMediaData,
        usedIds: MutableSet<String>
    ): String {
        val baseId =
            buildString {
                append(prefix)
                append('_')
                append(media.id)
                append('_')
                append(media.codecid ?: 0)
                val bandwidth = media.bandwidth.takeIf { it > 0 }
                if (bandwidth != null) {
                    append('_')
                    append(bandwidth)
                }
                val width = media.width?.takeIf { it > 0 }
                val height = media.height?.takeIf { it > 0 }
                if (width != null && height != null) {
                    append('_')
                    append(width)
                    append('x')
                    append(height)
                }
            }

        var id = baseId
        var suffix = 1
        while (!usedIds.add(id)) {
            id = "${baseId}_$suffix"
            suffix++
        }
        return id
    }

    private fun StringBuilder.appendRepresentation(
        representationId: String,
        media: BilibiliDashMediaData,
        cdnHostOverride: String?,
        cdnHostBlacklist: Set<String>
    ) {
        val codecs = media.codecs?.takeIf { it.isNotBlank() }
        val width = media.width?.takeIf { it > 0 }
        val height = media.height?.takeIf { it > 0 }
        val frameRate = media.frameRate?.takeIf { it.isNotBlank() }
        val sar = media.sar?.takeIf { it.isNotBlank() }
        val bandwidth = media.bandwidth.takeIf { it > 0 }

        append("      <Representation id=\"${escape(representationId)}\"")
        bandwidth?.let { append(" bandwidth=\"$it\"") }
        codecs?.let { append(" codecs=\"${escape(it)}\"") }
        width?.let { append(" width=\"$it\"") }
        height?.let { append(" height=\"$it\"") }
        frameRate?.let { append(" frameRate=\"${escape(it)}\"") }
        sar?.let { append(" sar=\"${escape(it)}\"") }
        append(">\n")

        val baseUrls =
            BilibiliCdnStrategy.resolveUrls(
                baseUrl = media.baseUrl,
                backupUrls = media.backupUrl,
                options = BilibiliCdnStrategy.Options(hostOverride = cdnHostOverride),
            )
        val resolved = baseUrls.ifEmpty { listOf(media.baseUrl) }
        val filtered =
            if (cdnHostBlacklist.isEmpty()) {
                resolved
            } else {
                resolved
                    .filterNot { url ->
                        val host = url.toHttpUrlOrNull()?.host ?: url
                        cdnHostBlacklist.contains(host)
                    }.ifEmpty { resolved }
            }
        filtered.forEachIndexed { index, url ->
            val priority = index + 1
            val serviceLocation = url.toHttpUrlOrNull()?.host ?: url
            append("        <BaseURL")
            append(" serviceLocation=\"${escape(serviceLocation)}\"")
            append(" dvb:priority=\"$priority\"")
            append(" dvb:weight=\"1\"")
            append(">${escapeUrl(url)}</BaseURL>\n")
        }

        val segment = media.segmentBase
        val indexRange = segment?.indexRange?.takeIf { it.isNotBlank() }
        val initRange = segment?.initialization?.takeIf { it.isNotBlank() }
        if (!indexRange.isNullOrEmpty()) {
            append("        <SegmentBase indexRange=\"${escape(indexRange)}\">\n")
            if (!initRange.isNullOrEmpty()) {
                append("          <Initialization range=\"${escape(initRange)}\" />\n")
            }
            append("        </SegmentBase>\n")
        }

        append("      </Representation>\n")
    }

    private fun escape(input: String?): String =
        input
            .orEmpty()
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    private fun escapeUrl(url: String): String = escape(url)
}
