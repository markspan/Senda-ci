package io.github.markspan.sensorium.sensors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import io.github.markspan.sensorium.lsl.IRREGULAR_RATE
import io.github.markspan.sensorium.lsl.LslNative
import io.github.markspan.sensorium.lsl.LslOutlet
import io.github.markspan.sensorium.lsl.TIMESTAMPS_ARRIVAL
import io.github.markspan.sensorium.lsl.TIMESTAMPS_SENSOR
import io.github.markspan.sensorium.stream.OutletInfo
import io.github.markspan.sensorium.stream.PreviewBuffer
import io.github.markspan.sensorium.stream.SensorPreview
import io.github.markspan.sensorium.stream.StreamState
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/** One streamable phone sensor, as shown in the UI. */
data class PhoneSensor(
    /** Stable key used in preferences and intents. */
    val key: String,
    val sensor: Sensor,
    val title: String,
    val detail: String,
)

object PhoneSensors {

    /**
     * Variants of the basic motion sensors that are rarely wanted in a recording: uncalibrated
     * and limited-axes copies, and sensors that need special hardware or setup (head tracker,
     * 6-DOF pose, heading).
     */
    val HIDDEN_TYPES: Set<Int> = setOf(
        Sensor.TYPE_ACCELEROMETER_UNCALIBRATED,
        Sensor.TYPE_GYROSCOPE_UNCALIBRATED,
        Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED,
        Sensor.TYPE_ACCELEROMETER_LIMITED_AXES,
        Sensor.TYPE_GYROSCOPE_LIMITED_AXES,
        Sensor.TYPE_ACCELEROMETER_LIMITED_AXES_UNCALIBRATED,
        Sensor.TYPE_GYROSCOPE_LIMITED_AXES_UNCALIBRATED,
        Sensor.TYPE_HEAD_TRACKER,
        Sensor.TYPE_POSE_6DOF,
        Sensor.TYPE_HEADING,
    )

    /**
     * The sensors on this phone that make sense as a stream, one per sensor type. Skipped are
     * one-shot sensors (significant motion, wake gestures), vendor-private types and
     * [HIDDEN_TYPES]. Where the phone has several sensors of one type (a vendor and an Android
     * version of gravity, say, or a wake-up copy), the one Android uses by default is listed.
     */
    fun list(context: Context): List<PhoneSensor> {
        val sm = context.getSystemService(SensorManager::class.java) ?: return emptyList()
        val all = sm.getSensorList(Sensor.TYPE_ALL)
            .filter { it.reportingMode != Sensor.REPORTING_MODE_ONE_SHOT }
            .filter { it.type in 1 until Sensor.TYPE_DEVICE_PRIVATE_BASE && it.type !in HIDDEN_TYPES }
        return onePerType(
            all,
            type = { it.type },
            isDefault = { it == sm.getDefaultSensor(it.type, false) || it == sm.getDefaultSensor(it.type, true) },
            isWakeUp = { it.isWakeUpSensor },
        )
            .sortedWith(compareBy({ order(it.type) }, { it.type }, { it.name }))
            .map { s ->
                val spec = SensorCatalog.specFor(s.type)
                val title = spec?.title ?: SensorCatalog.specFor(s.type, s.stringType, 0).title
                PhoneSensor(keyOf(s), s, title, describe(s, spec))
            }
    }

    /**
     * Picks one sensor per type, keeping the original order. Preferred, in turn: the default
     * non-wake-up sensor of the type, the default wake-up sensor, the first non-wake-up sensor,
     * the first sensor.
     */
    fun <T> onePerType(
        sensors: List<T>,
        type: (T) -> Int,
        isDefault: (T) -> Boolean,
        isWakeUp: (T) -> Boolean,
    ): List<T> {
        val chosen = sensors.groupBy(type).mapValues { (_, group) ->
            group.firstOrNull { isDefault(it) && !isWakeUp(it) }
                ?: group.firstOrNull(isDefault)
                ?: group.firstOrNull { !isWakeUp(it) }
                ?: group.first()
        }
        return sensors.filter { chosen[type(it)] === it }
    }

