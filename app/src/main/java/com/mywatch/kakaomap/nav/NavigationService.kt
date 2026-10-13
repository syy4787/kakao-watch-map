package com.mywatch.kakaomap.nav

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.mywatch.kakaomap.MainActivity
import com.mywatch.kakaomap.MapApp
import com.mywatch.kakaomap.R
import com.mywatch.kakaomap.data.ActiveRoute
import com.mywatch.kakaomap.data.ErrorLog
import com.mywatch.kakaomap.data.RoutePresentation
import com.mywatch.kakaomap.data.RouteRepository
import com.mywatch.kakaomap.location.Locator
import com.mywatch.kakaomap.location.toFix
import com.mywatch.kakaomap.model.Coord
import com.mywatch.kakaomap.model.Format
import com.mywatch.kakaomap.model.Geo
import com.mywatch.kakaomap.model.IntercityItineraries
import com.mywatch.kakaomap.model.IntercitySplice
import com.mywatch.kakaomap.model.RouteStatusException
import com.mywatch.kakaomap.model.TransitOptions
import com.mywatch.kakaomap.model.TransitRoute
import com.mywatch.kakaomap.model.TransitStep
import com.mywatch.kakaomap.model.TravelMode
import com.mywatch.kakaomap.net.IntercityPlanner
import com.mywatch.kakaomap.net.KakaoApi
import com.mywatch.kakaomap.net.LiveIntercitySource
import com.mywatch.kakaomap.net.TagoApi
import com.mywatch.kakaomap.net.WalkPaths
import com.mywatch.kakaomap.update.UpdateInstallWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * '안내 시작'한 동안 돌아가는 포그라운드 서비스.
 * 손목을 내려 화면이 꺼지거나 워치페이스로 돌아가도 위치를 계속 받아서
 * 갈림길·하차·이탈·도착을 진동으로 알리고, 도보·자전거는 경로를 벗어나면 다시 찾는다(카카오맵 재탐색처럼).
 * 워치페이스에는 '진행 중 활동' 아이콘이 떠서 눌러 바로 지도로 돌아올 수 있다.
 * 위치 간격은 [LocationPolicy] 가 상황에 맞춰 정한다 (갈림길 앞 1초 ~ 멀리 탈 때 15초, 도착 뒤엔 추가 전력 0).
 * 도착하면 5분 동안(또는 300m 넘게 떠날 때까지) '목적지까지 직선거리'를 보여 준 뒤 스스로 끝난다 —
 * 그래야 위치·진행 중 아이콘이 꺼지고, 미뤄 둔 자동 업데이트도 설치된다.
 * 버스에서 내릴 정류장을 지나치거나 환승 도보 중 길을 벗어나면 지금 위치에서 대중교통 경로를 다시 찾는다.
 */
class NavigationService : Service() {

    private val scope = MainScope()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var tracking = false
    private var foreground = false

    private var builder: NotificationCompat.Builder? = null
    private var ongoing: OngoingActivity? = null
    private var shownText: String? = null
    private var lastNotifyAt = 0L

    private var rerouteJob: Job? = null
    private var lastRerouteAt = 0L
    /** 새 경로에 올라서지 못한 채 연달아 재탐색한 횟수 → 간격을 30초·1분·2분·4분으로 늘림 */
    private var reroutesWithoutMatch = 0
    /** 이번 여정에서 다시 찾은 횟수 — 하루 무료 사용량(경로 API 1,000회)을 지키려고 10번까지만 */
    private var rerouteCount = 0
    /** 지금 따라가는 여정 (새 길찾기를 시작하면 횟수·시작 시각을 새로) */
    private var tripId = 0L
    private var retryJob: Job? = null
    /** 배터리 15% 이하인지 — 1분에 한 번만 확인 */
    private var lowBattery = false
    private var batteryCheckedAt = 0L
    private var lastStartId = 0

