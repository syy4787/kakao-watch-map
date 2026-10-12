package com.mywatch.kakaomap.data

import android.graphics.Color
import com.mywatch.kakaomap.model.Coord
import com.mywatch.kakaomap.model.Format
import com.mywatch.kakaomap.model.GuideStep
import com.mywatch.kakaomap.model.Place
import com.mywatch.kakaomap.model.SimpleRoute
import com.mywatch.kakaomap.model.TransitGuide
import com.mywatch.kakaomap.model.TransitRoute
import com.mywatch.kakaomap.model.TransitStep
import com.mywatch.kakaomap.model.TransitStepType
import com.mywatch.kakaomap.model.TravelMode

/** 노선 색·구간 이름 등 '보이는 모습'을 정하는 곳 (카카오맵과 같은 국내 노선 색 관례) */
object RoutePresentation {

    val WALK_COLOR = Color.parseColor("#9AA0A6")
    val APPROX_WALK_COLOR = Color.parseColor("#B8BEC4")
    val WALK_ROUTE_COLOR = Color.parseColor("#2F6FED")
    val BIKE_ROUTE_COLOR = Color.parseColor("#1FA35C")
    private val DEFAULT_BUS = Color.parseColor("#3366CC")
    private val DEFAULT_SUBWAY = Color.parseColor("#5C6BC0")

    fun busColor(type: String): Int = Color.parseColor(
        when {
            type.contains("간선") -> "#3366CC"
            type.contains("지선") -> "#33A23D"
            type.contains("마을") -> "#5BB025"
            type.contains("순환") -> "#F2B705"
            type.contains("공항") -> "#0099CC"
            listOf("광역", "직행", "좌석", "급행", "시외", "외곽").any { type.contains(it) } -> "#E2382F"
            else -> "#3366CC"
        }
    )

    /** 수도권·주요 도시 지하철 노선 색 (이름에 포함된 낱말로 판단, 모르면 기본색) */
    fun subwayColor(name: String): Int {
        val table = listOf(
            "신분당" to "#D4003B", "수인분당" to "#F5A200", "분당" to "#F5A200", "경의중앙" to "#77C4A3",
            "경춘" to "#0C8E72", "공항" to "#0090D2", "서해" to "#81A914", "우이신설" to "#B0CE18",
            "신림" to "#6789CA", "김포" to "#AD8605", "에버라인" to "#56AD2D", "의정부" to "#FDA600",
            "인천1" to "#7CA8D5", "인천2" to "#ED8B00", "ITX" to "#0C8E72",
            "1호선" to "#0052A4", "2호선" to "#00A84D", "3호선" to "#EF7C1C", "4호선" to "#00A5DE",
            "5호선" to "#996CAC", "6호선" to "#CD7C2F", "7호선" to "#747F00", "8호선" to "#E6186C",
            "9호선" to "#BDB092",
        )
        return table.firstOrNull { name.contains(it.first) }?.let { Color.parseColor(it.second) } ?: DEFAULT_SUBWAY
    }

    fun stepColor(step: TransitStep): Int = when (step.type) {
        TransitStepType.WALK -> WALK_COLOR
        TransitStepType.BUS -> step.vehicles.firstOrNull()?.let { busColor(it.type) } ?: DEFAULT_BUS
        TransitStepType.SUBWAY -> step.vehicles.firstOrNull()?.let { subwayColor(it.name + it.type) } ?: DEFAULT_SUBWAY
        TransitStepType.OTHER -> DEFAULT_SUBWAY
    }

    /** 구간 막대·칩에 쓰는 짧은 이름: 버스 번호 / 지하철 노선 */
    fun stepLabel(step: TransitStep): String = TransitGuide.label(step)

    fun transitSummary(r: TransitRoute): String =
        "${Format.duration(r.totalTimeSec)} · ${Format.clockAfter(r.totalTimeSec)} 도착"

    fun simpleSummary(r: SimpleRoute): String {
        val what = if (r.mode == TravelMode.BIKE) "자전거" else "도보"
        return "$what ${Format.duration(r.totalTimeSec)} · ${Format.distance(r.totalDistanceM)}"
    }

    fun activeFromTransit(r: TransitRoute, place: Place, origin: Coord, navigate: Boolean) = ActiveRoute(
        mode = TravelMode.TRANSIT,
        place = place,
        summary = transitSummary(r),
        origin = origin,
        segments = r.steps.filter { it.points.size >= 2 }.map {
            when {
                it.approx -> MapSegment(it.points, APPROX_WALK_COLOR, 3f)   // 직선으로 채운 도보는 연하게
                it.type == TransitStepType.WALK -> MapSegment(it.points, stepColor(it), 4.5f)
                else -> MapSegment(it.points, stepColor(it), 7f)
            }
        },
        steps = TransitGuide.steps(r),
        startNavigation = navigate,
        transit = r,
    )

    fun activeFromSimple(r: SimpleRoute, place: Place, origin: Coord, navigate: Boolean): ActiveRoute {
        val color = if (r.mode == TravelMode.BIKE) BIKE_ROUTE_COLOR else WALK_ROUTE_COLOR
        val all = r.steps.flatMap { it.points }
        return ActiveRoute(
            mode = r.mode,
            place = place,
            summary = simpleSummary(r),
            origin = origin,
            segments = if (all.size >= 2) listOf(MapSegment(all, color, 7f)) else emptyList(),
            // 도보·자전거 API 문구는 '이 구간 시작에서 할 일'이라 회전 안내(미리 알림·화살표)에 쓴다
            steps = r.steps.map { GuideStep(it.guidance, it.points, turnGuidance = true) },
            startNavigation = navigate,
            simple = r,
        )
    }
}
