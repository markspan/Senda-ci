package io.github.markspan.sensorium.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import io.github.markspan.sensorium.lsl.IRREGULAR_RATE
import io.github.markspan.sensorium.lsl.LslNative
import io.github.markspan.sensorium.lsl.LslOutlet
import io.github.markspan.sensorium.stream.OutletInfo
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
     * All sensors on this phone that make sense as a stream: one-shot sensors (significant
     * motion, wake gestures) and vendor-private types are skipped, and a wake-up variant is only
     * listed when the phone has no regular variant of that type.
     */
    fun list(context: Context): List<PhoneSensor> {
        val sm = context.getSystemService(SensorManager::class.java) ?: return emptyList()
        val all = sm.getSensorList(Sensor.TYPE_ALL)
            .filter { it.reportingMode != Sensor.REPORTING_MODE_ONE_SHOT }
            .filter { it.type in 1 until Sensor.TYPE_DEVICE_PRIVATE_BASE }
        val regularTypes = all.filterNot { it.isWakeUpSensor }.map { it.type }.toSet()
        val usable = all.filter { !it.isWakeUpSensor || it.type !in regularTypes }
        val seen = mutableSetOf<String>()
        return usable
            .sortedWith(compareBy({ order(it.type) }, { it.type }, { it.name }))
            .mapNotNull { s ->
                val key = keyOf(s)
                if (!seen.add(key)) return@mapNotNull null
                val spec = SensorCatalog.specFor(s.type)
                val sameType = usable.count { it.type == s.type } > 1
                val title = (spec?.title ?: SensorCatalog.specFor(s.type, s.stringType, 0).title) +
                    if (sameType) " · ${s.name}" else ""
                PhoneSensor(key, s, title, describe(s, spec))
            }
    }

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
 */
class PhoneSensorStreamer(
    context: Context,
    private val keys: Set<String>,
    private val rateHz: Int,
    private val deviceName: String,
    private val sourcePrefix: String,
) : SensorEventListener {

    private val sm = context.getSystemService(SensorManager::class.java)
    private val sensors = PhoneSensors.list(context).filter { it.key in keys }
    private val outlets = ConcurrentHashMap<Sensor, LslOutlet>()
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
        val sameType = sensors.count { it.sensor.type == sensor.type } > 1
        val suffix = if (sameType) " ${sensor.name}" else ""
        val name = "${spec.streamName}$suffix $deviceName"
        val nominal = when {
            !PhoneSensors.isContinuous(sensor) -> IRREGULAR_RATE
            sensor.minDelay > 0 && rateHz > 0 -> minOf(rateHz.toDouble(), 1_000_000.0 / sensor.minDelay)
            sensor.minDelay > 0 -> 1_000_000.0 / sensor.minDelay
            else -> rateHz.toDouble()
        }
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
        ).also { outlets[sensor] = it }
    } catch (e: Throwable) {
        failed += sensor
        StreamState.message("LSL error for ${spec.title}: ${e.message}")
        null
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

        outlet.push(sample, toLslTime(event.timestamp))

        if (sensor.type == Sensor.TYPE_ACCELEROMETER) StreamState.phoneAccPreview.add(sample)
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
