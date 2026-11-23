package io.github.markspan.sensorium

import io.github.markspan.sensorium.xdf.XdfFormat
import io.github.markspan.sensorium.xdf.XdfRecorder
import io.github.markspan.sensorium.xdf.XdfStreamInfo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class XdfRecorderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val ecg = XdfStreamInfo(
        "Polar H10 A1B2_ecg", "ECG", 1, 130.0, "Polar H10 A1B2_ecg", listOf("ECG"), listOf("microvolts"),
        "ECG", "Polar", "H10", "lsl_local_clock on arrival",
    )
    private val gps = XdfStreamInfo(
        "Location Pixel <8> & co", "Location", 2, 0.0, "s-location", listOf("Latitude", "Longitude"),
        listOf("degrees"), "Location", "Google", "GPS", "",
    )

    // --- a minimal, independent XDF reader -----------------------------------------------------

    private class Chunk(val tag: Int, val content: ByteBuffer) {
        val streamId get() = content.duplicate().order(ByteOrder.LITTLE_ENDIAN).int
        val xml: String
            get() {
                val b = content.duplicate()
                if (tag != XdfFormat.TAG_FILE_HEADER) b.position(b.position() + 4)
                return String(ByteArray(b.remaining()).also { b.get(it) })
            }
    }

    private class Sample(val stamp: Double?, val values: FloatArray)

    private fun varLen(b: ByteBuffer): Long = when (val n = b.get().toInt()) {
        1 -> (b.get().toLong() and 0xFF)
        4 -> (b.int.toLong() and 0xFFFFFFFFL)
        8 -> b.long
        else -> error("bad length size $n")
    }

    private fun read(file: File): List<Chunk> {
        val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4).also { b.get(it) }
        assertEquals("XDF:", String(magic))
        val chunks = mutableListOf<Chunk>()
        while (b.hasRemaining()) {
            val len = varLen(b).toInt()
            val tag = b.short.toInt()
            val content = b.slice().order(ByteOrder.LITTLE_ENDIAN).limit(len - 2) as ByteBuffer
            b.position(b.position() + len - 2)
            chunks += Chunk(tag, content)
        }
        return chunks
    }

    private fun samples(chunk: Chunk, channels: Int): List<Sample> {
        val b = chunk.content.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        b.int // stream id
        val n = varLen(b).toInt()
        return List(n) {
            val stamp = when (val tsBytes = b.get().toInt()) {
                0 -> null
                8 -> b.double
                else -> error("bad time stamp size $tsBytes")
            }
            Sample(stamp, FloatArray(channels) { b.float })
        }
    }

    private fun recorder(file: File, clock: () -> Double = { 100.0 }) =
        XdfRecorder(file, clock, "pixel", clockOffsetInterval = 1e9, boundaryInterval = 1e9)

    // --- tests ------------------------------------------------------------------------------------

    @Test
    fun writesHeadersSamplesAndFooters() {
        val file = tmp.newFile("a.xdf")
        val ecgKey = Any()
        val gpsKey = Any()
        recorder(file).use { r ->
            r.addStream(ecgKey, ecg)
            r.addStream(gpsKey, gps)
            r.samples(ecgKey, floatArrayOf(1f, 2f, 3f), 1, 10.0)
            r.samples(gpsKey, floatArrayOf(53.2f, 6.5f), 2, 10.5)
            r.samples(ecgKey, floatArrayOf(4f, Float.NaN), 1, 10.0 + 2 / 130.0)
        }
        val chunks = read(file)
        assertEquals(XdfFormat.TAG_FILE_HEADER, chunks[0].tag)
        assertTrue(chunks[0].xml.contains("<version>1.0</version>"))

        val headers = chunks.filter { it.tag == XdfFormat.TAG_STREAM_HEADER }
        assertEquals(listOf(1, 2), headers.map { it.streamId })
        val ecgXml = headers[0].xml
        listOf(
            "<name>Polar H10 A1B2_ecg</name>", "<type>ECG</type>", "<channel_count>1</channel_count>",
            "<channel_format>float32</channel_format>", "<nominal_srate>130.0</nominal_srate>",
            "<label>ECG</label>", "<unit>microvolts</unit>", "<manufacturer>Polar</manufacturer>",
            "<timestamps>lsl_local_clock on arrival</timestamps>",
        ).forEach { assertTrue(it, ecgXml.contains(it)) }
        // XML special characters are escaped; a single unit applies to every channel.
        assertTrue(headers[1].xml.contains("<name>Location Pixel &lt;8&gt; &amp; co</name>"))
        assertEquals(2, Regex("<unit>degrees</unit>").findAll(headers[1].xml).count())
        assertTrue(headers[1].xml.contains("<nominal_srate>0.0</nominal_srate>"))

        val data = chunks.filter { it.tag == XdfFormat.TAG_SAMPLES }
        assertEquals(listOf(1, 2, 1), data.map { it.streamId })
        val first = samples(data[0], 1)
        // The last sample has the pushed time stamp; the first is spaced back by the nominal rate
        // and is the only one written, as readers deduce the others.
        assertEquals(10.0 - 2 / 130.0, first[0].stamp!!, 1e-12)
        assertNull(first[1].stamp)
        assertNull(first[2].stamp)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f), first.map { it.values[0] }.toFloatArray(), 0f)
        val fix = samples(data[1], 2).single()
        assertEquals(10.5, fix.stamp!!, 0.0)
        assertArrayEquals(floatArrayOf(53.2f, 6.5f), fix.values, 0f)
        assertTrue(samples(data[2], 1)[1].values[0].isNaN())

        val footers = chunks.filter { it.tag == XdfFormat.TAG_STREAM_FOOTER }
        assertEquals(listOf(1, 2), footers.map { it.streamId })
        assertTrue(footers[0].xml.contains("<sample_count>5</sample_count>"))
        assertTrue(footers[0].xml.contains("<first_timestamp>${10.0 - 2 / 130.0}</first_timestamp>"))
        assertTrue(footers[1].xml.contains("<sample_count>1</sample_count>"))
        assertEquals(XdfFormat.TAG_STREAM_FOOTER, chunks.last().tag)
    }

    @Test
    fun irregularStreamStampsEverySample() {
        val file = tmp.newFile("b.xdf")
        val key = Any()
        recorder(file).use { r ->
            r.addStream(key, gps)
            r.samples(key, floatArrayOf(1f, 2f, 3f, 4f), 2, 7.0)
        }
        val s = samples(read(file).first { it.tag == XdfFormat.TAG_SAMPLES }, 2)
        assertEquals(listOf(7.0, 7.0), s.map { it.stamp })
    }

    @Test
    fun clockOffsetsAndBoundariesFollowTheHeaders() {
        val file = tmp.newFile("c.xdf")
        val key = Any()
        val start = System.nanoTime()
        val r = XdfRecorder(file, { (System.nanoTime() - start) / 1e9 }, "pixel", 0.05, 0.1)
        r.addStream(key, ecg)
        repeat(10) {
            r.samples(key, floatArrayOf(it.toFloat()), 1, it / 130.0)
            Thread.sleep(50)
        }
        r.close()
        val chunks = read(file)
        val header = chunks.indexOfFirst { it.tag == XdfFormat.TAG_STREAM_HEADER }
        val offsets = chunks.withIndex().filter { it.value.tag == XdfFormat.TAG_CLOCK_OFFSET }
        assertTrue("clock offsets written", offsets.size >= 2)
        assertTrue("no offset before the header", offsets.all { it.index > header })
        offsets.forEach {
            val b = it.value.content.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(1, b.int)
            b.double // collection time
            assertEquals(0.0, b.double, 0.0) // one clock for all streams
        }
        val boundary = chunks.first { it.tag == XdfFormat.TAG_BOUNDARY }.content
        assertArrayEquals(XdfFormat.BOUNDARY_UUID, ByteArray(16).also { boundary.duplicate().get(it) })
        val footer = chunks.last { it.tag == XdfFormat.TAG_STREAM_FOOTER }.xml
        assertEquals(offsets.size, Regex("<offset>").findAll(footer).count())
    }

    @Test
    fun dataAfterCloseIsDropped() {
        val file = tmp.newFile("d.xdf")
        val key = Any()
        val r = recorder(file)
        r.addStream(key, ecg)
        r.close()
        val size = file.length()
        r.samples(key, floatArrayOf(1f), 1, 1.0)
        r.addStream(Any(), gps)
        r.close() // closing twice is harmless
        assertEquals(size, file.length())
        assertEquals(size, r.bytesWritten)
        assertNull(r.error)
    }

    @Test
    fun lengthsUseOneFourOrEightBytes() {
        assertArrayEquals(byteArrayOf(1, 200.toByte()), XdfFormat.varLen(200))
        assertArrayEquals(byteArrayOf(4, 0, 1, 0, 0), XdfFormat.varLen(256))
        assertArrayEquals(byteArrayOf(8, 0, 0, 0, 0, 1, 0, 0, 0), XdfFormat.varLen(1L shl 32))
        val chunk = XdfFormat.chunk(XdfFormat.TAG_BOUNDARY, ByteArray(300))
        assertEquals(4, chunk[0].toInt()) // 302 bytes do not fit one length byte
    }

    /** A realistic file for checking with pyxdf: `python -c "import pyxdf; pyxdf.load_xdf(...)"`. */
    @Test
    fun writesSampleFileForExternalReaders() {
        val file = File("build/xdf-test/sample.xdf").apply { parentFile!!.mkdirs() }
        val ecgKey = Any()
        val gpsKey = Any()
        var now = 1000.0
        recorder(file) { now }.use { r ->
            r.addStream(ecgKey, ecg)
            r.addStream(gpsKey, gps)
            for (chunk in 0 until 20) {
                val last = 1000.0 + (chunk + 1) * 73 / 130.0
                r.samples(ecgKey, FloatArray(73) { (chunk * 73 + it).toFloat() }, 1, last)
                if (chunk % 2 == 0) r.samples(gpsKey, floatArrayOf(53.2f + chunk * 1e-4f, 6.56f), 2, last)
                now = last
            }
        }
        assertTrue(file.length() > 0)
    }
}
