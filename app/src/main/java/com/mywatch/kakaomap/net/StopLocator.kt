package com.mywatch.kakaomap.net

import android.util.Log
import com.mywatch.kakaomap.MapApp
import com.mywatch.kakaomap.data.ActiveRoute
import com.mywatch.kakaomap.model.Coord
import com.mywatch.kakaomap.model.Geo
import com.mywatch.kakaomap.model.StopPlacer
import com.mywatch.kakaomap.model.TransitStep
import com.mywatch.kakaomap.model.TransitStepType
import com.mywatch.kakaomap.nav.Navigator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 버스·지하철 구간의 정류장(역) 위치를 찾아 '정류장 n개 남음'·'다음 정류장 하차'에 쓴다.
 *  · 지하철: 카카오 장소 검색의 지하철역(SW8) 좌표 — 키 하나로 바로 됨
 *  · 버스: 공공데이터포털(TAGO) 노선 정류소 목록 — 그 키가 있을 때만 (없으면 남은 거리로 안내)
 * 못 찾으면 조용히 넘어간다 (안내는 남은 거리 기준으로 계속).
 */
object StopLocator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val stationCache = HashMap<String, Coord>()

    /** 새 대중교통 경로가 정해졌을 때 (메인 스레드). 결과는 [Navigator.setStops] 로 넣는다 */
    fun start(active: ActiveRoute, routeVersion: Int) {
        job?.cancel()
        val transit = active.transit ?: return
        job = scope.launch {
            transit.steps.forEachIndexed { i, step ->
                if (step.type != TransitStepType.BUS && step.type != TransitStepType.SUBWAY) return@forEachIndexed
                val along = runCatching { alongFor(step) }
                    .onFailure { Log.i(MapApp.TAG, "정류장 위치를 못 찾음: ${it.message}") }
                    .getOrNull() ?: return@forEachIndexed
                withContext(Dispatchers.Main) { Navigator.setStops(routeVersion, i, along) }
            }
        }
    }

    /** 탄 정류장 다음부터 내릴 정류장까지, 노선 시작부터의 거리(m). 하나라도 못 찾으면 null */
    fun alongFor(step: TransitStep): List<Double>? {
        val coords = when (step.type) {
            TransitStepType.SUBWAY -> subwayCoords(step)
            TransitStepType.BUS -> busCoords(step)
            else -> null
        } ?: return null
        val along = StopPlacer.alongDistances(step.points, coords).drop(1)
        return if (along.isEmpty() || along.any { it == null }) null else along.filterNotNull()
    }

    private fun subwayCoords(step: TransitStep): List<Coord?>? {
        if (step.stops.size < 2 || step.points.size < 2) return null
        val mid = step.points[step.points.size / 2]
        return step.stops.map { name ->
            synchronized(stationCache) { stationCache[name] }?.let { return@map it }
            val q = StopPlacer.stationQuery(name)
            val found = KakaoApi.subwayStations(q, mid)
                .filter { it.name.startsWith(q) || it.name.startsWith(name) }
                .minByOrNull { pathDistance(it.coord, step.points) }
                ?.coord
            if (found != null) synchronized(stationCache) { stationCache[name] = found }
            found
        }
    }

    private fun busCoords(step: TransitStep): List<Coord?>? {
        if (!TagoApi.isConfigured) return null
        val board = step.boardingStop ?: return null
        val alight = step.alightingStop ?: return null
        val start = step.points.firstOrNull() ?: return null
        val city = TagoApi.nearbyStops(start).firstOrNull()?.cityCode ?: return null
        val names = step.vehicles.map { it.name }.filter { it.isNotBlank() }
        for (no in names.take(2)) {
            for (r in TagoApi.routes(city, no).filter { it.routeNo == no }.take(3)) {
                val seq = TagoApi.routeStops(city, r.routeId)
                val bi = seq.indexOfFirst { StopPlacer.sameStop(it.name, board) }
                if (bi < 0) continue
                val ai = (bi + 1 until seq.size).firstOrNull { StopPlacer.sameStop(seq[it].name, alight) } ?: continue
                // 카카오가 준 정거장 수와 크게 다르면 다른 방향·다른 노선
                if (step.stops.isNotEmpty() && kotlin.math.abs((ai - bi + 1) - step.stops.size) > 2) continue
                return seq.subList(bi, ai + 1).map { it.coord }
            }
        }
        return null
    }

    private fun pathDistance(c: Coord, path: List<Coord>): Double =
        (0 until path.size - 1).minOfOrNull { Geo.distanceToSegment(c, path[it], path[it + 1]).first } ?: Double.MAX_VALUE
}
