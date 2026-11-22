package io.github.markspan.sensorium

import io.github.markspan.sensorium.stream.PreviewBuffer
import io.github.markspan.sensorium.ui.TimeAxis
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewTest {

    @Test
    fun timedBufferKeepsTimesWithSamples() {
        val buf = PreviewBuffer(channels = 2, capacity = 3, timed = true)
        buf.add(floatArrayOf(1f, 10f), 100L)
        buf.add(floatArrayOf(2f, 20f, 3f, 30f), 200L) // two samples arriving together
        buf.add(floatArrayOf(4f, 40f), 300L) // the oldest drops out
        val values = FloatArray(6)
        val times = LongArray(3)
        assertEquals(3, buf.snapshot(values, times))
        assertArrayEquals(floatArrayOf(2f, 20f, 3f, 30f, 4f, 40f), values, 0f)
        assertArrayEquals(longArrayOf(200L, 200L, 300L), times)
    }

    @Test
    fun regularBufferIgnoresTimes() {
        val buf = PreviewBuffer(channels = 1, capacity = 2)
        buf.add(floatArrayOf(1f, 2f, 3f), 5L)
        val values = FloatArray(2)
        val times = LongArray(2)
        assertEquals(2, buf.snapshot(values, times))
        assertArrayEquals(floatArrayOf(2f, 3f), values, 0f)
        assertArrayEquals(longArrayOf(0L, 0L), times)
    }

    @Test
    fun ticksAndSpanText() {
        assertEquals(1.0, TimeAxis.tickStep(5.0), 0.0)
        assertEquals(5.0, TimeAxis.tickStep(30.0), 0.0)
        assertEquals(10.0, TimeAxis.tickStep(120.0), 0.0)
        assertEquals(60.0, TimeAxis.tickStep(300.0), 0.0)
        assertEquals("5 s", TimeAxis.formatSpan(5.0))
        assertEquals("30 s", TimeAxis.formatSpan(30.0))
        assertEquals("40 s", TimeAxis.formatSpan(40.0))
        assertEquals("5 min", TimeAxis.formatSpan(300.0))
        assertEquals("90 s", TimeAxis.formatSpan(90.0))
    }
}
