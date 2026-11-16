package io.github.markspan.sensorium.sensors

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import io.github.markspan.sensorium.lsl.LslOutlet
import io.github.markspan.sensorium.stream.OutletInfo
import io.github.markspan.sensorium.stream.StreamState

/**
 * Streams the microphone as a mono float32 LSL stream (full scale = ±1). Audio is read in blocks
 * of ~20 ms; liblsl stamps each block at push time and spaces the samples by the nominal rate.
 */
class AudioStreamer(
    private val sampleRate: Int,
    private val deviceName: String,
    private val sourcePrefix: String,
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
        )
        record = rec
        running = true
        rec.startRecording()
        val decimate = (sampleRate * 5 / StreamState.audioPreview.capacity).coerceAtLeast(1)
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val pcm = ShortArray(block)
            var phase = 0
            while (running) {
                val n = rec.read(pcm, 0, pcm.size)
                if (n <= 0) continue
                val samples = FloatArray(n) { pcm[it] / 32768f }
                outlet?.push(samples)
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

    companion object {
        val RATES = listOf(8000, 16000, 22050, 44100, 48000)
        const val DEFAULT_RATE = 16000
    }
}
