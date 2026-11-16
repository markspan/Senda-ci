package io.github.markspan.sensorium

import io.github.markspan.sensorium.sensors.AudioStreamer
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioTimeTest {

    @Test
    fun referenceFrameGetsItsOwnCaptureTime() {
        assertEquals(12.5, AudioStreamer.frameTime(4000, 4000, 12_500_000_000L, 16000), 1e-9)
    }

    @Test
    fun laterAndEarlierFramesFollowTheSampleRate() {
        // 16000 frames after the reference frame is one second later, 8000 before is half a second earlier.
        assertEquals(13.5, AudioStreamer.frameTime(20000, 4000, 12_500_000_000L, 16000), 1e-9)
        assertEquals(12.0, AudioStreamer.frameTime(0, 8000, 12_500_000_000L, 16000), 1e-9)
    }
}
