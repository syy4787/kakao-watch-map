package com.mywatch.kakaomap.model

import java.text.NumberFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/** 화면에 보여 줄 문구 만들기 (카카오맵과 같은 표기) */
object Format {

    /** 850m, 1.2km, 12km */
    fun distance(meters: Int): String = when {
        meters < 1000 -> "${meters}m"
        meters < 10_000 -> String.format(Locale.KOREA, "%.1fkm", meters / 1000.0)
        else -> "${(meters / 1000.0).roundToInt()}km"
    }

    /** 1분, 42분, 1시간 5분, 2시간 (초 → 분 올림) */
    fun duration(seconds: Int): String {
        val minutes = maxOf(1, ceil(seconds / 60.0).toInt())
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "${m}분"
            m == 0 -> "${h}시간"
            else -> "${h}시간 ${m}분"
        }
    }

    /** 지금부터 seconds 뒤의 시각 → "오후 9:55" */
    fun clockAfter(seconds: Int, nowMillis: Long = System.currentTimeMillis()): String {
        val c = Calendar.getInstance(Locale.KOREA).apply { timeInMillis = nowMillis + seconds * 1000L }
        val hour24 = c.get(Calendar.HOUR_OF_DAY)
        val ampm = if (hour24 < 12) "오전" else "오후"
        val hour12 = (hour24 % 12).let { if (it == 0) 12 else it }
        return String.format(Locale.KOREA, "%s %d:%02d", ampm, hour12, c.get(Calendar.MINUTE))
    }

    /** 1,500원 */
    fun fare(won: Int): String = NumberFormat.getNumberInstance(Locale.KOREA).format(won) + "원"

    fun transfers(count: Int): String = if (count <= 0) "환승 없음" else "환승 ${count}회"

    /** 버스 도착 예정 → "3분 후", "곧 도착" */
    fun arrival(seconds: Int): String = when {
        seconds < 0 -> "정보 없음"
        seconds < 60 -> "곧 도착"
        else -> "${seconds / 60}분 후"
    }

    /** "3분 후 (2정거장 전)" */
    fun busArrival(a: BusArrival): String =
        if (a.stopsAway > 0) "${arrival(a.arrivalSec)} (${a.stopsAway}정거장 전)" else arrival(a.arrivalSec)
}