    /**
     * Whether [probe] can tell that a sensor works: continuous sensors report at their rate, and
     * on-change sensors report their current value as soon as they are switched on. Special
     * trigger sensors (step detector, motion detectors) only report when the event happens, and
     * sensors behind a runtime permission that is not granted report nothing; those are not probed.
     */
    fun probeable(reportingMode: Int, permissionGranted: Boolean) = permissionGranted &&
        (reportingMode == Sensor.REPORTING_MODE_CONTINUOUS || reportingMode == Sensor.REPORTING_MODE_ON_CHANGE)

    private fun requiredPermission(type: Int): String? = when (type) {
        Sensor.TYPE_HEART_RATE, Sensor.TYPE_HEART_BEAT -> Manifest.permission.BODY_SENSORS
        Sensor.TYPE_STEP_COUNTER, Sensor.TYPE_STEP_DETECTOR ->
            if (android.os.Build.VERSION.SDK_INT >= 29) Manifest.permission.ACTIVITY_RECOGNITION else null
        else -> null
    }

    /** Keys of the sensors that sent nothing during [probe]; null until probed in this process. */
    @Volatile
    var silentKeys: Set<String>? = null
        private set

    /**
     * Listens briefly to each probeable sensor of [sensors] and reports, on the main thread, the
     * keys of those that sent nothing: sensors the phone lists without real hardware behind them
     * (a hinge angle on a phone without a hinge, say). The result is kept for the process.
     */
    fun probe(context: Context, sensors: List<PhoneSensor>, onDone: (Set<String>) -> Unit) {
        silentKeys?.let { onDone(it); return }
        val sm = context.getSystemService(SensorManager::class.java)
        val candidates = sensors.filter { ps ->
            val permission = requiredPermission(ps.sensor.type)
            probeable(
                ps.sensor.reportingMode,
                permission == null || context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED,
            )
        }
        if (sm == null || candidates.isEmpty()) {
            silentKeys = emptySet()
            onDone(emptySet())
            return
        }
        val main = Handler(Looper.getMainLooper())
        val thread = HandlerThread("sensorium-probe").apply { start() }
        val handler = Handler(thread.looper)
        val heard = HashSet<Sensor>() // only touched on the probe thread
        var finished = false
        lateinit var listener: SensorEventListener

        fun finish() {
            if (finished) return
            finished = true
            sm.unregisterListener(listener)
            thread.quitSafely()
            val silent = candidates.filter { it.sensor !in heard }.map { it.key }.toSet()
            silentKeys = silent
            main.post { onDone(silent) }
        }

        listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (heard.add(event.sensor) && heard.size == candidates.size) finish()
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        candidates.forEach { sm.registerListener(listener, it.sensor, PROBE_PERIOD_US, handler) }
        handler.postDelayed({ finish() }, PROBE_TIMEOUT_MS)
    }

    private const val PROBE_PERIOD_US = 20_000
    private const val PROBE_TIMEOUT_MS = 1500L

    /** Plot label, e.g. `Accelerometer X / Y / Z (m/s²)` or `Light (lx)`. */
    fun previewLabel(title: String, spec: SensorSpec): String {
        val channels = if (spec.channelCount in 2..6) " " + spec.labels.joinToString(" / ") else ""
        val unit = spec.units.firstOrNull { it.isNotEmpty() }?.let { " (${it.replace("^2", "²")})" }.orEmpty()
        return title + channels + unit
    }

    /**
     * Preview length in samples: about [PREVIEW_SECONDS] at [nominalRate], or room for
     * [PREVIEW_EVENTS] events of an irregular (on-change) sensor, which is plotted against time.
     */
    fun previewCapacity(nominalRate: Double): Int =
        if (nominalRate <= 0) PREVIEW_EVENTS
        else (nominalRate * PREVIEW_SECONDS).roundToInt().coerceIn(50, 1000)

    /** Seconds a preview covers: its capacity at [nominalRate], or [EVENT_WINDOW_SECONDS]. */
    fun previewSpan(nominalRate: Double): Double =
        if (nominalRate <= 0) EVENT_WINDOW_SECONDS else previewCapacity(nominalRate) / nominalRate

