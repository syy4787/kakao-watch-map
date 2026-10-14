package com.mywatch.kakaomap

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.ambient.AmbientLifecycleObserver
import androidx.wear.widget.SwipeDismissFrameLayout
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.kakao.vectormap.KakaoMap
import com.kakao.vectormap.KakaoMapReadyCallback
import com.kakao.vectormap.KakaoMapSdk
import com.kakao.vectormap.LatLng
import com.kakao.vectormap.MapAuthException
import com.kakao.vectormap.MapGravity
import com.kakao.vectormap.MapLifeCycleCallback
import com.kakao.vectormap.MapView
import com.kakao.vectormap.camera.CameraAnimation
import com.kakao.vectormap.camera.CameraUpdateFactory
import com.kakao.vectormap.label.Label
import com.kakao.vectormap.label.LabelOptions
import com.kakao.vectormap.label.LabelStyle
import com.kakao.vectormap.label.LabelStyles
import com.kakao.vectormap.label.TransformMethod
import com.kakao.vectormap.route.RouteLine
import com.kakao.vectormap.route.RouteLineOptions
import com.kakao.vectormap.route.RouteLinePattern
import com.kakao.vectormap.route.RouteLineSegment
import com.kakao.vectormap.route.RouteLineStyle
import com.mywatch.kakaomap.data.ActiveRoute
import com.mywatch.kakaomap.data.ErrorLog
import com.mywatch.kakaomap.data.RouteRepository
import com.mywatch.kakaomap.location.HeadingMath
import com.mywatch.kakaomap.location.HeadingSensor
import com.mywatch.kakaomap.location.Locator
import com.mywatch.kakaomap.location.PermissionHelp
import com.mywatch.kakaomap.location.toFix
import com.mywatch.kakaomap.model.Coord
import com.mywatch.kakaomap.model.Action
import com.mywatch.kakaomap.model.Format
import com.mywatch.kakaomap.model.TravelMode
import com.mywatch.kakaomap.nav.LocPlan
import com.mywatch.kakaomap.nav.LocPower
import com.mywatch.kakaomap.nav.LocationPolicy
import com.mywatch.kakaomap.nav.NavIcon
import com.mywatch.kakaomap.nav.NavState
import com.mywatch.kakaomap.nav.NavigationService
import com.mywatch.kakaomap.nav.Navigator
import com.mywatch.kakaomap.ui.AmbientCover
import com.mywatch.kakaomap.ui.AmbientRouteView
import com.mywatch.kakaomap.ui.MapBitmaps
import com.mywatch.kakaomap.ui.NavOverlayText
import com.mywatch.kakaomap.ui.RouteDetailActivity
import com.mywatch.kakaomap.ui.SearchActivity
import com.mywatch.kakaomap.update.UpdateActivity
import com.mywatch.kakaomap.update.UpdateInstallWorker
import com.mywatch.kakaomap.update.UpdateManager
import com.mywatch.kakaomap.update.UpdateWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.hypot

class MainActivity : ComponentActivity() {

    private enum class LocMode { NONE, FOLLOW, COMPASS }

    private lateinit var swipeRoot: SwipeDismissFrameLayout
    private lateinit var mapView: MapView
    private lateinit var statusText: TextView
    private lateinit var guideBox: View
    private lateinit var guideIcon: ImageView
    private lateinit var guideDist: TextView
    private lateinit var guideMain: TextView
    private lateinit var guideSub: TextView
    private lateinit var updatePill: TextView
    private lateinit var routePill: TextView
    private lateinit var ambientRoot: View
    private lateinit var ambientMap: AmbientRouteView
    private lateinit var btnSearch: ImageButton
    private lateinit var btnZoomIn: ImageButton
    private lateinit var btnMyLocation: ImageButton
    private lateinit var btnZoomOut: ImageButton
    private lateinit var btnEnd: ImageButton
    private lateinit var controls: List<View>
    /** 안내 배너를 한 줄로 접어 둘지 (배너를 톡 해서 바꾸고, 다음에도 그대로) */
    private var guideCompact = false
    /** 배너 전체 최대 폭(px) — 오른쪽 글자 칸은 여기서 여백·왼쪽 칸을 뺀 만큼, 넘치면 글자를 조금 줄인다 */
    private var guideMaxPx = 0
    /** 안내 중 지도를 손으로 움직여 '내 위치 따라가기'가 풀렸는지 (8초 뒤 다시 따라감) */
    private var followLostByTouch = false
    private var modeBeforeTouch = LocMode.FOLLOW
    private val refollowRunnable = Runnable {
        val active = RouteRepository.activeRoute
        if (followLostByTouch && locMode == LocMode.NONE && active != null && active.startNavigation && !inAmbient) {
            followLostByTouch = false
            setLocMode(modeBeforeTouch)
        }
    }

    private var kakaoMap: KakaoMap? = null
    private var mapStarted = false
    private var mapFailed = false
    private var controlsShown = false
    private var inAmbient = false
    /**
     * onResume ~ onPause 사이인지. lifecycle.currentState 는 onResume() 이 '끝난 뒤'에야 RESUMED 가 되므로
     * onResume 안에서 위치 요청을 판단하려면 직접 표시해 둔 값을 써야 한다 (실기기에서 복귀 후 위치가 멈추던 문제)
     */
    private var screenResumed = false
    private var ambientRouteVersion = -1
    private var lastAmbientKey: Any? = null
    private var lastAmbientAt = 0L

    // 내 위치
    private var locMode = LocMode.NONE
    private var myLabel: Label? = null
    private var myLabelHasCone = false
    private var heading: Float? = null
    private var lastHeadingApplied = Float.NaN
    /** 부채꼴에 마지막으로 준 회전값(라디안, −π~π) */
    private var lastLabelRad = 0f
    private lateinit var headingSensor: HeadingSensor
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var updatesRunning = false
    /** 화면 쪽에서 지금 걸어 둔 위치 요청 */
    private var activePlan: LocPlan? = null

    // 경로
    private var drawnRouteVersion = -1
    /** '정확한 위치'를 이미 물어본 경로 (같은 경로로 권한 창을 두 번 띄우지 않게) */
    private var preciseAskedForVersion = -1
    private var routeLine: RouteLine? = null
    /** 걷는 길의 점 무늬 (점 사이 약 10dp) */
    private val walkPattern: RouteLinePattern by lazy {
        RouteLinePattern.from(MapBitmaps.walkDot(this), 10 * resources.displayMetrics.density)
    }
    private var routeLabels = mutableListOf<Label>()

