package io.github.markspan.sensorium

import android.hardware.Sensor
import io.github.markspan.sensorium.sensors.SensorCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SensorCatalogTest {

    @Test
    fun legacySendaStreamNamesAreKept() {
        val expected = mapOf(
            Sensor.TYPE_ACCELEROMETER to "Accelerometer",
            Sensor.TYPE_LIGHT to "Light",
            Sensor.TYPE_PROXIMITY to "Proximity",
            Sensor.TYPE_GRAVITY to "Gravity",
            Sensor.TYPE_LINEAR_ACCELERATION to "LinearAcceleration",
            Sensor.TYPE_ROTATION_VECTOR to "Rotation",
            Sensor.TYPE_STEP_COUNTER to "StepCount",
        )
        expected.forEach { (type, name) -> assertEquals(name, SensorCatalog.specFor(type)?.streamName) }
    }

    @Test
    fun channelLayouts() {
        assertEquals(3, SensorCatalog.specFor(Sensor.TYPE_GYROSCOPE)?.channelCount)
        assertEquals(4, SensorCatalog.specFor(Sensor.TYPE_ROTATION_VECTOR)?.channelCount)
        assertEquals(6, SensorCatalog.specFor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED)?.channelCount)
        assertNotNull(SensorCatalog.specFor(Sensor.TYPE_PRESSURE))
    }

    @Test
    fun unknownTypesGetGenericChannels() {
        val spec = SensorCatalog.specFor(9999, "com.vendor.sensor.tilt_detector", 2)
        assertEquals("TiltDetector", spec.streamName)
        assertEquals(listOf("ch1", "ch2"), spec.labels)
        assertEquals("Sensor9999", SensorCatalog.specFor(9999, null, 1).streamName)
    }
}
