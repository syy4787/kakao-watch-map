package com.mywatch.kakaomap.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.mywatch.kakaomap.data.RoutePresentation
import com.mywatch.kakaomap.model.TransitRoute
import com.mywatch.kakaomap.model.TransitStepType

/**
 * 카카오맵 대중교통 결과처럼 구간(도보·버스·지하철)을 시간 비율로 이어 그린 막대.
 * 탈것 구간 안에는 버스 번호 / 노선 이름을 넣는다.
 */
class SegmentBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private data class Seg(val weight: Float, val color: Int, val label: String?)

    private var segs: List<Seg> = emptyList()
    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics)
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    fun setRoute(route: TransitRoute) {
        val total = route.steps.sumOf { it.timeSec }.coerceAtLeast(1)
        segs = route.steps.filter { it.timeSec > 0 }.map { s ->
            Seg(
                weight = s.timeSec.toFloat() / total,
                color = RoutePresentation.stepColor(s),
                label = if (s.type == TransitStepType.WALK) null else RoutePresentation.stepLabel(s),
            )
        }
        contentDescription = route.steps.joinToString(" → ") { RoutePresentation.stepLabel(it) }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (segs.isEmpty()) return
        val gap = 2f * density
        val minW = 10f * density
        val h = height.toFloat()
        val usable = width - gap * (segs.size - 1)
        // 아주 짧은 구간도 보이도록 최소 폭을 주고 나머지를 비율대로 나눈다
        val raw = segs.map { maxOf(minW, it.weight * usable) }
        val scale = usable / raw.sum()
        var x = 0f
        segs.forEachIndexed { i, s ->
            val w = raw[i] * scale
            val segLabel = s.label
            val walk = segLabel == null
            val inset = if (walk) h * 0.32f else 0f
            rect.set(x, inset, x + w, h - inset)
            fill.color = s.color
            val r = (h - 2 * inset) / 2
            canvas.drawRoundRect(rect, r, r, fill)
            if (segLabel != null) {
                val label = ellipsize(segLabel, w - 4 * density)
                if (label.isNotEmpty()) {
                    val y = h / 2 - (text.descent() + text.ascent()) / 2
                    canvas.drawText(label, x + w / 2, y, text)
                }
            }
            x += w + gap
        }
    }

    private fun ellipsize(s: String, maxWidth: Float): String {
        if (text.measureText(s) <= maxWidth) return s
        var end = s.length
        while (end > 0 && text.measureText(s.substring(0, end) + "…") > maxWidth) end--
        return if (end <= 0) "" else s.substring(0, end) + "…"
    }
}
