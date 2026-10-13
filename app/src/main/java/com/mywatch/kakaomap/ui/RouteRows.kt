package com.mywatch.kakaomap.ui

import com.mywatch.kakaomap.R
import com.mywatch.kakaomap.data.RoutePresentation
import com.mywatch.kakaomap.model.Format
import com.mywatch.kakaomap.model.SimpleRoute
import com.mywatch.kakaomap.model.TransitGuide
import com.mywatch.kakaomap.model.TransitRoute
import com.mywatch.kakaomap.model.TransitStepType
import com.mywatch.kakaomap.model.TravelMode

/** 경로 단계 목록(도보·자전거 안내 / 대중교통 타임라인) — 길찾기 화면과 상세 화면이 같이 쓴다 */
object RouteRows {

    /** 도보·자전거: 갈림길마다 한 줄 + 도착 */
    fun simpleSteps(route: SimpleRoute, destName: String): List<Row> {
        val icon = if (route.mode == TravelMode.BIKE) R.drawable.ic_bike else R.drawable.ic_walk
        val color = if (route.mode == TravelMode.BIKE) RoutePresentation.BIKE_ROUTE_COLOR else RoutePresentation.WALK_ROUTE_COLOR
        val rows = ArrayList<Row>(route.steps.size + 1)
        route.steps.forEachIndexed { i, s ->
            rows += Row.Step(
                color = color,
                icon = icon,
                chip = null,
                title = s.guidance.ifBlank { if (i == 0) "출발" else "직진" },
                sub = "${Format.distance(s.distanceM)} · ${Format.duration(s.timeSec)}",
                key = "step$i",
            )
        }
        rows += Row.Step(RoutePresentation.busColor("광역"), R.drawable.ic_place, null, "도착 · $destName", null)
        return rows
    }

    /** 대중교통: 출발 → 도보 → 버스(실시간) → 지하철 → … → 도착 */
    fun transitTimeline(route: TransitRoute, destName: String, liveEnabled: Boolean): List<Row> {
        val rows = ArrayList<Row>(route.steps.size + 2)
        // 실시간 도착은 기차·고속버스를 타러 가는 버스만 (내려서 탈 버스는 몇 시간 뒤)
        val live = if (liveEnabled) TransitGuide.liveBusIndexes(route).toSet() else emptySet()
        rows += Row.Step(RoutePresentation.WALK_ROUTE_COLOR, R.drawable.ic_my_location, null, "출발 · 내 위치", null)
        route.steps.forEachIndexed { i, s ->
            val color = RoutePresentation.stepColor(s)
            rows += when (s.type) {
                TransitStepType.WALK -> Row.Step(
                    color, R.drawable.ic_walk, null,
                    "도보 ${Format.duration(s.timeSec)}",
                    Format.distance(s.distanceM),
                    key = "s$i",
                )
                TransitStepType.BUS -> Row.Step(
                    color, R.drawable.ic_bus, RoutePresentation.stepLabel(s),
                    "${s.boardingStop ?: "정류장"} 승차",
                    "${s.stopCount}개 정류장 · ${Format.duration(s.timeSec)}\n${s.alightingStop ?: ""} 하차",
                    live = if (i in live) "실시간 도착 확인 중…" else null,
                    key = "s$i",
                )
                TransitStepType.SUBWAY -> Row.Step(
                    color, R.drawable.ic_subway, RoutePresentation.stepLabel(s),
                    "${s.boardingStop ?: "역"} 승차",
                    "${s.stopCount}개 역 · ${Format.duration(s.timeSec)}\n${s.alightingStop ?: ""} 하차",
                    key = "s$i",
                )
                // 기차·고속·시외버스: 시간표의 출발·도착 시각 — 칩("KTX-산천 105") 옆 제목은 짧게 "09:58 출발",
                // 역 이름은 아래 넓은 줄에 ("대전역 승차 · 53분 / 서울역 10:51 도착")
                TransitStepType.TRAIN, TransitStepType.COACH -> Row.Step(
                    color, if (s.type == TransitStepType.TRAIN) R.drawable.ic_train else R.drawable.ic_bus,
                    RoutePresentation.stepLabel(s),
                    s.departAtMs?.let { "${TransitGuide.clock(it)} 출발" } ?: "${s.boardingStop ?: "역"} 승차",
                    listOfNotNull("${s.boardingStop ?: "역"} 승차", Format.duration(s.timeSec), if (s.weekly) "다음 주 시간표 기준" else null)
                        .joinToString(" · ") + "\n" +
                        listOfNotNull(s.alightingStop ?: "", s.arriveAtMs?.let { TransitGuide.clock(it) }).joinToString(" ") + " 도착",
                    key = "s$i",
                )
                TransitStepType.OTHER -> Row.Step(color, R.drawable.ic_directions, null, s.guidance, Format.duration(s.timeSec), key = "s$i")
            }
        }
        rows += Row.Step(RoutePresentation.busColor("광역"), R.drawable.ic_place, null, "도착 · $destName", null)
        return rows
    }
}
