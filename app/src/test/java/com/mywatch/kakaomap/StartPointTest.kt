package com.mywatch.kakaomap

import com.mywatch.kakaomap.model.Coord
import com.mywatch.kakaomap.model.Fix
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 길찾기 출발지로 '방금 받은 위치'를 그대로 써도 되는지.
 * 도착 뒤·지하철·멈춰 있을 때 안내 서비스는 Wi-Fi·기지국 위치(수백 m 오차)로 바꾸므로,
 * 그 위치로 다음 길을 찾으면 엉뚱한 곳에서 출발하는 경로가 나온다.
 */
class StartPointTest {
    private val here = Coord(36.3504, 127.3845)

    @Test
    fun onlyAccurateFixesAreReusedAsTheStart() {
        assertTrue(Fix(here, 6f, 0).goodForStart)       // GPS
        assertTrue(Fix(here, 50f, 0).goodForStart)      // 도심 Wi-Fi 정도까지
        assertFalse(Fix(here, 120f, 0).goodForStart)    // Wi-Fi·기지국
        assertFalse(Fix(here, 900f, 0).goodForStart)    // 기지국
        assertFalse(Fix(here, Float.NaN, 0).goodForStart) // 정확도를 모름
    }
}

/**
 * 위치 권한 창: 두 번 거절해 막힌 뒤엔 시스템이 창을 띄우지 않고 곧바로 '거절'을 돌려준다.
 * 그때만 앱 설정 화면으로 보낸다 — 첫 창을 쓸어 닫았을 때(다시 물을 수 있음)는 평소처럼 창을 띄운다.
 */
class LocationPermissionTest {
    @Test
    fun onlyAnInstantDenialMeansTheSystemNoLongerAsks() {
        assertTrue(com.mywatch.kakaomap.location.PermissionHelp.deniedWithoutDialog(askedAtMs = 10_000, answeredAtMs = 10_150))
        assertFalse(com.mywatch.kakaomap.location.PermissionHelp.deniedWithoutDialog(askedAtMs = 10_000, answeredAtMs = 12_400))
        assertFalse(com.mywatch.kakaomap.location.PermissionHelp.deniedWithoutDialog(askedAtMs = 0, answeredAtMs = 150))   // 물은 적 없음
    }
}
