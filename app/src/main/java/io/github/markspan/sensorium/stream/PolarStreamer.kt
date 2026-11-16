package io.github.markspan.sensorium.stream

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.markspan.sensorium.ble.HeartRateMeasurement
import io.github.markspan.sensorium.ble.PmdCommand
import io.github.markspan.sensorium.ble.PmdFrame
import io.github.markspan.sensorium.ble.PolarH10Client
import io.github.markspan.sensorium.lsl.IRREGULAR_RATE
import io.github.markspan.sensorium.lsl.LslOutlet

/**
 * Relays one Polar H10 to LSL, with the same stream names and source ids as the PolarBLE desktop
 * app and PolarMobile. The outlets survive BLE reconnects: consumers such as LabRecorder simply
 * see a gap instead of a vanished stream.
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
    private var hrOutlet: LslOutlet? = null
    private var rrOutlet: LslOutlet? = null

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
            listOf("ECG"), listOf("microvolts"), "ECG", "Polar", "H10",
        )
        if (config.acc) accOutlet = LslOutlet(
            "${n}_acc", "Accelerometer", 3, config.accRate.toDouble(), "${n}_acc",
            listOf("X", "Y", "Z"), listOf("mG"), "ACC", "Polar", "H10",
        )
        if (config.heartRate) {
            hrOutlet = LslOutlet(
                "${n}_hr", "HeartRate", 1, IRREGULAR_RATE, "${n}_hr", listOf("HR"), listOf("bpm"), "HR", "Polar", "H10",
            )
            rrOutlet = LslOutlet(
                "${n}_rr", "RR", 1, IRREGULAR_RATE, "${n}_rr", listOf("RR"), listOf("ms"), "RR", "Polar", "H10",
            )
        }
    }

    fun outletInfos(): List<OutletInfo> = listOfNotNull(ecgOutlet, accOutlet, hrOutlet, rrOutlet)
        .map { OutletInfo(it.name, it.type, it.hasConsumers) }

    @SuppressLint("MissingPermission")
    private fun connect() {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            setStatus("Bluetooth is off")
            StreamState.message("Bluetooth is off: Polar H10 not connected")
            return
        }
        client = PolarH10Client(context, adapter.getRemoteDevice(address), config, listener).also { it.connect() }
    }

    private fun setStatus(text: String) {
        StreamState.update { it.copy(polarStatus = text) }
        onStatus(text)
    }

    private val listener = object : PolarH10Client.Listener {
        override fun onStatus(text: String) = setStatus(text)

        override fun onStreaming() = StreamState.update { it.copy(polarStreaming = true) }

        override fun onPmdFrame(frame: PmdFrame) {
            when (frame.measurementType) {
                PmdCommand.MEASUREMENT_ECG -> {
                    ecgOutlet?.push(frame.samples)
                    StreamState.ecgPreview.add(frame.samples)
                    StreamState.update { it.copy(ecgSamples = it.ecgSamples + frame.sampleCount) }
                }
                PmdCommand.MEASUREMENT_ACC -> {
                    accOutlet?.push(frame.samples)
                    StreamState.polarAccPreview.add(frame.samples)
                    StreamState.update { it.copy(accSamples = it.accSamples + frame.sampleCount) }
                }
            }
        }

        override fun onHeartRate(measurement: HeartRateMeasurement) {
            hrOutlet?.push(floatArrayOf(measurement.bpm.toFloat()))
            if (measurement.rrMs.isNotEmpty()) rrOutlet?.push(measurement.rrMs.toFloatArray())
            StreamState.update { it.copy(heartRate = measurement.bpm) }
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
        listOfNotNull(ecgOutlet, accOutlet, hrOutlet, rrOutlet).forEach { it.close() }
        ecgOutlet = null; accOutlet = null; hrOutlet = null; rrOutlet = null
        StreamState.update { it.copy(polarStreaming = false, polarStatus = null) }
    }
}
