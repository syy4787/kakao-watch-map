package com.mywatch.kakaomap.nav

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.annotation.MainThread
import com.mywatch.kakaomap.data.RouteRepository
import com.mywatch.kakaomap.model.Fix
import com.mywatch.kakaomap.model.TravelMode
import com.mywatch.kakaomap.net.StopLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 지금 띄운 경로의 안내 상태를 한곳에서 관리한다.
 * 지도 화면과 안내 서비스가 둘 다 위치를 넣어 주고(같은 위치는 한 번만 처리), 둘 다 [state] 를 본다.
 * '안내 시작'한 경로일 때만 진동으로 알린다. 모든 호출은 메인 스레드에서.
 */
object Navigator {
    private val _state = MutableStateFlow(NavState())
    val state: StateFlow<NavState> = _state

    private var engine: NavEngine? = null
    private var lastFixElapsed = 0L

    /** 경로가 바뀌었거나 끝났으면 안내 상태를 새로 만든다 */
    @MainThread
    fun sync() {
        val active = RouteRepository.activeRoute
        val version = RouteRepository.activeRouteVersion
        if (active == null) {
            engine = null
            if (_state.value.routeVersion != version || _state.value.guide != null) _state.value = NavState(routeVersion = version)
            return
        }
        if (engine?.routeVersion == version) return
        engine = NavEngine(active.steps, active.destination, version, bike = active.mode == TravelMode.BIKE, destinationName = active.destinationName)
            .also { _state.value = it.state }
        // 버스·지하철 정류장 위치는 네트워크로 받아 와서 나중에 넣는다 ('정류장 n개 남음')
        if (active.mode == TravelMode.TRANSIT) StopLocator.start(active, version)
        lastFixElapsed = 0L
    }

    @MainThread
    fun onFix(context: Context, fix: Fix) {
        RouteRepository.updateFix(fix)
        sync()
        val eng = engine ?: return
        val active = RouteRepository.activeRoute ?: return
        if (fix.elapsedMs <= lastFixElapsed) return                                   // 이미 처리한 위치
        if (SystemClock.elapsedRealtime() - fix.elapsedMs > MAX_FIX_AGE_MS) return   // 오래된 위치로는 안내하지 않음
        lastFixElapsed = fix.elapsedMs
        val events = eng.update(fix.coord, fix.accuracyM, fix.elapsedMs)
        _state.value = eng.state
        if (active.startNavigation) events.forEach { vibrate(context, it) }
    }

    @MainThread
    fun setRerouting(on: Boolean) {
        val eng = engine ?: return
        eng.setRerouting(on)
        _state.value = eng.state
    }

    /** 탈 것 구간의 정류장 위치 (다른 경로로 바뀌었으면 무시) */
    @MainThread
    fun setStops(routeVersion: Int, stepIndex: Int, along: List<Double>) {
        val eng = engine ?: return
        if (eng.routeVersion != routeVersion) return
        eng.setStops(stepIndex, along)
    }

    /** GPS 신호를 한동안 못 받음 / 다시 받음 (배너에 '신호 약함' 표시) */
    @MainThread
    fun setSignalLost(lost: Boolean) {
        val eng = engine ?: return
        eng.setSignalLost(lost)
        _state.value = eng.state
    }

    fun vibrate(context: Context, event: NavEvent) {
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
        if (vibrator == null || !vibrator.hasVibrator()) return
        // 화면을 안 봐도 손목만으로 구별되게 (ms: 쉼, 울림, 쉼, 울림 …)
        //  좌회전 짧게 2번 · 우회전 짧게 3번 · 유턴 4번 · 횡단보도 등 길게 1번 · 승차 중간 2번 · 하차 길게 3번
        //  정류장 지남 아주 길게 2번 · 이탈 빠르게 5번 · 도착 길게 1번 · 새 경로 짧게 1번
        val pattern = when (event) {
            NavEvent.TURN_SOON_LEFT -> longArrayOf(0, 90, 130, 90)
            NavEvent.TURN_SOON_RIGHT -> longArrayOf(0, 90, 130, 90, 130, 90)
            NavEvent.UTURN_SOON -> longArrayOf(0, 90, 130, 90, 130, 90, 130, 90)
            NavEvent.ACTION_SOON -> longArrayOf(0, 300)
            NavEvent.BOARD_SOON -> longArrayOf(0, 200, 150, 200)
            NavEvent.ALIGHT_SOON -> longArrayOf(0, 350, 150, 350, 150, 350)
            NavEvent.PASSED_STOP -> longArrayOf(0, 600, 200, 600)
            NavEvent.OFF_ROUTE -> longArrayOf(0, 60, 60, 60, 60, 60, 60, 60, 60, 60)
            NavEvent.ARRIVED -> longArrayOf(0, 600)
            NavEvent.REROUTED -> longArrayOf(0, 150)
        }
        runCatching { vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1)) }
    }

    /** 이보다 오래된 위치는 안내 판단에 쓰지 않는다 */
    private const val MAX_FIX_AGE_MS = 30_000L
}
