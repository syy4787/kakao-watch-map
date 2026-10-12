package com.mywatch.kakaomap.model

import org.json.JSONObject

/** 장소를 기기에 저장(최근 목적지·집·회사)할 때 쓰는 JSON 모양 */
object PlaceJson {

    fun toJson(p: Place): JSONObject = JSONObject()
        .put("id", p.id).put("name", p.name).put("category", p.category)
        .put("address", p.address).put("road", p.roadAddress)
        .put("lat", p.coord.lat).put("lng", p.coord.lng)
        .put("group", p.group)

    /** 좌표가 없으면 길찾기에 쓸 수 없으므로 null. 거리는 검색한 곳 기준이라 저장하지 않는다 */
    fun fromJson(o: JSONObject): Place? {
        if (!o.has("lat") || !o.has("lng")) return null
        val lat = o.optDouble("lat")
        val lng = o.optDouble("lng")
        if (lat.isNaN() || lng.isNaN()) return null
        return Place(
            id = o.optString("id"),
            name = o.optString("name"),
            category = o.optString("category"),
            address = o.optString("address"),
            roadAddress = o.optString("road"),
            coord = Coord(lat, lng),
            distanceM = null,
            group = o.optString("group"),
        )
    }
}
