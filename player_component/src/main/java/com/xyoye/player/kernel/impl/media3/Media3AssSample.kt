package com.xyoye.player.kernel.impl.media3

/** Media3's Matroska timing wrapper is not part of libass's raw event packet. */
internal object Media3AssSample {
    private val prefix = "Dialogue: 0:00:00:00,".toByteArray()
    private val formatPrefix = "Format:".toByteArray()

    fun codecPrivate(initializationData: List<ByteArray>): ByteArray? {
        if (initializationData.isEmpty()) return null
        val header =
            if (initializationData.size > 1 && initializationData[0].startsWith(formatPrefix)) {
                initializationData[1]
            } else {
                initializationData[0]
            }
        var size = header.size
        while (size > 0 && header[size - 1] == 0.toByte()) size--
        return header.copyOf(size)
    }

    fun normalize(payload: ByteArray): Pair<ByteArray, Long?> {
        if (!payload.startsWith(prefix)) return payload to null
        val end = (prefix.size until payload.size).firstOrNull { payload[it] == ','.code.toByte() } ?: return payload to null
        val time = payload.copyOfRange(prefix.size, end).toString(Charsets.US_ASCII)
        val match = Regex("(\\d):(\\d{2}):(\\d{2}):(\\d{2})").matchEntire(time) ?: return payload to null
        val values = match.groupValues.drop(1).map { it.toLong() }
        val duration = ((values[0] * 3600 + values[1] * 60 + values[2]) * 100 + values[3]) * 10_000
        return payload.copyOfRange(end + 1, payload.size) to duration
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
