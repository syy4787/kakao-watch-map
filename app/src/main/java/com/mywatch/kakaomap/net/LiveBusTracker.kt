package com.mywatch.kakaomap.net

import android.content.Context
import android.os.SystemClock
import androidx.annotation.MainThread
import com.mywatch.kakaomap.data.ActiveRoute
import com.mywatch.kakaomap.model.TransitGuide
import com.mywatch.kakaomap.model.TransitRoute
import com.mywatch.kakaomap.model.TravelMode
import com.mywatch.kakaomap.nav.NavState
import com.mywatch.kakaomap.nav.Navigator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 안내 중 시내 버스를 타러 가거나 정류장에서 기다리는 동안 실시간 도착을 [EVERY_MS] 마다 받아
 * 안내 배너·알림에 붙인다 (카카오맵 길안내처럼 "106번 3분 (2번째 전)").
 * 배터리: 위치가 들어올 때 함께 판단하므로 따로 깨우지 않고, 버스에 타면·안내를 끝내면·지도에서 보기만 하면 묻지 않는다
 * (요청 하나는 정류장 도착 목록 한 번 — 승차 정류장은 처음 한 번 찾아 기억).
 */
object LiveBusTracker {
    const val EVERY_MS = 45_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    /** 지금 묻고 있는 "경로 번호:구간" */
    private var target: String? = null
    private var lastAt = 0L

    /**
     * 실시간 도착을 물을 버스 구간 (순수 판단): '안내 시작'한 대중교통 경로에서 타러 가거나 기다리는 시내 버스 구간.
     * 타는 중·도착·경로 이탈·재탐색·정류장 지남·놓침이면 null
     */
    fun targetStep(route: TransitRoute?, s: NavState, navigating: Boolean): Int? {
        if (!navigating || route == null) return null
        if (s.arrived || s.onRide || s.offRoute || s.rerouting || s.passedStop || s.missedRide) return null
        val i = s.boardingStep ?: return null
        return i.takeIf { it in TransitGuide.liveBusIndexes(route) }
    }

    /** 위치마다 (메인 스레드) */
    @MainThread
    fun onState(context: Context, active: ActiveRoute?, routeVersion: Int, s: NavState) {
        val navigating = active != null && active.startNavigation && active.mode == TravelMode.TRANSIT && TagoApi.isConfigured
        val idx = targetStep(active?.transit, s, navigating)
        val step = idx?.let { active?.transit?.steps?.getOrNull(it) }
        if (idx == null || step == null) {
            stop()
            return
        }
        val key = "$routeVersion:$idx"
        if (key != target) {
            stop()
            target = key
        }
        val now = SystemClock.elapsedRealtime()
        if (job?.isActive == true || (lastAt != 0L && now - lastAt < EVERY_MS)) return
        lastAt = now
        job = scope.launch {
            val bus = BusArrivalService.live(step)
            withContext(Dispatchers.Main) {
                if (target == key) Navigator.setLiveArrival(context, routeVersion, idx, bus)
            }
        }
    }

    /** 경로가 바뀌거나 끝났을 때 (메인 스레드) */
    @MainThread
    fun stop() {
        job?.cancel()
        job = null
        target = null
        lastAt = 0L
    }
}
