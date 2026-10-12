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
import com.kakao.vectormap.route.RouteLineSegment
import com.kakao.vectormap.route.RouteLineStyle
import com.mywatch.kakaomap.data.ActiveRoute
import com.mywatch.kakaomap.data.RouteRepository
import com.mywatch.kakaomap.location.HeadingSensor
import com.mywatch.kakaomap.location.Locator
import com.mywatch.kakaomap.location.toFix
import com.mywatch.kakaomap.model.Coord
import com.mywatch.kakaomap.model.Format
import com.mywatch.kakaomap.model.TravelMode
import com.mywatch.kakaomap.nav.LocPlan
import com.mywatch.kakaomap.nav.LocPower
import com.mywatch.kakaomap.nav.LocationPolicy
import com.mywatch.kakaomap.nav.NavState
import com.mywatch.kakaomap.nav.NavigationService
import com.mywatch.kakaomap.nav.Navigator
import com.mywatch.kakaomap.ui.AmbientRouteView
import com.mywatch.kakaomap.ui.MapBitmaps
import com.mywatch.kakaomap.ui.RouteDetailActivity
import com.mywatch.kakaomap.ui.SearchActivity
import com.mywatch.kakaomap.update.UpdateActivity
import com.mywatch.kakaomap.update.UpdateInstallWorker
import com.mywatch.kakaomap.update.UpdateManager
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
    private lateinit var guideText: TextView
    private lateinit var updatePill: TextView
    private lateinit var routeCard: View
    private lateinit var ambientRoot: View
    private lateinit var ambientMap: AmbientRouteView
    private lateinit var btnSearch: ImageButton
    private lateinit var btnZoomIn: ImageButton
    private lateinit var btnMyLocation: ImageButton
    private lateinit var btnZoomOut: ImageButton
    private lateinit var controls: List<View>

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
    private lateinit var headingSensor: HeadingSensor
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var updatesRunning = false
    /** 화면 쪽에서 지금 걸어 둔 위치 요청 */
    private var activePlan: LocPlan? = null

    // 경로
    private var drawnRouteVersion = -1
    private var routeLine: RouteLine? = null
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

    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.any { it }) {
            setLocMode(LocMode.FOLLOW)
        } else {
            showStatus(getString(R.string.location_permission_denied), autoHideMs = 2500)
        }
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
        guideText = findViewById(R.id.guide)
        updatePill = findViewById(R.id.update_pill)
        routeCard = findViewById(R.id.route_card)
        ambientRoot = findViewById(R.id.ambient_root)
        ambientMap = findViewById(R.id.ambient_map)
        btnSearch = findViewById(R.id.btn_search)
        btnZoomIn = findViewById(R.id.btn_zoom_in)
        btnMyLocation = findViewById(R.id.btn_my_location)
        btnZoomOut = findViewById(R.id.btn_zoom_out)
        controls = listOf(btnSearch, btnZoomIn, btnMyLocation, btnZoomOut)

        headingSensor = HeadingSensor(this) { deg -> runOnUiThread { onHeading(deg) } }
        // 손목을 내리면 꺼지는 대신 저전력 화면으로 (길 안내를 계속 볼 수 있게)
        lifecycle.addObserver(AmbientLifecycleObserver(this, ambientCallback))

        // 화면 '왼쪽 가장자리'에서 오른쪽으로 쓸면 앱 종료 (나머지 드래그는 지도 이동)
        swipeRoot.addCallback(object : SwipeDismissFrameLayout.Callback() {
            override fun onDismissed(layout: SwipeDismissFrameLayout) {
                layout.visibility = View.GONE
                finish()
            }
        })

        swipeRoot.doOnLayout { layoutOverlays() }

        btnSearch.setOnClickListener { startActivity(Intent(this, SearchActivity::class.java)) }
        btnZoomIn.setOnClickListener { zoomBy(+1); bumpControls() }
        btnZoomOut.setOnClickListener { zoomBy(-1); bumpControls() }
        btnMyLocation.setOnClickListener { onMyLocationButton(); bumpControls() }
        controls.forEach { it.isClickable = false }   // 숨겨진 상태로 시작

        updatePill.setOnClickListener { startActivity(Intent(this, UpdateActivity::class.java)) }
        routeCard.setOnClickListener { openRouteDetail() }
        findViewById<View>(R.id.route_close).setOnClickListener { endRoute() }

        statusText.setOnClickListener { if (mapFailed) recreate() }
        statusText.isClickable = false

        val initError = MapApp.initError
        if (initError != null) {
            showStatus(getString(R.string.error_sdk_init, initError.javaClass.simpleName), isError = true)
            return
        }

        showStatus(getString(R.string.loading_map))
        mapStarted = true
        mapView.start(lifeCycleCallback, readyCallback)
        bumpControls()

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
            ambientMap.update(RouteRepository.lastLocation, -1, getString(R.string.app_name), "")
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
            markAskedPermission()
            permissionRequest.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
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
        guideText.visibility = View.GONE
        routeCard.visibility = View.GONE
        showStatus(describeMapError(error), isError = true)
    }

    override fun onResume() {
        super.onResume()
        screenResumed = true
        if (mapStarted && !inAmbient) mapView.resume()   // 카카오 필수
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

        // 버튼: 평소 — 11시 길찾기, 5·6·7시 확대·내 위치·축소 / 경로 표시 중 — 11시 길찾기, 1시 내 위치
        val step = RoundScreen.ringAngleStep(w, h, size, inset, minGap = 8 * resources.displayMetrics.density)
        placeOnRing(btnSearch, inset, 330f)
        if (routeActive) {
            placeOnRing(btnMyLocation, inset, 30f)
        } else {
            placeOnRing(btnZoomIn, inset, 180f - step)
            placeOnRing(btnMyLocation, inset, 180f)
            placeOnRing(btnZoomOut, inset, 180f + step)
        }

        // 경로 카드: 폭 62%, 아래 모서리가 원 안에 들어오는 위치
        val cardW = (w * 0.62f).toInt()
        (routeCard.layoutParams as FrameLayout.LayoutParams).apply {
            width = cardW
            bottomMargin = (h - RoundScreen.bottomForWidth(w, h, cardW, inset * 2)).toInt()
            routeCard.layoutParams = this
        }
        // 안내 배너: 로고 아래, 폭 66%
        val guideW = (w * 0.66f).toInt()
        guideText.maxWidth = guideW
        (guideText.layoutParams as FrameLayout.LayoutParams).apply {
            topMargin = maxOf((h * 0.15f).toInt(), RoundScreen.topForWidth(w, h, guideW, inset * 2).toInt())
            guideText.layoutParams = this
        }
        (updatePill.layoutParams as FrameLayout.LayoutParams).apply {
            topMargin = (h * 0.16f).toInt()
            updatePill.layoutParams = this
        }
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
            routeCard.visibility = View.GONE
            guideText.visibility = View.GONE
            setControlsVisible(controlsShown)
            NavigationService.stop(this)
            return
        }

        val segments = active.segments.filter { it.points.size >= 2 }.map { seg ->
            RouteLineSegment.from(
                seg.points.map { LatLng.from(it.lat, it.lng) },
                RouteLineStyle.from(seg.widthDp, seg.color, 1.5f, android.graphics.Color.WHITE),
            )
        }
        if (segments.isNotEmpty()) {
            routeLine = map.routeLineManager?.layer?.addRouteLine(RouteLineOptions.from(segments))
        }
        addBubble(active.origin, "출발", ContextCompat.getColor(this, R.color.accent))
        addBubble(active.destination, "도착", android.graphics.Color.parseColor("#E2382F"))

        findViewById<ImageView>(R.id.route_icon).setImageResource(
            when (active.mode) {
                TravelMode.TRANSIT -> R.drawable.ic_bus
                TravelMode.WALK -> R.drawable.ic_walk
                TravelMode.BIKE -> R.drawable.ic_bike
            }
        )
        findViewById<TextView>(R.id.route_summary).text = active.summary
        findViewById<TextView>(R.id.route_dest).text = "→ ${active.destinationName}"
        routeCard.visibility = View.VISIBLE
        btnZoomIn.visibility = View.INVISIBLE
        btnZoomOut.visibility = View.INVISIBLE

        if (active.startNavigation && Locator.hasPermission(this)) {
            // 나침반 모드로 보고 있었다면(재탐색 등) 그대로 유지
            setLocMode(if (locMode == LocMode.COMPASS) LocMode.COMPASS else LocMode.FOLLOW)
            // 화면이 꺼져도 따라가며 진동으로 안내 (워치페이스에 진행 중 아이콘)
            maybeAskNotificationPermission()
            NavigationService.start(this)
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

    /** 안내 상태를 배너·카드·저전력 화면에 그린다 */
    private fun renderNav(s: NavState) {
        if (isFinishing || isDestroyed) return
        // 재탐색 등으로 경로가 바뀌었으면 지도부터 다시 그린다
        if (kakaoMap != null && RouteRepository.activeRouteVersion != drawnRouteVersion) {
            syncRoute()
            return
        }
        val active = RouteRepository.activeRoute
        if (active == null || s.routeVersion != RouteRepository.activeRouteVersion) {
            if (active == null) guideText.visibility = View.GONE
            return
        }
        val guide = s.guide ?: active.steps.firstOrNull()?.text
        guideText.text = guide.orEmpty()
        guideText.visibility = if (guide.isNullOrBlank() || mapFailed) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.route_dest).text = s.remainingM?.takeIf { !s.arrived }
            ?.let { "남은 거리 ${Format.distance(it)} · → ${active.destinationName}" }
            ?: "→ ${active.destinationName}"
        if (inAmbient) renderAmbient()
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
        if (!Locator.hasPermission(this)) {
            permissionRequest.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
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
        label.rotateTo(Math.toRadians(deg.toDouble() * AppConfig.HEADING_SIGN).toFloat(), 120)
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
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (locMode != LocMode.NONE) setLocMode(LocMode.NONE)
            MotionEvent.ACTION_MOVE ->
                if (locMode != LocMode.NONE && hypot(ev.x - touchDownX, ev.y - touchDownY) > touchSlop * 2) {
                    setLocMode(LocMode.NONE)
                }
        }
        return super.dispatchTouchEvent(ev)
    }

    // ───────────────────────── 버튼·안내 문구 ─────────────────────────

    private fun visibleControls(): List<View> =
        if (RouteRepository.activeRoute != null) listOf(btnSearch, btnMyLocation) else controls

    private fun setControlsVisible(visible: Boolean) {
        controlsShown = visible
        val shown = visibleControls()
        controls.forEach { v ->
            v.animate().cancel()
            val on = visible && v in shown
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

    /** 3시간에 한 번, 앱을 열 때 조용히 새 버전 확인 */
    private fun maybeCheckForUpdate() {
        if (UpdateManager.repo(this) == null) return
        if (System.currentTimeMillis() - UpdateManager.lastCheckAt(this) < 3 * 3600_000L) return
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { UpdateManager.check(this@MainActivity) }.getOrNull() }
            // 새 버전이 있으면 조건(Wi-Fi 또는 충전 중, 안내 중 아님)이 맞을 때 알아서 설치되도록 예약
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

    private fun askedPermissionBefore() =
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ASKED, false)

    private fun markAskedPermission() =
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_ASKED, true) }

    companion object {
        private const val PREFS = "main"
        private const val KEY_ASKED = "asked_location"
        private const val KEY_ASKED_NOTIFICATIONS = "asked_notifications"

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
