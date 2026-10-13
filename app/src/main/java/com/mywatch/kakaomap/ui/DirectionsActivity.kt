package com.mywatch.kakaomap.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import com.mywatch.kakaomap.MainActivity
import com.mywatch.kakaomap.R
import com.mywatch.kakaomap.data.ErrorLog
import com.mywatch.kakaomap.data.RoutePresentation
import com.mywatch.kakaomap.data.RouteRepository
import com.mywatch.kakaomap.location.Locator
import com.mywatch.kakaomap.location.PermissionHelp
import com.mywatch.kakaomap.model.Coord
import com.mywatch.kakaomap.model.Geo
import com.mywatch.kakaomap.model.Place
import com.mywatch.kakaomap.model.RouteChoice
import com.mywatch.kakaomap.model.RouteChoices
import com.mywatch.kakaomap.model.RouteOption
import com.mywatch.kakaomap.model.RouteStatusException
import com.mywatch.kakaomap.model.TransitFilter
import com.mywatch.kakaomap.model.TransitOptions
import com.mywatch.kakaomap.model.TransitRoute
import com.mywatch.kakaomap.model.TransitSort
import com.mywatch.kakaomap.model.TransitStepType
import com.mywatch.kakaomap.model.TravelMode
import com.mywatch.kakaomap.net.BusArrivalService
import com.mywatch.kakaomap.net.KakaoApi
import com.mywatch.kakaomap.net.TagoApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 카카오맵 길찾기 결과 화면.
 * 위쪽 이동수단 탭(대중교통·도보·자전거)에는 탭마다 걸리는 시간을, 가장 빨리 도착하는 수단은 초록으로 적는다.
 * 대중교통은 경로 카드(가장 빠름 표시·시간·도착 시각·요금·환승·구간 막대·실시간 버스)를,
 * 도보·자전거는 카카오맵의 경로 옵션 카드(큰길 우선·최단 거리·편안한 길 / 자전거도로 우선·최단 거리·편안한 길)와
 * 고른 길의 안내 시작 + 단계별 안내를 보여 준다. 처음엔 가장 빠른 길을 고르고, 다른 카드를 누르면 다음에도 그것.
 *
 * 보고 있는 탭을 먼저 찾고, 끝나면 다른 탭도 받아 시간을 적는다 (너무 먼 도보·자전거는 그 탭을 열 때만).
 */
class DirectionsActivity : ComponentActivity() {

    private lateinit var ui: WearList
    private lateinit var dest: Place
    private var mode = TravelMode.TRANSIT
    /** 위치 잡기 → 보고 있는 탭 → 나머지 탭 */
    private var loadJob: Job? = null
    /** 수단별로 진행 중인 경로 요청 */
    private val fetches = mutableMapOf<TravelMode, Job>()
    /** 수단별 실패 문구 ('다시 시도' 전까지 다시 묻지 않음) */
    private val errors = mutableMapOf<TravelMode, String>()
    /** 도보·자전거에서 이번에 받지 못한 경로 옵션 ('다시 받기'로) */
    private val missing = mutableMapOf<TravelMode, List<RouteOption>>()
    /** 받지 못한 옵션을 다시 받는 중인 수단 */
    private val retrying = mutableSetOf<TravelMode>()
    /** 이번에 쓰는 출발지 (위치를 잡기 전엔 null) */
    private var origin: Coord? = null
    /** 위치 권한이 없거나 위치를 못 잡았을 때 보여 줄 줄 */
    private var blocked: List<Row>? = null
    /** 출발지 설명 — 새로 잡은 위치면 '내 위치', 몇 분 지난 위치면 그렇게 알린다 */
    private var originLabel = "내 위치에서 출발"
    /** 대중교통 거르기(검색마다 전체로 시작)·정렬(기억, 처음엔 최소시간) */
    private var filter = TransitFilter.ALL
    private var sort = TransitSort.DEFAULT
    /** 도보·자전거에서 고른 경로 옵션 (없으면 가장 빠른 길) */
    private val remembered = mutableMapOf<TravelMode, RouteOption?>()
    /** 경로 번호 → 실시간 첫 버스 도착 (거르기·정렬을 바꿔도 다시 받지 않게) */
    private val liveTexts = mutableMapOf<Int, String>()

