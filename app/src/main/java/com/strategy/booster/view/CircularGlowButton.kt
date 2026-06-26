package com.strategy.booster.view

import android.content.Context
import android.graphics.*
import android.graphics.drawable.RippleDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.strategy.booster.R
import kotlin.math.min
import androidx.core.content.withStyledAttributes
import androidx.core.graphics.toColorInt

class CircularGlowButton @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.BottomNavColor)
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(4f)
    }
    private var icon = ContextCompat.getDrawable(context, R.drawable.add_icon)
    private var iconSize = dp(50f)

    init {
        isClickable = true
        isFocusable = true
        background = RippleDrawable(
            ContextCompat.getColorStateList(context, R.color.ripple_light)!!,
            null, null
        )

        attrs?.let {
            context.withStyledAttributes(it, R.styleable.CircularGlowButton) {
                icon = getDrawable(R.styleable.CircularGlowButton_cgb_icon) ?: icon
                iconSize = getDimension(R.styleable.CircularGlowButton_cgb_iconSize, iconSize)
            }
        }

        elevation = dp(10f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val cx = w / 2f
        val cy = h / 2f
        ringPaint.shader = SweepGradient(
            cx, cy,
            intArrayOf("#36cb4d".toColorInt(), "#52bdf5".toColorInt(), "#36cb4d".toColorInt()),
            floatArrayOf(0f, 0.6f, 1f)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = (min(width, height) / 2f)

        canvas.drawCircle(cx, cy, radius - dp(2f), fillPaint)
        canvas.drawCircle(cx, cy, radius - dp(6f), ringPaint)

        icon?.let {
            val l = (cx - iconSize / 2f).toInt()
            val t = (cy - iconSize / 2f).toInt()
            it.setBounds(l, t, (l + iconSize).toInt(), (t + iconSize).toInt())
            it.draw(canvas)
        }
    }

    fun setIcon(@DrawableRes resId: Int) {
        icon = ContextCompat.getDrawable(context, resId)
        invalidate()
    }

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
}
