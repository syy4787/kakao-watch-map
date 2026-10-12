package com.mywatch.kakaomap.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.mywatch.kakaomap.model.Coord
import com.mywatch.kakaomap.model.GuideStep
import com.mywatch.kakaomap.model.MiniMapProjection
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 손목을 내렸을 때(저전력 화면) 보여 주는 '선만 그린 미니 지도'.
 * 지도 엔진(GPU)·네트워크 없이 이미 가진 경로 좌표로 직접 그린다 → 거의 전력을 쓰지 않으면서
 * 경로·내 위치·목적지 방향·다음 안내를 계속 볼 수 있다.
 * Wear OS 저전력 화면 규칙: 화면 85% 이상 검정, 채우기 대신 윤곽선, 저비트 화면이면 안티에일리어싱 끄기,
 * 가장자리 10px 이상 여백 (번인 방지용 화면 이동은 시스템이 알아서 함).
 */
class AmbientRouteView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val dp = resources.displayMetrics.density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private var steps: List<GuideStep> = emptyList()
    private var destination: Coord? = null
    private var me: Coord? = null
    private var stepIndex = -1
    private var top: String = ""
    private var bottom: String = ""

    private val ahead = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * dp
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }
    private val passed = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * dp
        color = Color.rgb(110, 110, 110)
    }
    private val rideEffect = DashPathEffect(floatArrayOf(7 * dp, 5 * dp), 0f)
    private val outline = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * dp
        color = Color.WHITE
    }
    private val textTop = TextPaint().apply {
        color = Color.rgb(230, 230, 230)
        textSize = sp(14f)
    }
    private val textBottom = TextPaint().apply {
        color = Color.rgb(170, 170, 170)
        textSize = sp(12f)
    }
    private val path = Path()
    private val clipCircle = Path()

    /** 저비트 저전력 화면이면 안티에일리어싱·회색을 쓰지 않는다 */
    fun setLowBit(lowBit: Boolean) {
        val aa = !lowBit
        listOf(ahead, passed, outline, textTop, textBottom).forEach { it.isAntiAlias = aa }
        passed.color = if (lowBit) Color.WHITE else Color.rgb(110, 110, 110)
        passed.pathEffect = if (lowBit) DashPathEffect(floatArrayOf(2 * dp, 6 * dp), 0f) else null
        textBottom.color = if (lowBit) Color.WHITE else Color.rgb(170, 170, 170)
        invalidate()
    }

    fun setRoute(steps: List<GuideStep>, destination: Coord?) {
        this.steps = steps
        this.destination = destination
        invalidate()
    }

    fun update(me: Coord?, stepIndex: Int, top: String, bottom: String) {
        this.me = me
        this.stepIndex = stepIndex
        this.top = top
        this.bottom = bottom
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2
        val cy = h / 2
        val edge = maxOf(10f, 6 * dp)                       // 가장자리 여백 (시스템 번인 이동 대비)
        val radius = min(w, h) / 2 - edge
        val center = me ?: destination ?: steps.firstOrNull()?.points?.firstOrNull()

        if (center != null && steps.isNotEmpty()) {
            // 내 위치 기준 반경 약 300m 를 원 안에 보여 줌 (북쪽 위)
            val proj = MiniMapProjection.fit(center, RADIUS_M, radius, cx, cy)
            canvas.save()
            clipCircle.reset()
            clipCircle.addCircle(cx, cy, radius, Path.Direction.CW)
            canvas.clipPath(clipCircle)
            steps.forEachIndexed { i, s ->
                if (s.points.size < 2) return@forEachIndexed
                path.reset()
                s.points.forEachIndexed { j, p ->
                    if (j == 0) path.moveTo(proj.x(p), proj.y(p)) else path.lineTo(proj.x(p), proj.y(p))
                }
                if (i < stepIndex) {
                    canvas.drawPath(path, passed)
                } else {
                    ahead.pathEffect = if (s.ride) rideEffect else null
                    canvas.drawPath(path, ahead)
                }
            }
            ahead.pathEffect = null
            canvas.restore()

            // 목적지: 화면 안이면 윤곽 깃발 표시, 밖이면 가장자리에 방향 삼각형
            destination?.let { d ->
                val dist = proj.distancePx(d)
                if (dist <= radius - 8 * dp) {
                    val x = proj.x(d)
                    val y = proj.y(d)
                    canvas.drawLine(x, y, x, y - 16 * dp, outline)
                    path.reset()
                    path.moveTo(x, y - 16 * dp)
                    path.lineTo(x + 10 * dp, y - 12 * dp)
                    path.lineTo(x, y - 8 * dp)
                    canvas.drawPath(path, outline)
                } else {
                    val a = proj.angleTo(d)
                    val r = radius - 4 * dp
                    val tipX = cx + r * cos(a)
                    val tipY = cy + r * sin(a)
                    val back = 12 * dp
                    val half = 7 * dp
                    path.reset()
                    path.moveTo(tipX, tipY)
                    path.lineTo(tipX - back * cos(a) + half * sin(a), tipY - back * sin(a) - half * cos(a))
                    path.lineTo(tipX - back * cos(a) - half * sin(a), tipY - back * sin(a) + half * cos(a))
                    path.close()
                    canvas.drawPath(path, outline)
                }
            }
            // 내 위치: 속이 빈 원 (채우기 대신 윤곽선)
            if (me != null) canvas.drawCircle(cx, cy, 7 * dp, outline)
            // 북쪽 표시
            textBottom.textAlign = Paint.Align.CENTER
            canvas.drawText("N", cx, cy - radius + edge + sp(10f), textBottom)
            textBottom.textAlign = Paint.Align.LEFT
        }

        // 위: 다음 안내 (최대 3줄) / 아래: 남은 거리·목적지
        drawBlock(canvas, top, textTop, cx, h * 0.17f, w * 0.64f, 3, fromTop = true)
        drawBlock(canvas, bottom, textBottom, cx, h * 0.84f, w * 0.58f, 2, fromTop = false)
    }

    private fun drawBlock(c: Canvas, text: String, paint: TextPaint, cx: Float, y: Float, width: Float, maxLines: Int, fromTop: Boolean) {
        if (text.isBlank()) return
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt().coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        c.save()
        c.translate(cx - width / 2, if (fromTop) y else y - layout.height)
        layout.draw(c)
        c.restore()
    }

    companion object {
        /** 원 반지름에 들어가는 실제 거리 (m) */
        const val RADIUS_M = 300.0
    }
}
