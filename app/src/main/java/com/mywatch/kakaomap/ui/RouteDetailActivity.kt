package com.mywatch.kakaomap.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.mywatch.kakaomap.MainActivity
import com.mywatch.kakaomap.R
import com.mywatch.kakaomap.data.RoutePresentation
import com.mywatch.kakaomap.data.RouteRepository
import com.mywatch.kakaomap.model.Coord
import com.mywatch.kakaomap.model.Format
import com.mywatch.kakaomap.model.Place
import com.mywatch.kakaomap.model.SimpleRoute
import com.mywatch.kakaomap.model.TransitGuide
import com.mywatch.kakaomap.model.TransitRoute
import com.mywatch.kakaomap.model.TransitWalks
import com.mywatch.kakaomap.nav.NavigationService
import com.mywatch.kakaomap.nav.Navigator
import com.mywatch.kakaomap.net.BusArrivalService
import com.mywatch.kakaomap.net.TagoApi
import com.mywatch.kakaomap.net.WalkPaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 경로 상세.
 * · 길찾기 목록에서 연 대중교통 경로: 출발 → 도보 → 버스(실시간 도착) → 지하철 → … → 도착 타임라인 + 안내 시작/지도에서 보기
 * · 지도 아래 경로 카드에서 연 경우: 지금 지도에 띄운 '바로 그 경로'(대중교통·도보·자전거)의 상세 + 안내 시작/종료
 */
class RouteDetailActivity : ComponentActivity() {

    private lateinit var ui: WearList
    private lateinit var place: Place
    private var origin: Coord? = null
    private var transit: TransitRoute? = null
    private var simple: SimpleRoute? = null
    private var fromActive = false
    private var ambient: AmbientCover? = null
    /** 정류장까지·도착지까지 걷는 길(실제 길)을 받는 중 — 끝나면 시간·거리가 바뀌고 지도엔 그 길이 그려진다 */
    private var walks: Deferred<TransitRoute>? = null
    /** 받아 둔 실시간 도착 (구간 key → 글) — 상세를 다시 그려도 '실시간 도착 확인 중…'으로 돌아가지 않게 */
    private val liveTexts = HashMap<String, String>()
    /** 길찾기 목록에 있는 그 경로 객체 — 받아 둔 걷는 길을 이 경로 이름으로 기억한다 (반만 받아 둔 것을 다시 열어 마저 받아도) */
    private var listRoute: TransitRoute? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fromActive = intent.getBooleanExtra(EXTRA_ACTIVE, false)
        if (fromActive) {
            val a = RouteRepository.activeRoute
            if (a == null) {
                finish()
                return
            }
            place = a.place
            origin = a.origin
            transit = a.transit
            simple = a.simple
        } else {
            val r = RouteRepository.transitRoutes?.getOrNull(intent.getIntExtra(EXTRA_INDEX, 0))
            val d = RouteRepository.destination
            if (r == null || d == null) {
                finish()
                return
            }
            place = d
            origin = RouteRepository.routeOrigin
            // 전에 이 경로를 열어 걷는 길을 받아 뒀으면 그것 (다시 묻지 않음)
            listRoute = r
            transit = RouteRepository.walked(r)
        }
        if (transit == null && simple == null) {
            finish()
            return
        }
        setContentView(R.layout.activity_list)
        ui = WearList(this, ::onRow)
        ui.rows = buildRows()
        if (!fromActive) startWalks()
        // 손목을 내리면 검은 화면에 요약만 (Wear OS 6+ 는 이 화면을 켜 둔 채 둔다), 그동안 새로 고침은 쉰다
        ambient = AmbientCover(this, ::ambientSummary) { inAmbient ->
            if (!inAmbient && transit != null && TagoApi.isConfigured &&
                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            ) lifecycleScope.launch { refreshLive() }
        }

