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
    ): Long

    external fun pushChunk(handle: Long, data: FloatArray): Int
    external fun pushSample(handle: Long, data: FloatArray, timestamp: Double): Int
    external fun haveConsumers(handle: Long): Boolean
    external fun destroyOutlet(handle: Long)

    /** "1.17" style version string, or null when the native library could not be loaded. */
    fun versionString(): String? = runCatching {
        val v = libraryVersion()
        "${v / 100}.${v % 100}"
    }.getOrNull()
}

/** Use for streams without a fixed sampling rate (heart rate, RR intervals, on-change sensors). */
const val IRREGULAR_RATE = 0.0

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
) : Closeable {

    @Volatile
    private var handle: Long = LslNative.createOutlet(
        name, type, channelCount, nominalRate, sourceId, channelLabels.toTypedArray(),
        channelUnits.toTypedArray(), channelType, manufacturer, model, 0, maxBufferedSeconds,
    )

    init {
        check(handle != 0L) { "Could not create LSL outlet '$name'" }
    }

    /**
     * Pushes one or more samples of [channelCount] values each, multiplexed. liblsl assigns the
     * time stamps (see `pushChunk` in lsl_jni.cpp).
     */
    @Synchronized
    fun push(data: FloatArray) {
        val h = handle
        if (h != 0L && data.isNotEmpty()) LslNative.pushChunk(h, data)
    }

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
