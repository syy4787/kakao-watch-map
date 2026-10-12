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
import com.mywatch.kakaomap.model.TransitFilter
import com.mywatch.kakaomap.model.TransitOptions
import com.mywatch.kakaomap.model.TransitSort
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
    /** 대중교통 거르기(검색마다 전체로 시작)·정렬(기억) */
    private var filter = TransitFilter.ALL
    private var sort = TransitSort.RECOMMENDED
    /** 경로 번호 → 실시간 첫 버스 도착 (거르기·정렬을 바꿔도 다시 받지 않게) */
    private val liveTexts = mutableMapOf<Int, String>()

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
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        mode = runCatching { TravelMode.valueOf(prefs.getString(KEY_MODE, null) ?: "") }.getOrDefault(TravelMode.TRANSIT)
        sort = runCatching { TransitSort.valueOf(prefs.getString(KEY_SORT, null) ?: "") }.getOrDefault(TransitSort.RECOMMENDED)
        setContentView(R.layout.activity_list)
        ui = WearList(this, ::onRow)
        load()
    }

    override fun onResume() {
        super.onResume()
        // 그 사이 다른 목적지로 길찾기를 했으면(타일의 '집으로' 등) 이 화면의 경로 번호가 새 목록과 어긋나므로 닫는다
        if (RouteRepository.destination !== dest) {
            finish()
            return
        }
        // 상세 화면 등에서 돌아오면 실시간 버스 도착을 새로 (30초 안에 받은 것은 저장해 둔 값을 씀)
        if (mode == TravelMode.TRANSIT && liveTexts.isNotEmpty()) fetchLiveArrivals()
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
            val fresh = RouteRepository.transitRoutes !== routes
            RouteRepository.transitRoutes = routes
            if (fresh) liveTexts.clear()
            renderTransit()
            if (fresh || liveTexts.isEmpty()) fetchLiveArrivals()
        }.onFailure { e -> showError(e) }
    }

    /** 경로 카드 목록: 거르기·정렬 칩 + (거르고 정렬한) 카드. 카드 번호는 원래 목록의 번호 */
    private fun renderTransit() {
        val routes = RouteRepository.transitRoutes ?: return
        val choices = TransitOptions.filters(routes)
        filter = TransitOptions.valid(filter, choices.map { it.first })
        val shown = TransitOptions.apply(routes, filter, sort)
        val body = mutableListOf<Row>()
        if (routes.isEmpty()) {
            body += Row.Note("대중교통 경로가 없어요")
        } else {
            if (routes.size >= 2) {
                body += Row.TransitChoice(
                    filter = filter,
                    filterCount = choices.firstOrNull { it.first == filter }?.second ?: shown.size,
                    canFilter = choices.size >= 2,
                    sort = sort,
                )
            }
            body += shown.map { Row.TransitCard(it.index, it.value, liveTexts[it.index]) }
        }
        if (!TagoApi.isConfigured) body += Row.Note(getString(R.string.tago_missing))
        body += Row.Note(getString(R.string.transit_note))
        ui.rows = head() + body
    }

    /** 각 경로 카드의 첫 버스에 대해 실시간 도착 정보를 붙인다 */
    private fun fetchLiveArrivals() {
        if (!TagoApi.isConfigured) return
        val routes = RouteRepository.transitRoutes ?: return
        routes.forEachIndexed { i, r ->
            val bus = r.steps.firstOrNull { it.type == TransitStepType.BUS } ?: return@forEachIndexed
            lifecycleScope.launch {
                val text = withContext(Dispatchers.IO) { BusArrivalService.describe(bus) } ?: return@launch
                if (RouteRepository.transitRoutes !== routes) return@launch
                liveTexts[i] = text
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
            is Row.TransitChoice -> {
                val routes = RouteRepository.transitRoutes ?: return
                when (row.tapped) {
                    Row.TransitChoice.FILTER ->
                        filter = TransitOptions.next(filter, TransitOptions.filters(routes).map { it.first })
                    Row.TransitChoice.SORT -> {
                        sort = sort.next()
                        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY_SORT, sort.name) }
                    }
                }
                renderTransit()   // 칩이 손가락 아래 그대로 있게 스크롤은 그대로 둔다
            }
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
        private const val KEY_SORT = "transit_sort"
        private const val ACTION_RETRY = "retry"
        private const val ACTION_PERMISSION = "permission"
        private const val ACTION_NAVIGATE = "navigate"
        private const val ACTION_MAP = "map"

        fun intent(context: Context) = Intent(context, DirectionsActivity::class.java)
    }
}
