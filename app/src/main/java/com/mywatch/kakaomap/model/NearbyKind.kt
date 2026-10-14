package com.mywatch.kakaomap.model

/**
 * 검색 첫 화면의 '내 주변' 칩 — 카카오 카테고리 그룹 코드가 있으면 카테고리 검색, 없으면(화장실) 키워드 검색.
 * 가까운 순으로 5km 안 ([com.mywatch.kakaomap.net.KakaoApi.nearbyRequest])
 */
enum class NearbyKind(val label: String, val code: String?) {
    STORE("편의점", "CS2"),
    PHARMACY("약국", "PM9"),
    TOILET("화장실", null),
    CAFE("카페", "CE7"),
    SUBWAY("지하철역", "SW8");

    /**
     * 결과로 보여 줄 곳. 화장실은 카테고리가 없어 키워드로 찾는데, 이름에 '화장실'이 든 인테리어·조립식 주택 업체가 섞여 나온다
     * (실측: '대전 가온 화장실천장 교체' · '이동식화장실총판') — 카카오 분류가 '화장실'인 곳만
     */
    fun keeps(place: Place): Boolean = when (this) {
        TOILET -> place.category == "화장실"
        else -> true
    }
}
