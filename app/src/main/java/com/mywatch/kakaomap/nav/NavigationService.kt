package com.mywatch.kakaomap.nav

import android.Manifest
import android.annotation.SuppressLint
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
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.mywatch.kakaomap.MainActivity
import com.mywatch.kakaomap.MapApp
import com.mywatch.kakaomap.R
import com.mywatch.kakaomap.data.ActiveRoute
import com.mywatch.kakaomap.data.RoutePresentation
import com.mywatch.kakaomap.data.RouteRepository
import com.mywatch.kakaomap.location.Locator
import com.mywatch.kakaomap.location.toFix
import com.mywatch.kakaomap.model.Format
import com.mywatch.kakaomap.model.TravelMode
import com.mywatch.kakaomap.net.KakaoApi
import com.mywatch.kakaomap.update.UpdateInstallWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private var speedMps: Float? = null
    private var still = false
    private var lastSpeedFix: android.location.Location? = null
    private var lastPlanAt = 0L
    private var passiveTimerJob: Job? = null
    private var finishJob: Job? = null
    /** 경로를 못 받은(안내 시작 후 아직 경로 위가 아닌) 시각 — 1초 간격을 무한정 쓰지 않게 */
    private var startedAt = 0L
    private var signalJob: Job? = null

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            // 모아서 받은 경우(배칭) 순서대로 모두 처리해야 갈림길·하차를 놓치지 않는다
            result.locations.forEach { loc ->
                updateSpeed(loc)
                Navigator.onFix(this@NavigationService, loc.toFix())
            }
            onSignal(true)
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
            }
            .onFailure { Log.w(MapApp.TAG, "안내 위치 요청 실패", it) }
    }

    private fun stopTracking() {
        if (!tracking) return
        tracking = false
        plan = null
        fused.removeLocationUpdates(callback)
    }

    /**
     * 최근 속도 (GPS 가 준 값, 없으면 두 위치 사이로 계산) — 멈춰 있는지 판단용.
     * 멈춤 판단엔 여유를 둔다: 0.4m/s 아래로 내려가면 '멈춤', 0.9m/s 넘어야 '움직임'으로 되돌림.
     */
    private fun updateSpeed(loc: android.location.Location) {
        val prev = lastSpeedFix
        val raw = when {
            loc.hasSpeed() -> loc.speed
            prev != null && loc.elapsedRealtimeNanos > prev.elapsedRealtimeNanos -> {
                val dt = (loc.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) / 1e9f
                if (dt in 0.5f..60f) loc.distanceTo(prev) / dt else null
            }
            else -> null
        }
        lastSpeedFix = loc
        if (raw == null) return
        if (raw < LocationPolicy.STILL_MPS) still = true else if (raw > MOVING_MPS) still = false
        speedMps = if (still) 0f else raw
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
        }
        updateNotification(active, s)
        if (s.matched && !s.offRoute && !s.passedStop) reroutesWithoutMatch = 0
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
                passiveTimerJob?.cancel()
                passiveTimerJob = scope.launch {
                    delay(LocationPolicy.AFTER_ARRIVAL_ACTIVE_MS + 1_000)
                    onState(Navigator.state.value)   // 저전력 → 얻어 쓰기로 전환
                }
                finishJob?.cancel()
                finishJob = scope.launch {
                    // 위치가 안 들어와도(얻어 쓰기라 서 있으면 거의 안 옴) 5분 뒤엔 끝낸다
                    delay(NavEngine.FINISH_AFTER_MS)
                    if (Navigator.state.value.arrived && RouteRepository.activeRouteVersion == s.routeVersion) finishRoute()
                }
            }
            applyPlan(planFor(active, s))
            return
        }
        applyPlan(planFor(active, s))
        // 도보·자전거: 경로를 벗어나면 다시 찾기. 대중교통: 정류장을 지나쳤거나 환승 도보 중 벗어나면 대중교통으로 다시 찾기
        if ((s.offRoute || s.passedStop) && !s.rerouting) maybeReroute(active)
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
    private fun maybeReroute(active: ActiveRoute) {
        val now = SystemClock.elapsedRealtime()
        val interval = REROUTE_INTERVAL_MS shl reroutesWithoutMatch.coerceAtMost(3)
        if (rerouteJob?.isActive == true || now - lastRerouteAt < interval || rerouteCount >= MAX_REROUTES) return
        val fix = RouteRepository.freshFix(10_000)?.takeIf { !it.accuracyM.isNaN() && it.accuracyM <= 30f } ?: return
        val from = fix.coord
        lastRerouteAt = now
        reroutesWithoutMatch++
        rerouteCount++
        Navigator.setRerouting(true)
        rerouteJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    when (active.mode) {
                        TravelMode.BIKE -> RoutePresentation.activeFromSimple(
                            KakaoApi.bike(from, active.destination, active.destinationName), active.place, from, navigate = true,
                        )
                        TravelMode.WALK -> RoutePresentation.activeFromSimple(
                            KakaoApi.walk(from, active.destination, active.destinationName), active.place, from, navigate = true,
                        )
                        // 가장 빨리 도착하는 대중교통 경로 (목록은 소요 시간 순). 없으면(남은 거리가 짧음 등) 걸어서
                        TravelMode.TRANSIT -> runCatching { KakaoApi.transit(from, active.destination, active.destinationName) }
                            .getOrNull()?.firstOrNull()
                            ?.let { RoutePresentation.activeFromTransit(it, active.place, from, navigate = true) }
                            ?: RoutePresentation.activeFromSimple(
                                KakaoApi.walk(from, active.destination, active.destinationName), active.place, from, navigate = true,
                            )
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
                Navigator.setRerouting(false)
                // 네트워크 끊김 등 — 아직 벗어나 있으면 간격(30초·1분·2분…)을 두고 다시 시도
                retryJob?.cancel()
                retryJob = scope.launch {
                    delay(REROUTE_INTERVAL_MS shl reroutesWithoutMatch.coerceIn(0, 3))
                    val st = Navigator.state.value
                    if ((st.offRoute || st.passedStop) && RouteRepository.activeRoute === active) onState(st)
                }
            }
        }
    }

    private fun planFor(active: ActiveRoute, s: NavState): LocPlan {
        val now = SystemClock.elapsedRealtime()
        if (now - batteryCheckedAt > BATTERY_CHECK_MS || batteryCheckedAt == 0L) {
            lowBattery = BatteryInfo.isLow(this)
            batteryCheckedAt = now
        }
        // 재탐색을 다 쓴 뒤에도 벗어나 있으면 1초 GPS 를 계속 쓰지 않는다 (경로로 돌아오면 다시 촘촘히)
        if ((s.offRoute || s.passedStop) && rerouteCount >= MAX_REROUTES) return LocPlan(LocPower.HIGH, 3_000)
        return LocationPolicy.navigation(
            s,
            bike = active.mode == TravelMode.BIKE,
            speedMps = speedMps,
            sinceArrivalMs = now - arrivedAt,
            sinceStartMs = now - startedAt,
            lowBattery = lowBattery,
        )
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
            .setContentTitle(getString(R.string.app_name))
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
            .setContentTitle(active?.let { "→ ${it.destinationName}" } ?: getString(R.string.app_name))
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(R.drawable.ic_close, getString(R.string.end_route), end)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        builder = b
        ongoing = OngoingActivity.Builder(applicationContext, NOTIFICATION_ID, b)
            .setStaticIcon(R.drawable.ic_navigation)
            .setTouchIntent(open)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setTitle(getString(R.string.app_name))
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
        runCatching { ongoing?.update(applicationContext, statusOf(text)) }
    }

    /** 진행 중 활동의 상태 문구. '#' 은 템플릿 기호라 전각 문자로 바꿔 넣는다 */
    private fun statusOf(text: String): Status = Status.Builder().addTemplate(text.replace('#', '＃')).build()

    private fun statusText(active: ActiveRoute?, s: NavState): String {
        if (active == null) return getString(R.string.app_name)
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
        passiveTimerJob?.cancel()
        finishJob?.cancel()
        signalJob?.cancel()
        retryJob?.cancel()
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
        private const val ACTION_END = "com.mywatch.kakaomap.END_NAVIGATION"
        private const val REROUTE_INTERVAL_MS = 30_000L
        private const val PLAN_DWELL_MS = 5_000L
        private const val SIGNAL_LOST_AFTER_MS = 10_000L
        private const val MAX_REROUTES = 10
        private const val BATTERY_CHECK_MS = 60_000L
        private const val MOVING_MPS = 0.9f

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
