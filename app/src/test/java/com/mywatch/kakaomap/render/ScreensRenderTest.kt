package com.mywatch.kakaomap.render

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.activity.ComponentActivity
import com.mywatch.kakaomap.R
import com.mywatch.kakaomap.model.Coord
import com.mywatch.kakaomap.model.PathStep
import com.mywatch.kakaomap.model.RouteChoices
import com.mywatch.kakaomap.model.RouteOption
import com.mywatch.kakaomap.model.SimpleRoute
import com.mywatch.kakaomap.model.TransitFilter
import com.mywatch.kakaomap.model.TransitRoute
import com.mywatch.kakaomap.model.TransitSort
import com.mywatch.kakaomap.model.TransitStep
import com.mywatch.kakaomap.model.TransitStepType
import com.mywatch.kakaomap.model.TravelMode
import com.mywatch.kakaomap.model.Vehicle
import com.mywatch.kakaomap.ui.Row
import com.mywatch.kakaomap.ui.RouteRows
import com.mywatch.kakaomap.ui.WearList
import com.mywatch.kakaomap.ui.TubeRows
import com.mywatch.kakaomap.ui.HubActivity
import com.mywatch.kakaomap.ui.TubeThumbs
import com.mywatch.kakaomap.tube.Played
import com.mywatch.kakaomap.tube.TubeSource
import com.mywatch.kakaomap.tube.TubeVideo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import android.os.Looper
import android.view.Window
import java.io.File
import java.io.FileOutputStream

