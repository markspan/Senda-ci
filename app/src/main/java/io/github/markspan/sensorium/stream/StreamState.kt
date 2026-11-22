package io.github.markspan.sensorium.stream

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

data class OutletInfo(val name: String, val type: String, val hasConsumers: Boolean = false)

/** Samples lost in Bluetooth transfer (and filled with NaN), and in how many separate gaps. */
data class PacketLoss(val samples: Long = 0, val gaps: Int = 0) {
    fun add(lost: Int) = if (lost > 0) PacketLoss(samples + lost, gaps + 1) else this

    override fun toString() =
        if (gaps == 0) "" else "$samples lost in $gaps ${if (gaps == 1) "gap" else "gaps"}"
}

data class StreamUiState(
    val running: Boolean = false,
    val status: String = "Idle",
    // Phone
    val phoneSensors: Int = 0,
    val phoneSamples: Long = 0,
    val audio: Boolean = false,
    val audioSamples: Long = 0,
    val location: Boolean = false,
    val locationFixes: Long = 0,
    /** Latest fix for the status card, e.g. `52.21573, 6.89340 ±4 m`. */
    val locationFix: String? = null,
    // Polar H10
    val polarDevice: String? = null,
    val polarStatus: String? = null,
    val polarStreaming: Boolean = false,
    val battery: Int? = null,
    val ecgSamples: Long = 0,
    val accSamples: Long = 0,
    val ecgLoss: PacketLoss = PacketLoss(),
    val accLoss: PacketLoss = PacketLoss(),
    val outlets: List<OutletInfo> = emptyList(),
    val messages: List<String> = emptyList(),
)

/**
 * Live preview of one streaming phone sensor: its recent samples, the time in seconds the plot
 * covers, and how many samples were sent.
 */
class SensorPreview(val key: String, val label: String, val buffer: PreviewBuffer, val spanSeconds: Double) {
    val samples = AtomicLong()
}

/** Process-wide state shared between [StreamService] and the UI. */
object StreamState {
    private val _state = MutableStateFlow(StreamUiState())
    val state: StateFlow<StreamUiState> = _state.asStateFlow()

    /** Last seconds of signal for the on-screen preview. */
    val ecgPreview = PreviewBuffer(channels = 1, capacity = 130 * 5)
    val polarAccPreview = PreviewBuffer(channels = 3, capacity = 200 * 5)
    val audioPreview = PreviewBuffer(channels = 1, capacity = 1000)

    /** GPS fixes for the map: latitude, longitude, accuracy (m); the last 3600 fixes. */
    val locationTrack = PreviewBuffer(channels = 3, capacity = 3600)

    /**
     * High-rate counters are kept outside the state flow (sensor and audio threads would
     * otherwise publish a new UI state hundreds of times a second); the service copies them
     * into the state once a second.
     */
    val phoneSamples = AtomicLong()
    val audioSamples = AtomicLong()

    /**
     * Previews of the streaming phone sensors, in the order they started. Sensors without a known
     * channel layout are added when their first event arrives.
     */
    @Volatile
    var sensorPreviews: List<SensorPreview> = emptyList()
        private set

    @Synchronized
    fun addSensorPreview(preview: SensorPreview) {
        sensorPreviews = sensorPreviews + preview
    }

    fun update(transform: (StreamUiState) -> StreamUiState) = _state.update(transform)

    fun message(text: String) = _state.update {
        it.copy(messages = (it.messages + text).takeLast(MAX_MESSAGES))
    }

    fun reset() {
        listOf(ecgPreview, polarAccPreview, audioPreview, locationTrack).forEach { it.clear() }
        synchronized(this) { sensorPreviews = emptyList() }
        phoneSamples.set(0)
        audioSamples.set(0)
    }

    private const val MAX_MESSAGES = 6
}

/**
 * Fixed-size ring buffer of multiplexed samples, written by the service and read by the UI.
 * A [timed] buffer also keeps a time per sample, for streams without a regular rate.
 */
class PreviewBuffer(val channels: Int, val capacity: Int, val timed: Boolean = false) {
    private val data = FloatArray(channels * capacity)
    private val times = LongArray(if (timed) capacity else 0)
    private var head = 0
    private var size = 0

    /** Adds whole samples; in a [timed] buffer each gets [timeNs] (`elapsedRealtimeNanos`). */
    @Synchronized
    fun add(samples: FloatArray, timeNs: Long = 0L) {
        var i = 0
        while (i + channels <= samples.size) {
            System.arraycopy(samples, i, data, head * channels, channels)
            if (timed) times[head] = timeNs
            head = (head + 1) % capacity
            if (size < capacity) size++
            i += channels
        }
    }

    @Synchronized
    fun clear() {
        head = 0
        size = 0
    }

    /**
     * Copies the buffered samples, oldest first, into [out] (and their times into [outTimes],
     * for a timed buffer) and returns the sample count.
     */
    @Synchronized
    fun snapshot(out: FloatArray, outTimes: LongArray? = null): Int {
        val start = (head - size + capacity) % capacity
        for (s in 0 until size) {
            val p = (start + s) % capacity
            System.arraycopy(data, p * channels, out, s * channels, channels)
            if (timed && outTimes != null) outTimes[s] = times[p]
        }
        return size
    }
}