    /** 지금 걸어 둔 위치 요청 (바뀔 때만 다시 요청) */
    private var plan: LocPlan? = null
    private var arrivedAt = 0L
    private var arrivedVersion = -1
    /** 멈춤/움직임 (GPS 속도, 없으면 멈춘 곳에서 멀어졌는지) — 위치 간격을 정하는 데 씀 */
    private val motion = StillDetector()
    private var lastPlanAt = 0L
    /** 경로를 못 받은(안내 시작 후 아직 경로 위가 아닌) 시각 — 1초 간격을 무한정 쓰지 않게 */
    private var startedAt = 0L
    private var signalJob: Job? = null
    /** 경로 밖에 있는 시간 (10분: 저전력 위치로, 벗어난 채 30분: 안내 끝냄) */
    private val lostClock = LostClock()
    /**
     * 위치가 안 들어와도(멈춰 있으면 거의 안 옴) 정한 시각에 다시 판단하는 알람.
     * 코루틴 delay 는 워치가 잠든(CPU 꺼짐) 동안 시간이 안 가서 몇 시간씩 늦어지므로, 부팅 후 시각 기준 알람을 쓴다
     * (정확 알람이 아니라 남은 시간의 3/4 까지 늦을 수 있고 깊은 절전 중엔 미뤄짐 — 권한 불필요, 배터리 부담 없음.
     *  늦어도 그 사이 위치가 들어오면 그때 바로 판단하므로 이 알람은 '위치가 안 올 때'의 보험이다).
     */
    private val lostWake by lazy { Wake("kakaomap:lost") { onState(Navigator.state.value) } }
    private val passiveWake by lazy { Wake("kakaomap:passive") { onState(Navigator.state.value) } }
    private val finishWake by lazy { Wake("kakaomap:finish") { finishIfStillArrived() } }
    /** 저전력(Wi-Fi·기지국) 위치로 쉬는 동안 몇 분마다 GPS 로 한 번 확인 — 기지국 위치만 잡히면 걷기 시작해도 모르므로 */
    private val probeWake by lazy { Wake("kakaomap:probe") { probeGps() } }
    private var finishVersion = -1

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            // 모아서 받은 경우(배칭) 순서대로 모두 처리해야 갈림길·하차를 놓치지 않는다
            val wasStill = motion.still
            result.locations.forEach { loc ->
                motion.update(
                    loc.elapsedRealtimeNanos / 1_000_000, Coord(loc.latitude, loc.longitude),
                    if (loc.hasAccuracy()) loc.accuracy else null,
                    if (loc.hasSpeed()) loc.speed else null,
                )
                Navigator.onFix(this@NavigationService, loc.toFix())
            }
            onSignal(true)
            RouteRepository.activeRoute?.let { a ->
                val st = Navigator.state.value
                if (!foreground || !a.startNavigation || st.arrived || st.routeVersion != RouteRepository.activeRouteVersion) return@let
                // 멈춤 ↔ 움직임이 바뀌면 위치 계획을 다시 본다: 저전력(Wi-Fi·기지국) 위치는 거칠어서 엔진이 버리므로
                // 안내 상태가 안 바뀌어도, 걷기 시작하면 GPS 를 다시 켜야 한다
                if (motion.still != wasStill) applyPlan(planFor(a, st))
                // 벗어남·지나침·놓침은 상태가 그대로라 다시 알려 오지 않는다 — 위치가 올 때마다 다시 찾기를 시도 (간격은 그쪽에서 지킴)
                if ((st.offRoute || st.passedStop || st.missedRide) && !st.rerouting) maybeReroute(a, st)
            }
        }

        override fun onLocationAvailability(availability: LocationAvailability) {
            onSignal(availability.isLocationAvailable)
        }
    }

    /** 위치를 10초 넘게 못 받으면 배너에 '신호 약함' (지하·실내). 다시 받으면 바로 지움 */
    private fun onSignal(available: Boolean) {
        if (available) {
            signalJob?.cancel()
            signalJob = null
            if (Navigator.state.value.signalLost) Navigator.setSignalLost(false)
        } else if (signalJob?.isActive != true) {
            signalJob = scope.launch {
                delay(SIGNAL_LOST_AFTER_MS)
                Navigator.setSignalLost(true)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        scope.launch { Navigator.state.collect(::onState) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // startForegroundService 로 시작됐으면 무조건 먼저 포그라운드로 올려야 한다 (안 그러면 시스템이 앱을 끈다)
        if (!enterForeground()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        running = true
        if (intent?.action == ACTION_END) {
            // 알림의 '안내 종료'
            RouteRepository.activeRoute = null
            Navigator.sync()
            shutdown()
            return START_NOT_STICKY
        }
        val active = RouteRepository.activeRoute
        if (active == null || !active.startNavigation || !Locator.hasPrecise(this)) {
            shutdown()
            return START_NOT_STICKY
        }
        Navigator.sync()
        onState(Navigator.state.value)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        stopTracking()
        lostWake.cancel()
        passiveWake.cancel()
        finishWake.cancel()
        scope.cancel()
        super.onDestroy()
    }

    // ───────── 위치 (상황별로 간격을 바꿈) ─────────

    /**
     * 계획이 바뀌었을 때만 위치 요청을 갈아 끼운다 (같은 콜백으로 다시 요청하면 기존 요청이 대체됨).
     * 더 촘촘하게 바꾸는 건 즉시, 느슨하게 바꾸는 건 직전 변경 후 5초가 지나야 (경계에서 왔다 갔다 방지).
     */
    @SuppressLint("MissingPermission")
    private fun applyPlan(p: LocPlan) {
        if (tracking && p == plan) return
        val cur = plan
        val now = SystemClock.elapsedRealtime()
        if (tracking && cur != null && p.intervalMs >= cur.intervalMs && p.power == cur.power &&
            now - lastPlanAt < PLAN_DWELL_MS
        ) return
        if (!Locator.hasPermission(this)) return
        val priority = when (p.power) {
            LocPower.HIGH -> Priority.PRIORITY_HIGH_ACCURACY
            LocPower.BALANCED -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
            LocPower.PASSIVE -> Priority.PRIORITY_PASSIVE
        }
        val request = LocationRequest.Builder(priority, p.intervalMs)
            // 다른 앱(운동 기록 등)이 GPS 를 1초마다 받아도 우리 앱까지 1초마다 깨지 않게: 최소 간격 = 계획 간격
            .setMinUpdateIntervalMillis(p.intervalMs)
            .setMaxUpdateDelayMillis(p.maxDelayMs)
            .setMinUpdateDistanceMeters(p.minDistanceM)
            .build()
        runCatching { fused.requestLocationUpdates(request, callback, Looper.getMainLooper()) }
            .onSuccess {
                tracking = true
                plan = p
                lastPlanAt = now
                // 저전력 위치로 쉬는 중(도착 뒤 제외)이면 GPS 확인을 예약, 아니면 취소
                val st = Navigator.state.value
                // (기차·고속버스 안·역에서 기다리는 중 저전력으로 쉬는 건 일부러 — GPS 확인을 하지 않는다)
                if (p.power == LocPower.BALANCED && !st.arrived && !st.onSubway && !st.onLongRide && !st.waitingLongRide) {
                    probeWake.schedule(now + PROBE_EVERY_MS)
                } else {
                    probeWake.cancel()
                }
            }
            .onFailure { Log.w(MapApp.TAG, "안내 위치 요청 실패", it) }
    }

    /**
     * GPS 로 지금 위치를 한 번만 (최대 30초). 걷고 있으면 GPS 속도로 '움직임'이 되어 다시 GPS 안내로 돌아간다.
     * 실내라 못 잡으면 그냥 끝 (몇 분 뒤 다시).
     */
    @SuppressLint("MissingPermission")
    private fun probeGps() {
        val p = plan ?: return
        if (!tracking || p.power != LocPower.BALANCED || !Locator.hasPermission(this)) return
        probeWake.schedule(SystemClock.elapsedRealtime() + PROBE_EVERY_MS)
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setDurationMillis(PROBE_TIMEOUT_MS)
            .setMaxUpdateAgeMillis(5_000)
            .build()
        runCatching {
            fused.getCurrentLocation(request, CancellationTokenSource().token)
                .addOnSuccessListener { loc ->
                    if (loc != null && running && tracking) callback.onLocationResult(LocationResult.create(listOf(loc)))
                }
        }.onFailure { Log.w(MapApp.TAG, "GPS 확인 실패", it) }
    }

    private fun stopTracking() {
        probeWake.cancel()
        if (!tracking) return
        tracking = false
        plan = null
        fused.removeLocationUpdates(callback)
    }

    // ───────── 상태 변화 ─────────

    private fun onState(s: NavState) {
        if (!foreground) return
        val active = RouteRepository.activeRoute
        if (active == null || !active.startNavigation || s.routeVersion != RouteRepository.activeRouteVersion) {
            if (active == null || !active.startNavigation) shutdown()
            return
        }
        if (active.tripId != tripId) {
            // 새 길찾기: 재탐색 횟수·간격·시작 시각을 새로 센다 (재탐색으로 바뀐 경로는 같은 여정이라 이어감)
            tripId = active.tripId
            rerouteCount = 0
            reroutesWithoutMatch = 0
            lastRerouteAt = 0L
            startedAt = SystemClock.elapsedRealtime()
            lostClock.reset()
        }
        updateNotification(active, s)
        if (s.matched && !s.offRoute && !s.passedStop && !s.missedRide) reroutesWithoutMatch = 0
        trackLost(s)
        if (lostClock.shouldGiveUp(s, SystemClock.elapsedRealtime())) {
            // 경로를 벗어난 채 30분 — 잊고 둔 안내가 배터리를 다 쓰지 않게 스스로 끝낸다
            notifyGaveUp(active)
            finishRoute()
            return
        }
        if (s.arrived) {
            // 도착: 진행 중이던 재탐색은 버리고, 위치는 저전력으로 (2분 뒤엔 추가 전력 0), 5분 뒤 또는 300m 넘게 떠나면 끝
            if (rerouteJob?.isActive == true) rerouteJob?.cancel()
            if (s.rerouting) Navigator.setRerouting(false)
            if (s.finished) {
                finishRoute()
                return
            }
            if (arrivedVersion != s.routeVersion) {
                arrivedVersion = s.routeVersion
                arrivedAt = SystemClock.elapsedRealtime()
                // 2분 뒤 저전력 → 얻어 쓰기로 전환
                passiveWake.schedule(arrivedAt + LocationPolicy.AFTER_ARRIVAL_ACTIVE_MS + 1_000)
                // 위치가 안 들어와도(얻어 쓰기라 서 있으면 거의 안 옴) 5분 뒤엔 끝낸다
                finishVersion = s.routeVersion
                finishWake.schedule(arrivedAt + NavEngine.FINISH_AFTER_MS)
            }
            applyPlan(planFor(active, s))
            return
        }
        applyPlan(planFor(active, s))
        // 도보·자전거: 경로를 벗어나면 다시 찾기. 대중교통: 정류장을 지나쳤거나 환승 도보 중 벗어나거나 기차를 놓치면 다시 찾기
        if ((s.offRoute || s.passedStop || s.missedRide) && !s.rerouting) maybeReroute(active, s)
    }

    /** 경로 밖에 있는 시간을 잰다. 위치가 안 들어와도 다음 판단 시각(10분·30분)에 다시 보도록 알람을 건다 */
    private fun trackLost(s: NavState) {
        val now = SystemClock.elapsedRealtime()
        lostClock.update(s, now)
        lostWake.schedule(lostClock.nextDeadline(now))
    }

    private fun finishIfStillArrived() {
        if (Navigator.state.value.arrived && RouteRepository.activeRouteVersion == finishVersion) finishRoute()
    }

    /** 부팅 후 시각 기준의 (정확하지 않은) 깨우기 알람 하나. 같은 시각이면 다시 걸지 않는다 */
    private inner class Wake(private val tag: String, private val action: () -> Unit) : AlarmManager.OnAlarmListener {
        private var at: Long? = null

        override fun onAlarm() {
            at = null
            if (running) action()
        }

        fun schedule(atElapsedMs: Long?) {
            if (atElapsedMs == at) return
            cancel()
            if (atElapsedMs == null) return
            val alarms = getSystemService(AlarmManager::class.java) ?: return
            runCatching {
                alarms.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, atElapsedMs, tag, this, mainHandler)
                at = atElapsedMs
            }.onFailure { Log.w(MapApp.TAG, "알람 설정 실패 ($tag)", it) }
        }

        fun cancel() {
            if (at == null) return
            at = null
            runCatching { getSystemService(AlarmManager::class.java)?.cancel(this) }
        }
    }

    private fun sinceLost(): Long = lostClock.sinceLost(SystemClock.elapsedRealtime())

    /** 안내를 스스로 끝냈다는 알림 (소리 없이) */
    private fun notifyGaveUp(active: ActiveRoute) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel()
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_navigation)
            .setContentTitle(getString(R.string.nav_gave_up_title, active.destinationName))
            .setContentText(getString(R.string.nav_gave_up_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.nav_gave_up_text)))
            .setContentIntent(openMapIntent())
            .setAutoCancel(true)
            .setSilent(true)
            .build()
        runCatching { getSystemService(NotificationManager::class.java)?.notify(GAVE_UP_NOTIFICATION_ID, n) }
    }

    /** 도착 후 안내를 스스로 끝낸다 (지도 화면이 열려 있으면 다음에 돌아올 때 경로가 지워져 있다) */
    private fun finishRoute() {
        if (RouteRepository.activeRoute == null) return
        RouteRepository.activeRoute = null
        Navigator.sync()
        shutdown()
    }

    /**
     * 도보·자전거 경로를 벗어나면 지금 위치에서 다시 찾는다 (카카오맵 재탐색처럼).
     * 30초에 한 번까지, 새 경로에 못 올라서고 또 벗어나면 간격을 두 배씩 — 무료 사용량·배터리 보호.
     * 출발점은 10초 안에 잡힌, 정확도 30m 이내 위치만 쓴다 (튄 위치에서 다시 찾지 않게).
     */
    private fun maybeReroute(active: ActiveRoute, s: NavState) {
        val now = SystemClock.elapsedRealtime()
        val interval = REROUTE_INTERVAL_MS shl reroutesWithoutMatch.coerceAtMost(3)
        if (rerouteJob?.isActive == true || now - lastRerouteAt < interval || rerouteCount >= MAX_REROUTES) return
        // 역에서 기차를 놓쳤으면 역 근처면 충분 (역 안이라 GPS 가 거침). 기차·버스가 내릴 곳을 지나쳤으면 다음 역·정류장에 서야
        // 내릴 수 있으니 멈출 때까지 기다렸다가(시속 11km 아래) 그 자리에서 — 달리는 버스 안에서 찾으면 내리기도 전에 출발지가
        // 뒤처진다. 기차는 기지국 위치(1.5km)여도 다음 열차·버스를 찾기엔 충분
        if (s.passedStop && (motion.speedMps ?: 0f) > STOPPED_MPS) return
        val maxAcc = when {
            s.missedRide -> 400f
            s.onLongRide -> 1_500f
            else -> 30f
        }
        val fix = RouteRepository.freshFix(10_000)?.takeIf { !it.accuracyM.isNaN() && it.accuracyM <= maxAcc } ?: return
        val from = fix.coord
        lastRerouteAt = now
        reroutesWithoutMatch++
        rerouteCount++
        Navigator.setRerouting(true)
        rerouteJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    when (active.mode) {
                        // 고른 옵션(자전거도로 우선·최단 거리 …) 그대로 다시 찾는다
                        TravelMode.BIKE, TravelMode.WALK -> RoutePresentation.activeFromSimple(
                            KakaoApi.simple(active.mode, from, active.destination, active.destinationName, active.simple?.option),
                            active.place, from, navigate = true,
                        )
                        // 기차·고속버스를 아직 안 탔으면 그 역까지만 다시 찾고, 못 타게 됐으면 다음 편까지 처음부터.
                        // 그 밖엔 가장 빨리 도착하는 대중교통, 대중교통 길이 없다는 답(남은 거리가 짧음 등)일 때만 걸어서 —
                        // 끊김·서버 오류는 실패로 두어 간격을 두고 다시 대중교통으로 찾는다 (잠깐 끊겼다고 몇 km 를 걷게 하지 않게)
                        TravelMode.TRANSIT -> rerouteTransit(from, active, s)
                    }.copy(tripId = active.tripId)
                }
            }
            if (RouteRepository.activeRoute !== active) return@launch   // 그 사이 경로가 바뀜
            if (Navigator.state.value.arrived) {                         // 그 사이 도착함
                Navigator.setRerouting(false)
                return@launch
            }
            result.onSuccess { next ->
                RouteRepository.activeRoute = next
                Navigator.sync()
                Navigator.vibrate(this@NavigationService, NavEvent.REROUTED)
            }.onFailure {
                Log.w(MapApp.TAG, "재탐색 실패", it)
                ErrorLog.record("재탐색", it)
                Navigator.setRerouting(false)
                // 네트워크 끊김 등 — 아직 벗어나 있으면 간격(30초·1분·2분…)을 두고 다시 시도
                retryJob?.cancel()
                retryJob = scope.launch {
                    delay(REROUTE_INTERVAL_MS shl reroutesWithoutMatch.coerceIn(0, 3))
                    val st = Navigator.state.value
                    if ((st.offRoute || st.passedStop || st.missedRide) && RouteRepository.activeRoute === active) onState(st)
                }
            }
        }
    }

    /**
     * 대중교통 다시 찾기. 아직 타지 않은 기차·고속버스가 있으면
     *  · 그 역까지 지금 위치에서 다시 찾고(카카오 대중교통·도보) 나머지(기차·역에서 목적지까지)는 그대로 — 시간 안에 닿으면
     *  · 못 닿거나 이미 떠났으면 기차·고속·시외버스까지 처음부터 다시 찾아 가장 빨리 도착하는 것 (다음 편)
     * 기차·버스가 없는 시내 경로는 가장 빨리 도착하는 대중교통, 대중교통 길이 없다는 답이면 걸어서.
     */
    private suspend fun rerouteTransit(from: Coord, active: ActiveRoute, s: NavState): ActiveRoute {
        val t = active.transit
        val now = System.currentTimeMillis()
        // 정류장(역)까지 걷는 길도 실제 길로 — 지도에 그려지고 남은 거리도 길 따라 (못 받으면 직선, 3초까지만 기다림)
        suspend fun show(r: TransitRoute) = RoutePresentation.activeFromTransit(
            WalkPaths.enrich(r, timeoutMs = 3_000), active.place, from, navigate = true,
        )
        if (t != null && !s.missedRide) {
            val k = IntercitySplice.nextScheduled(t, s.stepIndex.coerceAtLeast(0), s.onRide)
            if (k != null) {
                val ride = t.steps[k]
                val access = accessTo(from, ride.points.first(), ride.boardingStop ?: "역")
                if (access != null && IntercitySplice.canMake(ride, access.second, now)) {
                    return show(IntercitySplice.splice(t, k, access.first, now))
                }
            }
        }
        if (s.missedRide || t?.steps?.any { it.type.scheduled } == true) {
            bestWithIntercity(from, active, now)?.let { return show(it) }
        }
        return transitOrNull(from, active)?.let { show(it) } ?: RoutePresentation.activeFromSimple(
            KakaoApi.walk(from, active.destination, active.destinationName), active.place, from, navigate = true,
        )
    }

    /** 지금 위치 → 역: (구간들, 걸리는 초). 700m 안은 걸어서, 대중교통 길이 없으면 2.5km 안만 걸어서, 그 밖엔 null */
    private fun accessTo(from: Coord, station: Coord, name: String): kotlin.Pair<List<TransitStep>, Int>? {
        val d = Geo.distanceM(from, station)
        val walkSec = IntercityItineraries.walkSec(from, station)
        val walk = IntercityItineraries.walk(from, station, "${name}까지 도보", walkSec) to walkSec
        if (d <= IntercityItineraries.WALK_ONLY_M) return walk
        val routes = try {
            KakaoApi.transit(from, station, name)
        } catch (e: RouteStatusException) {
            return if (d <= 2_500) walk else null
        }
        val best = TransitOptions.fastestIndex(routes)?.let(routes::get) ?: return if (d <= 2_500) walk else null
        return best.steps to best.totalTimeSec
    }

    /** 시내 대중교통 + (먼 곳이면) 기차·고속·시외버스 중 가장 빨리 도착하는 것 */
    private suspend fun bestWithIntercity(from: Coord, active: ActiveRoute, now: Long): TransitRoute? {
        val city = try {
            KakaoApi.transit(from, active.destination, active.destinationName)
        } catch (e: RouteStatusException) {
            emptyList()
        }
        val far = Geo.distanceM(from, active.destination) >= IntercityPlanner.MIN_DISTANCE_M
        val longRides = if (far && TagoApi.isConfigured) {
            // 따로 돌려 제한 시간에 바로 넘어간다 (막힌 연결이 끝나기를 기다리지 않게)
            val job = scope.async(Dispatchers.IO) {
                runCatching { IntercityPlanner(LiveIntercitySource(applicationContext)).plan(from, active.place, now) }
            }
            val got = try {
                withTimeoutOrNull(INTERCITY_TIMEOUT_MS) { job.await() }
            } finally {
                job.cancel()   // 시간 초과·재탐색 취소(도착 등) 때 따로 돌던 찾기도 멈춘다 (끝났으면 아무 일 없음)
            }
            got?.exceptionOrNull()?.let { if (it is CancellationException) throw it else ErrorLog.record("재탐색·기차·버스", it) }
            got?.getOrNull()?.routes.orEmpty()
        } else emptyList()
        val all = city + longRides
        return TransitOptions.fastestIndex(all)?.let(all::get)
    }

    /** 재탐색용 대중교통 경로: 가장 빨리 도착하는 것. 길이 없다는 답이면 null, 네트워크·서버 오류는 그대로 던진다 */
    private fun transitOrNull(from: Coord, active: ActiveRoute): TransitRoute? {
        val routes = try {
            KakaoApi.transit(from, active.destination, active.destinationName)
        } catch (e: RouteStatusException) {
            return null
        }
        return TransitOptions.fastestIndex(routes)?.let(routes::get)
    }

    private fun planFor(active: ActiveRoute, s: NavState): LocPlan {
        val now = SystemClock.elapsedRealtime()
        if (now - batteryCheckedAt > BATTERY_CHECK_MS || batteryCheckedAt == 0L) {
            lowBattery = BatteryInfo.isLow(this)
            batteryCheckedAt = now
        }
        val plan = LocationPolicy.navigation(
            s,
            bike = active.mode == TravelMode.BIKE,
            speedMps = motion.speedMps,
            sinceArrivalMs = now - arrivedAt,
            sinceStartMs = now - startedAt,
            lowBattery = lowBattery,
            sinceLostMs = sinceLost(),
        )
        // 재탐색을 다 쓴 뒤에도 벗어나 있으면 1초 GPS 를 계속 쓰지 않는다 (경로로 돌아오면 다시 촘촘히)
        val exhausted = (s.offRoute || s.passedStop || s.missedRide) && rerouteCount >= MAX_REROUTES
        return if (exhausted && plan.power == LocPower.HIGH && plan.intervalMs < 3_000) plan.copy(intervalMs = 3_000) else plan
    }

    // ───────── 알림 · 진행 중 활동 ─────────

    private fun enterForeground(): Boolean {
        if (foreground) return true
        // 알림을 꾸미다 실패해도 포그라운드 진입은 반드시 하도록 단순한 알림으로 대신한다
        val notification = runCatching { buildNotification(RouteRepository.activeRoute, Navigator.state.value) }
            .onFailure { Log.w(MapApp.TAG, "안내 알림 구성 실패 — 기본 알림 사용", it) }
            .getOrElse { plainNotification() }
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            foreground = true
            true
        } catch (e: Exception) {
            Log.w(MapApp.TAG, "안내 서비스를 시작하지 못함", e)
            false
        }
    }

    private fun plainNotification(): Notification {
        ensureChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_navigation)
            .setContentTitle(getString(R.string.map_name))
            .setContentText(getString(R.string.nav_channel))
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun openMapIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        // 상세·검색 화면이 위에 있어도 그 위에 지도를 하나 더 쌓지 않고 기존 지도로 돌아간다
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun buildNotification(active: ActiveRoute?, s: NavState): Notification {
        ensureChannel()
        val open = openMapIntent()
        val end = PendingIntent.getService(
            this, 1,
            Intent(this, NavigationService::class.java).setAction(ACTION_END),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = statusText(active, s)
        shownText = text
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_navigation)
            .setContentTitle(active?.let { "→ ${it.destinationName}" } ?: getString(R.string.map_name))
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(R.drawable.ic_close, getString(R.string.end_route), end)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            // Wear OS 7·안드로이드 16+ 의 '실시간 업데이트'(Live Update)로도 — 진행 중 활동 대신 이것을 보여 주는 기기용
            .setRequestPromotedOngoing(true)
        shortText(s)?.let { b.setShortCriticalText(it) }
        builder = b
        ongoing = OngoingActivity.Builder(applicationContext, NOTIFICATION_ID, b)
            .setStaticIcon(R.drawable.ic_navigation)
            .setTouchIntent(open)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setTitle(getString(R.string.map_name))
            .setStatus(statusOf(text))
            .build()
            .also { it.apply(applicationContext) }
        return b.build()
    }

    /** 문구가 바뀌었을 때만, 너무 잦지 않게(5초) 알림을 고친다 — 배터리 절약 */
    private fun updateNotification(active: ActiveRoute, s: NavState) {
        val text = statusText(active, s)
        if (text == shownText) return
        val now = SystemClock.elapsedRealtime()
        val important = s.arrived || s.offRoute || s.rerouting
        if (!important && now - lastNotifyAt < 5_000) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        lastNotifyAt = now
        shownText = text
        val b = builder ?: return
        b.setContentTitle("→ ${active.destinationName}").setContentText(text)
        shortText(s)?.let { b.setShortCriticalText(it) }
        runCatching { ongoing?.update(applicationContext, statusOf(text)) }
    }

    /** 실시간 업데이트 칩에 넣을 아주 짧은 글 (7자 안쪽): 남은 거리 또는 '도착' */
    private fun shortText(s: NavState): String? = when {
        s.arrived -> "도착"
        else -> s.remainingM?.let { Format.distance(it) }
    }

    /** 진행 중 활동의 상태 문구. '#' 은 템플릿 기호라 전각 문자로 바꿔 넣는다 */
    private fun statusOf(text: String): Status = Status.Builder().addTemplate(text.replace('#', '＃')).build()

    private fun statusText(active: ActiveRoute?, s: NavState): String {
        if (active == null) return getString(R.string.map_name)
        val guide = s.guide?.lineSequence()?.firstOrNull()?.ifBlank { null } ?: active.summary
        val left = s.remainingM?.takeIf { !s.arrived }?.let { " · 남은 ${Format.distance(it)}" } ?: ""
        return guide + left
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.nav_channel), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                enableVibration(false)
            }
        )
    }

    private fun shutdown() {
        stopTracking()
        rerouteJob?.cancel()
        passiveWake.cancel()
        finishWake.cancel()
        signalJob?.cancel()
        retryJob?.cancel()
        lostWake.cancel()
        lostClock.reset()
        if (Navigator.state.value.signalLost) Navigator.setSignalLost(false)
        // 안내 중이라 미뤄 둔 자동 업데이트가 있으면 이제 설치 예약 (조건이 맞으면 곧바로)
        UpdateInstallWorker.enqueueSoon(this)
        if (foreground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        running = false
        // 그 사이 들어온 시작 요청이 있으면 멈추지 않는다 (그 요청이 다시 판단)
        stopSelfResult(lastStartId)
    }

    companion object {
        private const val CHANNEL_ID = "navigation"
        private const val NOTIFICATION_ID = 42
        private const val GAVE_UP_NOTIFICATION_ID = 43
        private const val ACTION_END = "com.mywatch.kakaomap.END_NAVIGATION"
        private const val REROUTE_INTERVAL_MS = 30_000L
        private const val PLAN_DWELL_MS = 5_000L
        private const val SIGNAL_LOST_AFTER_MS = 10_000L
        private const val MAX_REROUTES = 10
        private const val BATTERY_CHECK_MS = 60_000L
        private const val PROBE_EVERY_MS = 4 * 60_000L
        private const val PROBE_TIMEOUT_MS = 30_000L
        /** 다음 기차·버스를 찾을 때 시간표를 이만큼까지 기다린다 */
        private const val INTERCITY_TIMEOUT_MS = 20_000L
        /** 내릴 곳을 지나친 기차·버스가 다음 역·정류장에 섰다고 볼 속도 */
        private const val STOPPED_MPS = 3f

        /** 서비스가 돌고 있는지 (메인 스레드에서 쓰고, 업데이트 작업이 다른 스레드에서 읽음) */
        @Volatile
        var running = false
            private set

        /** 화면이 떠 있을 때(앱이 앞에 있을 때) 불러야 한다 — 백그라운드 시작은 안드로이드가 막는다 */
        fun start(context: Context) {
            val active = RouteRepository.activeRoute ?: return
            if (!active.startNavigation || !Locator.hasPrecise(context)) return
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, NavigationService::class.java))
            }.onFailure { Log.w(MapApp.TAG, "안내 서비스 시작 실패", it) }
        }

        fun stop(context: Context) {
            if (!running) return
            context.stopService(Intent(context, NavigationService::class.java))
        }
    }
}
