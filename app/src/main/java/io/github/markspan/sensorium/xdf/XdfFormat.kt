package io.github.markspan.sensorium.xdf

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** What an XDF stream header says about one stream; mirrors the meta-data of the LSL outlet. */
data class XdfStreamInfo(
    val name: String,
    val type: String,
    val channelCount: Int,
    /** Hz; 0 for an irregular rate. */
    val nominalRate: Double,
    val sourceId: String,
    val labels: List<String>,
    /** One unit per channel, or a single unit for all channels. */
    val units: List<String>,
    val channelType: String,
    val manufacturer: String,
    val model: String,
    val timestamps: String,
)

/**
 * Encoding of XDF 1.0 files (https://github.com/sccn/xdf/wiki/Specifications), as written by
 * LabRecorder: the magic `XDF:`, then chunks of `[length][tag][content]`, all little-endian.
 * Samples are float32. Pure JVM code, so it can be unit tested.
 */
object XdfFormat {
    val MAGIC: ByteArray = "XDF:".toByteArray(Charsets.US_ASCII)

    const val TAG_FILE_HEADER = 1
    const val TAG_STREAM_HEADER = 2
    const val TAG_SAMPLES = 3
    const val TAG_CLOCK_OFFSET = 4
    const val TAG_BOUNDARY = 5
    const val TAG_STREAM_FOOTER = 6

    /** Fixed content of boundary chunks, which let readers resynchronise in a damaged file. */
    val BOUNDARY_UUID: ByteArray = intArrayOf(
        0x43, 0xA5, 0x46, 0xDC, 0xCB, 0xF5, 0x41, 0x0F, 0xB3, 0x0E, 0xD5, 0x46, 0x73, 0x83, 0xCB, 0xE4,
    ).map { it.toByte() }.toByteArray()

    fun fileHeader(): ByteArray =
        chunk(TAG_FILE_HEADER, "<?xml version=\"1.0\"?><info><version>1.0</version></info>".toByteArray())

    fun streamHeader(streamId: Int, xml: String): ByteArray = chunk(TAG_STREAM_HEADER, u32(streamId) + xml.toByteArray())

    fun streamFooter(streamId: Int, xml: String): ByteArray = chunk(TAG_STREAM_FOOTER, u32(streamId) + xml.toByteArray())

    fun clockOffset(streamId: Int, collectionTime: Double, offset: Double): ByteArray =
        chunk(TAG_CLOCK_OFFSET, le(20).putInt(streamId).putDouble(collectionTime).putDouble(offset).array())

    fun boundary(): ByteArray = chunk(TAG_BOUNDARY, BOUNDARY_UUID)

    /**
     * A samples chunk: [data] holds whole samples of [channels] values, multiplexed. Sample i is
     * written with time stamp [stamps]`[i]`, or without one when that is NaN; readers then
     * deduce it from the previous sample and the nominal rate.
     */
    fun samples(streamId: Int, data: FloatArray, channels: Int, stamps: DoubleArray): ByteArray {
        val n = data.size / channels
        require(stamps.size == n) { "need one time stamp (or NaN) per sample" }
        val explicit = stamps.count { !it.isNaN() }
        val count = varLen(n.toLong())
        val buf = le(4 + count.size + n * (1 + 4 * channels) + explicit * 8)
        buf.putInt(streamId).put(count)
        for (s in 0 until n) {
            if (stamps[s].isNaN()) buf.put(0) else buf.put(8).putDouble(stamps[s])
            for (c in 0 until channels) buf.putFloat(data[s * channels + c])
        }
        return chunk(TAG_SAMPLES, buf.array())
    }

    /** `[number of length bytes: 1, 4 or 8][length]`, little-endian. */
    fun varLen(value: Long): ByteArray = when {
        value <= 0xFF -> byteArrayOf(1, value.toByte())
        value <= 0xFFFFFFFFL -> le(5).put(4).putInt(value.toInt()).array()
        else -> le(9).put(8).putLong(value).array()
    }

    /** `[varLen(tag + content length)][tag: uint16][content]`. */
    fun chunk(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(content.size + 11)
        out.write(varLen(content.size + 2L))
        out.write(le(2).putShort(tag.toShort()).array())
        out.write(content)
        return out.toByteArray()
    }

    /** Stream header XML in the layout liblsl gives stream infos, with the outlet's meta-data. */
    fun headerXml(info: XdfStreamInfo, createdAt: Double, uid: String, hostname: String): String = buildString {
        append("<?xml version=\"1.0\"?><info>")
        el("name", info.name); el("type", info.type)
        el("channel_count", info.channelCount.toString())
        el("channel_format", "float32")
        el("source_id", info.sourceId)
        el("nominal_srate", number(info.nominalRate))
        el("version", "1.1")
        el("created_at", number(createdAt))
        el("uid", uid)
        el("session_id", "default")
        el("hostname", hostname)
        append("<desc>")
        if (info.manufacturer.isNotEmpty()) el("manufacturer", info.manufacturer)
        if (info.model.isNotEmpty()) el("model", info.model)
        append("<channels>")
        for (i in 0 until info.channelCount) {
            val label = info.labels.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: "ch${i + 1}"
            val unit = (if (info.units.size == 1) info.units[0] else info.units.getOrNull(i)).orEmpty()
            append("<channel>")
            el("label", label); el("name", label)
            if (unit.isNotEmpty()) el("unit", unit)
            if (info.channelType.isNotEmpty()) el("type", info.channelType)
            append("</channel>")
        }
        append("</channels><acquisition>")
        el("software", "Sensorium (Android)")
        if (info.timestamps.isNotEmpty()) el("timestamps", info.timestamps)
        append("</acquisition></desc></info>")
    }

    /** Stream footer XML, as LabRecorder writes it. */
    fun footerXml(
        firstTimestamp: Double,
        lastTimestamp: Double,
        sampleCount: Long,
        clockOffsets: List<Pair<Double, Double>>,
    ): String = buildString {
        append("<?xml version=\"1.0\"?><info>")
        el("first_timestamp", number(firstTimestamp))
        el("last_timestamp", number(lastTimestamp))
        el("sample_count", sampleCount.toString())
        append("<clock_offsets>")
        for ((time, value) in clockOffsets) {
            append("<offset>"); el("time", number(time)); el("value", number(value)); append("</offset>")
        }
        append("</clock_offsets></info>")
    }

    private fun StringBuilder.el(name: String, value: String) {
        append('<').append(name).append('>').append(escape(value)).append("</").append(name).append('>')
    }

    fun escape(s: String): String = buildString(s.length) {
        for (ch in s) when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(ch)
        }
    }

    /** Shortest exact form, independent of the phone's locale (`130.0`, `81234.567891234`). */
    private fun number(v: Double) = if (v.isNaN()) "0" else v.toString()

    private fun u32(v: Int) = le(4).putInt(v).array()

    private fun le(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
}
