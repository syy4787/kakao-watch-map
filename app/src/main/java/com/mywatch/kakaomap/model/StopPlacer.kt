package com.mywatch.kakaomap.model

import kotlin.math.max

/** 정류장·역 좌표를 노선 선 위로 올려 '노선 시작부터 몇 m'인지 구한다 (순수 계산) */
object StopPlacer {

    /**
     * [stops] 를 순서대로 [path] 위에 투영한 거리(m). 앞 정류장보다 뒤에만 올 수 있고(노선 순서),
     * 노선에서 [maxOffM] 넘게 떨어졌거나 좌표가 없으면 null.
     */
    fun alongDistances(path: List<Coord>, stops: List<Coord?>, maxOffM: Double = 150.0): List<Double?> {
        if (path.size < 2) return stops.map { null }
        val cum = DoubleArray(path.size)
        for (i in 1 until path.size) cum[i] = cum[i - 1] + Geo.distanceM(path[i - 1], path[i])
        var fromSeg = 0
        var lastAlong = 0.0
        return stops.map { stop ->
            if (stop == null) return@map null
            var best = -1
            var bestD = Double.MAX_VALUE
            var bestT = 0.0
            for (s in fromSeg until path.size - 1) {
                val (d, t) = Geo.distanceToSegment(stop, path[s], path[s + 1])
                val along = cum[s] + t * (cum[s + 1] - cum[s])
                if (along + 1.0 < lastAlong) continue           // 앞 정류장보다 뒤에 있어야 함
                if (d < bestD) {
                    bestD = d
                    best = s
                    bestT = t
                }
            }
            if (best < 0 || bestD > maxOffM) return@map null
            val along = cum[best] + bestT * (cum[best + 1] - cum[best])
            fromSeg = best
            lastAlong = max(lastAlong, along)
            along
        }
    }

    /** 카카오 대중교통 응답의 역 이름 → 장소 검색어 ("유성온천(충남대.목원대)" → "유성온천역") */
    fun stationQuery(name: String): String {
        val base = name.substringBefore('(').trim()
        return if (base.endsWith("역")) base else "${base}역"
    }

    /** 정류장 이름 비교 (괄호·띄어쓰기·'정류장' 낱말 무시) */
    fun sameStop(a: String, b: String): Boolean {
        fun norm(s: String) = s.substringBefore('(').replace(" ", "").removeSuffix("정류장").removeSuffix("정류소")
        return norm(a) == norm(b)
    }
}