    // 베젤·크라운 줌
    private var rotaryAccumulated = 0f
    private var lastRotaryAt = 0L
    private var pendingZoom = -1
    private var lastZoomRequestAt = 0L

    // 사용자가 지도를 직접 끌었는지 (추적 모드 해제용)
    private var touchDownX = 0f
    private var touchDownY = 0f
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { setControlsVisible(false) }
    private val hideStatusRunnable = Runnable { statusText.visibility = View.GONE }

    /** 위치 권한 창을 띄운 시각과 까닭 — 곧바로 거절이 오면 시스템이 창을 안 띄운 것(두 번 거절해 막힘) */
    private var permissionAskedAt = 0L
    private var permissionByUser = false

    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val blocked = PermissionHelp.deniedWithoutDialog(permissionAskedAt, SystemClock.elapsedRealtime())
        val byUser = permissionByUser
        permissionByUser = false
        if (result.values.any { it }) {
            setLocMode(LocMode.FOLLOW)
            // '안내 시작'을 정확한 위치 권한 때문에 미뤄 뒀다면 이제 시작 (알림 권한 요청도 그 뒤에 — 한 번에 하나만 뜰 수 있음)
            val active = RouteRepository.activeRoute
            if (active != null && active.startNavigation && Locator.hasPrecise(this)) {
                maybeAskNotificationPermission()
                NavigationService.start(this)
            } else if (active != null && active.startNavigation && blocked && byUser) {
                // '정확한 위치'를 두 번 거절해 창이 안 뜸 → 설정에서 바꾸도록
                showStatus(getString(R.string.precise_location_needed), autoHideMs = 4_000)
                PermissionHelp.openAppSettings(this)
            }
        } else if (blocked && byUser) {
            // 두 번 거절해 권한 창이 더는 안 뜸 → 앱 설정(권한) 화면으로
            showStatus(getString(R.string.location_blocked), autoHideMs = 4_000)
            PermissionHelp.openAppSettings(this)
        } else {
            showStatus(getString(R.string.location_permission_denied), autoHideMs = 2500)
        }
    }

    /** 위치 권한 창 띄우기. [byUser] = 사용자가 버튼·안내 시작으로 직접 원한 것 (막혀 있으면 설정 화면을 열어도 됨) */
    private fun requestLocationPermission(byUser: Boolean) {
        permissionAskedAt = SystemClock.elapsedRealtime()
        permissionByUser = byUser
        markAskedPermission()
        permissionRequest.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    /** 안내 알림(워치페이스의 진행 중 아이콘) 표시 권한 — 거부해도 안내는 된다 */
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            // 모아서 받은 위치는 순서대로 모두 처리 (안내 판단을 건너뛰지 않게)
            result.locations.forEach(::onLocation)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if (AppConfig.KEEP_SCREEN_ON) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        swipeRoot = findViewById(R.id.swipe_root)
        mapView = findViewById(R.id.map_view)
        statusText = findViewById(R.id.status_text)
        guideBox = findViewById(R.id.guide)
        guideIcon = findViewById(R.id.guide_icon)
        guideDist = findViewById(R.id.guide_dist)
        guideMain = findViewById(R.id.guide_main)
        guideSub = findViewById(R.id.guide_sub)
        updatePill = findViewById(R.id.update_pill)
        routePill = findViewById(R.id.route_pill)
        ambientRoot = findViewById(R.id.ambient_root)
        ambientMap = findViewById(R.id.ambient_map)
        btnSearch = findViewById(R.id.btn_search)
        btnZoomIn = findViewById(R.id.btn_zoom_in)
        btnMyLocation = findViewById(R.id.btn_my_location)
        btnZoomOut = findViewById(R.id.btn_zoom_out)
        btnEnd = findViewById(R.id.btn_end)
        controls = listOf(btnSearch, btnZoomIn, btnMyLocation, btnZoomOut, btnEnd)
        guideCompact = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_GUIDE_COMPACT, false)

        headingSensor = HeadingSensor(this) { deg -> runOnUiThread { onHeading(deg) } }
        // 손목을 내리면 꺼지는 대신 저전력 화면으로 (길 안내를 계속 볼 수 있게)
        if (AmbientCover.available) lifecycle.addObserver(AmbientLifecycleObserver(this, ambientCallback))

        // 화면 '왼쪽 가장자리'에서 오른쪽으로 쓸면 앱 종료 (나머지 드래그는 지도 이동)
        swipeRoot.addCallback(object : SwipeDismissFrameLayout.Callback() {
            override fun onDismissed(layout: SwipeDismissFrameLayout) {
                layout.visibility = View.GONE
                finish()
            }
        })
        // 옆 버튼(뒤로): 지도가 이 작업의 맨 아래 화면이면 닫지 않고 뒤로 보낸다 — 첫 화면에서 다시 고르면 보던 그대로
        // (지도 엔진을 다시 띄우지 않음). 앱 목록 아이콘이 '갤럭시워치 전용앱'으로 바뀌어 시스템이 더는 저절로 그렇게 하지 않는다
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isTaskRoot) moveTaskToBack(true) else finish()
            }
        })

        swipeRoot.doOnLayout { layoutOverlays() }

        btnSearch.setOnClickListener { startActivity(Intent(this, SearchActivity::class.java)) }
        btnZoomIn.setOnClickListener { zoomBy(+1); bumpControls() }
        btnZoomOut.setOnClickListener { zoomBy(-1); bumpControls() }
        btnMyLocation.setOnClickListener { onMyLocationButton(); bumpControls() }
        btnEnd.setOnClickListener { endRoute() }
        controls.forEach { it.isClickable = false }   // 숨겨진 상태로 시작

        // 새 버전 알림을 누르면 바로 받아 설치 (업데이트 화면에서 버튼을 또 찾지 않게)
        updatePill.setOnClickListener { startActivity(UpdateActivity.updateNowIntent(this)) }
        routePill.setOnClickListener { openRouteDetail() }
        guideBox.setOnClickListener {
            // 다시 찾기를 다 쓴 채 벗어나 있으면: 누르면 지금 한 번 더 찾는다. 그 밖엔 둘째 줄 접기·펴기
            if (rerouteStoppedNow(Navigator.state.value)) {
                guideBox.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                NavigationService.rerouteNow(this)
            } else {
                toggleGuideCompact()
            }
        }
        // 글자가 바뀌어 폭이 달라지면 원 가장자리 쪽으로 다시 붙인다 (레이아웃 중에 바꾸지 않도록 한 박자 뒤에)
        guideBox.addOnLayoutChangeListener { v, l, _, r, _, ol, _, or, _ ->
            if (r - l != or - ol) v.post { fitToCircle(v, atTop = true) }
        }
        routePill.addOnLayoutChangeListener { v, l, _, r, _, ol, _, or, _ ->
            if (r - l != or - ol) v.post { fitToCircle(v, atTop = false) }
        }

        statusText.setOnClickListener { if (mapFailed) recreate() }
        statusText.isClickable = false

        val initError = MapApp.initError
        if (initError != null) {
            showStatus(getString(R.string.error_sdk_init, initError.javaClass.simpleName), isError = true)
            ensureNavigation()   // 지도를 못 그려도 진동 안내는
            return
        }

        showStatus(getString(R.string.loading_map))
        mapStarted = true
        mapView.start(lifeCycleCallback, readyCallback)
        bumpControls()
        // 지도 인증·로딩을 기다리지 않고 안내부터 (블루투스로 느리거나 지도 인증이 실패해도 진동 안내는 되게)
        ensureNavigation()

        // 안내 상태(현재 구간·남은 거리·도착·재탐색)를 지도 배너와 저전력 화면에 반영
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Navigator.state.collect(::renderNav)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 길찾기 화면에서 '지도에서 보기/안내 시작'으로 돌아온 경우
        ensureNavigation()
        kakaoMap?.let { syncRoute() }
    }

    // ───────────────────────── 저전력(앰비언트) 화면 ─────────────────────────

    private val ambientCallback = object : AmbientLifecycleObserver.AmbientLifecycleCallback {
        override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
            inAmbient = true
            // 저비트 화면이면 안티에일리어싱·회색을 끈다. 번인 방지용 화면 이동은 시스템이 알아서 한다
            ambientMap.setLowBit(ambientDetails.deviceHasLowBitAmbient)
            renderAmbient(force = true)
            ambientRoot.visibility = View.VISIBLE
            // 지도 엔진(GPU)과 방향 센서는 멈추고, 대신 선만 그린 미니 지도로 계속 보여 준다
            if (mapStarted && !mapView.isPaused) mapView.pause()
            headingSensor.stop()
            refreshLocationPlan()
        }

        /** 저전력 화면에서 1분마다 */
        override fun onUpdateAmbient() = renderAmbient(force = true)

        override fun onExitAmbient() {
            inAmbient = false
            ambientRoot.visibility = View.GONE
            renderNav(Navigator.state.value)       // 저전력 화면 동안 건너뛴 배너·알약을 바로 맞춘다
            // 지도를 움직여 둔 채 손목을 내렸다 올리면 바로 다시 내 위치를 따라간다
            if (followLostByTouch) {
                mainHandler.removeCallbacks(refollowRunnable)
                refollowRunnable.run()
            }
            if (mapStarted && !mapFailed && mapView.isPaused) mapView.resume()
            refreshLocationPlan()
            // 손목을 내린 동안 모아 둔 위치를 바로 받아 지도를 따라잡는다
            runCatching { fused.flushLocations() }
        }
    }

    /**
     * 저전력 화면: 검은 바탕에 경로 선·내 위치·목적지 방향·다음 안내 (지도 엔진 없이 직접 그림).
     * 안내 단계가 바뀌거나 도착·이탈하면 바로, 그 밖에는 5초에 한 번만 다시 그린다.
     */
    private fun renderAmbient(force: Boolean = false) {
        val active = RouteRepository.activeRoute
        val nav = Navigator.state.value
        if (active == null) {
            ambientRouteVersion = -1
            ambientMap.setRoute(emptyList(), null)
            ambientMap.update(RouteRepository.lastLocation, -1, getString(R.string.map_name), "")
            return
        }
        if (ambientRouteVersion != RouteRepository.activeRouteVersion) {
            ambientRouteVersion = RouteRepository.activeRouteVersion
            ambientMap.setRoute(active.steps, active.destination)
            lastAmbientKey = null
        }
        val key = listOf(nav.routeVersion, nav.stepIndex, nav.arrived, nav.offRoute, nav.rerouting)
        val now = SystemClock.elapsedRealtime()
        if (!force && key == lastAmbientKey && now - lastAmbientAt < AMBIENT_REDRAW_MS) return
        lastAmbientKey = key
        lastAmbientAt = now
        val top = nav.guide ?: active.summary
        val bottom = buildString {
            nav.remainingM?.takeIf { !nav.arrived }?.let { append("남은 ").append(Format.distance(it)).append(" · ") }
            append("→ ").append(active.destinationName)
        }
        ambientMap.update(RouteRepository.lastLocation, nav.stepIndex, top, bottom)
    }

    // ───────────────────────── 지도 수명주기 ─────────────────────────

    private val lifeCycleCallback = object : MapLifeCycleCallback() {
        override fun onMapDestroy() = Unit

        override fun onMapError(error: Exception) {
            Log.e(MapApp.TAG, "지도 오류", error)
            ErrorLog.record("지도", error)
            runOnUiThread { onMapFailed(error) }
        }
    }

    private val readyCallback = object : KakaoMapReadyCallback() {
        override fun onMapReady(map: KakaoMap) {
            runOnUiThread { onMapReadyOnUi(map) }
        }

        override fun getPosition(): LatLng {
            val c = RouteRepository.lastLocation
            return if (c != null) LatLng.from(c.lat, c.lng)
            else LatLng.from(AppConfig.DEFAULT_LATITUDE, AppConfig.DEFAULT_LONGITUDE)
        }

        override fun getZoomLevel(): Int = AppConfig.DEFAULT_ZOOM_LEVEL
    }

    private fun onMapReadyOnUi(map: KakaoMap) {
        if (mapFailed || isFinishing || isDestroyed) return
        kakaoMap = map
        mainHandler.removeCallbacks(hideStatusRunnable)
        statusText.visibility = View.GONE

        // 카카오 로고: 기본 위치(오른쪽 아래 모서리)는 원형 화면 밖이라 안 보임 → 위쪽 가운데 (숨기면 안 됨)
        val topOffset = mapView.height * LOGO_TOP_RATIO / map.mapDpScale.coerceAtLeast(1f)
        map.logo?.setPosition(MapGravity.TOP or MapGravity.CENTER_HORIZONTAL, 0f, topOffset)

        map.setOnViewportClickListener { _, _, _ -> runOnUiThread { toggleControls() } }

        syncRoute()
        if (Locator.hasPermission(this)) {
            if (RouteRepository.activeRoute == null) setLocMode(LocMode.FOLLOW) else refreshLocationPlan()
        } else if (!askedPermissionBefore()) {
            requestLocationPermission(byUser = false)   // 처음 한 번만 저절로
        }
    }

    private fun onMapFailed(error: Exception) {
        if (isDestroyed) return
        mapFailed = true
        kakaoMap = null
        stopLocationUpdates()
        mainHandler.removeCallbacksAndMessages(null)
        controls.forEach {
            it.animate().cancel()
            it.isClickable = false
            it.visibility = View.INVISIBLE
        }
        controlsShown = false
        guideBox.visibility = View.GONE
        routePill.visibility = View.GONE
        showStatus(describeMapError(error), isError = true)
    }

    override fun onResume() {
        super.onResume()
        screenResumed = true
        if (mapStarted && !inAmbient) mapView.resume()   // 카카오 필수
        ensureNavigation()
        if (kakaoMap != null) {
            syncRoute()
            refreshLocationPlan()
            runCatching { fused.flushLocations() }
        }
        refreshUpdatePill()
        maybeCheckForUpdate()
    }

    override fun onPause() {
        screenResumed = false
        super.onPause()
        if (mapStarted && !mapView.isPaused) mapView.pause()    // 카카오 필수
        // 화면 쪽 위치 수신은 멈춘다. '안내 시작'한 경로는 안내 서비스가 계속 따라간다
        stopLocationUpdates()
    }

    override fun onDestroy() {
        // 재생성(글꼴 크기 변경 등)일 때는 SDK가 엔진을 스스로 끝내지 않으므로 직접 끝낸다
        if (mapStarted && !isFinishing) mapView.finish()
        stopLocationUpdates()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ───────────────────────── 원형 화면 배치 ─────────────────────────

    private fun layoutOverlays() {
        val w = swipeRoot.width
        val h = swipeRoot.height
        if (w == 0 || h == 0) return
        val inset = resources.getDimension(R.dimen.ring_edge_inset)
        val size = resources.getDimensionPixelSize(R.dimen.ring_button_size)
        val routeActive = RouteRepository.activeRoute != null

        // 버튼: 평소 — 11시 길찾기, 5·6·7시 확대·내 위치·축소
        //       경로 표시 중 — 위 안내 배너·아래 알약을 가리지 않게 3시 안내 종료, 9시 내 위치 (카카오맵 길안내처럼 검색 없음)
        //       ✕ 를 왼쪽 가장자리(쓸어서 닫기 영역)에 두지 않아 누르다 미끄러져도 안내가 꺼지거나 앱이 닫히지 않게
        val step = RoundScreen.ringAngleStep(w, h, size, inset, minGap = 8 * resources.displayMetrics.density)
        if (routeActive) {
            placeOnRing(btnEnd, inset, 90f)
            placeOnRing(btnMyLocation, inset, 270f)
        } else {
            placeOnRing(btnSearch, inset, 330f)
            placeOnRing(btnZoomIn, inset, 180f - step)
            placeOnRing(btnMyLocation, inset, 180f)
            placeOnRing(btnZoomOut, inset, 180f + step)
        }

        // 아래 한 줄 알약 (최대 폭 60%), 안내 배너 (최대 폭 62% — 이보다 좁아야 로고 바로 아래 높이에 들어간다).
        // 실제 위치는 글자 폭에 맞춰 fitToCircle 이 정한다
        routePill.maxWidth = (w * 0.60f).toInt()
        guideMaxPx = (w * 0.62f).toInt()
        fitToCircle(guideBox, atTop = true)
        fitToCircle(routePill, atTop = false)
        (updatePill.layoutParams as FrameLayout.LayoutParams).apply {
            topMargin = (h * 0.16f).toInt()
            updatePill.layoutParams = this
        }
    }

    /**
     * 글자 길이만큼만 넓은 배너·알약을 원 안쪽 끝(위·아래)에 붙인다 — 좁을수록 가장자리로 가서 지도가 더 보인다.
     * 남은 거리 숫자가 바뀔 때마다 들썩이지 않도록 폭은 16dp 계단으로 본다. 배너는 카카오 로고(위 9%) 아래.
     */
    private fun fitToCircle(view: View, atTop: Boolean) {
        val w = swipeRoot.width
        val h = swipeRoot.height
        if (w == 0 || h == 0 || view.width == 0) return
        val inset = resources.getDimension(R.dimen.ring_edge_inset)
        val stepPx = (16 * resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val width = (view.width + stepPx - 1) / stepPx * stepPx
        val lp = view.layoutParams as FrameLayout.LayoutParams
        if (atTop) {
            val top = maxOf((h * 0.15f).toInt(), RoundScreen.topForWidth(w, h, width, inset * 2).toInt())
            if (lp.topMargin == top) return
            lp.topMargin = top
        } else {
            val bottom = (h - RoundScreen.bottomForWidth(w, h, width, inset * 2)).toInt()
            if (lp.bottomMargin == bottom) return
            lp.bottomMargin = bottom
        }
        view.layoutParams = lp
    }

    private fun placeOnRing(view: View, inset: Float, angleDeg: Float) {
        val (x, y) = RoundScreen.ringItemTopLeft(
            swipeRoot.width, swipeRoot.height, maxOf(view.width, view.height), inset, angleDeg,
        )
        view.x = x
        view.y = y
    }

    // ───────────────────────── 경로 표시·안내 ─────────────────────────

    private fun syncRoute() {
        val map = kakaoMap ?: return
        val active = RouteRepository.activeRoute
        if (RouteRepository.activeRouteVersion == drawnRouteVersion) return
        drawnRouteVersion = RouteRepository.activeRouteVersion
        clearRouteDrawing()
        layoutOverlays()
        Navigator.sync()
        if (active == null) {
            routePill.visibility = View.GONE
            guideBox.visibility = View.GONE
            setControlsVisible(controlsShown)
            NavigationService.stop(this)
            return
        }

        val segments = active.segments.filter { it.points.size >= 2 }.map { seg ->
            // 걷는 길: 회색 선 위에 흰 점을 일정 간격으로 (점이 안 그려지는 기기여도 회색 선은 남는다)
            val style = if (seg.dotted) RouteLineStyle.from(seg.widthDp, seg.color, 1.5f, android.graphics.Color.WHITE, walkPattern)
            else RouteLineStyle.from(seg.widthDp, seg.color, 1.5f, android.graphics.Color.WHITE)
            RouteLineSegment.from(seg.points.map { LatLng.from(it.lat, it.lng) }, style)
        }
        if (segments.isNotEmpty()) {
            routeLine = map.routeLineManager?.layer?.addRouteLine(RouteLineOptions.from(segments))
        }
        addBubble(active.origin, "출발", ContextCompat.getColor(this, R.color.accent))
        addBubble(active.destination, "도착", android.graphics.Color.parseColor("#E2382F"))

        routePill.text = active.summary
        routePill.visibility = View.VISIBLE
        btnZoomIn.visibility = View.INVISIBLE
        btnZoomOut.visibility = View.INVISIBLE
        setControlsVisible(controlsShown)    // 경로가 생기면 버튼 구성이 바뀜 (✕ 추가)

        val needPrecise = active.startNavigation && Locator.hasPermission(this) && !Locator.hasPrecise(this)
        if (needPrecise) askPreciseOnce()
        if (active.startNavigation && Locator.hasPermission(this)) {
            // 나침반 모드로 보고 있었다면(재탐색 등) 그대로 유지
            setLocMode(if (locMode == LocMode.COMPASS) LocMode.COMPASS else LocMode.FOLLOW)
            // 화면이 꺼져도 따라가며 진동으로 안내 (워치페이스에 진행 중 아이콘)
            if (!needPrecise) {
                maybeAskNotificationPermission()
                NavigationService.start(this)
            }
        } else {
            setLocMode(LocMode.NONE)
            fitRoute(active)
            NavigationService.stop(this)
        }
        refreshLocationPlan()
        // 마지막 위치로 바로 한 번 계산 (30초 넘은 위치는 Navigator 가 무시)
        RouteRepository.lastFix?.let { Navigator.onFix(this, it) }
        renderNav(Navigator.state.value)
    }

    /** 안내 상태를 배너·알약·저전력 화면에 그린다 */
    private fun renderNav(s: NavState) {
        if (isFinishing || isDestroyed) return
        // 재탐색 등으로 경로가 바뀌었으면 지도부터 다시 그린다
        if (kakaoMap != null && RouteRepository.activeRouteVersion != drawnRouteVersion) {
            syncRoute()
            return
        }
        val active = RouteRepository.activeRoute
        if (active == null || s.routeVersion != RouteRepository.activeRouteVersion) {
            if (active == null) guideBox.visibility = View.GONE
            return
        }
        // 저전력 화면에선 가려진 배너·알약을 고치지 않는다 (위치마다 다시 그리면 배터리 낭비) — 돌아올 때 한 번에
        if (inAmbient) {
            renderAmbient()
            return
        }
        val guide = s.guide ?: active.steps.firstOrNull()?.text
        val fallback = NavOverlayText.bannerLines(guide.orEmpty())
        val main = s.bannerTitle ?: fallback.first
        val rawSub = if (s.bannerTitle != null) s.bannerSub else fallback.second
        // GPS 신호가 끊기면(지하·실내) 둘째 줄로 알린다 — 접어 둔 상태여도. 다시 찾기를 다 썼으면 그것을(누르면 한 번 더)
        val stopped = rerouteStoppedNow(s)
        val sub = when {
            stopped -> getString(R.string.reroute_stopped)
            s.signalLost && !s.arrived -> getString(R.string.gps_weak)
            else -> rawSub
        }
        // 왼쪽 칸: 아이콘 + 그 아래 거리
        val dist = s.bannerDistM?.let { Format.distance(it) }
        guideDist.text = dist.orEmpty()
        guideDist.visibility = if (dist == null) View.GONE else View.VISIBLE
        val dp = resources.displayMetrics.density
        val leftW = maxOf(22 * dp, if (dist == null) 0f else guideDist.paint.measureText(dist))
        val textMax = (guideMaxPx - guideBox.paddingLeft - guideBox.paddingRight - leftW - 6 * dp).toInt()
        guideMain.maxWidth = textMax
        guideSub.maxWidth = textMax
        fitText(guideMain, main, textMax, maxSp = 14f, minSp = 11f)
        fitText(guideSub, sub.orEmpty(), textMax, maxSp = 12f, minSp = 10.5f)
        guideSub.visibility = if (sub == null || (guideCompact && !s.signalLost && !stopped)) View.GONE else View.VISIBLE
        applyGuideIcon(s, active)
        guideBox.visibility = if (main.isEmpty() || mapFailed) View.GONE else View.VISIBLE
        routePill.text = NavOverlayText.pill(
            mode = active.mode,
            remainingM = s.remainingM,
            arrived = s.arrived,
            totalDistanceM = active.totalDistanceM,
            totalTimeSec = active.totalTimeSec,
            startedAtMs = active.startedAtMs,
            nowMs = System.currentTimeMillis(),
            summary = active.summary,
            destinationName = active.destinationName,
        )
    }

    /** 글자가 한 줄 폭을 넘으면 최대 minSp 까지 조금씩 줄여서 끝이 잘리지 않게 (그래도 넘치면 … 처리) */
    private fun fitText(tv: TextView, text: String, maxPx: Int, maxSp: Float, minSp: Float) {
        var sp = maxSp
        if (maxPx > 0 && text.isNotEmpty()) {
            val paint = android.text.TextPaint(tv.paint)
            while (sp > minSp) {
                paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
                if (paint.measureText(text) <= maxPx) break
                sp -= 0.5f
            }
        }
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        tv.text = text
    }

    /** 배너 앞 아이콘: 회전 화살표·횡단보도·버스·지하철·도착 깃발·경고 */
    private fun applyGuideIcon(s: NavState, active: ActiveRoute) {
        var rotation = 0f
        var mirror = false
        val res = when (s.icon) {
            NavIcon.ACTION -> when (s.action) {
                Action.LEFT, Action.SHARP_LEFT -> R.drawable.ic_turn_right.also { mirror = true }
                Action.RIGHT, Action.SHARP_RIGHT -> R.drawable.ic_turn_right
                Action.SLIGHT_LEFT -> R.drawable.ic_arrow_straight.also { rotation = -45f }
                Action.SLIGHT_RIGHT -> R.drawable.ic_arrow_straight.also { rotation = 45f }
                Action.UTURN -> R.drawable.ic_uturn
                Action.CROSSWALK -> R.drawable.ic_crosswalk
                Action.STAIRS, Action.OVERPASS, Action.UNDERPASS -> R.drawable.ic_stairs
                Action.ELEVATOR -> R.drawable.ic_info
                Action.INDOOR -> R.drawable.ic_subway
                Action.STRAIGHT, null -> R.drawable.ic_arrow_straight
            }
            NavIcon.WALK -> if (active.mode == TravelMode.BIKE) R.drawable.ic_bike else R.drawable.ic_walk
            NavIcon.BUS -> R.drawable.ic_bus
            NavIcon.SUBWAY -> R.drawable.ic_subway
            NavIcon.TRAIN -> R.drawable.ic_train
            NavIcon.FLAG -> R.drawable.ic_flag
            NavIcon.WARNING -> R.drawable.ic_warning
        }
        guideIcon.setImageResource(res)
        guideIcon.rotation = rotation
        guideIcon.scaleX = if (mirror) -1f else 1f
    }

    /** 배너 톡 → 한 줄로 접기 / 펼치기 (다음에 열어도 그대로) */
    private fun rerouteStoppedNow(s: NavState) =
        s.rerouteStopped && !s.rerouting && !s.arrived && (s.offRoute || s.passedStop || s.missedRide)

    private fun toggleGuideCompact() {
        guideCompact = !guideCompact
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_GUIDE_COMPACT, guideCompact) }
        guideBox.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        renderNav(Navigator.state.value)
    }

    /**
     * '안내 시작'한 경로면 지도 준비와 상관없이 안내 서비스를 켠다 — 지도 인증이 늦거나(폰 블루투스로 연결) 실패해도
     * 진동 안내·진행 중 아이콘은 되게. 지도에 경로를 그리는 건 지도가 준비된 뒤 syncRoute 가 한다.
     */
    private fun ensureNavigation() {
        val active = RouteRepository.activeRoute ?: return
        if (!active.startNavigation || NavigationService.running || !Locator.hasPermission(this)) return
        if (!Locator.hasPrecise(this)) {
            askPreciseOnce()
            return
        }
        maybeAskNotificationPermission()
        NavigationService.start(this)
    }

    /** '대략적 위치'만으로는 갈림길·정류장을 가릴 수 없어 안내가 시작되지 않는다 → 정확한 위치를 (경로마다 한 번) 다시 요청 */
    private fun askPreciseOnce() {
        if (preciseAskedForVersion == RouteRepository.activeRouteVersion) return
        preciseAskedForVersion = RouteRepository.activeRouteVersion
        showStatus(getString(R.string.precise_location_needed), autoHideMs = 4_000)
        requestLocationPermission(byUser = true)   // '안내 시작'을 누른 결과
    }

    private fun maybeAskNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ASKED_NOTIFICATIONS, false)) return
        prefs.edit { putBoolean(KEY_ASKED_NOTIFICATIONS, true) }
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun fitRoute(active: ActiveRoute) {
        val map = kakaoMap ?: return
        val pts = active.segments.flatMap { it.points } + listOf(active.origin, active.destination)
        val minLat = pts.minOf { it.lat }
        val maxLat = pts.maxOf { it.lat }
        val minLng = pts.minOf { it.lng }
        val maxLng = pts.maxOf { it.lng }
        val padding = (swipeRoot.width * 0.22f).toInt()
        map.moveCamera(
            CameraUpdateFactory.fitMapPoints(arrayOf(LatLng.from(minLat, minLng), LatLng.from(maxLat, maxLng)), padding),
            CameraAnimation.from(400),
        )
    }

    private fun addBubble(c: Coord, text: String, color: Int) {
        val style = LabelStyle.from(MapBitmaps.bubble(this, text, color))
            .setApplyDpScale(false)
            .setAnchorPoint(0.5f, 1f)
        kakaoMap?.labelManager?.layer?.addLabel(
            LabelOptions.from(LatLng.from(c.lat, c.lng)).setStyles(LabelStyles.from(style)).setRank(5_000)
        )?.let { routeLabels += it }
    }

    private fun clearRouteDrawing() {
        routeLine?.remove()
        routeLine = null
        routeLabels.forEach { it.remove() }
        routeLabels.clear()
    }

    private fun endRoute() {
        followLostByTouch = false
        mainHandler.removeCallbacks(refollowRunnable)
        RouteRepository.activeRoute = null
        NavigationService.stop(this)
        syncRoute()
        if (locMode != LocMode.NONE) setLocMode(LocMode.NONE) else refreshLocationPlan()
        bumpControls()
    }

    /** 지금 지도에 띄운 바로 그 경로의 상세 (다른 곳을 검색해 둔 상태여도) */
    private fun openRouteDetail() {
        if (RouteRepository.activeRoute == null) return
        startActivity(RouteDetailActivity.intentActive(this))
    }

    // ───────────────────────── 내 위치 (위치 → 추적 → 나침반) ─────────────────────────

    private fun onMyLocationButton() {
        // 직접 고른 모드는 저절로 되돌리지 않는다
        followLostByTouch = false
        mainHandler.removeCallbacks(refollowRunnable)
        if (!Locator.hasPermission(this)) {
            // 두 번 거절해 권한 창이 더는 안 뜨면 결과를 받는 곳에서 앱 설정(권한) 화면을 연다
            requestLocationPermission(byUser = true)
            return
        }
        setLocMode(
            when (locMode) {
                LocMode.NONE -> LocMode.FOLLOW
                LocMode.FOLLOW -> if (headingSensor.available) LocMode.COMPASS else LocMode.NONE
                LocMode.COMPASS -> LocMode.NONE
            }
        )
    }

    private fun setLocMode(mode: LocMode) {
        val map = kakaoMap
        val previous = locMode
        locMode = mode
        btnMyLocation.setImageResource(
            when (mode) {
                LocMode.NONE -> R.drawable.ic_my_location
                LocMode.FOLLOW -> R.drawable.ic_navigation
                LocMode.COMPASS -> R.drawable.ic_compass
            }
        )
        btnMyLocation.imageTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(this, if (mode == LocMode.NONE) R.color.text_dark else R.color.accent)
        )
        if (map == null) return
        val tracking = map.trackingManager
        when (mode) {
            LocMode.NONE -> {
                tracking?.stopTracking()
                if (previous == LocMode.COMPASS) map.moveCamera(CameraUpdateFactory.rotateTo(0.0), CameraAnimation.from(300))
            }
            LocMode.FOLLOW, LocMode.COMPASS -> {
                myLabel?.let {
                    tracking?.startTracking(it)
                    tracking?.setTrackingRotation(mode == LocMode.COMPASS)
                }
                RouteRepository.lastLocation?.let {
                    map.moveCamera(CameraUpdateFactory.newCenterPosition(LatLng.from(it.lat, it.lng)), CameraAnimation.from(300))
                }
            }
        }
        refreshLocationPlan()
    }

    /**
     * 화면 쪽 위치 요청을 지금 상황에 맞춘다.
     *  · 화면을 보는 중: 1초 (부드러운 내 위치)
     *  · 손목 내림 + '지도에서 보기'만 한 경로: 10초 (미니 지도용)
     *  · 손목 내림 + 안내 중: 화면 쪽은 끄고 안내 서비스가 상황별 간격으로 받음
     *  · 경로도 따라가기도 없으면: 끔
     */
    @SuppressLint("MissingPermission")
    private fun refreshLocationPlan() {
        val wanted = Locator.hasPermission(this) && kakaoMap != null &&
            screenResumed &&
            (locMode != LocMode.NONE || RouteRepository.activeRoute != null)
        val plan = when {
            !wanted -> null
            !inAmbient -> LocationPolicy.INTERACTIVE
            RouteRepository.activeRoute != null && !NavigationService.running -> LocationPolicy.AMBIENT_VIEW_ONLY
            else -> null
        }
        if (plan == null) {
            stopLocationUpdates()
            return
        }
        if (!inAmbient) headingSensor.start() else headingSensor.stop()
        if (updatesRunning && plan == activePlan) return
        val firstStart = !updatesRunning
        val priority = when (plan.power) {
            LocPower.HIGH -> Priority.PRIORITY_HIGH_ACCURACY
            LocPower.BALANCED -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
            LocPower.PASSIVE -> Priority.PRIORITY_PASSIVE
        }
        val request = LocationRequest.Builder(priority, plan.intervalMs)
            .setMinUpdateIntervalMillis(minOf(plan.intervalMs, 1_000L))
            .setMaxUpdateDelayMillis(plan.maxDelayMs)
            .setMinUpdateDistanceMeters(plan.minDistanceM)
            .build()
        fused.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        updatesRunning = true
        activePlan = plan
        if (firstStart) {
            // 새 위치가 오기 전 잠깐 보여 줄 마지막 위치 — 10분 넘은 것은 '내 위치'로 띄우지 않는다
            fused.lastLocation.addOnSuccessListener { loc ->
                if (loc != null && SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos <= STALE_MARKER_NS) onLocation(loc)
            }
        }
    }

    private fun stopLocationUpdates() {
        headingSensor.stop()
        if (!updatesRunning) return
        updatesRunning = false
        activePlan = null
        fused.removeLocationUpdates(locationCallback)
    }

    private fun onLocation(loc: Location) {
        val map = kakaoMap ?: return
        val fix = loc.toFix()
        val c = fix.coord
        Navigator.onFix(this, fix)      // 위치 저장 + 안내 계산 (같은 위치는 한 번만)
        if (inAmbient) return           // 저전력 화면에선 지도를 건드리지 않는다
        headingSensor.updateLocation(c)
        val pos = LatLng.from(c.lat, c.lng)
        val label = myLabel
        if (label == null) {
            myLabelHasCone = heading != null
            myLabel = map.labelManager?.layer?.addLabel(
                LabelOptions.from(pos)
                    .setStyles(myStyle(myLabelHasCone))
                    .setRank(10_000)
                    .setTransform(TransformMethod.AbsoluteRotation)
            )
            if (locMode != LocMode.NONE) myLabel?.let {
                map.trackingManager?.startTracking(it)
                map.trackingManager?.setTrackingRotation(locMode == LocMode.COMPASS)
            }
        } else {
            label.moveTo(pos, 500)
        }
    }

    private fun myStyle(withCone: Boolean) = LabelStyles.from(
        LabelStyle.from(MapBitmaps.myLocation(this, withCone))
            .setApplyDpScale(false)
            .setAnchorPoint(0.5f, 0.5f)
    )

    private fun onHeading(deg: Float) {
        heading = deg
        val label = myLabel ?: return
        if (!myLabelHasCone) {
            myLabelHasCone = true
            label.changeStyles(myStyle(true))
        }
        if (!lastHeadingApplied.isNaN() && angleDiff(deg, lastHeadingApplied) < 3f) return
        lastHeadingApplied = deg
        // 카카오 Label 회전은 −180~180°. 뒤쪽(±180°)을 넘을 때는 애니메이션 없이 — 숫자대로 돌리면
        // 반대쪽으로 한 바퀴 가까이 휘돌아(나침반 모드에선 지도까지) 방향이 '난리'로 보인다
        val target = HeadingMath.labelRadians(deg * AppConfig.HEADING_SIGN)
        label.rotateTo(target, HeadingMath.rotationAnimMs(lastLabelRad, target, 120))
        lastLabelRad = target
    }

    private fun angleDiff(a: Float, b: Float): Float {
        val d = abs(a - b) % 360
        return if (d > 180) 360 - d else d
    }

    // ───────────────────────── 줌 (베젤·크라운·버튼) ─────────────────────────

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_SCROLL && ev.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
            onRotary(-ev.getAxisValue(MotionEvent.AXIS_SCROLL))
            return true
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    private fun onRotary(clockwiseDelta: Float) {
        if (kakaoMap == null || clockwiseDelta == 0f) return
        val now = SystemClock.uptimeMillis()
        if (now - lastRotaryAt > ROTARY_RESET_MS || rotaryAccumulated * clockwiseDelta < 0f) rotaryAccumulated = 0f
        lastRotaryAt = now
        rotaryAccumulated += clockwiseDelta
        val steps = (rotaryAccumulated / ROTARY_UNITS_PER_ZOOM).toInt()
        if (steps == 0) return
        rotaryAccumulated = 0f
        zoomBy((if (AppConfig.CLOCKWISE_ZOOMS_IN) steps else -steps).coerceIn(-2, 2))
    }

    private fun zoomBy(levels: Int) {
        val map = kakaoMap ?: return
        if (levels == 0) return
        val now = SystemClock.uptimeMillis()
        val base = if (pendingZoom >= 0 && now - lastZoomRequestAt < ZOOM_CHAIN_MS) pendingZoom else map.zoomLevel
        val target = (base + levels).coerceIn(map.cameraMinLevel, map.cameraMaxLevel)
        if (target == base) return
        pendingZoom = target
        lastZoomRequestAt = now
        map.moveCamera(CameraUpdateFactory.zoomTo(target), CameraAnimation.from(ZOOM_ANIMATION_MS))
        mapView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    /** 지도를 직접 끌면(또는 두 손가락) 위치 추적을 끈다 — 카카오맵과 같은 동작 */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = ev.x
                touchDownY = ev.y
                mainHandler.removeCallbacks(refollowRunnable)
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (locMode != LocMode.NONE) stopFollowByTouch()
            MotionEvent.ACTION_MOVE ->
                if (locMode != LocMode.NONE && hypot(ev.x - touchDownX, ev.y - touchDownY) > touchSlop * 2) {
                    stopFollowByTouch()
                }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (followLostByTouch) {
                // 안내 중이면 지도를 놓고 8초 지나면 다시 내 위치를 따라간다 (카카오맵처럼)
                mainHandler.removeCallbacks(refollowRunnable)
                mainHandler.postDelayed(refollowRunnable, REFOLLOW_AFTER_MS)
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun stopFollowByTouch() {
        val active = RouteRepository.activeRoute
        if (active != null && active.startNavigation) {
            followLostByTouch = true
            modeBeforeTouch = locMode
        }
        setLocMode(LocMode.NONE)
    }

    // ───────────────────────── 버튼·안내 문구 ─────────────────────────

    private fun visibleControls(): List<View> =
        if (RouteRepository.activeRoute != null) listOf(btnMyLocation, btnEnd)
        else listOf(btnSearch, btnZoomIn, btnMyLocation, btnZoomOut)

    private fun setControlsVisible(visible: Boolean) {
        controlsShown = visible
        val shown = visibleControls()
        // 내 위치 버튼은 카카오맵처럼 늘 보인다 — 지도를 끌어 옮겼다가 바로 내 위치로 돌아올 수 있게 (나머지만 4초 뒤 숨김)
        val pinned = if (mapStarted && !mapFailed) btnMyLocation else null
        controls.forEach { v ->
            v.animate().cancel()
            val on = (visible && v in shown) || v == pinned
            v.isClickable = on
            if (on) {
                v.visibility = View.VISIBLE
                v.animate().alpha(1f).setDuration(150).start()
            } else {
                v.animate().alpha(0f).setDuration(250).withEndAction { v.visibility = View.INVISIBLE }.start()
            }
        }
    }

    private fun bumpControls() {
        if (mapFailed || !mapStarted) return
        setControlsVisible(true)
        mainHandler.removeCallbacks(hideControlsRunnable)
        if (AppConfig.CONTROLS_AUTO_HIDE_MS > 0) mainHandler.postDelayed(hideControlsRunnable, AppConfig.CONTROLS_AUTO_HIDE_MS)
    }

    private fun toggleControls() {
        if (controlsShown) {
            mainHandler.removeCallbacks(hideControlsRunnable)
            setControlsVisible(false)
        } else {
            bumpControls()
        }
    }

    private fun refreshUpdatePill() {
        val show = !mapFailed && RouteRepository.activeRoute == null &&
            (UpdateManager.hasUpdate(this) || UpdateManager.isPendingUserAction(this))
        updatePill.visibility = if (show) View.VISIBLE else View.GONE
    }

    /** 지도를 열 때 조용히 새 버전 확인 — 마지막 확인이 5분 넘었으면 (첫 화면·유튜브와 같은 간격) */
    private fun maybeCheckForUpdate() {
        if (UpdateManager.repo(this) == null) return
        if (System.currentTimeMillis() - UpdateManager.lastCheckAt(this) < UpdateWorker.OPEN_CHECK_EVERY_MS) return
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { UpdateManager.check(this@MainActivity) }.getOrNull() }
            // 새 버전이 있으면 쓰는 중·안내 중이 아닐 때 알아서 설치되도록 예약 (집 밖에서도 폰 데이터로)
            if (found != null) UpdateInstallWorker.enqueue(this@MainActivity)
            refreshUpdatePill()
        }
    }

    private val safeSquarePx by lazy {
        val dm = resources.displayMetrics
        (RoundScreen.inscribedSquareSide(dm.widthPixels, dm.heightPixels) * 0.96f).toInt()
    }

    private fun showStatus(text: String, isError: Boolean = false, autoHideMs: Long = 0) {
        mainHandler.removeCallbacks(hideStatusRunnable)
        val lp = statusText.layoutParams
        if (isError) {
            lp.width = safeSquarePx
            lp.height = safeSquarePx
            statusText.setAutoSizeTextTypeUniformWithConfiguration(7, 13, 1, TypedValue.COMPLEX_UNIT_SP)
            statusText.setBackgroundResource(R.drawable.bg_status_error)
        } else {
            statusText.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE)
            statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            statusText.setBackgroundResource(R.drawable.bg_status)
        }
        statusText.layoutParams = lp
        statusText.maxWidth = safeSquarePx
        statusText.isClickable = isError && mapFailed
        statusText.text = text
        statusText.visibility = View.VISIBLE
        if (autoHideMs > 0) {
            mainHandler.postDelayed(hideStatusRunnable, autoHideMs)
        }
    }

    private fun describeMapError(error: Exception): String {
        val retry = getString(R.string.tap_to_retry)
        if (error !is MapAuthException) {
            return getString(R.string.err_generic, error.message ?: error.javaClass.simpleName) + "\n\n" + retry
        }
        val reason = when (error.errorCode) {
            401 -> getString(R.string.err_401)
            403 -> getString(R.string.err_403)
            429 -> getString(R.string.err_429)
            -2, -3, -4, 499 -> getString(R.string.err_network)
            else -> getString(R.string.err_unknown, error.errorCode)
        }
        val hashKey = runCatching { KakaoMapSdk.INSTANCE.hashKey }.getOrNull() ?: "?"
        return getString(R.string.err_with_hash, reason, packageName, hashKey) + "\n\n" + retry
    }

    private fun askedPermissionBefore() = PermissionHelp.askedLocation(this)

    private fun markAskedPermission() = PermissionHelp.markAskedLocation(this)

    companion object {
        private const val PREFS = "main"
        private const val KEY_ASKED_NOTIFICATIONS = "asked_notifications"
        private const val KEY_GUIDE_COMPACT = "guide_compact"
        private const val REFOLLOW_AFTER_MS = 8_000L

        /** 카카오 로고를 위에서 얼마나 내릴지 (화면 높이 대비) */
        private const val LOGO_TOP_RATIO = 0.09f
        private const val ROTARY_UNITS_PER_ZOOM = 0.95f
        private const val ROTARY_RESET_MS = 500L
        private const val ZOOM_ANIMATION_MS = 180
        private const val ZOOM_CHAIN_MS = 600L
        private const val STALE_MARKER_NS = 10 * 60_000L * 1_000_000L
        private const val AMBIENT_REDRAW_MS = 5_000L

        /** 다른 화면에서 지도로 돌아오기 (경로를 표시하도록) */
        fun open(context: Context) {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }
}