    private const val PREVIEW_SECONDS = 5
    private const val PREVIEW_EVENTS = 3000
    private const val EVENT_WINDOW_SECONDS = 300.0

    fun keyOf(s: Sensor) = "${s.type}|${s.name}|${if (s.isWakeUpSensor) "w" else ""}"

    /** Familiar motion sensors first, then environment, then the rest. */
    private fun order(type: Int) = when (type) {
        Sensor.TYPE_ACCELEROMETER -> 0
        Sensor.TYPE_LINEAR_ACCELERATION -> 1
        Sensor.TYPE_GRAVITY -> 2
        Sensor.TYPE_GYROSCOPE -> 3
        Sensor.TYPE_MAGNETIC_FIELD -> 4
        Sensor.TYPE_ROTATION_VECTOR -> 5
        Sensor.TYPE_GAME_ROTATION_VECTOR -> 6
        Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> 7
        Sensor.TYPE_LIGHT -> 10
        Sensor.TYPE_PROXIMITY -> 11
        Sensor.TYPE_PRESSURE -> 12
        Sensor.TYPE_AMBIENT_TEMPERATURE -> 13
        Sensor.TYPE_RELATIVE_HUMIDITY -> 14
        Sensor.TYPE_STEP_COUNTER -> 20
        Sensor.TYPE_STEP_DETECTOR -> 21
        Sensor.TYPE_HEART_RATE -> 22
        else -> 100
    }

    private fun describe(s: Sensor, spec: SensorSpec?): String {
        val channels = spec?.let { "${it.channelCount} ch" + it.units.firstOrNull { u -> u.isNotEmpty() }?.let { u -> " · $u" }.orEmpty() }
        val rate = when {
            isContinuous(s) && s.minDelay > 0 -> "max ${(1_000_000f / s.minDelay).roundToInt()} Hz"
            isContinuous(s) -> "continuous"
            else -> "on change"
        }
        return listOfNotNull(channels, rate, s.vendor.takeIf { it.isNotBlank() }).joinToString(" · ")
    }

    fun isContinuous(s: Sensor) = s.reportingMode == Sensor.REPORTING_MODE_CONTINUOUS
}

/**
 * Streams the selected phone sensors, one LSL outlet each. Events are received on a dedicated
 * thread and stamped with the sensor's own hardware time stamp, converted to the LSL clock.
 *
 * @param rateHz requested rate for continuous sensors; 0 = as fast as possible.
 * @param arrivalStamps stamp each event with lsl_local_clock() when it reaches the app, as SENDA
 *   does, instead of with the sensor's hardware time stamp.
 */
