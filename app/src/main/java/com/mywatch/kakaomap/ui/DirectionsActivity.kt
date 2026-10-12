package com.mywatch.kakaomap.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import com.mywatch.kakaomap.MainActivity
import com.mywatch.kakaomap.R
import com.mywatch.kakaomap.data.RoutePresentation
import com.mywatch.kakaomap.data.RouteRepository
import com.mywatch.kakaomap.location.Locator
import com.mywatch.kakaomap.model.Coord
import com.mywatch.kakaomap.model.Place
import com.mywatch.kakaomap.model.SimpleRoute
import com.mywatch.kakaomap.model.TransitStepType
import com.mywatch.kakaomap.model.TravelMode
import com.mywatch.kakaomap.net.BusArrivalService
import com.mywatch.kakaomap.net.KakaoApi
import com.mywatch.kakaomap.net.TagoApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 카카오맵 길찾기 결과 화면: 위쪽 이동수단 탭(대중교통·도보·자전거) + 경로 목록.
 * 대중교통은 여러 경로 카드(시간·도착 시각·요금·환승·구간 막대·실시간 버스)를,
 * 도보·자전거는 요약 + 안내 시작 + 단계별 안내를 보여 준다.
 */
class DirectionsActivity : ComponentActivity() {

    private lateinit var ui: WearList
    private lateinit var dest: Place
    private var mode = TravelMode.TRANSIT
    private var loadJob: Job? = null
    /** 출발지 설명 — 새로 잡은 위치면 '내 위치', 몇 분 지난 위치면 그렇게 알린다 */
    private var originLabel = "내 위치에서 출발"

    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        load()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = RouteRepository.destination
        if (d == null) {
            finish()
            return
        }
        dest = d
        mode = runCatching {
            TravelMode.valueOf(getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODE, null) ?: "")
        }.getOrDefault(TravelMode.TRANSIT)
        setContentView(R.layout.activity_list)
        ui = WearList(this, ::onRow)
        load()
    }

    private fun head(): List<Row> = listOf(
        Row.Header("→ ${dest.name}", originLabel),
        Row.ModeTabs(mode),
    )

    private fun load() {
        loadJob?.cancel()
        ui.rows = head() + Row.Note(getString(R.string.routing))
        if (!Locator.hasPermission(this)) {
            ui.rows = head() + Row.Note(getString(R.string.location_needed)) +
                Row.Action(ACTION_PERMISSION, R.drawable.ic_my_location, "위치 권한 허용", accent = true)
            return
        }
        val forMode = mode
        loadJob = lifecycleScope.launch {
            val fix = Locator.current(this@DirectionsActivity)
            if (fix == null) {
                ui.rows = head() + Row.Note(getString(R.string.location_unavailable)) +
                    Row.Action(ACTION_RETRY, R.drawable.ic_refresh, "다시 시도")
                return@launch
            }
            val origin = fix.coord
            val age = Locator.ageMinutes(fix)
            originLabel = if (age >= 2) "${age}분 전 내 위치에서 출발" else "내 위치에서 출발"
            // 출발지가 바뀌었으면 저장해 둔 경로는 버린다
            val prev = RouteRepository.routeOrigin
            if (prev == null || distanceChanged(prev, origin)) {
                RouteRepository.transitRoutes = null
                RouteRepository.walkRoute = null
                RouteRepository.bikeRoute = null
                RouteRepository.routeOrigin = origin
            }
            when (forMode) {
                TravelMode.TRANSIT -> loadTransit(origin)
                TravelMode.WALK, TravelMode.BIKE -> loadSimple(origin, forMode)
            }
        }
    }

    private suspend fun loadTransit(origin: Coord) {
        val cached = RouteRepository.transitRoutes
        val result = if (cached != null) Result.success(cached) else withContext(Dispatchers.IO) {
            runCatching { KakaoApi.transit(origin, dest.coord, dest.name) }
        }
        result.onSuccess { routes ->
            RouteRepository.transitRoutes = routes
            val cards = routes.mapIndexed { i, r -> Row.TransitCard(i, r) }
            val tail = mutableListOf<Row>()
            if (!TagoApi.isConfigured) tail += Row.Note(getString(R.string.tago_missing))
            tail += Row.Note(getString(R.string.transit_note))
            ui.rows = head() + (if (routes.isEmpty()) listOf(Row.Note("대중교통 경로가 없어요")) else cards) + tail
            fetchLiveArrivals()
        }.onFailure { e -> showError(e) }
    }

    /** 각 경로 카드의 첫 버스에 대해 실시간 도착 정보를 붙인다 */
    private fun fetchLiveArrivals() {
        if (!TagoApi.isConfigured) return
        val routes = RouteRepository.transitRoutes ?: return
        routes.forEachIndexed { i, r ->
            val bus = r.steps.firstOrNull { it.type == TransitStepType.BUS } ?: return@forEachIndexed
            lifecycleScope.launch {
                val text = withContext(Dispatchers.IO) { BusArrivalService.describe(bus) } ?: return@launch
                val rows = ui.adapter.rows
                val pos = rows.indexOfFirst { it is Row.TransitCard && it.index == i }
                if (pos >= 0 && mode == TravelMode.TRANSIT) {
                    ui.adapter.replace(pos, (rows[pos] as Row.TransitCard).copy(live = text))
                }
            }
        }
    }

    private suspend fun loadSimple(origin: Coord, m: TravelMode) {
        val cached = if (m == TravelMode.WALK) RouteRepository.walkRoute else RouteRepository.bikeRoute
        val result = if (cached != null) Result.success(cached) else withContext(Dispatchers.IO) {
            runCatching {
                if (m == TravelMode.WALK) KakaoApi.walk(origin, dest.coord, dest.name)
                else KakaoApi.bike(origin, dest.coord, dest.name)
            }
        }
        result.onSuccess { route ->
            if (m == TravelMode.WALK) RouteRepository.walkRoute = route else RouteRepository.bikeRoute = route
            ui.rows = head() + simpleRows(route)
        }.onFailure { e -> showError(e) }
    }

    private fun simpleRows(route: SimpleRoute): List<Row> = listOf<Row>(
        Row.SimpleCard(route),
        Row.Action(ACTION_NAVIGATE, R.drawable.ic_navigation, getString(R.string.start_navigation), accent = true),
        Row.Action(ACTION_MAP, R.drawable.ic_map, getString(R.string.show_on_map)),
    ) + RouteRows.simpleSteps(route, dest.name)

    private fun showError(e: Throwable) {
        ui.rows = head() + Row.Note(e.message ?: "경로를 찾지 못했어요") +
            Row.Action(ACTION_RETRY, R.drawable.ic_refresh, "다시 시도")
    }

    private fun onRow(row: Row) {
        when (row) {
            is Row.ModeTabs -> if (row.selected != mode) {
                mode = row.selected
                getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY_MODE, mode.name) }
                load()
            }
            is Row.TransitCard -> startActivity(RouteDetailActivity.intent(this, row.index))
            is Row.Action -> when (row.id) {
                ACTION_RETRY -> {
                    if (mode == TravelMode.TRANSIT) RouteRepository.transitRoutes = null
                    load()
                }
                ACTION_PERMISSION -> permission.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                )
                ACTION_NAVIGATE, ACTION_MAP -> showSimpleOnMap(navigate = row.id == ACTION_NAVIGATE)
            }
            else -> Unit
        }
    }

    private fun showSimpleOnMap(navigate: Boolean) {
        val route = (if (mode == TravelMode.BIKE) RouteRepository.bikeRoute else RouteRepository.walkRoute) ?: return
        val origin = RouteRepository.routeOrigin ?: return
        RouteRepository.activeRoute = RoutePresentation.activeFromSimple(route, dest, origin, navigate)
        MainActivity.open(this)
    }

    private fun distanceChanged(a: Coord, b: Coord) =
        com.mywatch.kakaomap.model.Geo.distanceM(a, b) > 80.0

    companion object {
        private const val PREFS = "directions"
        private const val KEY_MODE = "mode"
        private const val ACTION_RETRY = "retry"
        private const val ACTION_PERMISSION = "permission"
        private const val ACTION_NAVIGATE = "navigate"
        private const val ACTION_MAP = "map"

        fun intent(context: Context) = Intent(context, DirectionsActivity::class.java)
    }
}
