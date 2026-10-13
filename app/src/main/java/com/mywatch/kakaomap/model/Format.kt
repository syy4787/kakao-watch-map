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

    /** 지금부터 seconds 뒤의 시각 → "14:05" (지도 위 작은 알약처럼 좁은 곳용) */
    fun clock24After(seconds: Int, nowMillis: Long = System.currentTimeMillis()): String {
        val c = Calendar.getInstance(Locale.KOREA).apply { timeInMillis = nowMillis + seconds * 1000L }
        return String.format(Locale.KOREA, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    /** 1,500원 */
    fun fare(won: Int): String = NumberFormat.getNumberInstance(Locale.KOREA).format(won) + "원"

    /** 거리 따라 달라지는 요금: "1,500~3,150원" (같거나 모르면 "1,500원") */
    fun fare(min: Int, max: Int?): String =
        if (max != null && max > min) NumberFormat.getNumberInstance(Locale.KOREA).format(min) + "~" + fare(max) else fare(min)

    /** 경로 요금 (모르면 null) */
    fun fare(route: TransitRoute): String? = route.fare?.let { fare(it, route.fareMax) }

    fun transfers(count: Int): String = if (count <= 0) "환승 없음" else "환승 ${count}회"

    /** 버스 도착 예정 → "3분", "곧 도착" (카카오맵 표기) */
    fun arrival(seconds: Int): String = when {
        seconds < 0 -> "정보 없음"
        seconds < 60 -> "곧 도착"
        else -> "${seconds / 60}분"
    }

    /** "3분 (2번째 전)", 1분 안이면 "곧 도착" (카카오맵 표기) */
    fun busArrival(a: BusArrival): String = when {
        a.arrivalSec in 0 until 60 -> "곧 도착"
        a.arrivalSec < 0 -> if (a.stopsAway > 0) "${a.stopsAway}번째 전" else arrival(a.arrivalSec)
        a.stopsAway > 0 -> "${arrival(a.arrivalSec)} (${a.stopsAway}번째 전)"
        else -> arrival(a.arrivalSec)
    }
}