        // 화면을 보는 동안 30초마다 실시간 버스 도착 정보를 새로 고침 (손목을 내린 동안은 쉼)
        if (transit != null && TagoApi.isConfigured) lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    if (ambient?.inAmbient != true) refreshLive()
                    delay(30_000)
                }
            }
        }
    }

    /**
     * 카카오맵처럼 정류장까지 걷는 길을 실제 길로 받아 둔다(화면을 보는 몇 초 동안) — 받으면 걷는 시간·거리를 고쳐 보여 주고,
     * 받은 것을 기억해 둬([RouteRepository.walked]) 다시 열거나 지도에 띄울 때 또 묻지 않는다.
     * 길을 받자마자 끝난다 — '안내 시작'은 이것만 기다린다 (실시간 도착은 따로 새로 고침, 받아 둔 글은 그대로 둠)
     */
    private fun startWalks() {
        val original = transit ?: return
        if (TransitWalks.pending(original).isEmpty()) return
        walks = lifecycleScope.async {
            val r = WalkPaths.enrich(original, timeoutMs = WALKS_TIMEOUT_MS)
            if (r !== original && transit === original) {
                transit = r
                RouteRepository.rememberWalked(listRoute ?: original, r)
                // '경로 준비 중…'을 띄운 사이엔 다시 그리지 않는다 (기다림이 끝나면 그쪽에서 그림)
                if (!opening) ui.rows = buildRows()
            }
            r
        }
    }

    private fun ambientSummary(): String {
        val t = transit
        val s = simple
        return when {
            t != null -> t.secondsFrom(System.currentTimeMillis()).let { sec ->
                "→ ${place.name}\n${Format.duration(sec)} · ${Format.clockAfter(sec)} 도착"
            }
            s != null -> "→ ${place.name}\n${RoutePresentation.simpleSummary(s)}"
            else -> "→ ${place.name}"
        }
    }

    private fun buildRows(): List<Row> {
        val rows = mutableListOf<Row>()
        val t = transit
        val s = simple
        if (t != null) {
            // 기차·버스 시간표 경로는 도착 시각이 정해져 있다 — 지금 기준 남은 시간
            val sec = t.secondsFrom(System.currentTimeMillis())
            rows += Row.Header(
                "${Format.duration(sec)} · ${Format.clockAfter(sec)} 도착",
                listOfNotNull(Format.fare(t), Format.transfers(t.transfers), Format.distance(t.totalDistanceM))
                    .joinToString(" · "),
            )
        } else if (s != null) {
            rows += Row.Header("→ ${place.name}", RoutePresentation.simpleSummary(s))
        }
        rows += actions()
        if (t != null) {
            rows += RouteRows.transitTimeline(t, place.name, TagoApi.isConfigured).map { row ->
                val live = (row as? Row.Step)?.key?.let(liveTexts::get)
                if (live != null) (row as Row.Step).copy(live = live) else row
            }
            if (!TagoApi.isConfigured) rows += Row.Note(getString(R.string.tago_missing))
        } else if (s != null) {
            rows += RouteRows.simpleSteps(s, place.name)
        }
        return rows
    }

    private fun actions(): List<Row> {
        val active = RouteRepository.activeRoute
        if (!fromActive) {
            return listOf(
                Row.Action(ACTION_NAVIGATE, R.drawable.ic_navigation, getString(R.string.start_navigation), accent = true),
                Row.Action(ACTION_MAP, R.drawable.ic_map, getString(R.string.show_on_map)),
                Row.Note(getString(R.string.transit_nav_note)),
            )
        }
        val list = mutableListOf<Row>()
        if (active != null && !active.startNavigation) {
            list += Row.Action(ACTION_NAVIGATE, R.drawable.ic_navigation, getString(R.string.start_navigation), accent = true)
        }
        list += Row.Action(ACTION_BACK, R.drawable.ic_map, getString(R.string.back_to_map))
        list += Row.Action(ACTION_END, R.drawable.ic_close, getString(R.string.end_route))
        return list
    }

    private suspend fun refreshLive() {
        val t = transit ?: return
        // 기차·고속버스 경로는 타러 가는 버스만 (내려서 탈 버스는 몇 시간 뒤라 지금 도착 정보는 엉뚱함)
        TransitGuide.liveBusIndexes(t).forEach { i ->
            val s = t.steps[i]
            val text = withContext(Dispatchers.IO) { BusArrivalService.describe(s) } ?: return@forEach
            liveTexts["s$i"] = text
            val rows = ui.adapter.rows
            val pos = rows.indexOfFirst { it is Row.Step && it.key == "s$i" }
            if (pos >= 0) ui.adapter.replace(pos, (rows[pos] as Row.Step).copy(live = text))
        }
    }

    private fun onRow(row: Row) {
        if (row !is Row.Action) return
        when (row.id) {
            ACTION_NAVIGATE, ACTION_MAP -> show(navigate = row.id == ACTION_NAVIGATE)
            ACTION_BACK -> MainActivity.open(this)
            ACTION_END -> {
                RouteRepository.activeRoute = null
                Navigator.sync()
                NavigationService.stop(this)
                MainActivity.open(this)
            }
        }
    }

    /** 이 경로를 지도에 띄운다 (navigate = 화면이 꺼져도 따라가며 진동 안내) */
    private fun show(navigate: Boolean) {
        val active = RouteRepository.activeRoute
        if (fromActive && active != null) {
            RouteRepository.activeRoute = active.copy(startNavigation = navigate || active.startNavigation)
            MainActivity.open(this)
            return
        }
        val o = origin ?: return
        val pending = walks
        if (pending == null || pending.isCompleted) {
            val t = transit ?: return
            RouteRepository.activeRoute = RoutePresentation.activeFromTransit(t, place, o, navigate)
            MainActivity.open(this)
            return
        }
        // 걷는 길을 아직 받는 중이면 잠깐만 기다린다 (못 받으면 직선으로 바로 시작)
        if (opening) return
        opening = true
        ui.showStatus(getString(R.string.preparing_route))
        lifecycleScope.launch {
            val got = try {
                withTimeoutOrNull(WALKS_WAIT_MS) { pending.await() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            opening = false
            // '경로 준비 중…'을 걷어 둔다 — 지도가 이 화면을 닫지 않고 위에 열려도(타일로 연 검색에서 온 경우) 돌아오면 상세가 보이게
            ui.rows = buildRows()
            val t = got ?: transit ?: return@launch
            RouteRepository.activeRoute = RoutePresentation.activeFromTransit(t, place, o, navigate)
            MainActivity.open(this@RouteDetailActivity)
        }
    }

    /** 안내 시작을 두 번 눌러 두 번 띄우지 않게 */
    private var opening = false

    companion object {
        private const val EXTRA_INDEX = "index"
        private const val EXTRA_ACTIVE = "active"
        private const val ACTION_MAP = "map"
        private const val ACTION_NAVIGATE = "navigate"
        private const val ACTION_BACK = "back"
        private const val ACTION_END = "end"
        /** 걷는 길을 받는 데 쓰는 최대 시간 (상세를 연 때부터) */
        private const val WALKS_TIMEOUT_MS = 3_000L
        /**
         * '안내 시작'을 누른 뒤 걷는 길을 기다리는 최대 시간 — 받는 시간보다 조금 길게 잡아, 받은 길이 안내에 늘 들어가게
         * (누른 뒤 늦게 와서 지도엔 직선으로 남는 일이 없게). 보통은 상세를 보는 동안 이미 받아 있어 기다리지 않는다
         */
        private const val WALKS_WAIT_MS = WALKS_TIMEOUT_MS + 500L

        /** 길찾기 목록의 n번째 대중교통 경로 */
        fun intent(context: Context, index: Int) =
            Intent(context, RouteDetailActivity::class.java).putExtra(EXTRA_INDEX, index)

        /** 지금 지도에 띄운 경로 */
        fun intentActive(context: Context) =
            Intent(context, RouteDetailActivity::class.java).putExtra(EXTRA_ACTIVE, true)
    }
}