    /** 위치 권한 창을 띄운 시각 — 곧바로 거절이 오면 시스템이 창을 안 띄운 것(두 번 거절해 막힘) */
    private var permissionAskedAt = 0L
    /** 권한 창이 더는 안 뜨는 상태 → 버튼이 앱 설정(권한) 화면을 연다 */
    private var permissionBlocked = false

    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (!Locator.hasPermission(this) &&
            PermissionHelp.deniedWithoutDialog(permissionAskedAt, SystemClock.elapsedRealtime())
        ) permissionBlocked = true
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
        val prefs = prefs()
        mode = runCatching { TravelMode.valueOf(prefs.getString(KEY_MODE, null) ?: "") }.getOrDefault(TravelMode.TRANSIT)
        sort = TransitSort.saved(prefs.getString(KEY_SORT, null))
        for (m in SIMPLE_MODES) {
            val name = prefs.getString(optionKey(m), null)
            remembered[m] = RouteOption.of(m).firstOrNull { it.name == name }
        }
        setContentView(R.layout.activity_list)
        ui = WearList(this, ::onRow)
        // 손목을 내리면 검은 화면에 목적지만 (Wear OS 6+ 는 이 화면을 켜 둔 채 둔다).
        // 손목을 올리면(화면은 계속 '재개' 상태라 onResume 이 안 옴) 실시간 버스를 새로
        AmbientCover(this, { "→ ${dest.name}" }) { inAmbient ->
            if (!inAmbient && mode == TravelMode.TRANSIT && RouteRepository.transitRoutes != null) fetchLiveArrivals()
        }
        load()
    }

    override fun onResume() {
        super.onResume()
        // 그 사이 다른 목적지로 길찾기를 했으면(타일의 '집으로' 등) 이 화면의 경로 번호가 새 목록과 어긋나므로 닫는다
        if (RouteRepository.destination !== dest) {
            finish()
            return
        }
        // 설정 화면에서 위치 권한을 켜고 돌아왔으면 바로 찾는다
        if (blocked != null && origin == null && loadJob?.isActive != true && Locator.hasPermission(this)) {
            load()
            return
        }
        // 상세 화면 등에서 돌아오면 실시간 버스 도착을 새로 (30초 안에 받은 것은 저장해 둔 값을 씀)
        if (mode == TravelMode.TRANSIT && liveTexts.isNotEmpty()) fetchLiveArrivals()
    }

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun modeName(m: TravelMode) = getString(
        when (m) {
            TravelMode.TRANSIT -> R.string.mode_transit
            TravelMode.WALK -> R.string.mode_walk
            TravelMode.BIKE -> R.string.mode_bike
        }
    )

    // ───────── 경로 받기 ─────────

    private fun load() {
        loadJob?.cancel()
        fetches.values.forEach { it.cancel() }
        fetches.clear()
        retrying.clear()
        errors.clear()
        origin = null
        blocked = null
        if (!Locator.hasPermission(this)) {
            // 두 번 거절해 권한 창이 더는 안 뜨면 같은 버튼이 앱 설정(권한) 화면을 연다
            val settings = permissionBlocked
            blocked = listOf(
                Row.Note(getString(if (settings) R.string.location_blocked else R.string.location_needed)),
                Row.Action(ACTION_PERMISSION, R.drawable.ic_my_location, if (settings) "설정에서 위치 허용" else "위치 권한 허용", accent = true),
            )
            render()
            return
        }
        render()   // 경로 찾는 중…
        loadJob = lifecycleScope.launch {
            val fix = Locator.current(this@DirectionsActivity)
            if (fix == null) {
                ErrorLog.record("위치", "길찾기에 쓸 현재 위치를 잡지 못함")
                blocked = listOf(
                    Row.Note(getString(R.string.location_unavailable)),
                    Row.Action(ACTION_RETRY, R.drawable.ic_refresh, "다시 시도"),
                )
                render()
                return@launch
            }
            val age = Locator.ageMinutes(fix)
            originLabel = if (age >= 2) "${age}분 전 내 위치에서 출발" else "내 위치에서 출발"
            // 출발지가 바뀌었으면 저장해 둔 경로는 버린다 (80m 안이면 전에 찾은 경로·출발지를 그대로)
            val prev = RouteRepository.routeOrigin
            if (prev == null || Geo.distanceM(prev, fix.coord) > 80.0) {
                RouteRepository.clearRoutes()
                RouteRepository.routeOrigin = fix.coord
                liveTexts.clear()
                missing.clear()
            }
            val from = RouteRepository.routeOrigin ?: fix.coord
            origin = from
            render()
            // 전에 찾아 둔 대중교통 경로를 그대로 보여 줄 때도 실시간 버스는 새로
            if (mode == TravelMode.TRANSIT && RouteRepository.transitRoutes != null && liveTexts.isEmpty()) fetchLiveArrivals()
            fetch(mode, from)?.join()   // 보고 있는 탭 먼저 (그동안 다른 탭을 누르면 그 탭도 바로)
            val straightM = Geo.distanceM(from, dest.coord).toInt()
            TravelMode.entries.filter { RouteChoices.worthPrefetching(it, straightM) }.forEach { fetch(it, from) }
        }
    }

    private fun hasRoutes(m: TravelMode) =
        if (m == TravelMode.TRANSIT) RouteRepository.transitRoutes != null else RouteRepository.choices(m) != null

    /** [m] 의 경로를 찾는다. 이미 있거나 찾는 중이거나 실패해 있으면 그대로 */
    private fun fetch(m: TravelMode, from: Coord): Job? {
        if (hasRoutes(m) || m in errors) return null
        fetches[m]?.let { if (it.isActive) return it }
        val job = if (m == TravelMode.TRANSIT) {
            launchFetch(m, from, { KakaoApi.transit(from, dest.coord, dest.name) }) { routes ->
                RouteRepository.transitRoutes = routes
                liveTexts.clear()
            }
        } else {
            launchFetch(m, from, { simpleChoices(m, from) }) { got ->
                RouteRepository.setChoices(m, got.choices)
                missing[m] = got.missing
            }
        }
        fetches[m] = job
        return job
    }

    private fun <T> launchFetch(m: TravelMode, from: Coord, request: suspend () -> T, store: (T) -> Unit): Job =
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { request() } }
            // 그 사이 목적지·출발지가 바뀌었으면 버린다
            if (RouteRepository.destination !== dest || RouteRepository.routeOrigin != from) return@launch
            result.onSuccess(store).onFailure { e ->
                if (e is CancellationException) throw e
                errors[m] = e.message ?: "경로를 찾지 못했어요"
                // '길이 없어요'(너무 가까움·너무 멂 등)는 오류가 아니라 답이므로 기록하지 않는다
                if (e !is RouteStatusException) ErrorLog.record("길찾기·${modeName(m)}", e)
            }
            if (m == mode) {
                render()
                if (m == TravelMode.TRANSIT && result.isSuccess) fetchLiveArrivals()
            } else {
                refreshTabs()
            }
        }

    /** 옵션별로 받은 결과: 카드 + 이번에 받지 못한 옵션(잠깐 끊김 등 — 다시 받아 볼 만한 것) */
    private class SimpleResult(val choices: List<RouteChoice>, val missing: List<RouteOption>)

    /**
     * 카카오맵의 세 가지 경로 옵션을 한꺼번에 받아 같은 길은 한 카드로. 하나라도 받으면 성공.
     * '길이 없어요' 답이 아닌 실패는 한 번만 다시 묻고, 그래도 못 받으면 [SimpleResult.missing] 으로 알린다.
     */
    private suspend fun simpleChoices(m: TravelMode, from: Coord): SimpleResult = coroutineScope {
        val options = RouteOption.of(m)
        fun ask(o: RouteOption) = runCatching { KakaoApi.simple(m, from, dest.coord, dest.name, o) }
        fun Result<*>.retryable() = isFailure && exceptionOrNull() !is RouteStatusException
        val first = options.map { o -> async { ask(o) } }.awaitAll()
        val results = options.zip(first).map { (o, r) ->
            async {
                if (r.retryable()) {
                    delay(RETRY_DELAY_MS)
                    ask(o)
                } else r
            }
        }.awaitAll()
        val ok = options.zip(results).mapNotNull { (o, r) -> r.getOrNull()?.let { o to it } }
        if (ok.isEmpty()) throw results.firstNotNullOf { it.exceptionOrNull() }
        SimpleResult(RouteChoices.merge(ok), options.zip(results).filter { (_, r) -> r.retryable() }.map { it.first })
    }

    /**
     * 받지 못한 옵션만 다시 받아 지금 카드에 더한다. 또 실패해도 이미 받은 카드는 그대로 두고 '다시 받기'를 다시 보여 준다.
     */
    private fun retryMissing(m: TravelMode, from: Coord) {
        val lost = missing[m].orEmpty()
        if (lost.isEmpty() || fetches[m]?.isActive == true) return
        retrying += m
        render()
        fetches[m] = lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                lost.map { o -> async { o to runCatching { KakaoApi.simple(m, from, dest.coord, dest.name, o) } } }.awaitAll()
            }
            retrying -= m
            if (RouteRepository.destination !== dest || RouteRepository.routeOrigin != from) return@launch
            val got = results.mapNotNull { (o, r) -> r.getOrNull()?.let { o to it } }
            results.mapNotNull { it.second.exceptionOrNull() }
                .firstOrNull { it !is RouteStatusException }
                ?.let { ErrorLog.record("길찾기·${modeName(m)}", it) }
            val current = RouteRepository.choices(m).orEmpty()
            // 이미 받은 카드의 (옵션, 길) + 새로 받은 것을 카카오 순서대로 다시 합친다
            val pairs = (current.flatMap { c -> c.options.map { it to c.route } } + got).sortedBy { it.first.ordinal }
            if (pairs.isNotEmpty()) RouteRepository.setChoices(m, RouteChoices.merge(pairs))
            missing[m] = results.filter { (_, r) -> r.isFailure && r.exceptionOrNull() !is RouteStatusException }.map { it.first }
            if (m == mode) render() else refreshTabs()
        }
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

    // ───────── 화면 ─────────

    private fun render() {
        ui.rows = listOf(Row.Header("→ ${dest.name}", originLabel), tabs()) + body()
    }

    /** 다른 탭의 경로가 도착하면 탭(시간·가장 빠름)만 바꾼다 — 보고 있는 목록은 그대로 */
    private fun refreshTabs() {
        val i = ui.adapter.rows.indexOfFirst { it is Row.ModeTabs }
        if (i >= 0) ui.adapter.replace(i, tabs())
    }

    private fun tabs(): Row.ModeTabs {
        val seconds = mutableMapOf<TravelMode, Int>()
        if (origin != null && blocked == null) {
            RouteRepository.transitRoutes?.let { r ->
                TransitOptions.fastestIndex(r)?.let { seconds[TravelMode.TRANSIT] = r[it].totalTimeSec }
            }
            for (m in SIMPLE_MODES) {
                RouteChoices.seconds(RouteRepository.choices(m), remembered[m])?.let { seconds[m] = it }
            }
        }
        return Row.ModeTabs(mode, seconds, RouteChoices.fastestMode(seconds.toMap()))
    }

    private fun body(): List<Row> {
        blocked?.let { return it }
        if (origin == null) return listOf(Row.Note(getString(R.string.routing)))
        errors[mode]?.let { return listOf(Row.Note(it), Row.Action(ACTION_RETRY, R.drawable.ic_refresh, "다시 시도")) }
        val rows = when (mode) {
            TravelMode.TRANSIT -> RouteRepository.transitRoutes?.let(::transitRows)
            TravelMode.WALK, TravelMode.BIKE -> RouteRepository.choices(mode)?.let { simpleRows(mode, it) }
        }
        return rows ?: listOf(Row.Note(getString(R.string.routing)))
    }

    /** 경로 카드 목록: 거르기·정렬 칩 + (거르고 정렬한) 카드. 카드 번호는 원래 목록의 번호 */
    private fun transitRows(routes: List<TransitRoute>): List<Row> {
        val choices = TransitOptions.filters(routes)
        filter = TransitOptions.valid(filter, choices.map { it.first })
        val shown = TransitOptions.apply(routes, filter, sort)
        val fastest = if (routes.size >= 2) TransitOptions.fastestIndex(routes) else null
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
            body += shown.map { Row.TransitCard(it.index, it.value, liveTexts[it.index], fastest = it.index == fastest) }
        }
        if (!TagoApi.isConfigured) body += Row.Note(getString(R.string.tago_missing))
        body += Row.Note(getString(R.string.transit_note))
        return body
    }

    /** 도보·자전거: 옵션 카드(고른 것·가장 빠름 표시) + 안내 시작 + 지도 + 고른 길의 단계 */
    private fun simpleRows(m: TravelMode, choices: List<RouteChoice>): List<Row> {
        val picked = RouteChoices.pick(choices, remembered[m]) ?: return listOf(Row.Note("경로가 없어요"))
        val fastest = if (choices.size >= 2) RouteChoices.fastest(choices) else null
        val lost = missing[m].orEmpty()
        val names = lost.joinToString(" · ") { it.label }
        val retry = when {
            lost.isEmpty() -> emptyList()
            m in retrying -> listOf(Row.Note("$names 경로 다시 받는 중…"))
            else -> listOf(
                Row.Note("$names 경로를 받지 못했어요"),
                Row.Action(ACTION_RETRY_OPTIONS, R.drawable.ic_refresh, "다시 받기"),
            )
        }
        return choices.mapIndexed { i, c -> Row.OptionCard(i, c, selected = c == picked, fastest = c == fastest) } + retry +
            Row.Action(ACTION_NAVIGATE, R.drawable.ic_navigation, getString(R.string.start_navigation), accent = true) +
            Row.Action(ACTION_MAP, R.drawable.ic_map, getString(R.string.show_on_map)) +
            RouteRows.simpleSteps(picked.route, dest.name)
    }

    // ───────── 누르기 ─────────

    private fun onRow(row: Row) {
        when (row) {
            is Row.ModeTabs -> if (row.tapped != mode) {
                mode = row.tapped
                prefs().edit { putString(KEY_MODE, mode.name) }
                // 뒤에서 미리 받다 실패한 탭(잠깐 끊김 등)은 열 때 한 번 더, 멀어서 미리 받지 않은 도보·자전거는 지금
                origin?.let { from ->
                    errors.remove(mode)
                    fetch(mode, from)
                }
                render()
                if (mode == TravelMode.TRANSIT && liveTexts.isEmpty()) fetchLiveArrivals()
            }
            is Row.TransitCard -> startActivity(RouteDetailActivity.intent(this, row.index))
            is Row.TransitChoice -> {
                val routes = RouteRepository.transitRoutes ?: return
                when (row.tapped) {
                    Row.TransitChoice.FILTER ->
                        filter = TransitOptions.next(filter, TransitOptions.filters(routes).map { it.first })
                    Row.TransitChoice.SORT -> {
                        sort = sort.next()
                        prefs().edit { putString(KEY_SORT, sort.name) }
                    }
                }
                render()   // 칩이 손가락 아래 그대로 있게 스크롤은 그대로 둔다
            }
            is Row.OptionCard -> {
                val choices = RouteRepository.choices(mode) ?: return
                if (row.choice !in choices) return
                val keep = RouteChoices.rememberAfterTap(row.choice, choices, remembered[mode])
                remembered[mode] = keep
                prefs().edit { if (keep == null) remove(optionKey(mode)) else putString(optionKey(mode), keep.name) }
                render()
            }
            is Row.Action -> when (row.id) {
                ACTION_RETRY -> {
                    val from = origin
                    errors.remove(mode)
                    if (blocked != null || from == null) load() else {
                        fetch(mode, from)
                        render()
                    }
                }
                ACTION_RETRY_OPTIONS -> origin?.let { retryMissing(mode, it) }
                ACTION_PERMISSION -> if (permissionBlocked) {
                    PermissionHelp.openAppSettings(this)   // 돌아오면 onResume 에서 다시 찾는다
                } else {
                    PermissionHelp.markAskedLocation(this)
                    permissionAskedAt = SystemClock.elapsedRealtime()
                    permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }
                ACTION_NAVIGATE, ACTION_MAP -> showSimpleOnMap(navigate = row.id == ACTION_NAVIGATE)
            }
            else -> Unit
        }
    }

    private fun showSimpleOnMap(navigate: Boolean) {
        val choices = RouteRepository.choices(mode) ?: return
        val picked = RouteChoices.pick(choices, remembered[mode]) ?: return
        // 같은 길로 합쳐진 카드면 사용자가 고른 옵션(편안한 길 등)으로 — 경로 이탈 재탐색도 그 옵션으로
        val route = picked.route.copy(option = RouteChoices.optionFor(picked, remembered[mode]))
        val from = RouteRepository.routeOrigin ?: return
        RouteRepository.activeRoute = RoutePresentation.activeFromSimple(route, dest, from, navigate)
        MainActivity.open(this)
    }

    companion object {
        private const val PREFS = "directions"
        private const val KEY_MODE = "mode"
        private const val KEY_SORT = "transit_sort"
        private const val ACTION_RETRY = "retry"
        private const val ACTION_PERMISSION = "permission"
        private const val ACTION_NAVIGATE = "navigate"
        private const val ACTION_MAP = "map"
        private const val ACTION_RETRY_OPTIONS = "retry_options"
        private const val RETRY_DELAY_MS = 400L
        private val SIMPLE_MODES = listOf(TravelMode.WALK, TravelMode.BIKE)

        private fun optionKey(m: TravelMode) = "option_${m.name}"

        fun intent(context: Context) = Intent(context, DirectionsActivity::class.java)
    }
}
