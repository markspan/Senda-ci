package io.github.markspan.sensorium.ble

import kotlin.math.abs

/**
 * Detects PMD data frames lost over Bluetooth, and fills the samples they carried with NaN so
 * that the sample count keeps matching time.
 *
 * Every PMD frame carries the H10's own clock time of its last sample ([PmdFrame.sensorTimeNs]).
 * The sensor time between two consecutive frames, in sample periods, is the number of samples the
 * second frame should complete; any samples beyond the frame's own were lost in between.
 *
 * The strap's actual sample period can differ a little from the nominal one. Frames whose sensor
 * time matches their own sample count to within [NORMAL_TOLERANCE] arrived back to back; they
 * pass unchanged and refine the estimate of the actual period, which is then used to count the
 * samples of lost frames. A lost frame always takes a whole packet of samples (at least some
 * tens), so it cannot be mistaken for rate drift.
 *
 * Only the sample values come from the sensor clock: the caller still stamps each frame on
 * arrival, so the NaN samples get time stamps at the nominal rate before the frame, like any
 * other sample of it.
 *
 * Call [reset] when a new measurement starts (after a reconnect): the time since the last frame
 * of the previous connection is a gap in the stream, not lost packets.
 */
class PmdGapFiller(
    sampleRateHz: Int,
    private val channels: Int,
    maxGapSeconds: Double = MAX_GAP_SECONDS,
) {
    class Result(
        /** The frame's samples, preceded by [lost] samples of NaN; multiplexed, [channels] per sample. */
        val samples: FloatArray,
        /** Samples missing between the previous frame and this one. */
        val lost: Int,
        /**
         * The sensor time went backwards, or jumped further than [maxGapSeconds]: the frame is
         * passed on unchanged and nothing is filled.
         */
        val discontinuity: Boolean = false,
    )

    private val nominalPeriodNs = 1e9 / sampleRateHz
    private var periodNs = nominalPeriodNs
    private val maxGapSamples = Math.round(maxGapSeconds * sampleRateHz)
    private var lastTimeNs: Long? = null

    init {
        require(sampleRateHz > 0 && channels > 0)
    }

    @Synchronized
    fun reset() {
        lastTimeNs = null
    }

    /** Current estimate of the actual sample period, in nanoseconds of sensor time. */
    val actualPeriodNs: Double
        @Synchronized get() = periodNs

    @Synchronized
    fun fill(frame: PmdFrame): Result {
        require(frame.channels == channels) { "Expected $channels channels, got ${frame.channels}" }
        val previous = lastTimeNs
        lastTimeNs = frame.sensorTimeNs
        if (previous == null || frame.sampleCount == 0) return Result(frame.samples, 0)

        val dt = (frame.sensorTimeNs - previous).toDouble()
        if (dt <= 0) return Result(frame.samples, 0, discontinuity = true)
        val perSample = dt / frame.sampleCount
        if (abs(perSample / periodNs - 1) < NORMAL_TOLERANCE) {
            // Back to back: learn the actual period (bounded, in case of a clock glitch).
            periodNs = (periodNs + (perSample - periodNs) * LEARNING_RATE)
                .coerceIn(nominalPeriodNs * (1 - MAX_RATE_ERROR), nominalPeriodNs * (1 + MAX_RATE_ERROR))
            return Result(frame.samples, 0)
        }

        val lost = Math.round(dt / periodNs) - frame.sampleCount
        return when {
            lost > maxGapSamples -> Result(frame.samples, 0, discontinuity = true)
            lost <= 0 -> Result(frame.samples, 0)
            else -> {
                val fill = lost.toInt() * channels
                val out = FloatArray(fill + frame.samples.size)
                out.fill(Float.NaN, 0, fill)
                frame.samples.copyInto(out, fill)
                Result(out, lost.toInt())
            }
        }
    }

    companion object {
        /**
         * Longer jumps are not filled. Within one connection they do not happen: Android drops a
         * BLE link that stays silent this long, and a new connection starts with [reset].
         */
        const val MAX_GAP_SECONDS = 10.0

        /** Relative deviation of a frame's sample period that still counts as back to back. */
        const val NORMAL_TOLERANCE = 0.02

        /** Largest accepted deviation of the actual from the nominal sample rate. */
        const val MAX_RATE_ERROR = 0.05

        private const val LEARNING_RATE = 0.1
    }
}
