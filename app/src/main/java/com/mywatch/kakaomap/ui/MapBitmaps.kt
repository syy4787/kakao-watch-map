package com.mywatch.kakaomap.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt

/** 지도 위 표시용 그림 (내 위치 점·방향, 출발/도착 말풍선) — 기기 픽셀 크기로 직접 그린다 */
object MapBitmaps {

    private val BLUE = "#2F6FED".toColorInt()

    /** 내 위치: 파란 점 + (선택) 위쪽을 가리키는 반투명 부채꼴 */
    fun myLocation(context: Context, withCone: Boolean): Bitmap {
        val d = context.resources.displayMetrics.density
        val size = (64 * d).toInt()
        val bmp = createBitmap(size, size)
        val c = Canvas(bmp)
        val cx = size / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        if (withCone) {
            p.color = Color.argb(90, 47, 111, 237)
            val path = Path().apply {
                moveTo(cx, cx)
                // 위쪽(북쪽)으로 70° 벌어진 부채꼴
                arcTo(RectF(0f, 0f, size.toFloat(), size.toFloat()), -125f, 70f)
                close()
            }
            c.drawPath(path, p)
        }
        val r = 9 * d
        p.color = Color.WHITE
        c.drawCircle(cx, cx, r, p)
        p.color = BLUE
        c.drawCircle(cx, cx, r * 0.68f, p)
        return bmp
    }

    /** 걷는 길 위에 일정한 간격으로 찍는 흰 점 (카카오맵의 점선 도보처럼). 지도 엔진이 선 굵기에 맞춰 늘린다 */
    fun walkDot(context: Context): Bitmap {
        val size = (6 * context.resources.displayMetrics.density).toInt().coerceAtLeast(6)
        val bmp = createBitmap(size, size)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        Canvas(bmp).drawCircle(size / 2f, size / 2f, size * 0.32f, p)
        return bmp
    }

    /** "출발" / "도착" 말풍선 (아래 꼭짓점이 좌표) */
    fun bubble(context: Context, text: String, color: Int): Bitmap {
        val d = context.resources.displayMetrics.density
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, context.resources.displayMetrics)
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val padH = 9 * d
        val bodyH = 24 * d
        val tail = 7 * d
        val w = (tp.measureText(text) + padH * 2).toInt()
        val h = (bodyH + tail).toInt()
        val bmp = createBitmap(w, h)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), bodyH), bodyH / 2, bodyH / 2, p)
        val path = Path().apply {
            moveTo(w / 2f - tail, bodyH - 1)
            lineTo(w / 2f + tail, bodyH - 1)
            lineTo(w / 2f, bodyH + tail)
            close()
        }
        c.drawPath(path, p)
        val y = bodyH / 2 - (tp.descent() + tp.ascent()) / 2
        c.drawText(text, w / 2f, y, tp)
        return bmp
    }
}
