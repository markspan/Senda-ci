package io.github.markspan.sensorium.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.HandlerThread
import android.os.SystemClock
import io.github.markspan.sensorium.lsl.IRREGULAR_RATE
import io.github.markspan.sensorium.lsl.LslNative
import io.github.markspan.sensorium.lsl.LslOutlet
import io.github.markspan.sensorium.lsl.TIMESTAMPS_ARRIVAL
import io.github.markspan.sensorium.lsl.TIMESTAMPS_LOCATION
import io.github.markspan.sensorium.stream.OutletInfo
import io.github.markspan.sensorium.stream.StreamState
import java.util.Locale
import kotlin.math.abs

/**
 * Streams the phone's GPS position: one sample per fix, asked for at [rateHz], on an LSL stream
 * of irregular rate (`Location <phone model>`, type `Location`). The GPS may deliver fixes less
 * often than asked (most phones manage at most 1 Hz) or skip some. Each fix is stamped with the
 * time it was taken (`Location.getElapsedRealtimeNanos`, converted to the LSL clock), or on
 * arrival in the app with [arrivalStamps].
 */
@SuppressLint("MissingPermission") // the caller only starts this with ACCESS_FINE_LOCATION granted
class LocationStreamer(
    context: Context,
    private val deviceName: String,
    private val sourcePrefix: String,
    private val arrivalStamps: Boolean = false,
    private val rateHz: Double = LocationFormat.DEFAULT_RATE_HZ,
) {
    private val lm = context.getSystemService(LocationManager::class.java)
    private var outlet: LslOutlet? = null
    private var thread: HandlerThread? = null

    fun start() {
        val manager = checkNotNull(lm) { "no location service" }
        check(manager.allProviders.contains(LocationManager.GPS_PROVIDER)) { "this phone has no GPS" }
        outlet = LslOutlet(
            name = "Location $deviceName",
            type = "Location",
            channelCount = LocationFormat.LABELS.size,
            nominalRate = IRREGULAR_RATE,
            sourceId = "$sourcePrefix-location",
            channelLabels = LocationFormat.LABELS,
            channelUnits = LocationFormat.UNITS,
            channelType = "Location",
            manufacturer = Build.MANUFACTURER,
            model = "GPS",
            timestamps = if (arrivalStamps) TIMESTAMPS_ARRIVAL else TIMESTAMPS_LOCATION,
        )
        if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            StreamState.message("Location is off on this phone: no GPS fixes until it is switched on")
        }
        val t = HandlerThread("sensorium-location").also { it.start() }
        thread = t
        manager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER, LocationFormat.intervalMs(rateHz), 0f, listener, t.looper,
        )
    }

    fun stop() {
        lm?.removeUpdates(listener)
        thread?.quitSafely()
        thread = null
        outlet?.close()
        outlet = null
    }

    fun outletInfos(): List<OutletInfo> = listOfNotNull(outlet).map { OutletInfo(it.name, it.type, it.hasConsumers) }

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val out = outlet ?: return
            val values = LocationFormat.values(
                location.latitude,
                location.longitude,
                location.altitude.takeIf { location.hasAltitude() },
                location.accuracy.takeIf { location.hasAccuracy() },
                location.speed.takeIf { location.hasSpeed() },
                location.bearing.takeIf { location.hasBearing() },
            )
            val ts = if (arrivalStamps) null else fixTime(location.elapsedRealtimeNanos)
            if (ts == null) out.push(values) else out.push(values, ts)
            val text = LocationFormat.describe(
                location.latitude, location.longitude, location.accuracy.takeIf { location.hasAccuracy() },
            )
            StreamState.update { it.copy(locationFixes = it.locationFixes + 1, locationFix = text) }
        }

        override fun onProviderDisabled(provider: String) =
            StreamState.message("Location was switched off: GPS stream paused")

        override fun onProviderEnabled(provider: String) =
            StreamState.message("Location was switched on: GPS stream resumes")

        // Abstract before API 30; without an implementation older phones would crash here.
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    /**
     * The fix time on the LSL clock, or null (stamp on arrival) when it lies implausibly far
     * from now, as with a stale cached fix or a phone that fills the field wrongly.
     */
    private fun fixTime(fixNanos: Long): Double? {
        val before = SystemClock.elapsedRealtimeNanos()
        val lsl = LslNative.localClock()
        val after = SystemClock.elapsedRealtimeNanos()
        val now = (before + after) / 2
        if (fixNanos <= 0 || abs(now - fixNanos) > MAX_FIX_AGE_NS) return null
        return lsl - (now - fixNanos) / 1e9
    }

    private companion object {
        const val MAX_FIX_AGE_NS = 10_000_000_000L
    }
}

/** Channel layout and formatting of the location stream, apart from Android so it can be tested. */
object LocationFormat {
    /** Rates offered in the UI, in Hz. */
    val RATES_HZ = listOf(0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0)
    const val DEFAULT_RATE_HZ = 1.0

    /** Interval between fixes asked of the GPS, in ms. */
    fun intervalMs(rateHz: Double): Long = Math.round(1000.0 / rateHz).coerceAtLeast(1)

    /** `0.1`, `0.5`, `1`, `10`: the dropdown label of a rate. */
    fun rateLabel(rateHz: Double): String =
        if (rateHz == Math.rint(rateHz)) rateHz.toLong().toString() else String.format(Locale.ROOT, "%.1f", rateHz)

    val LABELS = listOf("Latitude", "Longitude", "Altitude", "Accuracy", "Speed", "Bearing")
    val UNITS = listOf("degrees", "degrees", "m", "m", "m/s", "degrees")

    /**
     * One sample in [LABELS] order. Values the fix does not have are NaN. Altitude is above the
     * WGS84 ellipsoid; accuracy is the horizontal 68% radius.
     */
    fun values(
        latitude: Double,
        longitude: Double,
        altitude: Double?,
        accuracy: Float?,
        speed: Float?,
        bearing: Float?,
    ) = floatArrayOf(
        latitude.toFloat(),
        longitude.toFloat(),
        altitude?.toFloat() ?: Float.NaN,
        accuracy ?: Float.NaN,
        speed ?: Float.NaN,
        bearing ?: Float.NaN,
    )

    /** `52.21573, 6.89340 ±4 m` for the status card. */
    fun describe(latitude: Double, longitude: Double, accuracy: Float?): String =
        String.format(Locale.ROOT, "%.5f, %.5f", latitude, longitude) +
            (accuracy?.let { " ±${Math.round(it)} m" } ?: "")
}
