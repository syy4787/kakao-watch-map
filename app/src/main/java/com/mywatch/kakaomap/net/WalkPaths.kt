package com.mywatch.kakaomap.net

import com.mywatch.kakaomap.data.ErrorLog
import com.mywatch.kakaomap.model.Coord
import com.mywatch.kakaomap.model.RouteStatusException
import com.mywatch.kakaomap.model.SimpleRoute
import com.mywatch.kakaomap.model.TransitRoute
import com.mywatch.kakaomap.model.TransitWalks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 대중교통 경로의 직선 도보를 카카오 도보 길찾기의 실제 길로 바꿔 끼운다 ([TransitWalks]).
 * 구간마다 동시에 묻고, 다 합쳐 [enrich] 의 제한 시간까지만 기다린다 — 못 받은 구간은 직선 그대로.
 * 묻는 일은 따로 돌린다: 끊긴 연결이 끝나기를(최대 12초) 기다리느라 안내 시작이 늦어지지 않게.
 */
object WalkPaths {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * [log] = 오류 기록. 길이 없다는 답(너무 가까움 등)은 오류가 아니라 남기지 않고, 끊김 등은 한 번 부를 때 한 줄만
     * (못 받아도 직선으로 안내하는 덤이라 최근 오류 기록을 밀어내지 않게)
     */
    @OptIn(ExperimentalCoroutinesApi::class)   // getCompleted: 끝난 것만 골라 씀 (isCompleted 확인 뒤)
    suspend fun enrich(
        route: TransitRoute,
        timeoutMs: Long = 4_000,
        log: (where: String, e: Throwable) -> Unit = { where, e -> ErrorLog.record(where, e) },
        fetch: (from: Coord, to: Coord) -> SimpleRoute = { from, to -> KakaoApi.walk(from, to, "정류장") },
    ): TransitRoute {
        val legs = TransitWalks.pending(route)
        if (legs.isEmpty()) return route
        val logged = AtomicBoolean(false)
        val jobs = legs.map { i ->
            val pts = route.steps[i].points
            i to scope.async {
                try {
                    fetch(pts.first(), pts.last())
                } catch (e: Exception) {
                    if (e !is RouteStatusException && logged.compareAndSet(false, true)) log("정류장까지 걷는 길", e)
                    null
                }
            }
        }
        withTimeoutOrNull(timeoutMs) { jobs.map { it.second }.awaitAll() }
        var r = route
        for ((i, job) in jobs) {
            val walk = if (job.isCompleted && !job.isCancelled) job.getCompleted() else null
            if (walk != null) r = TransitWalks.apply(r, i, walk)
        }
        return r
    }
}
