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
        video: BilibiliDashMediaData,
        audio: BilibiliDashMediaData?,
        cdnHostOverride: String? = null,
    ): File {
        if (outputFile.parentFile?.exists() != true) {
            outputFile.parentFile?.mkdirs()
        }
        val mpd = buildMpd(dash, video, audio, cdnHostOverride)
        outputFile.writeText(mpd)
        return outputFile
    }

    private fun buildMpd(
        dash: BilibiliDashData,
        video: BilibiliDashMediaData,
        audio: BilibiliDashMediaData?,
        cdnHostOverride: String?,
    ): String {
        val duration = dash.duration.takeIf { it > 0 } ?: 0
        val mpdDuration = "PT${duration}S"
        val minBufferTime = dash.minBufferTime?.takeIf { it > 0 } ?: 1.5

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
            append("    <AdaptationSet contentType=\"video\" mimeType=\"${escape(video.mimeType)}\">\n")
            appendRepresentation(video, cdnHostOverride)
            append("    </AdaptationSet>\n")

            if (audio != null) {
                append("    <AdaptationSet contentType=\"audio\" mimeType=\"${escape(audio.mimeType)}\">\n")
                appendRepresentation(audio, cdnHostOverride)
                append("    </AdaptationSet>\n")
            }

            append("  </Period>\n")
            append("</MPD>\n")
        }
    }

    private fun StringBuilder.appendRepresentation(
        media: BilibiliDashMediaData,
        cdnHostOverride: String?,
    ) {
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

        val baseUrls =
            BilibiliCdnStrategy.resolveUrls(
                baseUrl = media.baseUrl,
                backupUrls = media.backupUrl,
                options = BilibiliCdnStrategy.Options(hostOverride = cdnHostOverride),
            )
        val resolved = baseUrls.ifEmpty { listOf(media.baseUrl) }
        resolved.forEachIndexed { index, url ->
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
        input.orEmpty()
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    private fun escapeUrl(url: String): String = escape(url)
}
