package io.github.markspan.sensorium

import android.hardware.Sensor
import io.github.markspan.sensorium.sensors.PhoneSensors
import io.github.markspan.sensorium.sensors.SensorCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneSensorsTest {

    private data class FakeSensor(val type: Int, val name: String, val default: Boolean = false, val wakeUp: Boolean = false)

    private fun pick(vararg sensors: FakeSensor) =
        PhoneSensors.onePerType(sensors.toList(), { it.type }, { it.default }, { it.wakeUp }).map { it.name }

    @Test
    fun keepsTheDefaultSensorOfEachType() {
        val picked = pick(
            FakeSensor(Sensor.TYPE_ACCELEROMETER, "lsm6dso"),
            FakeSensor(Sensor.TYPE_GRAVITY, "AOSP gravity"),
            FakeSensor(Sensor.TYPE_ACCELEROMETER, "bmi260 secondary display"),
            FakeSensor(Sensor.TYPE_GRAVITY, "Vendor gravity", default = true),
            FakeSensor(Sensor.TYPE_LIGHT, "Light"),
        )
        assertEquals(listOf("lsm6dso", "Vendor gravity", "Light"), picked)
    }

    @Test
    fun prefersTheNonWakeUpDefault() {
        // Phones often list the wake-up proximity sensor as default, next to a non-wake-up one.
        assertEquals(
            listOf("Proximity"),
            pick(
                FakeSensor(Sensor.TYPE_PROXIMITY, "Proximity wake-up", default = true, wakeUp = true),
                FakeSensor(Sensor.TYPE_PROXIMITY, "Proximity", default = true),
            ),
        )
        assertEquals(
            listOf("Proximity wake-up"),
            pick(
                FakeSensor(Sensor.TYPE_PROXIMITY, "Proximity wake-up", default = true, wakeUp = true),
                FakeSensor(Sensor.TYPE_PROXIMITY, "Proximity"),
            ),
        )
    }

    @Test
    fun withoutDefaultTheFirstNonWakeUpSensorIsKept() {
        assertEquals(
            listOf("b"),
            pick(
                FakeSensor(Sensor.TYPE_PRESSURE, "a", wakeUp = true),
                FakeSensor(Sensor.TYPE_PRESSURE, "b"),
                FakeSensor(Sensor.TYPE_PRESSURE, "c"),
            ),
        )
        assertEquals(listOf("a"), pick(FakeSensor(Sensor.TYPE_PRESSURE, "a", wakeUp = true)))
    }

    @Test
    fun rawVariantsAreHidden() {
        listOf(
            Sensor.TYPE_ACCELEROMETER_UNCALIBRATED, Sensor.TYPE_GYROSCOPE_UNCALIBRATED,
            Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED, Sensor.TYPE_ACCELEROMETER_LIMITED_AXES,
            Sensor.TYPE_GYROSCOPE_LIMITED_AXES, Sensor.TYPE_ACCELEROMETER_LIMITED_AXES_UNCALIBRATED,
            Sensor.TYPE_GYROSCOPE_LIMITED_AXES_UNCALIBRATED, Sensor.TYPE_HEAD_TRACKER,
            Sensor.TYPE_POSE_6DOF, Sensor.TYPE_HEADING,
        ).forEach { assertTrue("type $it", it in PhoneSensors.HIDDEN_TYPES) }
        listOf(
            Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE, Sensor.TYPE_MAGNETIC_FIELD,
            Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_STEP_DETECTOR, Sensor.TYPE_MOTION_DETECT,
            Sensor.TYPE_HINGE_ANGLE,
        ).forEach { assertFalse("type $it", it in PhoneSensors.HIDDEN_TYPES) }
    }

    @Test
    fun previewLabelsNameChannelsAndUnit() {
        val acc = SensorCatalog.specFor(Sensor.TYPE_ACCELEROMETER)!!
        assertEquals("Accelerometer X / Y / Z (m/s²)", PhoneSensors.previewLabel("Accelerometer", acc))
        val light = SensorCatalog.specFor(Sensor.TYPE_LIGHT)!!
        assertEquals("Light (lx)", PhoneSensors.previewLabel("Light", light))
        val rotation = SensorCatalog.specFor(Sensor.TYPE_ROTATION_VECTOR)!!
        assertEquals("Rotation vector X / Y / Z / W", PhoneSensors.previewLabel("Rotation vector", rotation))
        val unknown = SensorCatalog.specFor(9999, "com.vendor.tilt", 2)
        assertEquals("tilt ch1 / ch2", PhoneSensors.previewLabel("tilt", unknown))
    }

    @Test
    fun previewsHoldAboutFiveSecondsOrTheLastEvents() {
        assertEquals(250, PhoneSensors.previewCapacity(50.0))
        assertEquals(1000, PhoneSensors.previewCapacity(400.0)) // capped
        assertEquals(50, PhoneSensors.previewCapacity(1.0)) // at least 50 points
        assertEquals(100, PhoneSensors.previewCapacity(0.0)) // on change: last 100 events
    }

    @Test
    fun onlySensorsThatReportOnTheirOwnAreProbed() {
        assertTrue(PhoneSensors.probeable(Sensor.REPORTING_MODE_CONTINUOUS, permissionGranted = true))
        assertTrue(PhoneSensors.probeable(Sensor.REPORTING_MODE_ON_CHANGE, permissionGranted = true))
        // Step detector, motion detect: they only report when the event happens.
        assertFalse(PhoneSensors.probeable(Sensor.REPORTING_MODE_SPECIAL_TRIGGER, permissionGranted = true))
        // Heart rate or step counter before the permission is granted: always silent.
        assertFalse(PhoneSensors.probeable(Sensor.REPORTING_MODE_ON_CHANGE, permissionGranted = false))
    }
}
