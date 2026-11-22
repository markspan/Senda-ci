package io.github.markspan.sensorium

import io.github.markspan.sensorium.sensors.LocationFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationFormatTest {

    @Test
    fun channelsHaveLabelsAndUnits() {
        assertEquals(listOf("Latitude", "Longitude", "Altitude", "Accuracy", "Speed", "Bearing"), LocationFormat.LABELS)
        assertEquals(LocationFormat.LABELS.size, LocationFormat.UNITS.size)
    }

    @Test
    fun fullFix() {
        val v = LocationFormat.values(53.2194, 6.5665, 12.5, 4f, 1.5f, 270f)
        assertArrayEquals(floatArrayOf(53.2194f, 6.5665f, 12.5f, 4f, 1.5f, 270f), v, 0f)
    }

    @Test
    fun missingValuesAreNan() {
        val v = LocationFormat.values(53.2194, 6.5665, null, 4f, null, null)
        assertEquals(6, v.size)
        assertTrue(v[2].isNaN() && v[4].isNaN() && v[5].isNaN())
        assertEquals(4f, v[3], 0f)
    }

    @Test
    fun float32KeepsPositionWithinHalfAMetre() {
        // One degree of latitude is about 111 km; the worst case is near the poles.
        for (lat in listOf(0.123456789, 53.219412345, 89.999987654)) {
            val error = Math.abs(lat.toFloat() - lat) * 111_000
            assertTrue("latitude $lat: $error m", error < 0.5)
        }
    }

    @Test
    fun ratesAndIntervals() {
        assertEquals(1.0, LocationFormat.DEFAULT_RATE_HZ, 0.0)
        assertTrue(LocationFormat.DEFAULT_RATE_HZ in LocationFormat.RATES_HZ)
        assertEquals(10_000L, LocationFormat.intervalMs(0.1))
        assertEquals(1000L, LocationFormat.intervalMs(1.0))
        assertEquals(100L, LocationFormat.intervalMs(10.0))
        assertEquals(
            listOf("0.1", "0.2", "0.5", "1", "2", "5", "10"),
            LocationFormat.RATES_HZ.map(LocationFormat::rateLabel),
        )
    }

    @Test
    fun statusText() {
        assertEquals("53.21941, 6.56650 ±4 m", LocationFormat.describe(53.219412, 6.5665, 4.2f))
        assertEquals("-33.85678, 151.21530", LocationFormat.describe(-33.856784, 151.215297, null))
    }
}
