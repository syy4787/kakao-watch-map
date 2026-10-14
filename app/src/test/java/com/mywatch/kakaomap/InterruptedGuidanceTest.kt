package com.mywatch.kakaomap

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import androidx.core.content.edit
import com.mywatch.kakaomap.data.ActiveRoute
import com.mywatch.kakaomap.data.RouteRepository
import com.mywatch.kakaomap.model.Coord
import com.mywatch.kakaomap.model.Fix
import com.mywatch.kakaomap.model.GuideStep
import com.mywatch.kakaomap.model.Place
import com.mywatch.kakaomap.model.TravelMode
import com.mywatch.kakaomap.nav.Navigator
import com.mywatch.kakaomap.ui.DirectionsActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * 안내 중에 앱 본체 프로세스가 꺼지면(2GB 워치에서 동영상 재생 화면이 메모리를 많이 쓸 때 등) 경로·안내가 말없이 사라졌다.
 * 안내를 시작한 목적지는 기기에 적어 두고, 정상으로 끝나면(도착·종료·스스로 끝냄) 지운다 —
 * 다음에 지도를 열 때 남아 있으면 '끊긴 안내'로 그 목적지를 다시 찾아 준다 (6시간 안).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class InterruptedGuidanceTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val place = Place("p1", "한밭수목원", "공원", "대전 서구", "대전 서구 둔산대로 169", Coord(36.367, 127.388), null, "AT4")
    private var now = 1_760_000_000_000L

    private fun route(navigate: Boolean, mode: TravelMode = TravelMode.WALK) =
        ActiveRoute(mode, place, "요약", Coord(36.35, 127.38), emptyList(), emptyList(), startNavigation = navigate)

    @Before
    fun setUp() {
        RouteRepository.init(context, clock = { now })
        RouteRepository.activeRoute = null
        Navigator.sync()
    }

    @After
    fun tearDown() {
        RouteRepository.activeRoute = null
        Navigator.sync()
        MapApp.initError = null
    }

    /** 안내 중 프로세스가 꺼진 것처럼: 메모리의 경로만 사라지고 기기에 적어 둔 것은 남는다 */
    private fun diedDuringGuidance(mode: TravelMode = TravelMode.WALK) {
        val prefs = context.getSharedPreferences("route_prefs", Context.MODE_PRIVATE)
        RouteRepository.activeRoute = route(navigate = true, mode = mode)
        val written = prefs.all.filterValues { it is String }
        RouteRepository.activeRoute = null
        prefs.edit { written.forEach { (k, v) -> putString(k, v as String) } }
    }

    @Test
    fun guidanceThatDiedWithTheProcessIsOfferedAgain() {
        RouteRepository.activeRoute = route(navigate = true, mode = TravelMode.BIKE)
        // (여기서 프로세스가 꺼짐 — 메모리의 경로는 사라지고 기록만 남음)
        val back = RouteRepository.takeInterrupted(context)
        assertEquals(place.name, back?.place?.name)
        assertEquals(place.coord, back?.place?.coord)
        assertEquals(TravelMode.BIKE, back?.mode)
        assertNull("한 번만", RouteRepository.takeInterrupted(context))
    }

    @Test
    fun guidanceThatEndedNormallyIsNotOffered() {
        RouteRepository.activeRoute = route(navigate = true)
        RouteRepository.activeRoute = null   // 도착·종료·스스로 끝냄
        assertNull(RouteRepository.takeInterrupted(context))
    }

    @Test
    fun aRouteOnlyShownOnTheMapIsNotGuidance() {
        RouteRepository.activeRoute = route(navigate = false)
        assertNull(RouteRepository.takeInterrupted(context))
    }

    @Test
    fun aLongAgoInterruptionIsForgotten() {
        RouteRepository.activeRoute = route(navigate = true)
        now += 7 * 3_600_000L
        assertNull(RouteRepository.takeInterrupted(context))
    }

    /**
     * 리뷰에서 찾음: 앱이 정리된 뒤 최근 앱·허브로 지도에 돌아오면 Android 가 저장해 둔 상태와 함께 화면을 다시 만든다.
     * 저장된 상태가 있으면 묻지 않아서 바로 그 경우(가장 흔한 경우)에 끊긴 안내를 놓쳤고, 기록은 남아 몇 시간 뒤 엉뚱할 때 떴다
     */
    @Test
    fun theMapRestoredAfterTheAppWasClosedOffersTheLostGuidance() {
        diedDuringGuidance(TravelMode.BIKE)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup(Bundle()).get()
        assertEquals(DirectionsActivity::class.java.name, shadowOf(activity).nextStartedActivity?.component?.className)
        assertEquals(place.name, RouteRepository.destination?.name)
        assertEquals(context.getString(R.string.guidance_interrupted, place.name), ShadowToast.getTextOfLatestToast())
        assertNull("한 번만", RouteRepository.takeInterrupted(context))
    }

    @Test
    fun theLostGuidanceIsOfferedEvenWhenTheMapEngineDidNotStart() {
        // 지도를 못 그려도 진동 안내는 되므로 다시 찾아 준다 (지도 엔진 초기화 실패로 일찍 끝나기 전에)
        diedDuringGuidance()
        MapApp.initError = IllegalStateException("지도 엔진")
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertEquals(DirectionsActivity::class.java.name, shadowOf(activity).nextStartedActivity?.component?.className)
    }

    @Test
    fun aMapRecreatedWhileTheRouteIsStillUpOffersNothing() {
        // 같은 프로세스 안에서 화면만 다시 만들어진 경우 (경로가 그대로 있음)
        RouteRepository.activeRoute = route(navigate = true)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup(Bundle()).get()
        val next = shadowOf(activity).nextStartedActivity
        assertTrue(next?.component?.className.toString(), next?.component?.className != DirectionsActivity::class.java.name)
        assertNotNull("기록은 그대로 (안내 중)", RouteRepository.takeInterrupted(context))
    }

    /**
     * 리뷰에서 찾음: 도착한 뒤 5분 동안은 경로를 남겨 두는데(목적지까지 직선거리), 그 사이 앱이 꺼지면
     * 다 끝난 여정을 다음에 '끊긴 안내'로 다시 찾아 줬다 → 도착하면 바로 지운다
     */
    @Test
    fun arrivingForgetsTheGuidanceWhileTheRouteStaysUpForAFewMinutes() {
        val start = Coord(37.0, 127.000)
        val dest = Coord(37.0, 127.002)
        RouteRepository.activeRoute = ActiveRoute(
            TravelMode.WALK, place.copy(coord = dest), "요약", start, emptyList(),
            listOf(GuideStep("출발", listOf(start, dest))), startNavigation = true,
        )
        Navigator.sync()
        repeat(3) {
            SystemClock.sleep(1_000)
            Navigator.onFix(context, Fix(dest, 5f, SystemClock.elapsedRealtime()))
        }
        assertTrue(Navigator.state.value.arrived)
        assertNotNull("경로는 몇 분 더 보인다", RouteRepository.activeRoute)
        assertNull(RouteRepository.takeInterrupted(context))
    }
}
