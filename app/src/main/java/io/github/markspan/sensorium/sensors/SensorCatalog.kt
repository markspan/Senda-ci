package io.github.markspan.sensorium.sensors

import android.hardware.Sensor

/**
 * How an Android sensor type maps to an LSL stream: stream name prefix and type, channel labels
 * and units. Types missing from this table (newer or vendor specific) are still streamed; their
 * channel count is taken from the first event and the channels are labelled ch1..chN.
 */
data class SensorSpec(
    /** Stream name prefix; for the sensors SENDA 1.x already streamed, the names are unchanged. */
    val streamName: String,
    /** Human readable name for the UI. */
    val title: String,
    /** LSL stream type. */
    val lslType: String,
    val labels: List<String>,
    val units: List<String>,
) {
    val channelCount get() = labels.size
}

object SensorCatalog {

    private fun xyz(unit: String) = listOf("X", "Y", "Z") to listOf(unit)
    private fun xyzBias(unit: String) =
        listOf("X", "Y", "Z", "BiasX", "BiasY", "BiasZ") to listOf(unit)
    private fun quat() = listOf("X", "Y", "Z", "W") to listOf("")

    private fun spec(name: String, title: String, type: String, ch: Pair<List<String>, List<String>>) =
        SensorSpec(name, title, type, ch.first, ch.second)

    private fun single(name: String, title: String, type: String, label: String, unit: String) =
        SensorSpec(name, title, type, listOf(label), listOf(unit))

    // Constants of newer API levels are inlined by the compiler, so they are safe on minSdk 26.
    private val specs: Map<Int, SensorSpec> = mapOf(
        Sensor.TYPE_ACCELEROMETER to spec("Accelerometer", "Accelerometer", "Accelerometer", xyz("m/s^2")),
        Sensor.TYPE_ACCELEROMETER_UNCALIBRATED to
            spec("AccelerometerUncalibrated", "Accelerometer (uncalibrated)", "Accelerometer", xyzBias("m/s^2")),
        Sensor.TYPE_LINEAR_ACCELERATION to
            spec("LinearAcceleration", "Linear acceleration", "Accelerometer", xyz("m/s^2")),
        Sensor.TYPE_GRAVITY to spec("Gravity", "Gravity", "Accelerometer", xyz("m/s^2")),
        Sensor.TYPE_GYROSCOPE to spec("Gyroscope", "Gyroscope", "Gyroscope", xyz("rad/s")),
        Sensor.TYPE_GYROSCOPE_UNCALIBRATED to
            spec("GyroscopeUncalibrated", "Gyroscope (uncalibrated)", "Gyroscope", xyzBias("rad/s")),
        Sensor.TYPE_MAGNETIC_FIELD to spec("MagneticField", "Magnetometer", "Magnetometer", xyz("uT")),
        Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED to
            spec("MagneticFieldUncalibrated", "Magnetometer (uncalibrated)", "Magnetometer", xyzBias("uT")),
        Sensor.TYPE_ROTATION_VECTOR to spec("Rotation", "Rotation vector", "Orientation", quat()),
        Sensor.TYPE_GAME_ROTATION_VECTOR to
            spec("GameRotation", "Game rotation vector", "Orientation", quat()),
        Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR to
            spec("GeomagneticRotation", "Geomagnetic rotation vector", "Orientation", quat()),
        Sensor.TYPE_POSE_6DOF to SensorSpec(
            "Pose6DOF", "Pose (6 DOF)", "Pose",
            listOf("QX", "QY", "QZ", "QW", "TX", "TY", "TZ", "DQX", "DQY", "DQZ", "DQW", "DTX", "DTY", "DTZ", "Seq"),
            listOf(""),
        ),
        Sensor.TYPE_LIGHT to single("Light", "Light", "Light", "Illuminance", "lx"),
        Sensor.TYPE_PROXIMITY to single("Proximity", "Proximity", "Proximity", "Distance", "cm"),
        Sensor.TYPE_PRESSURE to single("Pressure", "Barometer", "Pressure", "Pressure", "hPa"),
        Sensor.TYPE_RELATIVE_HUMIDITY to single("Humidity", "Relative humidity", "Humidity", "Humidity", "%"),
        Sensor.TYPE_AMBIENT_TEMPERATURE to
            single("Temperature", "Ambient temperature", "Temperature", "Temperature", "degC"),
        Sensor.TYPE_STEP_COUNTER to single("StepCount", "Step counter", "Steps", "Steps", "steps"),
        Sensor.TYPE_STEP_DETECTOR to single("StepDetector", "Step detector", "Markers", "Step", ""),
        Sensor.TYPE_HEART_RATE to single("HeartRate", "Heart rate", "HeartRate", "HR", "bpm"),
        Sensor.TYPE_HEART_BEAT to single("HeartBeat", "Heart beat", "Markers", "Confidence", ""),
        Sensor.TYPE_STATIONARY_DETECT to single("Stationary", "Stationary detect", "Markers", "Stationary", ""),
        Sensor.TYPE_MOTION_DETECT to single("Motion", "Motion detect", "Markers", "Motion", ""),
        Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT to
            single("OffBody", "Off-body detect", "Markers", "OnBody", ""),
        Sensor.TYPE_HINGE_ANGLE to single("HingeAngle", "Hinge angle", "Angle", "Angle", "deg"),
        Sensor.TYPE_HEAD_TRACKER to SensorSpec(
            "HeadTracker", "Head tracker", "Orientation",
            listOf("RX", "RY", "RZ", "VX", "VY", "VZ"), listOf("rad", "rad", "rad", "rad/s", "rad/s", "rad/s"),
        ),
        Sensor.TYPE_ACCELEROMETER_LIMITED_AXES to SensorSpec(
            "AccelerometerLimitedAxes", "Accelerometer (limited axes)", "Accelerometer",
            listOf("X", "Y", "Z", "SupportedX", "SupportedY", "SupportedZ"), listOf("m/s^2"),
        ),
        Sensor.TYPE_GYROSCOPE_LIMITED_AXES to SensorSpec(
            "GyroscopeLimitedAxes", "Gyroscope (limited axes)", "Gyroscope",
            listOf("X", "Y", "Z", "SupportedX", "SupportedY", "SupportedZ"), listOf("rad/s"),
        ),
        Sensor.TYPE_HEADING to SensorSpec(
            "Heading", "Heading", "Orientation", listOf("Heading", "Accuracy"), listOf("deg"),
        ),
    )

    fun specFor(type: Int): SensorSpec? = specs[type]

    /** Spec for any sensor type; unknown types get [channels] generic channels. */
    fun specFor(type: Int, stringType: String?, channels: Int): SensorSpec = specs[type] ?: run {
        val base = stringType?.substringAfterLast('.')?.takeIf { it.isNotBlank() } ?: "Sensor$type"
        val name = base.split('_', ' ', '-').joinToString("") { part ->
            part.lowercase().replaceFirstChar { it.uppercase() }
        }
        SensorSpec(name, base.replace('_', ' '), "Sensor", List(channels) { "ch${it + 1}" }, listOf(""))
    }

    /** Sampling rates offered in the UI, in Hz; 0 means "as fast as the sensor allows". */
    val RATES = listOf(0, 200, 100, 50, 25, 10)
    const val DEFAULT_RATE = 50
}
