package io.github.markspan.sensorium.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.google.android.material.color.MaterialColors
import io.github.markspan.sensorium.stream.PreviewBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * Auto-scaling live plot of a [PreviewBuffer]; one trace per channel, redrawn each frame while
 * attached. The newest sample is at the right edge. A regular buffer is drawn sample by sample;
 * a timed buffer (irregular, on-change sensors) is drawn against time, as steps that hold each
 * value until the next one and run on to the present.
 */
class SignalView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var buffer: PreviewBuffer? = null
    private var scratch = FloatArray(0)
    private var times = LongArray(0)
    private val path = Path()
    private val density = resources.displayMetrics.density
    private val traceColors = intArrayOf(
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary),
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorTertiary),
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorSecondary),
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorError),
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline),
    )
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeJoin = Paint.Join.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(this@SignalView, com.google.android.material.R.attr.colorOnSurfaceVariant)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(this@SignalView, com.google.android.material.R.attr.colorOutlineVariant)
        strokeWidth = 1f * density
    }
    var label: String = ""
    private var count: AtomicLong? = null
    private var spanSeconds: Double? = null

    /**
     * Plots [buffer] under [label]. With [count], the label also shows that sample count and,
     * for a single channel, the latest value. With [spanSeconds], the time the plot covers, a
     * time axis is drawn: ticks every second (every 5 or 10 s for longer spans) back from the
     * present at the right edge. A timed buffer needs [spanSeconds].
     */
    fun bind(buffer: PreviewBuffer, label: String, count: AtomicLong? = null, spanSeconds: Double? = null) {
        require(!buffer.timed || spanSeconds != null) { "A timed preview needs a time span" }
        this.buffer = buffer
        this.label = label
        this.count = count
        this.spanSeconds = spanSeconds
        scratch = FloatArray(buffer.channels * buffer.capacity)
        times = LongArray(if (buffer.timed) buffer.capacity else 0)
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val buf = buffer ?: return
        val n = buf.snapshot(scratch, if (buf.timed) times else null)
        val ch = buf.channels
        val text = count?.let { c ->
            label + " · ${c.get()}" + if (ch == 1 && n > 0) " · ${"%.4g".format(scratch[n - 1])}" else ""
        } ?: label
        canvas.drawText(text, 4f, labelPaint.textSize, labelPaint)

        val span = spanSeconds
        val top = labelPaint.textSize * 1.4f
        val axisY = height - paddingBottom - 1f * density
        val bottom = axisY - 3f * density
        if (span != null) drawTimeAxis(canvas, span, axisY)

        if (buf.timed) drawTimed(canvas, n, ch, span!!, top, bottom)
        else if (n > 1) drawRegular(canvas, buf, n, ch, top, bottom)

        if (isAttachedToWindow && windowVisibility == VISIBLE) postInvalidateOnAnimation()
    }

    /** Base line with ticks back from the present (right edge), and the span at the top right. */
    private fun drawTimeAxis(canvas: Canvas, span: Double, axisY: Float) {
        canvas.drawLine(0f, axisY, width.toFloat(), axisY, axisPaint)
        val step = TimeAxis.tickStep(span)
        val tick = 4f * density
        var k = 0
        while (true) {
            val x = width - (k * step / span * width).toFloat()
            if (x < 0f) break
            canvas.drawLine(x, axisY, x, axisY - tick, axisPaint)
            k++
        }
        val spanText = TimeAxis.formatSpan(span)
        canvas.drawText(spanText, width - labelPaint.measureText(spanText) - 4f, labelPaint.textSize, labelPaint)
    }

    private fun drawRegular(canvas: Canvas, buf: PreviewBuffer, n: Int, ch: Int, top: Float, bottom: Float) {
        val (min, range) = scale(0, n, ch) ?: return
        val h = bottom - top
        val dx = width.toFloat() / (buf.capacity - 1)
        for (c in 0 until ch) {
            path.reset()
            var penUp = true
            for (s in 0 until n) {
                val v = scratch[s * ch + c]
                if (v.isNaN()) { penUp = true; continue } // gap where samples were lost
                val x = width - (n - 1 - s) * dx
                val y = top + h * (1f - (v - min) / range)
                if (penUp) path.moveTo(x, y) else path.lineTo(x, y)
                penUp = false
            }
            paint.color = traceColors[c % traceColors.size]
            canvas.drawPath(path, paint)
        }
    }

    /** Steps against time over the last [span] seconds; each value holds until the next. */
    private fun drawTimed(canvas: Canvas, n: Int, ch: Int, span: Double, top: Float, bottom: Float) {
        if (n == 0) return
        val now = SystemClock.elapsedRealtimeNanos()
        val start = now - (span * 1e9).toLong()
        // The last sample before the window still holds at its left edge.
        var first = 0
        while (first < n - 1 && times[first + 1] <= start) first++
        val (min, range) = scale(first, n, ch) ?: return
        val h = bottom - top
        fun xOf(t: Long) = (width * ((t - start).toDouble() / (now - start))).toFloat().coerceIn(0f, width.toFloat())
        for (c in 0 until ch) {
            path.reset()
            var lastY = Float.NaN
            for (s in first until n) {
                val v = scratch[s * ch + c]
                if (v.isNaN()) continue
                val x = xOf(times[s])
                val y = top + h * (1f - (v - min) / range)
                if (lastY.isNaN()) path.moveTo(x, y) else { path.lineTo(x, lastY); path.lineTo(x, y) }
                lastY = y
            }
            if (lastY.isNaN()) continue
            path.lineTo(width.toFloat(), lastY)
            paint.color = traceColors[c % traceColors.size]
            canvas.drawPath(path, paint)
        }
    }

    /** Minimum and range of samples [from] until [n], ignoring NaN; null when all are NaN. */
    private fun scale(from: Int, n: Int, ch: Int): Pair<Float, Float>? {
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        for (i in from * ch until n * ch) {
            val v = scratch[i]
            if (v.isNaN()) continue // lost samples
            min = minOf(min, v); max = maxOf(max, v)
        }
        if (min > max) return null
        if (max == min) { min -= 0.5f; max += 0.5f } // constant: draw it mid-height
        return min to (max - min)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) postInvalidateOnAnimation()
    }
}

/** Time-axis layout of [SignalView], kept apart from the view so it can be unit tested. */
object TimeAxis {
    /** Seconds between ticks: 1 s up to a 10 s span, then 5 s up to a minute, then 10 s. */
    fun tickStep(spanSeconds: Double) = when {
        spanSeconds <= 10 -> 1.0
        spanSeconds <= 60 -> 5.0
        else -> 10.0
    }

    /** `5 s`, `2.5 s` (in the phone's number format), `30 s`. */
    fun formatSpan(spanSeconds: Double): String =
        if (spanSeconds >= 10 || spanSeconds == Math.rint(spanSeconds)) "${Math.round(spanSeconds)} s"
        else "${"%.1f".format(spanSeconds)} s"
}
