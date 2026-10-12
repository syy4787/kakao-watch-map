package com.mywatch.kakaomap.model

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** 거리 계산과 경로 진행 위치 찾기 (순수 계산) */
object Geo {
    private const val EARTH_R = 6_371_000.0

    fun distanceM(a: Coord, b: Coord): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val s = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * EARTH_R * atan2(sqrt(s), sqrt(1 - s))
    }

    /** 0°=북, 시계 방향 */
    fun bearingDeg(from: Coord, to: Coord): Double {
        val phi1 = Math.toRadians(from.lat)
        val phi2 = Math.toRadians(to.lat)
        val dLambda = Math.toRadians(to.lng - from.lng)
        val y = sin(dLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /** 점 p 에서 선분 ab 까지 (거리 m, 선분 위 비율 0..1) — 짧은 거리라 평면 근사 */
    fun distanceToSegment(p: Coord, a: Coord, b: Coord): Pair<Double, Double> {
        val k = cos(Math.toRadians(p.lat)) * PI / 180 * EARTH_R
        val ax = (a.lng - p.lng) * k
        val ay = (a.lat - p.lat) * PI / 180 * EARTH_R
        val bx = (b.lng - p.lng) * k
        val by = (b.lat - p.lat) * PI / 180 * EARTH_R
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(ax + t * dx, ay + t * dy) to t
    }
}

/**
 * 안내용 구간: 화면에 띄울 문구 + 그 구간의 좌표.
 * ride = 버스·지하철을 타고 가는 구간(경로 이탈 판단 안 함), alight = 내릴 정류장(하차 알림용).
 */
data class GuideStep(
    val text: String,
    val points: List<Coord>,
    val ride: Boolean = false,
    val alight: String? = null,
    val subway: Boolean = false,
    /** 직선으로 채운 도보 구간 — 실제 걷는 길과 달라서 이탈 판단을 하지 않는다 */
    val approx: Boolean = false,
)

/** 경로 위 현재 진행 상황 */
data class RouteProgress(
    val stepIndex: Int,
    /** 현재 구간 끝(다음 안내 지점)까지 남은 거리 m */
    val toStepEndM: Int,
    /** 도착지까지 남은 거리 m */
    val remainingM: Int,
    /** 경로에서 벗어난 거리 m */
    val offRouteM: Int,
    /** 경로 선분 번호 (다음 계산 때 이 근처부터 찾는다) */
    val segIndex: Int = 0,
) {
    companion object {
        private const val FORWARD_MARGIN_M = 2.0

        /**
         * 내 위치가 경로의 어디쯤인지 계산한다.
         * 경로가 같은 길을 되돌아가는 경우(정류장까지 걸어가서 반대 방향 버스 등)에 뒤쪽 구간으로
         * 잘못 건너뛰지 않도록, 가장 가까운 거리와 [toleranceM] 이내로 비슷한 후보 중 경로상 '먼저 지날' 곳을 고른다.
         * [fromSeg] 보다 앞 선분은 보지 않는다(이미 지나온 곳).
         */
        fun locate(location: Coord, steps: List<GuideStep>, fromSeg: Int = 0, toleranceM: Double = 15.0): RouteProgress? {
            // 구간별 선분 목록과 길이
            data class Seg(val step: Int, val a: Coord, val b: Coord, val len: Double)
            val segs = ArrayList<Seg>()
            steps.forEachIndexed { i, s ->
                for (j in 0 until s.points.size - 1) {
                    val a = s.points[j]
                    val b = s.points[j + 1]
                    segs += Seg(i, a, b, Geo.distanceM(a, b))
                }
            }
            if (segs.isEmpty()) return null

            val start = fromSeg.coerceIn(0, segs.size - 1)
            val dist = DoubleArray(segs.size) { Double.MAX_VALUE }
            val along = DoubleArray(segs.size)
            var minDist = Double.MAX_VALUE
            for (i in start until segs.size) {
                val (d, t) = Geo.distanceToSegment(location, segs[i].a, segs[i].b)
                dist[i] = d
                along[i] = t
                if (d < minDist) minDist = d
            }
            var best = start
            for (i in start until segs.size) {
                if (dist[i] <= minDist + toleranceM) {
                    best = i
                    break
                }
            }
            // 이어진 다음 선분이 확실히(2m 넘게) 더 가까우면 따라 내려간다
            // (선분 경계에서 한 칸 뒤에 머무는 것 방지, 경계에서 GPS 흔들림으로 왔다 갔다 하는 것은 막음)
            while (best + 1 < segs.size && dist[best + 1] + FORWARD_MARGIN_M < dist[best]) best++

            val cur = segs[best]
            val restOfSeg = cur.len * (1 - along[best])
            var toStepEnd = restOfSeg
            var remaining = restOfSeg
            for (i in best + 1 until segs.size) {
                remaining += segs[i].len
                if (segs[i].step == cur.step) toStepEnd += segs[i].len
            }
            return RouteProgress(cur.step, toStepEnd.toInt(), remaining.toInt(), dist[best].toInt(), best)
        }
    }
}
