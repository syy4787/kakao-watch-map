package com.mywatch.kakaomap.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.mywatch.battery.core.DrainChartModel
import com.mywatch.kakaomap.R

/**
 * 배터리 화면의 24시간 그래프 — 잔량 선(기록이 끊긴 곳은 끊김), 충전(초록 음영), 가장 많이 닳은 시간(빨간 음영),
 * 화면 켜짐(아래 노란 띠, 사용 통계가 있을 때). 세로는 늘 0~100% (기울기를 날마다 비교할 수 있게).
 * 무엇을 어디에 그릴지는 [DrainChartModel] (battery-core) 이 정한다.
 */
class DrainChartView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var model: DrainChartModel? = null
        set(value) {
            field = value
            contentDescription = value?.description
            invalidate()
        }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3A3C40.toInt(); strokeWidth = dp(1f) }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.accent_light); style = Paint.Style.STROKE; strokeWidth = dp(2f)
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val charge = Paint().apply { color = 0x405AD17A }
    private val hot = Paint().apply { color = 0x48E2382F }
    private val screen = Paint().apply { color = 0xFFFFC107.toInt() }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.text_secondary); textSize = sp(10f) }
    private val now = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = sp(11f); isFakeBoldText = true }
    private val path = Path()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = resolveSize((dp(PLOT_DP + LABEL_DP) + paddingTop + paddingBottom).toInt(), heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val m = model ?: return
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat() - dp(30f)     // 오른쪽은 지금 잔량 글자 자리
        val top = paddingTop.toFloat() + dp(4f)
        val bottom = (height - paddingBottom).toFloat() - dp(LABEL_DP)
        if (right <= left || bottom <= top) return
        fun x(f: Double) = (left + (right - left) * f).toFloat()
        fun y(level: Int) = (bottom - (bottom - top) * level.coerceIn(0, 100) / 100f)

        // 가장 많이 닳은 시간·충전 음영 (선 아래에)
        for (r in m.hot) canvas.drawRect(x(r.from), top, maxOf(x(r.to), x(r.from) + dp(2f)), bottom, hot)
        for (r in m.charging) canvas.drawRect(x(r.from), top, maxOf(x(r.to), x(r.from) + dp(2f)), bottom, charge)
        // 0·50·100% 선
        for (lv in intArrayOf(0, 50, 100)) canvas.drawLine(left, y(lv), right, y(lv), grid)
        // 화면 켜짐 띠 (정확할 때만)
        if (m.screenExact) {
            val bandTop = bottom - dp(4f)
            for (r in m.screenOn) canvas.drawRect(x(r.from), bandTop, maxOf(x(r.to), x(r.from) + dp(1f)), bottom, screen)
        }
        // 잔량 선
        for (seg in m.lines) {
            if (seg.isEmpty()) continue
            path.reset()
            path.moveTo(x(seg[0].x), y(seg[0].level))
            for (i in 1 until seg.size) path.lineTo(x(seg[i].x), y(seg[i].level))
            if (seg.size == 1) canvas.drawCircle(x(seg[0].x), y(seg[0].level), dp(1.5f), dot) else canvas.drawPath(path, line)
        }
        // 마지막 점과 지금 잔량
        m.lines.lastOrNull()?.lastOrNull()?.let { p ->
            val px = x(p.x)
            val py = y(p.level)
            canvas.drawCircle(px, py, dp(3f), dot)
            val text = "${p.level}%"
            val ty = (py + now.textSize / 2.8f).coerceIn(top + now.textSize, bottom)
            canvas.drawText(text, minOf(px + dp(5f), (width - paddingRight).toFloat() - now.measureText(text)), ty, now)
        }
        // 시각 글자 (6시간마다) + 오른쪽 끝 '지금' — 겹치는 글자는 건너뛴다
        val ly = (height - paddingBottom).toFloat() - dp(2f)
        val nowW = label.measureText(NOW_LABEL)
        val nowLeft = right - nowW
        canvas.drawText(NOW_LABEL, nowLeft, ly, label)
        var lastEnd = Float.NEGATIVE_INFINITY
        for (t in m.ticks) {
            val w = label.measureText(t.label)
            val tx = (x(t.x) - w / 2).coerceAtLeast(left)
            if (tx < lastEnd + dp(4f) || tx + w > nowLeft - dp(4f)) continue
            canvas.drawLine(x(t.x), bottom, x(t.x), bottom + dp(3f), grid)
            canvas.drawText(t.label, tx, ly, label)
            lastEnd = tx + w
        }
    }

    companion object {
        private const val PLOT_DP = 84f
        private const val LABEL_DP = 14f
        const val NOW_LABEL = "지금"
    }
}
