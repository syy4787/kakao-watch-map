package com.mywatch.kakaomap.net

import android.content.Context
import com.mywatch.kakaomap.data.RouteRepository
import com.mywatch.kakaomap.location.Locator
import com.mywatch.kakaomap.model.Coord
import com.mywatch.kakaomap.model.NearbyKind
import com.mywatch.kakaomap.model.Place

/**
 * 장소 검색은 내 주변부터. 카카오 키워드 검색은 위치를 주면 가까운 곳을 먼저 주지만, 위치 없이 찾으면 전국에서 아무 데나
 * 나온다(실측: '스타벅스' → 은평·경주·강릉). 예전엔 30분 안에 잡힌 위치가 없으면(타일의 '검색'으로 바로 들어온 경우 등)
 * 위치 없이 찾았다 → 그럴 땐 대략적 위치(Wi-Fi·기지국)를 잠깐 잡아 보고 찾는다. 그래도 없으면 전국 ([Found.center] 없음).
 * '내 주변'은 거리가 곧 결과라 지금 위치만 쓴다 (10분 안, 새로 못 잡으면 15분 안의 마지막 위치까지 — 그보다 오래면 '위치를 잡지 못했어요').
 * 네트워크를 쓰므로 백그라운드에서 부른다.
 */
class PlaceSearch(
    /** [maxAgeMs] 안에 잡힌 위치 (깨우지 않고 바로) */
    private val recent: (maxAgeMs: Long) -> Coord?,
    /** 대략적 위치를 새로 (몇 초까지). 못 잡으면 [lastMaxAgeMs] 안의 마지막 위치 */
    private val locate: suspend (lastMaxAgeMs: Long) -> Coord?,
    private val keyword: (String, Coord?) -> List<Place>,
    private val nearby: (NearbyKind, Coord) -> List<Place>,
) {
    /** 찾은 곳들과 찾을 때 쓴 내 위치 (없으면 전국에서 찾음) */
    class Found(val places: List<Place>, val center: Coord?)

    suspend fun search(query: String): Found {
        val center = recent(RECENT_MS) ?: locate(SEARCH_LAST_MAX_AGE_MS)
        return Found(keyword(query, center), center)
    }

    /** 내 주변 [kind] — 지금 위치를 모르면 null */
    suspend fun nearby(kind: NearbyKind): Found? {
        val center = recent(NEARBY_RECENT_MS) ?: locate(NEARBY_LAST_MAX_AGE_MS) ?: return null
        return Found(nearby(kind, center), center)
    }

    companion object {
        /** 이름 검색: 이만큼 지난 위치까지는 검색 중심으로 그대로 (가까운 곳을 먼저 받는 데만 씀) */
        private const val RECENT_MS = 30 * 60_000L
        /** 이름 검색: 새로 못 잡으면 이만큼 지난 마지막 위치까지 (전국에서 아무 데나 찾는 것보다는 낫다) */
        private const val SEARCH_LAST_MAX_AGE_MS = 6 * 60 * 60_000L
        /** 내 주변: 결과와 거리가 그 위치 기준이라 지금 위치만 */
        private const val NEARBY_RECENT_MS = 10 * 60_000L
        private const val NEARBY_LAST_MAX_AGE_MS = 15 * 60_000L

        fun live(context: Context) = PlaceSearch(
            recent = { maxAgeMs -> RouteRepository.freshLocation(maxAgeMs) },
            locate = { lastMaxAgeMs -> Locator.rough(context.applicationContext, lastMaxAgeMs = lastMaxAgeMs) },
            keyword = KakaoApi::searchPlaces,
            nearby = KakaoApi::nearby,
        )
    }
}
