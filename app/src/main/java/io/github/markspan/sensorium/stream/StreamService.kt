package io.github.markspan.sensorium.stream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.markspan.sensorium.MainActivity
import io.github.markspan.sensorium.R
import io.github.markspan.sensorium.ble.PolarH10Client
import io.github.markspan.sensorium.sensors.AudioStreamer
import io.github.markspan.sensorium.sensors.LocationFormat
import io.github.markspan.sensorium.sensors.LocationStreamer
import io.github.markspan.sensorium.sensors.PhoneSensorStreamer
import io.github.markspan.sensorium.xdf.XdfRecording

/**
 * Foreground service that owns all LSL outlets (phone sensors, microphone, GPS, Polar H10), so
 * streaming continues with the screen off.
 */
class StreamService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var sensors: PhoneSensorStreamer? = null
    private var audio: AudioStreamer? = null
    private var location: LocationStreamer? = null
    private var polar: PolarStreamer? = null
    private var running = false
    private var recordingErrorShown = false

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (!running) start(intent) else Unit
            ACTION_STOP -> stop()
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        val deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: Build.MODEL
        val sourcePrefix = intent.getStringExtra(EXTRA_SOURCE_PREFIX) ?: "sensorium"
        val sensorKeys = intent.getStringArrayExtra(EXTRA_SENSORS)?.toSet().orEmpty()
        val audioRate = intent.getIntExtra(EXTRA_AUDIO_RATE, 0)
        val polarAddress = intent.getStringExtra(EXTRA_POLAR_ADDRESS)
        val arrivalStamps = intent.getBooleanExtra(EXTRA_ARRIVAL_STAMPS, false)
        val withLocation = intent.getBooleanExtra(EXTRA_LOCATION, false)
        val record = intent.getBooleanExtra(EXTRA_RECORD, false)

        running = true
        goForeground(withMicrophone = audioRate > 0, withLocation = withLocation)
        acquireLocks()
        StreamState.reset()
        StreamState.update {
            StreamUiState(running = true, status = "Starting…", audio = audioRate > 0)
        }
        // Before any outlet exists: every outlet joins the recording when it is created.
        recordingErrorShown = false
        if (record) try {
            val file = XdfRecording.start(this, deviceName)
            StreamState.update { it.copy(recordingFile = file.name) }
        } catch (e: Exception) {
            StreamState.message("Recording: ${e.message}")
        }

        try {
            if (sensorKeys.isNotEmpty()) {
                sensors = PhoneSensorStreamer(
                    this, sensorKeys, intent.getIntExtra(EXTRA_SENSOR_RATE, 50), deviceName, sourcePrefix,
                    arrivalStamps,
                ).also { it.start() }
            }
        } catch (e: Throwable) {
            StreamState.message("Phone sensors: ${e.message}")
        }
        if (audioRate > 0) try {
            audio = AudioStreamer(audioRate, deviceName, sourcePrefix, arrivalStamps).also { it.start() }
        } catch (e: Throwable) {
            audio?.stop()
            audio = null
            StreamState.message("Microphone: ${e.message}")
        }
        if (withLocation) try {
            location = LocationStreamer(
                this, deviceName, sourcePrefix, arrivalStamps,
                intent.getDoubleExtra(EXTRA_LOCATION_RATE, LocationFormat.DEFAULT_RATE_HZ),
            ).also { it.start() }
        } catch (e: Throwable) {
            location?.stop()
            location = null
            StreamState.message("GPS: ${e.message}")
        }
        if (polarAddress != null) try {
            polar = PolarStreamer(
                this, polarAddress, intent.getStringExtra(EXTRA_POLAR_NAME) ?: "Polar H10",
                PolarH10Client.Config(
                    ecg = intent.getBooleanExtra(EXTRA_ECG, true),
                    acc = intent.getBooleanExtra(EXTRA_ACC, true),
                    accRate = intent.getIntExtra(EXTRA_ACC_RATE, 200),
                    accRangeG = intent.getIntExtra(EXTRA_ACC_RANGE, 8),
                ),
                onStatus = { updateNotification() },
            ).also { it.start() }
        } catch (e: Throwable) {
            polar?.stop()
            polar = null
            StreamState.message("Polar H10: ${e.message}")
        }

        if (sensors == null && audio == null && location == null && polar == null) {
            StreamState.message("Nothing to stream")
            stop()
            return
        }
        StreamState.update {
            it.copy(
                status = "Streaming", phoneSensors = sensors?.sensorCount ?: 0, audio = audio != null,
                location = location != null,
            )
        }
        updateNotification()
        main.post(poll)
    }

    /** Publishes counters and consumer state to the UI once a second. */
    private val poll = object : Runnable {
        override fun run() {
            if (!running) return
            val outlets = sensors?.outletInfos().orEmpty() + audio?.outletInfos().orEmpty() +
                location?.outletInfos().orEmpty() + polar?.outletInfos().orEmpty()
            StreamState.update {
                it.copy(
                    outlets = outlets,
                    phoneSamples = StreamState.phoneSamples.get(),
                    audioSamples = StreamState.audioSamples.get(),
                    recordingBytes = XdfRecording.bytesWritten,
                )
            }
            val error = XdfRecording.error
            if (error != null && !recordingErrorShown) {
                recordingErrorShown = true
                StreamState.message("Recording stopped: ${error.message}")
            }
            main.postDelayed(this, 1000)
        }
    }

    private fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        sensors?.stop(); sensors = null
        audio?.stop(); audio = null
        location?.stop(); location = null
        polar?.stop(); polar = null
        finishRecording()
        releaseLocks()
        StreamState.update {
            it.copy(running = false, status = "Stopped", outlets = emptyList(), polarStreaming = false)
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Completes the XDF file and moves it to Download/Sensorium, on a background thread: the
     * last chunks and the copy can take a moment for long recordings.
     */
    private fun finishRecording() {
        // Detached here, so a new streaming session started meanwhile records to its own file.
        val recorder = XdfRecording.detach() ?: return
        val app = applicationContext
        Thread({
            recorder.close()
            val size = recorder.bytesWritten
            val where = try {
                XdfRecording.publish(app, recorder.file)
            } catch (e: Exception) {
                StreamState.message("Could not move the recording to Downloads: ${e.message}")
                recorder.file.absolutePath
            }
            val damaged = recorder.error?.let { " (incomplete: ${it.message})" }.orEmpty()
            StreamState.message("Saved $where · ${formatBytes(size)}$damaged")
            StreamState.update { s -> s.copy(recordingFile = s.recordingFile.takeIf { it != recorder.file.name }) }
        }, "sensorium-xdf-finish").start()
    }

    override fun onDestroy() {
        if (running) stop()
        super.onDestroy()
    }

    // --- foreground notification -------------------------------------------------------------

    private fun goForeground(withMicrophone: Boolean, withLocation: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
        )
        var type = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (withMicrophone && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            if (withLocation) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
    }

    private fun notificationText(): String {
        val s = StreamState.state.value
        return buildList {
            if (s.phoneSensors > 0) add("${s.phoneSensors} sensors")
            if (s.audio) add("audio")
            if (s.location) add("GPS")
            if (s.recordingFile != null) add("recording")
            s.polarDevice?.let { add("H10: ${s.polarStatus ?: "…"}") }
        }.joinToString(" · ").ifEmpty { "Starting…" } +
            LanAddresses.list().takeIf { it.isNotEmpty() }
                ?.joinToString(prefix = "\nIP ") { it.address }.orEmpty()
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, StreamService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_stream)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(notificationText().substringBefore('\n'))
            .setStyle(NotificationCompat.BigTextStyle().bigText(notificationText()))
            .setSubText(LanAddresses.list().firstOrNull()?.address)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.stop), stop)
            .build()
    }

    private fun updateNotification() {
        if (!running) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    // --- locks --------------------------------------------------------------------------------

    /**
     * LSL discovery uses UDP multicast/broadcast, which Android drops unless a multicast lock is
     * held; the Wi-Fi lock avoids power-save latency spikes; the wake lock keeps the CPU (and so
     * the sensors) running with the screen off.
     */
    private fun acquireLocks() {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("Sensorium:lsl").apply { setReferenceCounted(false); acquire() }
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wifi.createWifiLock(mode, "Sensorium:lsl").apply { setReferenceCounted(false); acquire() }
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Sensorium:stream")
            .apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseLocks() {
        multicastLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock?.takeIf { it.isHeld }?.release()
        multicastLock = null; wifiLock = null; wakeLock = null
    }

    companion object {
        const val ACTION_START = "io.github.markspan.sensorium.START"
        const val ACTION_STOP = "io.github.markspan.sensorium.STOP"
        const val EXTRA_DEVICE_NAME = "deviceName"
        const val EXTRA_SOURCE_PREFIX = "sourcePrefix"
        const val EXTRA_SENSORS = "sensors"
        const val EXTRA_SENSOR_RATE = "sensorRate"
        /** Microphone sample rate in Hz; 0 = no audio stream. */
        const val EXTRA_AUDIO_RATE = "audioRate"
        /** Stream the GPS position; the caller has checked ACCESS_FINE_LOCATION. */
        const val EXTRA_LOCATION = "location"
        /** GPS rate in Hz (a Double). */
        const val EXTRA_LOCATION_RATE = "locationRate"
        /** Polar H10 to stream; absent = no Polar streams. */
        const val EXTRA_POLAR_ADDRESS = "polarAddress"
        const val EXTRA_POLAR_NAME = "polarName"
        const val EXTRA_ECG = "ecg"
        const val EXTRA_ACC = "acc"
        const val EXTRA_ACC_RATE = "accRate"
        const val EXTRA_ACC_RANGE = "accRange"
        /** Stamp phone sensors and audio on arrival in the app, as SENDA does. */
        const val EXTRA_ARRIVAL_STAMPS = "arrivalStamps"
        /** Also record all streams to an XDF file on the phone. */
        const val EXTRA_RECORD = "record"

        fun formatBytes(bytes: Long): String = when {
            bytes >= 1_000_000_000 -> String.format(java.util.Locale.ROOT, "%.2f GB", bytes / 1e9)
            bytes >= 1_000_000 -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1e6)
            else -> String.format(java.util.Locale.ROOT, "%.0f kB", bytes / 1e3)
        }

        private const val CHANNEL_ID = "streaming"
        private const val NOTIFICATION_ID = 1
    }
}
