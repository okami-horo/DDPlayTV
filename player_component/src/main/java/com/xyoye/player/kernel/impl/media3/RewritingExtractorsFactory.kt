package com.xyoye.player.kernel.impl.media3

import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.common.DataReader
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.common.util.ParsableByteArray

/**
 * 包装 Extractor，拦截 TrackOutput.format，对部分非规范 csd 做轻量修补。
 * 当前仅处理 H.264 avcC -> Annex-B 起始码转换，其他保持原样。
 */
@UnstableApi
class RewritingExtractorsFactory(
    private val delegate: DefaultExtractorsFactory = DefaultExtractorsFactory()
) : ExtractorsFactory {

    override fun createExtractors(): Array<Extractor> {
        return delegate.createExtractors().map { RewritingExtractor(it) }.toTypedArray()
    }
}

@UnstableApi
private class RewritingExtractor(private val upstream: Extractor) : Extractor {
    override fun init(output: ExtractorOutput) {
        upstream.init(RewritingExtractorOutput(output))
    }

    override fun sniff(input: ExtractorInput): Boolean = upstream.sniff(input)
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int =
        upstream.read(input, seekPosition)

    override fun seek(position: Long, timeUs: Long) = upstream.seek(position, timeUs)
    override fun release() = upstream.release()
}

@UnstableApi
private class RewritingExtractorOutput(private val downstream: ExtractorOutput) : ExtractorOutput {
    override fun track(id: Int, type: Int): TrackOutput {
        val original = downstream.track(id, type)
        return RewritingTrackOutput(original)
    }

    override fun endTracks() = downstream.endTracks()
    override fun seekMap(seekMap: SeekMap) = downstream.seekMap(seekMap)
}

@UnstableApi
private class RewritingTrackOutput(private val downstream: TrackOutput) : TrackOutput {
    override fun format(format: Format) {
        val rewritten = Media3BitstreamRewriter.rewrite(format)
        Media3Diagnostics.logFormatRewritten(format, rewritten)
        downstream.format(rewritten)
    }

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
        downstream.sampleData(input, length, allowEndOfInput, sampleDataPart)

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        downstream.sampleData(data, length, sampleDataPart)
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?
    ) = downstream.sampleMetadata(timeUs, flags, size, offset, cryptoData)
}
