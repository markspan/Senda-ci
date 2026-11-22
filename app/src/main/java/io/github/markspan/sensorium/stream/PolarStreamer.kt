package io.github.markspan.sensorium.stream

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.markspan.sensorium.ble.PmdCommand
import io.github.markspan.sensorium.ble.PmdFrame
import io.github.markspan.sensorium.ble.PmdGapFiller
import io.github.markspan.sensorium.ble.PolarH10Client
import io.github.markspan.sensorium.lsl.LslOutlet
import io.github.markspan.sensorium.lsl.TIMESTAMPS_BLE_ARRIVAL

/**
 * Relays the ECG and accelerometer of one Polar H10 to LSL, with the same stream names and source
 * ids as the PolarBLE desktop app and PolarMobile. The outlets survive BLE reconnects: consumers
 * such as LabRecorder simply see a gap instead of a vanished stream.
 *
 * Bluetooth packets lost within a connection are detected from the H10's own time stamps and
 * replaced by NaN samples (see [PmdGapFiller]), so that sample numbers keep matching time.
 */
class PolarStreamer(
    private val context: Context,
    private val address: String,
    private val deviceName: String,
    private val config: PolarH10Client.Config,
    private val onStatus: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var client: PolarH10Client? = null
    private var active = false

    private var ecgOutlet: LslOutlet? = null
    private var accOutlet: LslOutlet? = null

    private val ecgGaps = PmdGapFiller(PmdCommand.ECG_SAMPLE_RATE, channels = 1)
    private val accGaps = PmdGapFiller(config.accRate, channels = 3)

    fun start() {
        active = true
        StreamState.update {
            it.copy(polarDevice = deviceName, polarStatus = "Connecting…", polarStreaming = false)
        }
        createOutlets()
        connect()
    }

    private fun createOutlets() {
        val n = deviceName
        if (config.ecg) ecgOutlet = LslOutlet(
            "${n}_ecg", "ECG", 1, PmdCommand.ECG_SAMPLE_RATE.toDouble(), "${n}_ecg",
            listOf("ECG"), listOf("microvolts"), "ECG", "Polar", "H10", timestamps = TIMESTAMPS_BLE_ARRIVAL,
        )
        if (config.acc) accOutlet = LslOutlet(
            "${n}_acc", "Accelerometer", 3, config.accRate.toDouble(), "${n}_acc",
            listOf("X", "Y", "Z"), listOf("mG"), "ACC", "Polar", "H10", timestamps = TIMESTAMPS_BLE_ARRIVAL,
        )
    }

    fun outletInfos(): List<OutletInfo> = listOfNotNull(ecgOutlet, accOutlet)
        .map { OutletInfo(it.name, it.type, it.hasConsumers) }

    @SuppressLint("MissingPermission")
    private fun connect() {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            setStatus("Bluetooth is off")
            StreamState.message("Bluetooth is off: Polar H10 not connected")
            return
        }
        // A new connection starts new measurements: the time since the last frame is a
        // reconnect gap, not lost packets.
        ecgGaps.reset()
        accGaps.reset()
        client = PolarH10Client(context, adapter.getRemoteDevice(address), config, listener).also { it.connect() }
    }

    private fun setStatus(text: String) {
        StreamState.update { it.copy(polarStatus = text) }
        onStatus(text)
    }

    private fun checkGap(label: String, result: PmdGapFiller.Result) {
        if (result.lost > 0) {
            Log.w(TAG, "$label: ${result.lost} samples lost in Bluetooth transfer, filled with NaN")
        }
        if (result.discontinuity) {
            StreamState.message("$label: H10 clock jumped; samples not filled")
        }
    }

    private val listener = object : PolarH10Client.Listener {
        override fun onStatus(text: String) = setStatus(text)

        override fun onStreaming() = StreamState.update { it.copy(polarStreaming = true) }

        override fun onPmdFrame(frame: PmdFrame) {
            when (frame.measurementType) {
                PmdCommand.MEASUREMENT_ECG -> {
                    val r = ecgGaps.fill(frame)
                    ecgOutlet?.push(r.samples)
                    StreamState.ecgPreview.add(r.samples)
                    checkGap("ECG", r)
                    StreamState.update {
                        it.copy(
                            ecgSamples = it.ecgSamples + frame.sampleCount,
                            ecgLoss = it.ecgLoss.add(r.lost),
                        )
                    }
                }
                PmdCommand.MEASUREMENT_ACC -> {
                    val r = accGaps.fill(frame)
                    accOutlet?.push(r.samples)
                    StreamState.polarAccPreview.add(r.samples)
                    checkGap("ACC", r)
                    StreamState.update {
                        it.copy(
                            accSamples = it.accSamples + frame.sampleCount,
                            accLoss = it.accLoss.add(r.lost),
                        )
                    }
                }
            }
        }

        override fun onBattery(percent: Int) = StreamState.update { it.copy(battery = percent) }

        override fun onWarning(text: String) = StreamState.message(text)

        override fun onDisconnected(reason: String) {
            main.post {
                if (!active) return@post // stopped by the user
                client?.close()
                client = null
                StreamState.update { it.copy(polarStreaming = false) }
                setStatus("Reconnecting… ($reason)")
                main.postDelayed({ if (active && client == null) connect() }, 2000)
            }
        }
    }

    fun stop() {
        active = false
        main.removeCallbacksAndMessages(null)
        client?.close()
        client = null
        listOfNotNull(ecgOutlet, accOutlet).forEach { it.close() }
        ecgOutlet = null; accOutlet = null
        StreamState.update { it.copy(polarStreaming = false, polarStatus = null) }
    }

    private companion object {
        const val TAG = "PolarStreamer"
    }
}
