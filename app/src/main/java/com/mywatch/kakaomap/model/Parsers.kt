package com.mywatch.kakaomap.model

import org.json.JSONArray
import org.json.JSONObject

/** 경로 API가 'OK'가 아닌 상태를 돌려줄 때 */
class RouteStatusException(val status: String, message: String) : Exception(message)

/** 카카오 로컬·경로 API 응답 → 모델 (순수 함수라 기기 없이 테스트 가능) */
object KakaoParsers {

    fun places(json: JSONObject): List<Place> {
        val docs = json.optJSONArray("documents") ?: return emptyList()
        return (0 until docs.length()).mapNotNull { i ->
            val d = docs.optJSONObject(i) ?: return@mapNotNull null
            val lng = d.optString("x").toDoubleOrNull() ?: return@mapNotNull null
            val lat = d.optString("y").toDoubleOrNull() ?: return@mapNotNull null
            Place(
                id = d.optString("id"),
                name = d.optString("place_name"),
                category = d.optString("category_name").substringAfterLast('>').trim(),
                address = d.optString("address_name"),
                roadAddress = d.optString("road_address_name"),
                coord = Coord(lat, lng),
                distanceM = d.optString("distance").toIntOrNull(),
                group = d.optString("category_group_code"),
            )
        }
    }

    /** 도보·자전거 경로 (두 API의 응답 구조가 같음) */
    fun simpleRoute(json: JSONObject, mode: TravelMode): SimpleRoute {
        val status = json.optString("status")
        if (status != "OK") throw RouteStatusException(status, simpleStatusMessage(status, mode))
        val route = json.getJSONObject("route")
        val props = route.optJSONObject("properties") ?: JSONObject()
        val steps = ArrayList<PathStep>()
        val legs = route.optJSONArray("legs") ?: JSONArray()
        for (l in 0 until legs.length()) {
            val stepArr = legs.optJSONObject(l)?.optJSONArray("steps") ?: continue
            for (s in 0 until stepArr.length()) {
                val step = stepArr.optJSONObject(s) ?: continue
                val p = step.optJSONObject("properties") ?: JSONObject()
                val points = points(step.optJSONObject("path"))
                val start = if (p.has("x") && p.has("y")) Coord(p.optDouble("y"), p.optDouble("x"))
                else points.firstOrNull() ?: continue
                steps += PathStep(
                    guidance = p.optString("guidance"),
                    distanceM = p.optInt("distance"),
                    timeSec = p.optInt("time"),
                    start = start,
                    points = points.ifEmpty { listOf(start) },
                )
            }
        }
        return SimpleRoute(
            mode = mode,
            totalDistanceM = props.optInt("totalDistance", steps.sumOf { it.distanceM }),
            totalTimeSec = props.optInt("totalTime", steps.sumOf { it.timeSec }),
            steps = steps,
        )
    }

    fun transitRoutes(json: JSONObject): List<TransitRoute> {
        val status = json.optString("status")
        if (status != "OK") throw RouteStatusException(status, transitStatusMessage(status))
        val routes = json.optJSONArray("routes") ?: return emptyList()
        return (0 until routes.length()).mapNotNull { i ->
            val r = routes.optJSONObject(i) ?: return@mapNotNull null
            val p = r.optJSONObject("properties") ?: JSONObject()
            val fare = p.optJSONObject("fare")?.let { f -> if (f.has("value")) f.optInt("value") else null }
            val stepArr = r.optJSONArray("steps") ?: JSONArray()
            val steps = (0 until stepArr.length()).mapNotNull { s ->
                val step = stepArr.optJSONObject(s) ?: return@mapNotNull null
                val sp = step.optJSONObject("properties") ?: JSONObject()
                TransitStep(
                    type = when (sp.optString("type")) {
                        "WALKING", "WALK" -> TransitStepType.WALK
                        "BUS" -> TransitStepType.BUS
                        "SUBWAY" -> TransitStepType.SUBWAY
                        else -> TransitStepType.OTHER
                    },
                    guidance = sp.optString("guidance"),
                    distanceM = sp.optInt("distance"),
                    timeSec = sp.optInt("time"),
                    stops = sp.optJSONArray("stops").objects().map { it.optString("name") }.filter { it.isNotBlank() },
                    vehicles = sp.optJSONArray("vehicles").objects().map { Vehicle(it.optString("type"), it.optString("name")) },
                    points = points(step.optJSONObject("path")),
                )
            }
            TransitRoute(
                type = p.optString("type"),
                totalDistanceM = p.optInt("totalDistance"),
                totalTimeSec = p.optInt("totalTime"),
                transfers = p.optInt("transfers"),
                fare = fare,
                steps = steps,
            )
        }.sortedBy { it.totalTimeSec }
    }