class PhoneSensorStreamer(
    context: Context,
    private val keys: Set<String>,
    private val rateHz: Int,
    private val deviceName: String,
    private val sourcePrefix: String,
    private val arrivalStamps: Boolean = false,
) : SensorEventListener {

    private val sm = context.getSystemService(SensorManager::class.java)
    private val sensors = PhoneSensors.list(context).filter { it.key in keys }
    private val outlets = ConcurrentHashMap<Sensor, LslOutlet>()
    private val previews = ConcurrentHashMap<Sensor, SensorPreview>()
    private val failed = ConcurrentHashMap.newKeySet<Sensor>()
    private var thread: HandlerThread? = null

    /** lsl_local_clock() minus elapsedRealtime, both in seconds; refreshed while streaming. */
    @Volatile
    private var clockOffset = 0.0
    private var lastOffsetUpdate = 0L

    val sensorCount get() = sensors.size

    fun start() {
        updateClockOffset()
        // Outlets of sensors with a known layout are created up front so they are visible to
        // LabRecorder immediately; others are created when their first event shows the layout.
        sensors.forEach { ps ->
            SensorCatalog.specFor(ps.sensor.type)?.let { createOutlet(ps.sensor, it) }
        }
        val t = HandlerThread("sensorium-sensors").also { it.start() }
        thread = t
        val handler = Handler(t.looper)
        sensors.forEach { ps ->
            val period = if (PhoneSensors.isContinuous(ps.sensor) && rateHz > 0) 1_000_000 / rateHz
            else SensorManager.SENSOR_DELAY_FASTEST
            val ok = sm.registerListener(this, ps.sensor, period, 0, handler)
            if (!ok) StreamState.message("Could not start sensor ${ps.title}")
        }
    }

    fun stop() {
        sm.unregisterListener(this)
        thread?.quitSafely()
        thread = null
        outlets.values.forEach { it.close() }
        outlets.clear()
    }

    fun outletInfos(): List<OutletInfo> =
        outlets.values.sortedBy { it.name }.map { OutletInfo(it.name, it.type, it.hasConsumers) }

    private fun createOutlet(sensor: Sensor, spec: SensorSpec): LslOutlet? = try {
        val name = "${spec.streamName} $deviceName"
        val nominal = when {
            !PhoneSensors.isContinuous(sensor) -> IRREGULAR_RATE
            sensor.minDelay > 0 && rateHz > 0 -> minOf(rateHz.toDouble(), 1_000_000.0 / sensor.minDelay)
            sensor.minDelay > 0 -> 1_000_000.0 / sensor.minDelay
            else -> rateHz.toDouble()
        }
        addPreview(sensor, spec, nominal)
        LslOutlet(
            name = name,
            type = spec.lslType,
            channelCount = spec.channelCount,
            nominalRate = nominal,
            sourceId = "$sourcePrefix-${PhoneSensors.keyOf(sensor).hashCode().toUInt().toString(16)}",
            channelLabels = spec.labels,
            channelUnits = spec.units,
            channelType = spec.lslType,
            manufacturer = sensor.vendor,
            model = sensor.name,
            timestamps = if (arrivalStamps) TIMESTAMPS_ARRIVAL else TIMESTAMPS_SENSOR,
        ).also { outlets[sensor] = it }
    } catch (e: Throwable) {
        failed += sensor
        StreamState.message("LSL error for ${spec.title}: ${e.message}")
        null
    }

    private fun addPreview(sensor: Sensor, spec: SensorSpec, nominalRate: Double) {
        val ps = sensors.firstOrNull { it.sensor == sensor } ?: return
        val preview = SensorPreview(
            ps.key, PhoneSensors.previewLabel(ps.title, spec),
            PreviewBuffer(spec.channelCount, PhoneSensors.previewCapacity(nominalRate), timed = nominalRate <= 0),
            PhoneSensors.previewSpan(nominalRate),
        )
        previews[sensor] = preview
        StreamState.addSensorPreview(preview)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val sensor = event.sensor
        val outlet = outlets[sensor] ?: run {
            if (sensor in failed) return
            createOutlet(sensor, SensorCatalog.specFor(sensor.type, sensor.stringType, event.values.size))
        } ?: return

        val n = outlet.channelCount
        val sample = if (event.values.size == n) event.values.copyOf()
        else FloatArray(n) { i -> event.values.getOrElse(i) { 0f } }

        if (arrivalStamps) outlet.push(sample) else outlet.push(sample, toLslTime(event.timestamp))

        previews[sensor]?.let {
            it.buffer.add(sample, SystemClock.elapsedRealtimeNanos())
            it.samples.incrementAndGet()
        }
        StreamState.phoneSamples.incrementAndGet()
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    /**
     * Sensor time stamps use the elapsedRealtime (boot time) clock; liblsl uses the monotonic
     * clock. The offset between them only changes during deep sleep, which the wake lock
     * prevents, but it is refreshed every few seconds anyway. Stamps that are implausible (some
     * vendor sensors use another time base) fall back to the arrival time.
     */
    private fun toLslTime(eventNanos: Long): Double {
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastOffsetUpdate > 5_000) updateClockOffset()
        val ts = eventNanos / 1e9 + clockOffset
        val now = nowMs / 1e3 + clockOffset
        return if (abs(now - ts) < 2.0) ts else LslNative.localClock()
    }

    private fun updateClockOffset() {
        val before = SystemClock.elapsedRealtimeNanos()
        val lsl = LslNative.localClock()
        val after = SystemClock.elapsedRealtimeNanos()
        clockOffset = lsl - (before + after) / 2 / 1e9
        lastOffsetUpdate = SystemClock.elapsedRealtime()
    }
}
