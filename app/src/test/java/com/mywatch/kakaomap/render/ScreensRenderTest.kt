package com.mywatch.kakaomap.render

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.activity.ComponentActivity
import com.mywatch.kakaomap.R
import com.mywatch.kakaomap.tile.batteryTileLayout
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
import com.mywatch.kakaomap.ui.Look
import com.mywatch.kakaomap.ui.Row
import com.mywatch.kakaomap.ui.RouteRows
import com.mywatch.kakaomap.ui.WearList
import com.mywatch.kakaomap.ui.TubeRows
import com.mywatch.kakaomap.ui.HubActivity
import com.mywatch.kakaomap.ui.TubeThumbs
import com.mywatch.kakaomap.tube.Played
import com.mywatch.kakaomap.tube.TubeChannel
import com.mywatch.kakaomap.tube.TubeLinks
import com.mywatch.kakaomap.tube.TubePage
import com.mywatch.kakaomap.tube.TubePlaylist
import com.mywatch.kakaomap.tube.TubeSource
import com.mywatch.kakaomap.tube.TubeVideo
import com.mywatch.kakaomap.ui.tube.ChannelTab
import com.mywatch.kakaomap.ui.tube.Slot
import com.mywatch.kakaomap.ui.tube.SubsChip
import com.mywatch.kakaomap.tube.HomeChip
import com.mywatch.kakaomap.tube.HomeFeed
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

    private fun shoot(name: String, rows: List<Row>, pages: Int = 3, fontScale: Float = 1f, look: Look = Look.MAP) {
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
        val ui = WearList(activity, {}, null, look)
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
    fun hubScreen() = hub("hub_0")

    /** 워치 설정에서 글자를 키운 경우 — 아래쪽 버튼이 둥근 화면 밖으로 밀려 안 보이면 안 됨 */
    @Test
    fun hubScreenLargeFonts() {
        hub("hub_font115", 1.15f)
        hub("hub_font130", 1.3f)
    }

    /** 새 버전이 나와 있을 때 — 파란 '새 버전 업데이트' 버튼 */
    @Test
    fun hubScreenWithNewVersion() {
        com.mywatch.kakaomap.update.FakeReleaseServer().use { server ->
            server.updateJson = server.updateJsonFor(999)
            com.mywatch.kakaomap.update.UpdateManager.baseUrl = server.base
            com.mywatch.kakaomap.update.UpdateManager.setOwner(tubeApp, "tester")
            com.mywatch.kakaomap.update.UpdateManager.check(tubeApp)
            hub("hub_new_version")
            hub("hub_new_version_font130", 1.3f)
            com.mywatch.kakaomap.update.UpdateManager.baseUrl = com.mywatch.kakaomap.update.UpdateManager.GITHUB
        }
    }

    private fun hub(name: String, fontScale: Float = 1f) {
        val controller = Robolectric.buildActivity(HubActivity::class.java)
        val activity = controller.get()
        activity.requestWindowFeature(Window.FEATURE_NO_TITLE)
        if (fontScale != 1f) {
            val conf = activity.resources.configuration
            conf.fontScale = fontScale
            @Suppress("DEPRECATION")
            activity.resources.updateConfiguration(conf, activity.resources.displayMetrics)
        }
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
        FileOutputStream(File(out, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        controller.pause().stop().destroy()
    }

    // ---------- 유튜브 (워치용 유튜브 앱 — 16:9 동영상 카드, 채널·재생목록, 로그인) ----------

    private val tubeNow = 1_760_000_000_000L
    private val tubeDay = 86_400_000L

    private val tubeVideos = listOf(
        TubeVideo("BzYnNdJhZQw", "[MV] IU(아이유) _ Through the Night(밤편지)", "1theK (원더케이)", 294, channelId = "UCweOkPb1wVVH0Q0Tlj4a5Pw", views = 512_345_678, publishedAt = tubeNow - 8 * 366 * tubeDay),
        TubeVideo("pSUydWEqKwE", "NewJeans (뉴진스) 'Ditto' Official MV (side A)", "HYBE LABELS", 186, channelId = "UC3IZKseVpdzPSBaWxBxundA", views = 123_456, publishedAt = tubeNow - 3 * tubeDay),
        TubeVideo("jfKfPfyJRdk", "lofi hip hop radio 📚 beats to relax/study to", "Lofi Girl", null, live = true),
        TubeVideo("aaaaaaaaaaa", "밤편지 - 아이유 (cover by 아주 긴 채널 이름의 커버 가수) | 피아노 반주 ver.", "커버하는 사람들의 아주 긴 채널 이름", 3723, views = 999, publishedAt = tubeNow - 2 * 3_600_000, embeddable = false),
        TubeVideo("bbbbbbbbbbb", "아이유", "", null),
    )
    private val tubeChannel = TubeChannel("UCweOkPb1wVVH0Q0Tlj4a5Pw", "1theK (원더케이)", "https://yt3.ggpht.com/test=s240", 25_300_000, 13_000, "UUweOkPb1wVVH0Q0Tlj4a5Pw")
    private val tubePlaylists = listOf(
        TubePlaylist("PL1aaaaaaaaaa", "드라이브할 때 듣는 노래 모음 (2026 최신 업데이트)", "멜론", "https://i.ytimg.com/vi/pSUydWEqKwE/mqdefault.jpg", 42),
        TubePlaylist("PL2bbbbbbbbbb", "라이브", "1theK (원더케이)", null, 7),
    )

    private val tubeApp get() = org.robolectric.RuntimeEnvironment.getApplication()

    private fun ready(videos: List<TubeVideo>, next: String? = null) = Slot(page = TubePage(videos, next))

    /**
     * 유튜브 화면을 그린다. 그림은 받지 않고 동영상마다 다른 색 그림(480×360 — 앱이 받는 크기, 위아래 검은 띠까지)을 넣어
     * 16:9 로 잘린 모양·글자와의 균형까지 보이게. 채널 사진은 동그란 그림
     */
    private fun tube(name: String, rows: List<Row>, pages: Int = 3, fontScale: Float = 1f) {
        val palette = listOf(0xFF3A5BA0, 0xFFB0413E, 0xFF2E7D5B, 0xFF7B4FA0, 0xFFC08A2E).map { it.toInt() }
        tubeVideos.forEachIndexed { i, v ->
            val bmp = Bitmap.createBitmap(480, 360, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(Color.BLACK)
            c.drawRect(0f, 45f, 480f, 315f, Paint().apply { color = palette[i % palette.size] })
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 255, 255, 255) }
            c.drawCircle(240f, 160f, 60f, p)
            c.drawRect(160f, 230f, 320f, 315f, p)
            TubeThumbs.preload(TubeLinks.thumbnail(v.id, big = true), bmp)
        }
        val face = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFC08A2E.toInt()) }
        TubeThumbs.preload(tubeChannel.thumb!!, face)
        TubeThumbs.preload(tubePlaylists[0].thumb!!, Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF2E7D5B.toInt()) })
        shoot(name, rows, pages, fontScale, Look.TUBE)
    }

    /** 세로 Shorts (미리보기는 4:3 가운데 세로 그림 — 유튜브 hqdefault 처럼 양옆이 검은 띠) */
    private val tubeShorts = (1..5).map { n ->
        TubeVideo("short00000$n", listOf("고양이가 박스에 들어가는 법 #shorts", "1분 만에 끝내는 김치볶음밥", "오늘의 출근길 🚇", "강아지 산책 브이로그", "춤 챌린지")[n - 1],
            "채널 $n", 30 + n, channelId = "UC" + n.toString().padStart(22, '0'), portrait = true)
    }

    private fun shortsPictures() {
        val palette = listOf(0xFFB0413E, 0xFF3A5BA0, 0xFF2E7D5B, 0xFF7B4FA0, 0xFFC08A2E).map { it.toInt() }
        tubeShorts.forEachIndexed { i, v ->
            val bmp = Bitmap.createBitmap(480, 360, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(Color.BLACK)
            c.drawRect(138f, 0f, 342f, 360f, Paint().apply { color = palette[i % palette.size] })
            c.drawCircle(240f, 150f, 40f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 255, 255, 255) })
            TubeThumbs.preload(TubeLinks.thumbnail(v.id, big = true), bmp)
        }
    }

    private fun home(chip: HomeChip = HomeChip.ALL, loading: Boolean = false, update: Boolean = false) = TubeRows.home(
        tubeApp, TubeRows.HomeData(
            chip,
            if (loading) HomeFeed(tubeVideos.take(1)) else HomeFeed(tubeVideos, tubeShorts, shelfAt = 3),
            TubeRows.Load(loading = loading),
            more = ready(tubeVideos.take(1), "T2"),
            update = update, now = tubeNow,
        ),
    )

    @Test
    fun tubeHome() {
        TubeThumbs.enabled = false
        shortsPictures()
        tube("tube_home", home(), pages = 9)
        tube("tube_home_update", home(update = true), pages = 2)
        tube("tube_home_loading", home(loading = true), pages = 3)
        tube("tube_home_font130", home(), pages = 5, fontScale = 1.3f)
        tube(
            "tube_home_mixes", TubeRows.home(tubeApp, TubeRows.HomeData(HomeChip.MIXES, HomeFeed(tubeVideos.take(3)), TubeRows.Load(), null, false, tubeNow)),
            pages = 4,
        )
        tube("tube_home_live_empty", TubeRows.home(tubeApp, TubeRows.HomeData(HomeChip.LIVE, HomeFeed(emptyList()), TubeRows.Load(), null, false, tubeNow)), pages = 2)
    }

    @Test
    fun tubeShortsFindAndSearch() {
        TubeThumbs.enabled = false
        shortsPictures()
        tube("tube_shorts", TubeRows.shorts(tubeApp, tubeShorts, TubeRows.Load()), pages = 4)
        tube("tube_shorts_font130", TubeRows.shorts(tubeApp, tubeShorts, TubeRows.Load()), pages = 4, fontScale = 1.3f)
        tube("tube_find", TubeRows.find(tubeApp, listOf("아이유 밤편지", "비 오는 날 듣기 좋은 노래 플레이리스트 모음", "뉴진스")), pages = 3)
        val page = TubePage(tubeVideos.take(4) + tubeShorts.take(3), "S2", channels = listOf(tubeChannel), playlists = tubePlaylists.take(1))
        tube("tube_search", TubeRows.search(tubeApp, "아이유", Slot(page = page, source = TubeSource.YOUTUBE), tubeNow), pages = 7)
        tube(
            "tube_search_kakao", TubeRows.search(tubeApp, "비 오는 날 듣기 좋은 노래", Slot(page = TubePage(tubeVideos.take(1), null), source = TubeSource.KAKAO,
                note = "오늘 유튜브 검색 한도를 다 써서 카카오 동영상 검색으로 찾았어요"), tubeNow), pages = 3,
        )
    }

    @Test
    fun tubeChannelPlaylist() {
        TubeThumbs.enabled = false
        shortsPictures()
        val info = Slot(page = TubePage(emptyList(), null, channels = listOf(tubeChannel)))
        val lists = Slot(page = TubePage(emptyList(), null, playlists = tubePlaylists))
        tube("tube_channel", TubeRows.channel(tubeApp, "1theK", ChannelTab.VIDEOS, info, ready(tubeVideos.take(2), "C2"), lists, tubeNow), pages = 5)
        tube("tube_channel_shorts", TubeRows.channel(tubeApp, "1theK", ChannelTab.SHORTS, info, ready(tubeShorts), lists, tubeNow), pages = 5)
        tube("tube_channel_playlists", TubeRows.channel(tubeApp, "1theK", ChannelTab.PLAYLISTS, info, Slot(), lists, tubeNow), pages = 3)
        tube("tube_playlist", TubeRows.playlist(tubeApp, "드라이브할 때 듣는 노래 모음", "PL1aaaaaaaaaa", false, ready(tubeVideos.take(3)), tubeNow), pages = 4)
    }

    @Test
    fun tubeYouSubscriptionsLoginAccount() {
        TubeThumbs.enabled = false
        shortsPictures()
        val signedIn = TubeRows.Account(configured = true, signedIn = true, channel = tubeChannel)
        val history = tubeVideos.take(3).map { Played(it, tubeNow) }
        tube("tube_you", TubeRows.library(tubeApp, signedIn, history, recs = 96, dismissed = 3, seen = 12, searches = 4, update = false, now = tubeNow), pages = 5)
        tube("tube_you_out", TubeRows.library(tubeApp, TubeRows.Account(configured = true, signedIn = false), emptyList(), 0, 0, update = false, now = tubeNow), pages = 3)
        val channels = Slot(page = TubePage(emptyList(), "SC2", channels = (1..6).map { tubeChannel.copy(id = "UC" + it.toString().padStart(22, '7'), title = "채널 이름 $it") }))
        tube(
            "tube_subscriptions",
            TubeRows.subscriptions(tubeApp, signedIn, SubsChip.ALL, channels, ready(tubeVideos.take(4) + tubeShorts.take(2)), ready(tubeShorts), null, tubeNow), pages = 5,
        )
        tube(
            "tube_subscriptions_shorts",
            TubeRows.subscriptions(tubeApp, signedIn, SubsChip.SHORTS, channels, ready(tubeVideos.take(2)), ready(tubeShorts), null, tubeNow), pages = 4,
        )
        tube("tube_subs_channels", TubeRows.subsChannels(tubeApp, channels), pages = 3)
        val code = com.mywatch.kakaomap.tube.DeviceCode("dc", "PSQ-RWD-XKZ", "https://www.google.com/device", tubeNow + 1_800_000, 5)   // 실제 코드 길이(11자)
        tube("tube_login", TubeRows.login(tubeApp, TubeRows.LoginState.Waiting(code)), pages = 3)
        tube("tube_login_font130", TubeRows.login(tubeApp, TubeRows.LoginState.Waiting(code)), pages = 3, fontScale = 1.3f)
        tube("tube_login_denied", TubeRows.login(tubeApp, TubeRows.LoginState.Denied), pages = 1)
        tube("tube_account", TubeRows.account(tubeApp, signedIn), pages = 2)
        tube("tube_terms_first", TubeRows.terms(tubeApp, agreed = false, privacy = true), pages = 3)
        tube("tube_terms_first_font130", TubeRows.terms(tubeApp, agreed = false, privacy = true), pages = 4, fontScale = 1.3f)
    }

    @Test
    fun tubeMenuAndHandoff() {
        TubeThumbs.enabled = false
        tube("tube_menu", TubeRows.menu(tubeApp, tubeVideos[0], inHistory = true), pages = 4)
        tube("tube_menu_phone_only", TubeRows.menu(tubeApp, tubeVideos[3], inHistory = false), pages = 4)
        tube("tube_menu_font130", TubeRows.menu(tubeApp, tubeVideos[3], inHistory = true), pages = 5, fontScale = 1.3f)
        val h = com.mywatch.kakaomap.tube.Handoff(tubeVideos[3], 83, true)
        tube("tube_handoff", TubeRows.handoff(tubeApp, h), pages = 2)
        tube("tube_history", TubeRows.history(tubeApp, tubeVideos.take(2).map { Played(it, tubeNow) }, tubeNow), pages = 4)
    }

    private fun place(name: String, category: String, address: String, m: Int?) =
        com.mywatch.kakaomap.model.Place("1", name, category, address, address, Coord(36.36, 127.39), m)

    @Test
    fun searchHome() = shoot(
        "search",
        listOf(
            Row.Header("길찾기"),
            Row.Action("input", R.drawable.ic_search, "도착지 검색", accent = true),
            Row.FavoriteItem(com.mywatch.kakaomap.data.FavoriteSlot(com.mywatch.kakaomap.data.Favorite.HOME, place("우리 집", "아파트", "대전", null))),
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

    // ───────── 이번에 바뀐 화면 (watch-polish) ─────────

    @Test
    fun transitCardsMissAndLongMeta() = shoot(
        "transit_miss",
        head(TravelMode.TRANSIT, times) +
            Row.TransitCard(1, transit[1], "106번 3분 (2번째 전) · 놓칠 수 있어요 · 다음 15분", fastest = true) +
            Row.TransitCard(2, transit[2], "105 곧 도착 · 115 7분 · 912 12분", fastest = false),
        fontScale = 1.3f,
    )

    @Test
    fun tubeLoginPhoneSteps() {
        TubeThumbs.enabled = false
        val code = com.mywatch.kakaomap.tube.DeviceCode("dc", "PSQ-RWD-XKZ", "https://www.google.com/device", tubeNow + 1_800_000, 5)
        tube("tube_login_sending", TubeRows.login(tubeApp, TubeRows.LoginState.Waiting(code, phone = TubeRows.PhoneStep.SENDING)), pages = 3)
        tube("tube_login_opened", TubeRows.login(tubeApp, TubeRows.LoginState.Waiting(code, phone = TubeRows.PhoneStep.OPENED)), pages = 3, fontScale = 1.3f)
    }

    /** 실제 길찾기 검색 화면 (가짜 위치·검색): 첫 화면의 '내 주변' → 종류 고르기 → 화장실 결과 */
    @Test
    fun searchNearby() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        com.mywatch.kakaomap.data.RouteRepository.init(app)
        com.mywatch.kakaomap.data.RouteRepository.clearRecents(app)
        com.mywatch.kakaomap.data.RouteRepository.addRecent(app, place("한밭수목원", "식물원,수목원", "대전 서구 둔산대로 169", 7300))
        com.mywatch.kakaomap.data.Favorites.set(app, com.mywatch.kakaomap.data.Favorite.HOME, place("우리 집", "아파트", "대전", null))
        val toilets = listOf(
            place("보라매공원 공중화장실", "화장실", "대전 서구 둔산동 1488", 140),
            place("대전시청 1층 개방화장실", "화장실", "대전 서구 둔산로 100", 260),
            place("둔산대공원 화장실", "화장실", "대전 서구 만년동 396", 870),
        )
        val here = Coord(36.3504, 127.3849)
        com.mywatch.kakaomap.ui.SearchActivity.finderFor = {
            com.mywatch.kakaomap.net.PlaceSearch(
                recent = { here }, locate = { null }, keyword = { _, _ -> emptyList() }, nearby = { _, _ -> toilets },
            )
        }
        try {
            val controller = Robolectric.buildActivity(com.mywatch.kakaomap.ui.SearchActivity::class.java)
            val activity = controller.get()
            activity.requestWindowFeature(Window.FEATURE_NO_TITLE)
            controller.setup()
            shadowOf(Looper.getMainLooper()).idle()
            capture(activity, "search_home_nearby", pages = 2)
            tapRow(activity) { it is Row.Action && it.text == activity.getString(R.string.nearby_title) }
            capture(activity, "nearby_kinds", pages = 2)
            tapRow(activity) { it is Row.Action && it.text == "화장실" }
            val deadline = System.currentTimeMillis() + 3_000
            while (System.currentTimeMillis() < deadline && rowsOf(activity).none { it is Row.PlaceItem }) {
                Thread.sleep(10)
                shadowOf(Looper.getMainLooper()).idle()
            }
            capture(activity, "nearby_toilets", pages = 2)
            controller.pause().stop().destroy()
        } finally {
            com.mywatch.kakaomap.ui.SearchActivity.finderFor = com.mywatch.kakaomap.ui.SearchActivity.liveFinder
            com.mywatch.kakaomap.data.Favorites.clear(app, com.mywatch.kakaomap.data.Favorite.HOME)
        }
    }

    private fun rowsOf(activity: android.app.Activity): List<Row> =
        (activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.list).adapter as com.mywatch.kakaomap.ui.RowAdapter).rows

    private fun tapRow(activity: android.app.Activity, which: (Row) -> Boolean) {
        val rv = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.list)
        val i = rowsOf(activity).indexOfFirst(which)
        check(i >= 0) { "누를 줄이 없음: ${rowsOf(activity)}" }
        val size = activity.resources.displayMetrics.widthPixels
        val spec = android.view.View.MeasureSpec.makeMeasureSpec(size, android.view.View.MeasureSpec.EXACTLY)
        rv.scrollToPosition(i)
        rv.measure(spec, spec)
        rv.layout(0, 0, size, size)
        rv.findViewHolderForAdapterPosition(i)!!.itemView.performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** 배터리 화면 맨 위: 지금 줄·남은 시간·24시간 그래프(충전·가장 많이 닳은 시간·화면 켜짐 띠)·소모율 */
    @Test
    fun batteryChart() {
        val zone = java.time.ZoneId.of("Asia/Seoul")
        val now = java.time.LocalDateTime.of(2026, 10, 21, 22, 0).atZone(zone).toInstant().toEpochMilli()
        val q = 15 * 60_000L
        val samples = ArrayList<com.mywatch.battery.core.BatterySample>()
        var level = 96.0
        for (i in 0..96) {
            val t = now - (96 - i) * q
            val hour = java.time.Instant.ofEpochMilli(t).atZone(zone).hour
            val plugged = hour == 7
            level = when {
                plugged -> minOf(100.0, level + 9.0)
                hour in 12..13 -> level - 2.5          // 점심때 많이 씀
                hour in 0..6 -> level - 0.3
                else -> level - 0.9
            }.coerceIn(3.0, 100.0)
            if (i in 50..52) continue                  // 기록이 빠진 곳 (선이 끊김)
            samples += com.mywatch.battery.core.BatterySample(t = t, level = level.toInt(), plugged = plugged)
        }
        val screen = (0 until 40).map { k -> val st = now - 20 * 3_600_000L + k * 27 * 60_000L; com.mywatch.battery.core.Span(st, st + 60_000L * (1 + k % 4)) }
        val noon = java.time.LocalDateTime.of(2026, 10, 21, 12, 0).atZone(zone).toInstant().toEpochMilli()
        val model = com.mywatch.battery.core.DrainChart.build(samples, now, zone, screen = screen, hot = listOf(noon to noon + 3_600_000L))
        val rows = listOf(
            Row.Header("배터리", "${samples.last().level}% · 3.91V · 33.1℃ · -84mA · 방전 중"),
            Row.Note("지금 페이스(시간당 0.9%)면 내일 오전 7:40에 15%"),
            Row.Chart("day", model),
            Row.Header("소모율"),
            Row.Step(0xFF5AD17A.toInt(), R.drawable.ic_battery, "정상", "1시간: 시간당 0.9% (실측 4 mAh/h)", null, key = "rate:1"),
            Row.Step(0xFFFFC107.toInt(), R.drawable.ic_battery, "주의", "24시간: 시간당 2.1%", null, key = "rate:24"),
        )
        shoot("battery_chart", rows, pages = 2)
    }

    /** '최근 며칠': 열흘 기록 — 18일 새벽 소프트웨어 업데이트·Play 서비스 업데이트·AOD 켬 뒤로 대기 소모가 늘어난 막대·결론·바뀐 것 */
    @Test
    fun batteryTrend() {
        val zone = java.time.ZoneId.of("Asia/Seoul")
        fun at(d: Int, h: Int, m: Int = 0) = java.time.LocalDateTime.of(2026, 10, d, h, m).atZone(zone).toInstant().toEpochMilli()
        val rates = listOf(1.0, 1.1, 0.9, 1.0, null, 1.1, 2.4, 2.6, 4.7, 2.3)
        val samples = ArrayList<com.mywatch.battery.core.BatterySample>()
        val q = 15 * 60_000L
        rates.forEachIndexed { i, r ->
            val d = 12 + i
            var t = at(d, 0)
            val end = if (d == 21) at(21, 18) else at(d, 20)
            while (t <= end) {
                val h = (t - at(d, 0)) / 3_600_000.0
                samples += com.mywatch.battery.core.BatterySample(t, if (r == null) 100 else (100 - r * h).toInt().coerceAtLeast(1), plugged = r == null && h >= 2, status = if (r == null && h >= 2) com.mywatch.battery.core.Status.FULL else com.mywatch.battery.core.Status.DISCHARGING)
                t += q
            }
            if (d != 21) { var c = at(d, 20) + q; while (c < at(d + 1, 0)) { samples += com.mywatch.battery.core.BatterySample(c, 100, plugged = true, status = com.mywatch.battery.core.Status.CHARGING); c += q } }
        }
        // 바뀐 것은 그 무렵의 15분 표본에 붙는다 (워치가 하는 그대로)
        fun note(t: Long, vararg items: com.mywatch.battery.core.ChangeNote.Item) {
            val i = samples.indices.minByOrNull { kotlin.math.abs(samples[it].t - t) }!!
            samples[i] = samples[i].copy(note = com.mywatch.battery.core.ChangeNote.encode(items.toList()))
        }
        val I = com.mywatch.battery.core.ChangeNote::Item
        note(at(18, 3, 12), I(com.mywatch.battery.core.ChangeKind.FIRMWARE, "fw", "L350XXU1AZH1", "L350XXU1AZI1"))
        note(at(18, 10, 40), I(com.mywatch.battery.core.ChangeKind.APP, "com.google.android.gms", null, "26.40.31"))
        note(at(18, 12, 5), I(com.mywatch.battery.core.ChangeKind.SETTING, "aod", "0", "1"))
        note(at(20, 9, 12), I(com.mywatch.battery.core.ChangeKind.CONFIG, "night_aod", "0", "1"))
        note(at(16, 15, 30), I(com.mywatch.battery.core.ChangeKind.APP, "com.samsung.android.wear.shealth", null, "6.30.5"))
        val now = at(21, 18)
        val t = com.mywatch.battery.core.TrendReport.of(samples.sortedBy { it.t }, now, zone)
        val shift = t.shift!!
        fun line(c: com.mywatch.battery.core.Change) = "· ${com.mywatch.kakaomap.battery.BatterySummary.time(c.t)} ${com.mywatch.battery.core.ChangeFinder.label(c) { null }}"
        val near = t.suspects.take(3)
        val (todoTitle, todoDetail) = t.advice { null }!!
        val rows = listOf(
            // 맨 위 '지금 할 일' 줄 — 세 줄 안에 할 일이 다 들어가는지
            Row.Header("지금 할 일"),
            Row.Step(0xFFFFC107.toInt(), R.drawable.ic_warning, "1", todoTitle, todoDetail, key = "todo:0"),
            Row.Action("todo:act:0", R.drawable.ic_edit, "Play 서비스 앱 정보"),
            Row.Header("최근 며칠", "대기(화면 꺼짐) 시간당 %"),
            Row.Trend("days", t.bars!!),
            Row.Step(0xFFFFC107.toInt(), R.drawable.ic_warning, null, com.mywatch.battery.core.ChangeFinder.title(shift), com.mywatch.battery.core.ChangeFinder.numbers(shift), key = "trend"),
            Row.Note("그 무렵 바뀐 것:\n" + near.joinToString("\n", transform = ::line)),
            Row.Note("할 일: $todoTitle — $todoDetail"),
            Row.Note("최근 바뀐 것:\n" + t.recent.filter { it !in near }.take(5).joinToString("\n", transform = ::line)),
        )
        shoot("battery_trend", rows, pages = 4)
    }

    /**
     * 배터리 타일 — 둥근 480px(44mm)·432px(40mm)·작은 원형(192dp) 에 글 세 줄(소모율·언제 15%·0%·상태)이 잘리지 않고 들어가는지, 긴 글로.
     * 타일 렌더러(protolayout)로 실제 타일처럼 그린다. 기기 값에 글꼴 배율(1)·렌더러 버전(최근 워치 어림 1.500)을 꼭 준다 — 글꼴 배율이
     * 없으면(0) 아래 '자세히' 단추 글 크기가 무한대가 되어 비어 보였다 (잘림은 BatteryTileFitTest 가 잰다)
     */
    @Test
    fun batteryTile() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val t = com.mywatch.kakaomap.tile.BatteryTileService
        val cases = listOf(
            "night" to com.mywatch.kakaomap.tile.BatteryTileService.Snapshot("배터리 55%", "시간당 4.0% · 주의", "내일 15% 새벽 3:24 · 0% 오전 7:40", t.STATUS_NIGHT, "0% 내일 오전 7:40"),
            "issue" to com.mywatch.kakaomap.tile.BatteryTileService.Snapshot("배터리 31%", "시간당 12.5% · 비정상", "15% 오후 10:05 · 0% 내일 새벽 2:10", "Play 서비스 과소모 — 캐시 지우기", "0% 내일 새벽 2:10"),
            "charging" to com.mywatch.kakaomap.tile.BatteryTileService.Snapshot("충전 중 100%", "하루 평균 시간당 2.4%", t.FORECAST_CHARGING, "Samsung Galaxy… 업데이트 뒤 더 닳음"),
            "drift" to com.mywatch.kakaomap.tile.BatteryTileService.Snapshot("배터리 8%", t.RATE_COLLECTING, t.FORECAST_LATER, "잔량 표시가 25포인트 낮음 — 실제론 더 남음"),
        )
        for ((widthDp, label, fontScale) in listOf(Triple(226, "44mm", 1f), Triple(203, "40mm", 1f), Triple(192, "small", 1f), Triple(226, "44mm_big", 1.3f))) {
            // 렌더러가 화면 크기(구성)·글꼴 배율도 보므로 그 워치처럼
            org.robolectric.RuntimeEnvironment.setQualifiers("+w${widthDp}dp-h${widthDp}dp")
            org.robolectric.RuntimeEnvironment.setFontScale(fontScale)
            val device = androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters.Builder()
                .setScreenWidthDp(widthDp).setScreenHeightDp(widthDp).setScreenDensity(340f / 160f).setFontScale(fontScale)
                .setRendererSchemaVersion(androidx.wear.protolayout.expression.VersionBuilders.VersionInfo.Builder().setMajor(1).setMinor(500).build())
                .setScreenShape(androidx.wear.protolayout.DeviceParametersBuilders.SCREEN_SHAPE_ROUND)
                .setDevicePlatform(androidx.wear.protolayout.DeviceParametersBuilders.DEVICE_PLATFORM_WEAR_OS)
                .build()
            val size = (widthDp * 340f / 160f).toInt()
            for ((name, snap) in cases) {
                val root = androidx.wear.protolayout.material3.materialScope(app, device, allowDynamicTheme = false) {
                    batteryTileLayout(app, snap)
                }
                val layout = androidx.wear.protolayout.LayoutElementBuilders.Layout.Builder().setRoot(root).build()
                val renderer = androidx.wear.tiles.renderer.TileRenderer(app, androidx.core.content.ContextCompat.getMainExecutor(app)) { }
                val parent = android.widget.FrameLayout(app)
                val future = renderer.inflateAsync(layout, androidx.wear.protolayout.ResourceBuilders.Resources.Builder().setVersion("1").build(), parent)
                shadowOf(Looper.getMainLooper()).idle()
                future.get(10, java.util.concurrent.TimeUnit.SECONDS)
                val spec = android.view.View.MeasureSpec.makeMeasureSpec(size, android.view.View.MeasureSpec.EXACTLY)
                parent.measure(spec, spec)
                parent.layout(0, 0, size, size)
                val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                c.drawColor(Color.BLACK)
                parent.draw(c)
                val mask = Path().apply {
                    addRect(0f, 0f, size.toFloat(), size.toFloat(), Path.Direction.CW)
                    addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CCW)
                }
                c.drawPath(mask, Paint().apply { color = Color.rgb(40, 40, 40) })
                FileOutputStream(File(out, "battery_tile_${label}_$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        org.robolectric.RuntimeEnvironment.setFontScale(1f)
    }

    /** '지금 할 일' 후보 전부 — 제목 두 줄·설명 세 줄 안에 잘리지 않고 들어가는지 (TodoText 로 맞춘 글) */
    @Test
    fun batteryTodos() {
        val now = 1_792_500_000_000L
        val day = 24 * 3_600_000L
        val gmsRank = com.mywatch.battery.core.AppDrainRank(com.mywatch.battery.core.UidUse("com.google.android.gms", cpuMs = 40 * 60_000L), 1e7, listOf("stuck"), com.mywatch.battery.core.Grade.ABNORMAL)
        val faceRank = com.mywatch.battery.core.AppDrainRank(com.mywatch.battery.core.UidUse("com.example.face", cpuMs = 30 * 60_000L), 1e6, emptyList(), com.mywatch.battery.core.Grade.CAUTION)
        val issues = com.mywatch.battery.core.KnownIssues.detect(listOf(gmsRank, faceRank), "com.example.face", 2 * 3_600_000L)
        val drain = (0..96).map { i -> com.mywatch.battery.core.BatterySample(t = now - (96 - i) * 15 * 60_000L, level = (100 - 4.0 * i / 4).toInt()) }
        val analysis = com.mywatch.battery.core.DrainAnalyzer(445, java.time.ZoneId.of("Asia/Seoul")).analyze(drain, now)
        val wakes = com.mywatch.battery.core.WakeStats(400, 300, 120, 200, 600_000L, 150, listOf("com.kakao.talk" to 120), 20.0, 40, 120_000L)
        val night = com.mywatch.battery.core.NightReport(0, 1, 7.0, emptyList(), listOf("…"), listOf("삼성 헬스 센서 밤새 시간당 118분" to "삼성 헬스 › 설정에서 수면 중 혈중 산소·피부 온도 측정 끄기"))
        val charging = com.mywatch.battery.core.ChargeHabits(7.0, 7, 12.5, false, 44.5, listOf("a", "b"),
            listOf("충전기에서 하루 12.5시간 100%" to "설정 › 배터리 › 배터리 보호 켜기 (90%에서 멈춰 덜 늙음)", "충전 중 최고 44.5℃" to "충전할 땐 벗기고 햇빛·이불 속·두꺼운 케이스 피하기"))
        val ins = com.mywatch.kakaomap.battery.Insights(analysis, null, emptyList(), issues, com.mywatch.battery.core.LeverEffect(emptyList(), emptyList()), wakes, night,
            charging = charging, bootAt = now - 12 * day)
        val all = com.mywatch.kakaomap.battery.BatteryTodo.candidates(ins, permitted = false, label = { if (it == "com.kakao.talk") "카카오톡" else it }, now = now)
        val rows = listOf<Row>(Row.Header("지금 할 일")) + all.mapIndexed { i, t ->
            Row.Step(0xFFFFC107.toInt(), R.drawable.ic_warning, "${i + 1}", t.title, t.detail, key = "todo:$i")
        }
        shoot("battery_todos", rows, pages = 6)
    }

    /** 실제 배터리 화면: 하루 반 기록(밤 충전 포함)으로 맨 위 지금 줄·그래프·지금 할 일·소모율·지킴이 칸 */
    @Test
    fun batteryScreen() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val log = com.mywatch.kakaomap.battery.BatteryLog(app)
        log.dir.deleteRecursively()
        val now = System.currentTimeMillis()
        val q = 15 * 60_000L
        var level = 70.0
        var t = now - 30 * 3_600_000L
        while (t <= now) {
            val hour = java.time.Instant.ofEpochMilli(t).atZone(java.time.ZoneId.systemDefault()).hour
            val plugged = hour in 1..2
            level = if (plugged) minOf(100.0, level + 12) else maxOf(5.0, level - (if (hour in 12..13) 2.5 else 0.6))
            log.append(com.mywatch.battery.core.BatterySample(t = t, level = level.toInt(), plugged = plugged, voltageMv = 3900, tempDeciC = 320, currentUa = -60_000))
            t += q
        }
        val controller = Robolectric.buildActivity(com.mywatch.kakaomap.battery.BatteryActivity::class.java)
        val activity = controller.get()
        activity.requestWindowFeature(Window.FEATURE_NO_TITLE)
        controller.setup()
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && rowsOf(activity).none { it is Row.Chart }) {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
        }
        capture(activity, "battery_screen", pages = 16)
        controller.pause().stop().destroy()
        log.dir.deleteRecursively()
    }

    /** 실제 화면(액티비티)을 둥근 워치 크기로 — [shoot] 와 같은 모양 */
    private fun capture(activity: android.app.Activity, name: String, pages: Int) {
        val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.list)
        list.scrollToPosition(0)
        shadowOf(Looper.getMainLooper()).idle()
        val root = activity.window.decorView
        val size = activity.resources.displayMetrics.widthPixels
        for (page in 0 until pages) {
            if (page > 0) {
                list.scrollBy(0, (size * 0.8f).toInt())
                shadowOf(Looper.getMainLooper()).idle()
            }
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(Color.BLACK)
            root.draw(c)
            val mask = Path().apply {
                addRect(0f, 0f, size.toFloat(), size.toFloat(), Path.Direction.CW)
                addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CCW)
            }
            c.drawPath(mask, Paint().apply { color = Color.rgb(40, 40, 40) })
            FileOutputStream(File(out, "${name}_$page.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
