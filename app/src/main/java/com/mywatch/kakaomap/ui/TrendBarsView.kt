package com.mywatch.kakaomap.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.mywatch.battery.core.Grade
import com.mywatch.battery.core.Thresholds
import com.mywatch.battery.core.TrendBarsModel
import com.mywatch.kakaomap.R
import java.util.Locale

/**
 * 배터리 화면 '최근 며칠' — 날마다 대기 소모 막대(판정 색), 막대 위 값, 그날 바뀐 것이 있으면 위쪽 흰 점,
 * 대기 기준(3.2%/h) 점선, 꺾인 자리 세로 점선, 오늘 날짜는 굵은 흰 글자. 무엇을 그릴지는 [TrendBarsModel] (battery-core) 이 정한다.
 */
class TrendBarsView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var model: TrendBarsModel? = null
        set(value) {
            field = value
            contentDescription = value?.description
            invalidate()
        }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private fun fill(colorRes: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(colorRes) }
    private val normal = fill(R.color.grade_normal)
    private val caution = fill(R.color.grade_caution)
    private val abnormal = fill(R.color.grade_abnormal)
    private val empty = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3A3C40.toInt(); strokeWidth = dp(2f) }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF55585E.toInt(); strokeWidth = dp(1f); style = Paint.Style.STROKE; pathEffect = DashPathEffect(floatArrayOf(dp(3f), dp(3f)), 0f)
    }
    private val split = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt(); strokeWidth = dp(1.5f); style = Paint.Style.STROKE; pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(3f)), 0f)
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.text_primary); textSize = sp(9f); textAlign = Paint.Align.CENTER }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.text_secondary); textSize = sp(10f); textAlign = Paint.Align.CENTER }
    private val todayLabel = Paint(label).apply { color = context.getColor(R.color.text_primary); isFakeBoldText = true }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = resolveSize((dp(DOT_DP + VALUE_DP + PLOT_DP + LABEL_DP) + paddingTop + paddingBottom).toInt(), heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val m = model ?: return
        val n = m.bars.size
        if (n == 0) return
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val dotY = paddingTop + dp(DOT_DP / 2)
        val top = paddingTop + dp(DOT_DP + VALUE_DP)
        val bottom = (height - paddingBottom).toFloat() - dp(LABEL_DP)
        if (right <= left || bottom <= top) return
        val slot = (right - left) / n
        val barW = (slot * 0.62f).coerceAtMost(dp(22f))
        fun y(pph: Double) = (bottom - (bottom - top) * (pph / m.top).coerceIn(0.0, 1.0)).toFloat()

        // 대기 기준선 (이 아래면 정상)
        val ref = y(Thresholds.IDLE_NORMAL)
        canvas.drawLine(left, ref, right, ref, grid)
        for ((i, b) in m.bars.withIndex()) {
            val cx = left + slot * (i + 0.5f)
            val p = b.pph
            if (p == null) {
                canvas.drawLine(cx - barW / 3, bottom - dp(1f), cx + barW / 3, bottom - dp(1f), empty)
            } else {
                val paint = when (b.grade) { Grade.ABNORMAL -> abnormal; Grade.CAUTION -> caution; else -> normal }
                val yTop = minOf(y(p), bottom - dp(2f))
                canvas.drawRoundRect(cx - barW / 2, yTop, cx + barW / 2, bottom, dp(2f), dp(2f), paint)
                canvas.drawText(String.format(Locale.US, "%.1f", p), cx, yTop - dp(3f), value)
            }
            if (b.changes > 0) canvas.drawCircle(cx, dotY, dp(2.5f), dot)
            canvas.drawText(b.label, cx, (height - paddingBottom).toFloat() - dp(2f), if (b.today) todayLabel else label)
        }
        // 꺾인 자리
        m.splitAfter?.let { i ->
            val x = left + slot * (i + 1)
            canvas.drawLine(x, paddingTop.toFloat(), x, bottom, split)
        }
    }

    companion object {
        private const val DOT_DP = 8f
        private const val VALUE_DP = 12f
        private const val PLOT_DP = 64f
        private const val LABEL_DP = 14f
    }
}
