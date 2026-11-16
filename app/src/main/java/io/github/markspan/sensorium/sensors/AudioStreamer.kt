package io.github.markspan.sensorium.sensors

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.Process
import io.github.markspan.sensorium.lsl.LslNative
import io.github.markspan.sensorium.lsl.LslOutlet
import io.github.markspan.sensorium.lsl.TIMESTAMPS_ARRIVAL
import io.github.markspan.sensorium.lsl.TIMESTAMPS_CAPTURE
import io.github.markspan.sensorium.stream.OutletInfo
import io.github.markspan.sensorium.stream.StreamState

/**
 * Streams the microphone as a mono float32 LSL stream (full scale = ±1). Audio is read in blocks
 * of ~20 ms. The last sample of each block is stamped with its capture time as reported by
 * [AudioRecord.getTimestamp], converted to the LSL clock, so the delay of the phone's audio input
 * path is not in the time stamps; liblsl spaces the earlier samples back by the nominal rate.
 * When the phone gives no capture time, the block is stamped at push time instead.
 *
 * @param arrivalStamps stamp each block at push time, as SENDA does, instead of with its capture
 *   time.
 */
class AudioStreamer(
    private val sampleRate: Int,
    private val deviceName: String,
    private val sourcePrefix: String,
    private val arrivalStamps: Boolean = false,
) {
    private var outlet: LslOutlet? = null
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile
    private var running = false

    /** Caller ensures RECORD_AUDIO is granted. */
    @SuppressLint("MissingPermission")
    fun start() {
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(minBuf > 0) { "Audio at $sampleRate Hz is not supported on this phone" }
        val block = (sampleRate / 50).coerceAtLeast(64)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, block * 2 * 4),
        )
        require(rec.state == AudioRecord.STATE_INITIALIZED) { "Microphone could not be opened" }
        outlet = LslOutlet(
            name = "Audio $deviceName",
            type = "Audio",
            channelCount = 1,
            nominalRate = sampleRate.toDouble(),
            sourceId = "$sourcePrefix-audio",
            channelLabels = listOf("Mic"),
            channelUnits = listOf("normalized"),
            channelType = "Audio",
            manufacturer = android.os.Build.MANUFACTURER,
            model = android.os.Build.MODEL,
            timestamps = if (arrivalStamps) TIMESTAMPS_ARRIVAL else TIMESTAMPS_CAPTURE,
        )
        record = rec
        running = true
        rec.startRecording()
        val decimate = (sampleRate * 5 / StreamState.audioPreview.capacity).coerceAtLeast(1)
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val pcm = ShortArray(block)
            val clock = CaptureClock(rec, sampleRate)
            var framesRead = 0L
            var phase = 0
            while (running) {
                val n = rec.read(pcm, 0, pcm.size)
                if (n <= 0) continue
                framesRead += n
                val samples = FloatArray(n) { pcm[it] / 32768f }
                outlet?.pushChunk(samples, if (arrivalStamps) 0.0 else clock.lslTimeOf(framesRead - 1))
                StreamState.audioSamples.addAndGet(n.toLong())
                // Decimated copy for the on-screen preview.
                var i = phase
                while (i < n) {
                    StreamState.audioPreview.add(floatArrayOf(samples[i]))
                    i += decimate
                }
                phase = i - n
            }
        }, "sensorium-audio").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        record?.let { runCatching { it.stop() }; it.release() }
        record = null
        outlet?.close()
        outlet = null
    }

    fun outletInfos(): List<OutletInfo> =
        listOfNotNull(outlet?.let { OutletInfo(it.name, it.type, it.hasConsumers) })

    /**
     * Capture time of a recorded frame on the LSL clock. [AudioRecord.getTimestamp] gives the
     * capture time of one frame on the monotonic clock (System.nanoTime); other frames follow at the
     * sample rate. The offset between the monotonic clock and lsl_local_clock() is measured
     * directly (on Android both are normally CLOCK_MONOTONIC, so it is close to 0).
     */
    private class CaptureClock(private val rec: AudioRecord, private val sampleRate: Int) {
        private val ts = AudioTimestamp()
        private var offset = 0.0
        private var lastOffsetUpdate = 0L

        init {
            updateOffset()
        }

        /** LSL time of frame [frame] (0 = first frame recorded), or 0.0 = "stamp at push time". */
        fun lslTimeOf(frame: Long): Double {
            if (rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) != AudioRecord.SUCCESS) return 0.0
            val nowNanos = System.nanoTime()
            if (nowNanos - lastOffsetUpdate > 5_000_000_000L) updateOffset()
            val t = frameTime(frame, ts.framePosition, ts.nanoTime, sampleRate) + offset
            val now = nowNanos / 1e9 + offset
            // A capture time in the future, or older than the audio buffer can be, is not plausible.
            return if (t <= now + 0.005 && now - t < 1.0) t else 0.0
        }

        private fun updateOffset() {
            val before = System.nanoTime()
            val lsl = LslNative.localClock()
            val after = System.nanoTime()
            offset = lsl - (before + after) / 2 / 1e9
            lastOffsetUpdate = after
        }
    }

    companion object {
        /**
         * Capture time in seconds (monotonic clock) of frame [frame], given that frame
         * [refFrame] was captured at [refNanos].
         */
        fun frameTime(frame: Long, refFrame: Long, refNanos: Long, sampleRate: Int): Double =
            refNanos / 1e9 + (frame - refFrame).toDouble() / sampleRate

        val RATES = listOf(8000, 16000, 22050, 44100, 48000)
        const val DEFAULT_RATE = 16000
    }
}
