package io.github.markspan.sensorium.lsl

import java.io.Closeable

/** JNI bindings to liblsl, implemented in `src/main/cpp/lsl_jni.cpp`. */
object LslNative {
    init {
        System.loadLibrary("sensoriumlsl")
    }

    external fun libraryVersion(): Int
    external fun libraryInfo(): String
    external fun localClock(): Double

    external fun createOutlet(
        name: String,
        type: String,
        channelCount: Int,
        nominalRate: Double,
        sourceId: String,
        channelLabels: Array<String>,
        channelUnits: Array<String>,
        channelType: String,
        manufacturer: String,
        model: String,
        chunkSize: Int,
        maxBuffered: Int,
        timestamps: String,
    ): Long

    external fun pushChunk(handle: Long, data: FloatArray, timestamp: Double): Int
    external fun pushSample(handle: Long, data: FloatArray, timestamp: Double): Int
    external fun haveConsumers(handle: Long): Boolean
    external fun destroyOutlet(handle: Long)

    /** "1.17" style version string, or null when the native library could not be loaded. */
    fun versionString(): String? = runCatching {
        val v = libraryVersion()
        "${v / 100}.${v % 100}"
    }.getOrNull()
}

/** Use for streams without a fixed sampling rate (on-change sensors). */
const val IRREGULAR_RATE = 0.0

// Values of <acquisition><timestamps> in the stream meta-data.
const val TIMESTAMPS_SENSOR = "sensor hardware time stamp, converted to lsl_local_clock"
const val TIMESTAMPS_CAPTURE = "audio capture time (AudioRecord.getTimestamp), converted to lsl_local_clock"
const val TIMESTAMPS_ARRIVAL = "lsl_local_clock on arrival in the app"
const val TIMESTAMPS_BLE_ARRIVAL = "lsl_local_clock on arrival of each Bluetooth packet (last sample), earlier samples at the nominal rate; samples of lost packets are NaN"

/** A float32 LSL stream outlet. */
class LslOutlet(
    val name: String,
    val type: String,
    val channelCount: Int,
    val nominalRate: Double,
    sourceId: String,
    channelLabels: List<String>,
    channelUnits: List<String>,
    channelType: String,
    manufacturer: String,
    model: String = "",
    maxBufferedSeconds: Int = 360,
    /** How samples are time stamped; written to `<acquisition><timestamps>` in the meta-data. */
    timestamps: String = "",
) : Closeable {

    @Volatile
    private var handle: Long = LslNative.createOutlet(
        name, type, channelCount, nominalRate, sourceId, channelLabels.toTypedArray(),
        channelUnits.toTypedArray(), channelType, manufacturer, model, 0, maxBufferedSeconds, timestamps,
    )

    init {
        check(handle != 0L) { "Could not create LSL outlet '$name'" }
    }

    /**
     * Pushes one or more samples of [channelCount] values each, multiplexed. The last sample is
     * stamped [lastTimestamp] (seconds, `lsl_local_clock` domain), or the current time when it is
     * 0; liblsl spaces the earlier samples back by the nominal rate (see `pushChunk` in lsl_jni.cpp).
     */
    @Synchronized
    fun pushChunk(data: FloatArray, lastTimestamp: Double = 0.0) {
        val h = handle
        if (h != 0L && data.isNotEmpty()) LslNative.pushChunk(h, data, lastTimestamp)
    }

    /** [pushChunk] stamped at push time. */
    fun push(data: FloatArray) = pushChunk(data)

    /** Pushes a single sample stamped with [timestamp] (seconds, `lsl_local_clock` domain). */
    @Synchronized
    fun push(sample: FloatArray, timestamp: Double) {
        val h = handle
        if (h != 0L && sample.isNotEmpty()) LslNative.pushSample(h, sample, timestamp)
    }

    val hasConsumers: Boolean
        get() = handle.let { it != 0L && LslNative.haveConsumers(it) }

    @Synchronized
    override fun close() {
        val h = handle
        handle = 0L
        if (h != 0L) LslNative.destroyOutlet(h)
    }
}
