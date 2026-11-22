package io.github.markspan.sensorium

import io.github.markspan.sensorium.ble.PmdCommand
import io.github.markspan.sensorium.ble.PmdFrame
import io.github.markspan.sensorium.ble.PmdGapFiller
import io.github.markspan.sensorium.stream.PacketLoss
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToLong

class PmdGapFillerTest {

    /** A realistic H10 clock value: nanoseconds since 2000-01-01, about 26 years on. */
    private val t0 = 820_000_000_000_000_000L

    private fun ns(samples: Double, rateHz: Double) = (samples * 1e9 / rateHz).roundToLong()

    private fun ecg(timeNs: Long, count: Int = 73, first: Float = 1f) =
        PmdFrame(PmdCommand.MEASUREMENT_ECG, timeNs, 1, FloatArray(count) { first + it })

    private fun acc(timeNs: Long, count: Int = 36) =
        PmdFrame(PmdCommand.MEASUREMENT_ACC, timeNs, 3, FloatArray(count * 3) { it.toFloat() })

    @Test
    fun firstFrameIsPassedOn() {
        val frame = ecg(t0)
        val r = PmdGapFiller(130, 1).fill(frame)
        assertEquals(0, r.lost)
        assertFalse(r.discontinuity)
        assertSame(frame.samples, r.samples)
    }

    @Test
    fun consecutiveFramesAreNotFilled() {
        val filler = PmdGapFiller(130, 1)
        var t = t0
        filler.fill(ecg(t))
        repeat(100) {
            t += ns(73.0, 130.0)
            val frame = ecg(t)
            val r = filler.fill(frame)
            assertEquals(0, r.lost)
            assertSame(frame.samples, r.samples)
        }
    }

    @Test
    fun lostEcgFramesAreFilledWithNan() {
        val filler = PmdGapFiller(130, 1)
        filler.fill(ecg(t0))
        // Two frames of 73 samples lost: the next one arrives 3 frames after the first.
        val frame = ecg(t0 + ns(3 * 73.0, 130.0), first = 100f)
        val r = filler.fill(frame)
        assertEquals(146, r.lost)
        assertFalse(r.discontinuity)
        assertEquals(146 + 73, r.samples.size)
        assertTrue(r.samples.take(146).all { it.isNaN() })
        assertArrayEquals(frame.samples, r.samples.copyOfRange(146, r.samples.size), 0f)
    }

    @Test
    fun partialLossIsFilledToTheSample() {
        // Frames need not all have the same length; what counts is the sensor time.
        val filler = PmdGapFiller(130, 1)
        filler.fill(ecg(t0, count = 50))
        val r = filler.fill(ecg(t0 + ns(80.0, 130.0), count = 73))
        assertEquals(7, r.lost)
    }

    @Test
    fun lostAccSamplesAreFilledOnAllAxes() {
        val filler = PmdGapFiller(200, 3)
        filler.fill(acc(t0))
        val frame = acc(t0 + ns(2 * 36.0, 200.0))
        val r = filler.fill(frame)
        assertEquals(36, r.lost)
        assertEquals((36 + 36) * 3, r.samples.size)
        assertTrue(r.samples.take(36 * 3).all { it.isNaN() })
        assertArrayEquals(frame.samples, r.samples.copyOfRange(36 * 3, r.samples.size), 0f)
    }

    @Test
    fun actualRateOffNominalIsNotMistakenForLoss() {
        // The H10's actual rates differ slightly from nominal; that must neither count as loss
        // nor build up over a long recording.
        for (actualRate in listOf(128.0, 130.3, 131.9)) {
            val filler = PmdGapFiller(130, 1)
            var samples = 0.0
            filler.fill(ecg(t0))
            repeat(10_000) {
                samples += 73
                assertEquals(0, filler.fill(ecg(t0 + ns(samples, actualRate))).lost)
            }
        }
    }

    @Test
    fun lossIsCountedAtTheActualRate() {
        // At 131.5 Hz, 3 lost frames span 288.7 nominal periods: counting with the nominal rate
        // would report 216 lost samples instead of 219.
        val rate = 131.5
        val filler = PmdGapFiller(130, 1)
        var samples = 0.0
        filler.fill(ecg(t0))
        repeat(200) {
            samples += 73
            filler.fill(ecg(t0 + ns(samples, rate)))
        }
        assertEquals(1e9 / rate, filler.actualPeriodNs, 1e9 / rate * 1e-4)
        samples += 4 * 73
        assertEquals(219, filler.fill(ecg(t0 + ns(samples, rate))).lost)
    }

    @Test
    fun lossIsFoundWithJitteredTimestamps() {
        val filler = PmdGapFiller(130, 1)
        filler.fill(ecg(t0))
        // One frame lost, and the sensor time off by a third of a sample.
        val r = filler.fill(ecg(t0 + ns(2 * 73.0 + 0.33, 130.0)))
        assertEquals(73, r.lost)
    }

    @Test
    fun timeGoingBackIsADiscontinuity() {
        val filler = PmdGapFiller(130, 1)
        filler.fill(ecg(t0))
        val frame = ecg(t0 - ns(73.0, 130.0))
        val r = filler.fill(frame)
        assertTrue(r.discontinuity)
        assertEquals(0, r.lost)
        assertSame(frame.samples, r.samples)
        // The next frame is compared with the new time base.
        assertEquals(0, filler.fill(ecg(t0)).lost)
    }

    @Test
    fun longJumpIsNotFilled() {
        val filler = PmdGapFiller(130, 1, maxGapSeconds = 10.0)
        filler.fill(ecg(t0))
        val r = filler.fill(ecg(t0 + 20_000_000_000L))
        assertTrue(r.discontinuity)
        assertEquals(0, r.lost)
        assertEquals(73, r.samples.size)
    }

    @Test
    fun gapUpToTheLimitIsFilled() {
        val filler = PmdGapFiller(130, 1, maxGapSeconds = 10.0)
        filler.fill(ecg(t0))
        val r = filler.fill(ecg(t0 + ns(1300.0 + 73.0, 130.0)))
        assertFalse(r.discontinuity)
        assertEquals(1300, r.lost)
    }

    @Test
    fun resetStartsOver() {
        // After a reconnect the time since the last frame is a reconnect gap, not lost packets.
        val filler = PmdGapFiller(130, 1)
        filler.fill(ecg(t0))
        filler.reset()
        val r = filler.fill(ecg(t0 + ns(5 * 73.0, 130.0)))
        assertEquals(0, r.lost)
        assertFalse(r.discontinuity)
    }

    @Test
    fun packetLossCountsSamplesAndGaps() {
        val loss = PacketLoss().add(0).add(73).add(0).add(36)
        assertEquals(PacketLoss(109, 2), loss)
        assertEquals("109 lost in 2 gaps", loss.toString())
        assertEquals("73 lost in 1 gap", PacketLoss().add(73).toString())
        assertEquals("", PacketLoss().toString())
    }
}
