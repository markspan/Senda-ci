package io.github.markspan.sensorium.xdf

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Writes an XDF file of the streams it is given, like LabRecorder: a header per stream, sample
 * chunks as they are pushed, clock offsets every [clockOffsetInterval] s and a boundary chunk every
 * [boundaryInterval] s, and a footer per stream at the end. At every boundary the file is flushed
 * to storage, so a crash loses at most the last [boundaryInterval] seconds.
 *
 * Chunks are encoded on the calling thread (keeping each stream's order) and written by a
 * background thread, so slow storage never holds up a sensor, audio or Bluetooth thread. All
 * streams share the recording clock ([clock], `lsl_local_clock` in the app), so every clock
 * offset is 0. After an I/O error the recording stops ([error]) and further data is dropped.
 *
 * Thread-safe; plain JVM code, so it can be unit tested.
 */
class XdfRecorder(
    val file: File,
    private val clock: () -> Double,
    private val hostname: String,
    private val clockOffsetInterval: Double = 5.0,
    private val boundaryInterval: Double = 10.0,
) : Closeable {

    private class Stream(val id: Int, val nominalRate: Double) {
        @Volatile var headerWritten = false
        var first = Double.NaN
        var last = Double.NaN
        var count = 0L
        val offsets = mutableListOf<Pair<Double, Double>>() // only touched by the writer thread
    }

    /** A chunk to write; [header] marks the stream whose header it is. */
    private class Item(val bytes: ByteArray, val header: Stream? = null)

    private val streams = LinkedHashMap<Any, Stream>() // guarded by this
    private val queue = LinkedBlockingQueue<Item>()
    private val end = Item(ByteArray(0))
    private var closed = false // guarded by this
    private val fileOut = FileOutputStream(file)
    private val out = BufferedOutputStream(fileOut, 1 shl 16)

    @Volatile
    var bytesWritten = 0L
        private set

    /** The I/O error that stopped the recording, if any. */
    @Volatile
    var error: Exception? = null
        private set

    init {
        write(XdfFormat.MAGIC)
        write(XdfFormat.fileHeader())
    }

    private val thread = Thread(::run, "sensorium-xdf").apply { start() }

    /** Adds a stream, identified by [key] (the outlet); a key already added is ignored. */
    @Synchronized
    fun addStream(key: Any, info: XdfStreamInfo) {
        if (closed || key in streams) return
        val stream = Stream(streams.size + 1, info.nominalRate)
        streams[key] = stream
        val xml = XdfFormat.headerXml(info, clock(), UUID.randomUUID().toString(), hostname)
        queue.add(Item(XdfFormat.streamHeader(stream.id, xml), header = stream))
    }

    /**
     * Records [data] (whole samples of [channels] values) of the stream [key]. The last sample has
     * time stamp [lastTimestamp]; earlier samples are spaced back by the nominal rate, as liblsl
     * stamps a pushed chunk. Only the first sample of the chunk carries an explicit time stamp in
     * the file; readers deduce the others. On an irregular stream every sample gets [lastTimestamp].
     */
    fun samples(key: Any, data: FloatArray, channels: Int, lastTimestamp: Double) {
        val n = data.size / channels
        if (n == 0) return
        synchronized(this) {
            if (closed) return
            val s = streams[key] ?: return
            val regular = s.nominalRate > 0
            val first = if (regular) lastTimestamp - (n - 1) / s.nominalRate else lastTimestamp
            val stamps = DoubleArray(n) { i -> if (!regular) lastTimestamp else if (i == 0) first else Double.NaN }
            if (s.first.isNaN()) s.first = first
            s.last = lastTimestamp
            s.count += n
            val whole = if (data.size == n * channels) data else data.copyOf(n * channels)
            queue.add(Item(XdfFormat.samples(s.id, whole, channels, stamps)))
        }
    }

    /** Finishes the file (offsets, footers) and closes it; blocks until everything is written. */
    override fun close() {
        synchronized(this) {
            if (!closed) {
                closed = true
                queue.add(end)
            }
        }
        thread.join()
    }

    private fun run() {
        var nextOffsets = clock()
        var nextBoundary = clock() + boundaryInterval
        try {
            while (true) {
                val item = queue.poll(200, TimeUnit.MILLISECONDS)
                if (item === end) break
                if (item != null) {
                    write(item.bytes)
                    item.header?.headerWritten = true
                }
                val now = clock()
                if (now >= nextOffsets) {
                    writeClockOffsets(now)
                    nextOffsets = now + clockOffsetInterval
                }
                if (now >= nextBoundary) {
                    write(XdfFormat.boundary())
                    // Onto the storage itself: after a crash or a flat battery the file is
                    // complete up to here, and XDF readers load a file without footers.
                    out.flush()
                    fileOut.fd.sync()
                    nextBoundary = now + boundaryInterval
                }
            }
            writeClockOffsets(clock())
            val all = synchronized(this) { streams.values.toList() }
            for (s in all) {
                val (first, last, count) = synchronized(this) { Triple(s.first, s.last, s.count) }
                write(XdfFormat.streamFooter(s.id, XdfFormat.footerXml(first, last, count, s.offsets)))
            }
            out.flush()
            fileOut.fd.sync()
            out.close()
        } catch (e: Exception) {
            error = e
            synchronized(this) { closed = true }
            queue.clear()
            runCatching { out.close() }
        }
    }

    /** Offset 0 for every stream whose header is already in the file. */
    private fun writeClockOffsets(now: Double) {
        val all = synchronized(this) { streams.values.toList() }
        for (s in all) {
            if (!s.headerWritten) continue
            s.offsets += now to 0.0
            write(XdfFormat.clockOffset(s.id, now, 0.0))
        }
    }

    private fun write(bytes: ByteArray) {
        out.write(bytes)
        bytesWritten += bytes.size
    }
}