    /** [[x, y], ...] → Coord 목록 */
    fun points(path: JSONObject?): List<Coord> {
        val arr = path?.optJSONArray("points") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val pair = arr.optJSONArray(i) ?: return@mapNotNull null
            if (pair.length() < 2) return@mapNotNull null
            Coord(lat = pair.optDouble(1), lng = pair.optDouble(0))
        }
    }

    fun simpleStatusMessage(status: String, mode: TravelMode): String {
        val what = if (mode == TravelMode.BIKE) "자전거" else "도보"
        return when (status) {
            "SAME_POINT" -> "출발지와 도착지가 같아요"
            "START_LINK_NOT_FOUND" -> "출발지 근처에서 길을 찾지 못했어요"
            "END_LINK_NOT_FOUND" -> "도착지 근처에서 길을 찾지 못했어요"
            "TOO_FAR_AWAY" -> "너무 멀어서 $what 길찾기를 할 수 없어요"
            "TOO_MANY_SEARCH_LINK" -> "경로가 너무 복잡해요"
            else -> "$what 경로를 찾지 못했어요"
        }
    }

    fun transitStatusMessage(status: String): String = when (status) {
        "STARTNODES_NULL" -> "출발지 근처에 정류장·역이 없어요"
        "ENDNODES_NULL" -> "도착지 근처에 정류장·역이 없어요"
        "EQUAL_POINTS" -> "출발지와 도착지가 같아요"
        "NO_RESULTS" -> "대중교통 경로가 없어요"
        else -> "대중교통 경로를 찾지 못했어요"
    }
}

/** 공공데이터포털(TAGO) 응답 → 모델 */
object TagoParsers {

    /** 오류면 사람이 읽을 메시지, 정상이면 null */
    fun errorMessage(json: JSONObject): String? {
        json.optJSONObject("OpenAPI_ServiceResponse")?.let { r ->
            val h = r.optJSONObject("cmmMsgHeader")
            return h?.optString("returnAuthMsg")?.ifBlank { null } ?: h?.optString("errMsg") ?: "공공데이터 오류"
        }
        val header = json.optJSONObject("response")?.optJSONObject("header") ?: return "공공데이터 응답 형식 오류"
        val code = header.optString("resultCode")
        return if (code == "00" || code == "0") null else header.optString("resultMsg").ifBlank { "공공데이터 오류($code)" }
    }

    /** items 가 비면 "" , 하나면 객체, 여러 개면 배열로 오는 TAGO 특유의 형식을 모두 처리 */
    fun items(json: JSONObject): List<JSONObject> {
        val items = json.optJSONObject("response")?.optJSONObject("body")?.opt("items")
        val item = (items as? JSONObject)?.opt("item") ?: return emptyList()
        return when (item) {
            is JSONArray -> item.objects()
            is JSONObject -> listOf(item)
            else -> emptyList()
        }
    }

    fun stops(json: JSONObject): List<BusStop> = items(json).mapNotNull { o ->
        val lat = o.optDouble("gpslati", Double.NaN)
        val lng = o.optDouble("gpslong", Double.NaN)
        val nodeId = o.optString("nodeid")
        if (nodeId.isBlank() || lat.isNaN() || lng.isNaN()) return@mapNotNull null
        BusStop(
            cityCode = o.optInt("citycode"),
            nodeId = nodeId,
            name = o.optString("nodenm"),
            coord = Coord(lat, lng),
        )
    }

    /** 노선 번호 검색 결과 (getRouteNoList) */
    fun routes(json: JSONObject): List<BusRoute> = items(json).mapNotNull { o ->
        val id = o.optString("routeid").ifBlank { null } ?: return@mapNotNull null
        BusRoute(id, o.opt("routeno")?.toString()?.trim().orEmpty())
    }

    /** 노선이 지나는 정류소 (getRouteAcctoThrghSttnList) — 노선 순서(nodeord)대로 */
    fun routeStops(json: JSONObject): List<BusStop> = items(json).mapNotNull { o ->
        val lat = o.optDouble("gpslati", Double.NaN)
        val lng = o.optDouble("gpslong", Double.NaN)
        if (lat.isNaN() || lng.isNaN()) return@mapNotNull null
        o.optInt("nodeord", Int.MAX_VALUE) to BusStop(
            cityCode = 0, nodeId = o.optString("nodeid"), name = o.optString("nodenm"), coord = Coord(lat, lng),
        )
    }.sortedBy { it.first }.map { it.second }

    fun arrivals(json: JSONObject): List<BusArrival> = items(json).mapNotNull { o ->
        val routeNo = o.opt("routeno")?.toString()?.trim().orEmpty()
        if (routeNo.isEmpty()) return@mapNotNull null
        BusArrival(
            routeNo = routeNo,
            routeType = o.optString("routetp"),
            arrivalSec = o.optInt("arrtime", -1),
            stopsAway = o.optInt("arrprevstationcnt", -1),
        )
    }.sortedBy { if (it.arrivalSec < 0) Int.MAX_VALUE else it.arrivalSec }
}

/** JSONArray → JSONObject 목록 (null 이면 빈 목록) */
fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONObject(it) }
}
