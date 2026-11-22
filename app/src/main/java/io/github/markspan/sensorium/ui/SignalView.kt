package io.github.markspan.sensorium.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.google.android.material.color.MaterialColors
import io.github.markspan.sensorium.stream.PreviewBuffer
import java.util.concurrent.atomic.AtomicLong

/** Auto-scaling live plot of a [PreviewBuffer]; one trace per channel, redrawn each frame while attached. */
class SignalView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var buffer: PreviewBuffer? = null
    private var scratch = FloatArray(0)
    private val path = Path()
    private val traceColors = intArrayOf(
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary),
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorTertiary),
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorSecondary),
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorError),
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline),
    )
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        strokeJoin = Paint.Join.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(this@SignalView, com.google.android.material.R.attr.colorOnSurfaceVariant)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
    }
    var label: String = ""
    private var count: AtomicLong? = null

    /**
     * Plots [buffer] under [label]. With [count], the label also shows that sample count and,
     * for a single channel, the latest value (useful for slow on-change sensors).
     */
    fun bind(buffer: PreviewBuffer, label: String, count: AtomicLong? = null) {
        this.buffer = buffer
        this.label = label
        this.count = count
        scratch = FloatArray(buffer.channels * buffer.capacity)
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val buf = buffer ?: return
        val n = buf.snapshot(scratch)
        val ch = buf.channels
        val text = count?.let { c ->
            label + " · ${c.get()}" + if (ch == 1 && n > 0) " · ${"%.4g".format(scratch[n - 1])}" else ""
        } ?: label
        canvas.drawText(text, 4f, labelPaint.textSize, labelPaint)
        if (n > 1) {
            var min = Float.MAX_VALUE
            var max = -Float.MAX_VALUE
            for (i in 0 until n * ch) {
                val v = scratch[i]
                if (v.isNaN()) continue // lost samples
                min = minOf(min, v); max = maxOf(max, v)
            }
            if (min > max) { min = 0f; max = 0f } // nothing but NaN
            if (max == min) { min -= 0.5f; max += 0.5f } // constant: draw it mid-height
            val range = max - min
            val top = labelPaint.textSize * 1.4f
            val h = height - top - paddingBottom
            val dx = width.toFloat() / (buf.capacity - 1)
            for (c in 0 until ch) {
                path.reset()
                var penUp = true
                for (s in 0 until n) {
                    val v = scratch[s * ch + c]
                    if (v.isNaN()) { penUp = true; continue } // gap where samples were lost
                    val x = s * dx
                    val y = top + h * (1f - (v - min) / range)
                    if (penUp) path.moveTo(x, y) else path.lineTo(x, y)
                    penUp = false
                }
                paint.color = traceColors[c % traceColors.size]
                canvas.drawPath(path, paint)
            }
        }
        if (isAttachedToWindow && windowVisibility == VISIBLE) postInvalidateOnAnimation()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) postInvalidateOnAnimation()
    }
}
