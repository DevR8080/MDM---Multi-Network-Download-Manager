package com.strategy.booster.view

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import com.strategy.booster.R

class DomeBackgroundView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.BottomNavColor)
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.gray)
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }

    private val path = Path()
    private val rect = RectF()
    private val radii = FloatArray(8)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        path.reset()
        rect.set(0f, 0f, w.toFloat(), h.toFloat())

        val r = w / 2f
        radii[0] = r; radii[1] = r
        radii[2] = r; radii[3] = r
        radii[4] = 0f; radii[5] = 0f
        radii[6] = 0f; radii[7] = 0f

        path.addRoundRect(rect, radii, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, borderPaint)
    }

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
}