/**
 * 워치9 44mm(480×480 원형, 밀도 2.125 ≈ 340dpi) 크기로 목록 화면을 실제 안드로이드 레이아웃으로 그려 PNG 로 남긴다.
 * 글자 넘침·잘림을 눈으로 확인하는 용도 — ./gradlew testDebugUnitTest -Prender, 결과는 app/build/render/
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ko-rKR-w226dp-h226dp-round-340dpi", application = Application::class)
class ScreensRenderTest {

    private val out = File("build/render").apply { mkdirs() }

    private fun route(mode: TravelMode, m: Int, s: Int, vararg names: String) = SimpleRoute(
        mode, m, s,
        names.mapIndexed { i, n ->
            PathStep(n, m / names.size, s / names.size, Coord(36.35 + i * 0.001, 127.38), listOf(Coord(36.35 + i * 0.001, 127.38), Coord(36.351 + i * 0.001, 127.381)))
        },
    )

    private val bikeChoices = RouteChoices.merge(listOf(
        RouteOption.BIKE_ROAD to route(TravelMode.BIKE, 4384, 996, "출발", "우회전", "자전거도로 진입", "좌회전"),
        RouteOption.BIKE_SHORTEST to route(TravelMode.BIKE, 3104, 781, "출발", "좌회전", "직진"),
        RouteOption.BIKE_COMFORT to route(TravelMode.BIKE, 3220, 799, "출발", "우회전", "직진"),
    ))

    private val walkChoices = RouteChoices.merge(listOf(
        RouteOption.WALK_BROAD to route(TravelMode.WALK, 5538, 5115, "출발", "횡단보도", "우회전"),
        RouteOption.WALK_COMFORT to route(TravelMode.WALK, 5538, 5115, "출발", "횡단보도", "우회전"),
        RouteOption.WALK_SHORTEST to route(TravelMode.WALK, 5410, 4920, "출발", "계단", "좌회전"),
    ))

    private fun step(type: TransitStepType, min: Int, vehicle: Vehicle? = null, guidance: String = "") = TransitStep(
        type, guidance, 1000, min * 60, stops = listOf("대전광역시청", "둔산경찰서", "대전역"),
        vehicles = listOfNotNull(vehicle), points = listOf(Coord(36.35, 127.38), Coord(36.33, 127.43)),
    )

    private val transit = listOf(
        TransitRoute("BUS", 6200, 31 * 60, 0, 1500, listOf(
            step(TransitStepType.WALK, 4), step(TransitStepType.BUS, 22, Vehicle("간선", "707"), "간선 707 (대전광역시청 > 대전역)"), step(TransitStepType.WALK, 5),
        )),
        TransitRoute("BUS_AND_SUBWAY", 6900, 27 * 60, 1, 1500, listOf(
            step(TransitStepType.WALK, 6), step(TransitStepType.SUBWAY, 12, Vehicle("1호선", "대전 1호선"), "대전 1호선 (시청 > 대전역)"),
            step(TransitStepType.BUS, 6, Vehicle("지선", "311")), step(TransitStepType.WALK, 3),
        )),
        // 아무 버스나 타도 되는 구간 (카카오맵 "105외 5대") + 거리 따라 달라지는 요금
        TransitRoute("BUS", 7400, 33 * 60, 0, 1500, listOf(
            step(TransitStepType.WALK, 5),
            step(TransitStepType.BUS, 24, null, "간선 105 (대전광역시청 > 대전역)").copy(
                vehicles = listOf("105", "115", "912", "117", "911", "1").map { Vehicle("간선", it) },
            ),
            step(TransitStepType.WALK, 4),
        ), fareMax = 2800),
    )

    private fun head(mode: TravelMode, seconds: Map<TravelMode, Int>) = listOf(
        Row.Header("→ 한밭수목원", "내 위치에서 출발"),
        Row.ModeTabs(mode, seconds, RouteChoices.fastestMode(seconds)),
    )

    private fun shoot(name: String, rows: List<Row>, pages: Int = 3, fontScale: Float = 1f) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        val activity = controller.get()
        activity.requestWindowFeature(Window.FEATURE_NO_TITLE)   // 워치 테마엔 제목 막대가 없다
        if (fontScale != 1f) {
            val conf = activity.resources.configuration
            conf.fontScale = fontScale
            @Suppress("DEPRECATION")
            activity.resources.updateConfiguration(conf, activity.resources.displayMetrics)
        }
        controller.setup()
        activity.setTheme(R.style.Theme_KakaoWatchMap)
        activity.setContentView(R.layout.activity_list)
        val ui = WearList(activity, {})
        ui.rows = rows
        shadowOf(Looper.getMainLooper()).idle()
        val root = activity.window.decorView
        val size = activity.resources.displayMetrics.widthPixels
        for (page in 0 until pages) {
            if (page > 0) {
                ui.list.scrollBy(0, (size * 0.8f).toInt())
                shadowOf(Looper.getMainLooper()).idle()
            }
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(Color.BLACK)
            root.draw(c)
            // 둥근 화면 밖은 검게 + 테두리 표시
            val mask = Path().apply {
                addRect(0f, 0f, size.toFloat(), size.toFloat(), Path.Direction.CW)
                addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CCW)
            }
            c.drawPath(mask, Paint().apply { color = Color.rgb(40, 40, 40) })
            FileOutputStream(File(out, "${name}_$page.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        controller.pause().stop().destroy()
    }

    private fun simpleRows(choices: List<com.mywatch.kakaomap.model.RouteChoice>, missing: String? = null): List<Row> {
        val picked = RouteChoices.pick(choices, null)!!
        val fastest = RouteChoices.fastest(choices)
        return choices.mapIndexed { i, c -> Row.OptionCard(i, c, selected = c == picked, fastest = c == fastest) } +
            (missing?.let { listOf(Row.Note("$it 경로를 받지 못했어요"), Row.Action("retry_options", R.drawable.ic_refresh, "다시 받기")) } ?: emptyList()) +
            Row.Action("navigate", R.drawable.ic_navigation, "안내 시작", accent = true) +
            Row.Action("map", R.drawable.ic_map, "지도에서 보기") +
            RouteRows.simpleSteps(picked.route, "한밭수목원")
    }

    private val times = mapOf(TravelMode.TRANSIT to 27 * 60, TravelMode.WALK to 4920, TravelMode.BIKE to 781)

    @Test
    fun bikeOptions() = shoot("bike", head(TravelMode.BIKE, times) + simpleRows(bikeChoices))

    @Test
    fun walkOptionsLongTimes() = shoot("walk", head(TravelMode.WALK, times) + simpleRows(walkChoices, missing = "최단 거리"))

    @Test
    fun bikeOptionsLargeFont() = shoot("bike_font115", head(TravelMode.BIKE, times) + simpleRows(bikeChoices), fontScale = 1.15f)

    // ---------- 갤럭시워치 전용앱 첫 화면 (카카오맵 / 유튜브 고르기) ----------

    @Test
    fun hubScreen() {
        val controller = Robolectric.buildActivity(HubActivity::class.java)
        val activity = controller.get()
        activity.requestWindowFeature(Window.FEATURE_NO_TITLE)
        controller.setup()
        shadowOf(Looper.getMainLooper()).idle()
        val size = activity.resources.displayMetrics.widthPixels
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)
        activity.window.decorView.draw(c)
        val mask = Path().apply {
            addRect(0f, 0f, size.toFloat(), size.toFloat(), Path.Direction.CW)
            addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CCW)
        }
        c.drawPath(mask, Paint().apply { color = Color.rgb(40, 40, 40) })
        FileOutputStream(File(out, "hub_0.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        controller.pause().stop().destroy()
    }

    // ---------- 유튜브 음악 (워치에서 찾고 폰 유튜브 앱으로 재생) ----------

    private val tubeVideos = listOf(
        TubeVideo("BzYnNdJhZQw", "[MV] IU(아이유) _ Through the Night(밤편지)", "1theK (원더케이)", 294),
        TubeVideo("pSUydWEqKwE", "NewJeans (뉴진스) 'Ditto' Official MV (side A)", "HYBE LABELS", 186),
        TubeVideo("jfKfPfyJRdk", "lofi hip hop radio 📚 beats to relax/study to", "Lofi Girl", null, live = true),
        TubeVideo("aaaaaaaaaaa", "밤편지 - 아이유 (cover by 아주 긴 채널 이름의 커버 가수) | 피아노 반주 ver.", "커버하는 사람들의 아주 긴 채널 이름", 3723),
        TubeVideo("bbbbbbbbbbb", "아이유", "", null),
    )

    private val tubeApp get() = org.robolectric.RuntimeEnvironment.getApplication()

    @Test
    fun tubeHome() {
        TubeThumbs.enabled = false
        val played = tubeVideos.take(3).mapIndexed { i, v -> Played(v, 1_000L - i) }
        shoot("tube_home", TubeRows.home(tubeApp, played, listOf("아이유 밤편지", "뉴진스 Ditto", "비 오는 날 듣기 좋은 노래 플레이리스트 모음")), pages = 5)
    }

    @Test
    fun tubeHomeEmpty() {
        TubeThumbs.enabled = false
        shoot("tube_home_empty", TubeRows.home(tubeApp, emptyList(), emptyList()), pages = 1)
    }

    @Test
    fun tubeResults() {
        TubeThumbs.enabled = false
        shoot("tube_results", TubeRows.results(tubeApp, "아이유 밤편지", TubeSource.KAKAO, tubeVideos, hasMore = true, note = null), pages = 4)
    }

    @Test
    fun tubeResultsLargeFontWithNote() {
        TubeThumbs.enabled = false
        shoot(
            "tube_results_font115",
            TubeRows.results(tubeApp, "비 오는 날 듣기 좋은 노래", TubeSource.KAKAO, tubeVideos.take(2), hasMore = false,
                note = "오늘 유튜브 검색 한도를 다 써서 카카오 동영상 검색으로 찾았어요"),
            pages = 3, fontScale = 1.15f,
        )
    }

    @Test
    fun tubeMenuAndError() {
        TubeThumbs.enabled = false
        shoot("tube_menu", TubeRows.menu(tubeApp, tubeVideos[3], fromHome = true), pages = 2)
        shoot("tube_error", TubeRows.error(tubeApp, "아이유", "네트워크 연결을 확인하세요"), pages = 1)
    }

    private fun place(name: String, category: String, address: String, m: Int?) =
        com.mywatch.kakaomap.model.Place("1", name, category, address, address, Coord(36.36, 127.39), m)

    @Test
    fun searchHome() = shoot(
        "search",
        listOf(
            Row.Header("길찾기"),
            Row.Action("input", R.drawable.ic_search, "도착지 검색", accent = true),
            Row.FavoriteItem(com.mywatch.kakaomap.data.FavoriteSlot(com.mywatch.kakaomap.data.Favorite.HOME, place("선비마을아파트", "아파트", "대전 대덕구 송촌동", null))),
            Row.FavoriteItem(com.mywatch.kakaomap.data.FavoriteSlot(com.mywatch.kakaomap.data.Favorite.WORK, null)),
            Row.Note("길게 누르면 집·회사로 등록할 수 있어요"),
            Row.Header("최근 목적지"),
            Row.PlaceItem(place("한밭수목원", "식물원,수목원", "대전 서구 둔산대로 169", 7300), recent = true),
            Row.PlaceItem(place("대전역 KTX 고속철도 승강장", "기차역", "대전 동구 중앙로 215", 6100), recent = true),
            Row.Action("clear", R.drawable.ic_delete, "최근 목적지 지우기"),
            Row.Action("update", R.drawable.ic_info, "앱 정보 · 업데이트"),
        ),
    )

    @Test
    fun transitDetail() = shoot(
        "detail",
        listOf(
            Row.Header("27분 · 오전 11:58 도착", "1,500원 · 환승 1회 · 6.9km"),
            Row.Action("navigate", R.drawable.ic_navigation, "안내 시작", accent = true),
            Row.Action("map", R.drawable.ic_map, "지도에서 보기"),
        ) + RouteRows.transitTimeline(transit[1], "한밭수목원", true),
        pages = 4,
    )

    @Test
    fun appInfoWithErrors() {
        val now = System.currentTimeMillis()
        val e = com.mywatch.kakaomap.data.ErrorLogFormat
        shoot(
            "info",
            listOf(
                Row.Header("카카오 지도", "버전 2.12 (112)"),
                Row.Note("최신 버전이에요"),
                Row.Action("check", R.drawable.ic_refresh, "업데이트 확인"),
                Row.Header("최근 오류 기록"),
                Row.Note(e.line(com.mywatch.kakaomap.data.ErrorEntry(now, "실시간 버스", "공공데이터 키가 이 기능에 아직 등록되지 않았어요", "2.12", 3))),
                Row.Note(e.line(com.mywatch.kakaomap.data.ErrorEntry(now - 3_600_000, "앱 멈춤", "IllegalStateException: boom\n← DirectionsActivity.render:210\n← WearList.a:12", "2.11"))),
                Row.Action("clear_errors", R.drawable.ic_delete, "오류 기록 지우기"),
                Row.Note("실시간 버스(공공데이터) 키: 들어 있음"),
            ),
        )
    }

    // ── 장거리: 대전시청 → 서울시청 (KTX · 고속버스 · 카카오 시내버스 갈아타기) ──
    private val zone = java.time.ZoneId.of("Asia/Seoul")
    private fun at(h: Int, m: Int) = java.time.LocalDateTime.of(2026, 10, 13, h, m).atZone(zone).toInstant().toEpochMilli()
    private val cityHall = Coord(36.3504, 127.3849)
    private val seoulHall = com.mywatch.kakaomap.model.Place("s", "서울시청", "시청", "서울 중구", "", Coord(37.5663, 126.9779), null)
    private fun kplace(name: String, lat: Double, lng: Double) =
        com.mywatch.kakaomap.model.Place(name, name, "KTX정차역", "", "", Coord(lat, lng), 1000)
    private val daejeonSt = kplace("대전역", 36.3326, 127.4344)
    private val seoulSt = kplace("서울역", 37.5547, 126.9707)
    private val djTerminal = kplace("대전복합터미널", 36.3500, 127.4370)
    private val seoulExp = kplace("서울고속버스터미널(경부)", 37.5049, 127.0049)
    private val subwayToStation = TransitRoute("SUBWAY", 5600, 20 * 60, 0, 1500, listOf(
        TransitStep(TransitStepType.WALK, "시청역까지 도보", 300, 300, emptyList(), emptyList(), listOf(cityHall, Coord(36.351, 127.388)), approx = true),
        TransitStep(TransitStepType.SUBWAY, "대전 1호선 (시청 > 대전역)", 5000, 600, listOf("시청", "중앙로", "대전역"),
            listOf(Vehicle("도시철도", "대전 1호선")), listOf(Coord(36.351, 127.388), Coord(36.332, 127.433))),
        TransitStep(TransitStepType.WALK, "대전역까지 도보", 300, 300, emptyList(), emptyList(), listOf(Coord(36.332, 127.433), daejeonSt.coord), approx = true),
    ))
    private val longRoutes: List<TransitRoute> by lazy {
        val m = com.mywatch.kakaomap.model.IntercityKind.TRAIN
        val e = com.mywatch.kakaomap.model.IntercityKind.EXPRESS
        com.mywatch.kakaomap.model.IntercityItineraries.build(
            at(9, 25), cityHall, seoulHall,
            listOf(
                com.mywatch.kakaomap.model.StationPair(m,
                    com.mywatch.kakaomap.model.AccessLeg(daejeonSt, subwayToStation, 20 * 60, 1500),
                    com.mywatch.kakaomap.model.AccessLeg(seoulSt, null, 15 * 60, 0),
                    listOf(com.mywatch.kakaomap.model.ScheduledRun(m, "105", "KTX-산천", "대전", "서울", at(9, 58), at(10, 51), 23700, weekly = true))),
                com.mywatch.kakaomap.model.StationPair(e,
                    com.mywatch.kakaomap.model.AccessLeg(djTerminal, null, 25 * 60, 0),
                    com.mywatch.kakaomap.model.AccessLeg(seoulExp, null, 35 * 60, 0),
                    listOf(com.mywatch.kakaomap.model.ScheduledRun(e, "", "심야고속", "대전복합", "서울경부", at(10, 5), at(12, 5), 14800))),
            ),
            perKind = 1,
        )
    }
    private val busChain = TransitRoute("BUS", 170_244, 10549, 3, null, listOf(
        step(TransitStepType.WALK, 5), step(TransitStepType.BUS, 20, Vehicle("일반", "106")), step(TransitStepType.WALK, 3),
        step(TransitStepType.BUS, 90, Vehicle("시외", "8366")), step(TransitStepType.WALK, 4),
        step(TransitStepType.BUS, 50, Vehicle("직행", "5005")), step(TransitStepType.WALK, 4),
    ))

    @Test
    fun longDistanceCards() = shoot(
        "intercity",
        listOf(Row.Header("→ 서울시청", "내 위치에서 출발"), Row.ModeTabs(TravelMode.TRANSIT, mapOf(TravelMode.TRANSIT to longRoutes[0].totalTimeSec), null)) +
            Row.TransitChoice(TransitFilter.ALL, 3, true, TransitSort.FASTEST) +
            Row.TransitCard(0, longRoutes[0], "106번 4분 후 (2정거장 전)", fastest = true) +
            Row.TransitCard(1, longRoutes[1], null) +
            Row.TransitCard(2, busChain, null) +
            Row.Note("오늘 열차 시간표에 빠진 KTX가 있어 다음 주 같은 요일 시간표로 채웠어요") +
            Row.Note("버스 도착 시간은 공공데이터(TAGO) 실시간 정보, 기차·고속·시외버스는 공공데이터(TAGO) 시간표예요."),
        pages = 4,
    )

    @Test
    fun longDistanceDetail() = shoot(
        "intercity_detail",
        listOf(
            Row.Header("1시간 41분 · 오전 11:06 도착", "26,700원 · 환승 1회 · 168km"),
            Row.Action("navigate", R.drawable.ic_navigation, "안내 시작", accent = true),
        ) + RouteRows.transitTimeline(longRoutes[0], "서울시청", true) + RouteRows.transitTimeline(longRoutes[1], "서울시청", false),
        pages = 5,
    )

    @Test
    fun transitCards() = shoot(
        "transit",
        head(TravelMode.TRANSIT, times) + Row.TransitChoice(TransitFilter.ALL, 3, true, TransitSort.FASTEST) +
            Row.TransitCard(1, transit[1], "311번 3분 (2번째 전) · 다음 14분", fastest = true) +
            Row.TransitCard(0, transit[0], "707번 12분 (7번째 전)", fastest = false) +
            Row.TransitCard(2, transit[2], "105 곧 도착 · 115 7분 · 912 12분", fastest = false),
    )

    @Test
    fun transitDetailManyLines() = shoot(
        "detail_lines",
        listOf(
            Row.Header("33분 · 오전 12:04 도착", "1,500~2,800원 · 환승 없음 · 7.4km"),
            Row.Action("navigate", R.drawable.ic_navigation, "안내 시작", accent = true),
        ) + RouteRows.transitTimeline(transit[2], "한밭수목원", true).map {
            if (it is Row.Step && it.live != null) it.copy(live = "105 곧 도착 · 115 7분 · 912 12분") else it
        },
        pages = 3,
    )
}
