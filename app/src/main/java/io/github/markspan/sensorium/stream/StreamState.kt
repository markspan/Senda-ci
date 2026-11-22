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

/** Process-wide state shared between [StreamService] and the UI. */
object StreamState {
    private val _state = MutableStateFlow(StreamUiState())
    val state: StateFlow<StreamUiState> = _state.asStateFlow()

    /** Last seconds of signal for the on-screen preview. */
    val ecgPreview = PreviewBuffer(channels = 1, capacity = 130 * 5)
    val polarAccPreview = PreviewBuffer(channels = 3, capacity = 200 * 5)
    val phoneAccPreview = PreviewBuffer(channels = 3, capacity = 500)
    val audioPreview = PreviewBuffer(channels = 1, capacity = 1000)

    /**
     * High-rate counters are kept outside the state flow (sensor and audio threads would
     * otherwise publish a new UI state hundreds of times a second); the service copies them
     * into the state once a second.
     */
    val phoneSamples = AtomicLong()
    val audioSamples = AtomicLong()

    fun update(transform: (StreamUiState) -> StreamUiState) = _state.update(transform)

    fun message(text: String) = _state.update {
        it.copy(messages = (it.messages + text).takeLast(MAX_MESSAGES))
    }

    fun reset() {
        listOf(ecgPreview, polarAccPreview, phoneAccPreview, audioPreview).forEach { it.clear() }
        phoneSamples.set(0)
        audioSamples.set(0)
    }

    private const val MAX_MESSAGES = 6
}

/** Fixed-size ring buffer of multiplexed samples, written by the service and read by the UI. */
class PreviewBuffer(val channels: Int, val capacity: Int) {
    private val data = FloatArray(channels * capacity)
    private var head = 0
    private var size = 0

    @Synchronized
    fun add(samples: FloatArray) {
        var i = 0
        while (i + channels <= samples.size) {
            System.arraycopy(samples, i, data, head * channels, channels)
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

    /** Copies the buffered samples, oldest first, into [out] and returns the sample count. */
    @Synchronized
    fun snapshot(out: FloatArray): Int {
        val start = (head - size + capacity) % capacity
        for (s in 0 until size) {
            System.arraycopy(data, ((start + s) % capacity) * channels, out, s * channels, channels)
        }
        return size
    }
}
