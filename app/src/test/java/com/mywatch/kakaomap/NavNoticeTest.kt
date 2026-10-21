package com.mywatch.kakaomap

import com.mywatch.kakaomap.nav.NavNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 안내 알림(진행 중 활동·실시간 업데이트 칩)은 위치마다(1~5초) 바뀌던 1m 단위 남은 거리 때문에 5초마다 다시 올라갔다 —
 * 시계 화면·시스템 UI 가 그때마다 깨어남. 남은 거리는 50m(1km 넘으면 100m) 단위로, 화면이 꺼져 있으면 30초에 한 번(중요한 것은 바로)
 */
class NavNoticeTest {

    @Test
    fun remainingDistanceIsRoundedForTheNotice() {
        assertEquals(350, NavNotice.round(337))
        assertEquals(350, NavNotice.round(362))
        assertEquals(50, NavNotice.round(12))
        assertEquals(1_200, NavNotice.round(1_234))
        assertEquals(12_300, NavNotice.round(12_288))
        assertEquals("좌회전 · 남은 350m", NavNotice.text("좌회전", 337, arrived = false))
        assertEquals("좌회전 · 남은 350m", NavNotice.text("좌회전", 361, arrived = false))
        assertEquals("도착", NavNotice.text("도착", 5, arrived = true))
    }

    @Test
    fun screenOffUpdatesAreSparseButImportantOnesGoAtOnce() {
        // 화면 켜짐: 5초
        assertTrue(NavNotice.shouldUpdate(changed = true, sinceLastMs = 6_000, interactive = true, important = false))
        assertFalse(NavNotice.shouldUpdate(changed = true, sinceLastMs = 4_000, interactive = true, important = false))
        // 화면 꺼짐: 30초
        assertFalse(NavNotice.shouldUpdate(changed = true, sinceLastMs = 20_000, interactive = false, important = false))
        assertTrue(NavNotice.shouldUpdate(changed = true, sinceLastMs = 31_000, interactive = false, important = false))
        // 도착·이탈·다시 찾기·다음 할 일이 바뀜 → 바로
        assertTrue(NavNotice.shouldUpdate(changed = true, sinceLastMs = 1_000, interactive = false, important = true))
        // 글이 그대로면 올리지 않음
        assertFalse(NavNotice.shouldUpdate(changed = false, sinceLastMs = 100_000, interactive = true, important = true))
    }
}
