package com.mywatch.kakaomap

import com.mywatch.kakaomap.MapApp.ProcessRole
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 앱이 시작될 때 하는 일(지도 엔진·업데이트 예약)은 앱 본체 프로세스에서만 — 유튜브 재생 화면(:player)과
 * 그 안의 브라우저 엔진(GeckoView)이 띄우는 프로세스 수십 개(:tab…·:gpu…·:media 등, 일부는 권한 없는 격리 프로세스)에서는 하지 않는다
 */
class AppProcessTest {
    private val pkg = "com.mywatch.kakaomap"

    @Test
    fun onlyTheMainProcessStartsTheAppsBackgroundWork() {
        assertEquals(ProcessRole.MAIN, MapApp.roleOf(pkg, pkg))
        assertEquals("이름을 모르면 본체로 (예전처럼)", ProcessRole.MAIN, MapApp.roleOf(null, pkg))
        assertEquals(ProcessRole.PLAYER, MapApp.roleOf("$pkg:player", pkg))
        for (p in listOf(":tab_disable_art_image_0", ":isolatedTab_disable_art_image_3", ":gpu_disable_art_image_", ":media", ":crashhelper_disable_art_image_")) {
            assertEquals(p, ProcessRole.ENGINE, MapApp.roleOf(pkg + p, pkg))
        }
    }
}
