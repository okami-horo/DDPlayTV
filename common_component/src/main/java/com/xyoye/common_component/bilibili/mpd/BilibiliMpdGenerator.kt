package com.xyoye.common_component.bilibili.mpd

import com.xyoye.data_component.data.bilibili.BilibiliDashData
import com.xyoye.data_component.data.bilibili.BilibiliDashMediaData
import java.io.File

object BilibiliMpdGenerator {
    fun writeDashMpd(
        outputFile: File,
        dash: BilibiliDashData,
        video: BilibiliDashMediaData,
        audio: BilibiliDashMediaData?,
    ): File {
        if (outputFile.parentFile?.exists() != true) {
            outputFile.parentFile?.mkdirs()
        }
        val mpd = buildMpd(dash, video, audio)
        outputFile.writeText(mpd)
        return outputFile
    }

    private fun buildMpd(
        dash: BilibiliDashData,
        video: BilibiliDashMediaData,
        audio: BilibiliDashMediaData?,
    ): String {
        val duration = dash.duration.takeIf { it > 0 } ?: 0
        val mpdDuration = "PT${duration}S"
        val minBufferTime = dash.minBufferTime?.takeIf { it > 0 } ?: 1.5

        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append(
                "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" " +
                    "type=\"static\" " +
                    "mediaPresentationDuration=\"$mpdDuration\" " +
                    "minBufferTime=\"PT${minBufferTime}S\">\n",
            )
            append("  <Period>\n")
            append("    <AdaptationSet contentType=\"video\" mimeType=\"${escape(video.mimeType)}\">\n")
            appendRepresentation(video)
            append("    </AdaptationSet>\n")

            if (audio != null) {
                append("    <AdaptationSet contentType=\"audio\" mimeType=\"${escape(audio.mimeType)}\">\n")
                appendRepresentation(audio)
                append("    </AdaptationSet>\n")
            }

            append("  </Period>\n")
            append("</MPD>\n")
        }
    }

    private fun StringBuilder.appendRepresentation(media: BilibiliDashMediaData) {
        val codecs = media.codecs?.takeIf { it.isNotBlank() }
        val width = media.width?.takeIf { it > 0 }
        val height = media.height?.takeIf { it > 0 }
        val bandwidth = media.bandwidth.takeIf { it > 0 }

        append("      <Representation id=\"${media.id}\"")
        bandwidth?.let { append(" bandwidth=\"$it\"") }
        codecs?.let { append(" codecs=\"${escape(it)}\"") }
        width?.let { append(" width=\"$it\"") }
        height?.let { append(" height=\"$it\"") }
        append(">\n")
        append("        <BaseURL>${escapeUrl(media.baseUrl)}</BaseURL>\n")

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
        input.orEmpty()
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    private fun escapeUrl(url: String): String = escape(url)
}

