package com.mywatch.kakaomap

import com.mywatch.kakaomap.model.Coord
import com.mywatch.kakaomap.nav.StillDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 멈춤/움직임 판단 — 위치를 얼마나 자주 받을지(배터리)를 정한다.
 * GPS 는 도플러 속도가 있어 믿을 만하지만, Wi-Fi·기지국 위치는 속도가 없고 수십 m 씩 튄다.
 * 튄 것을 '걷기 시작'으로 보면 GPS 를 다시 켰다 껐다 반복해 배터리를 더 쓴다.
 */
class StillDetectorTest {

    private val home = Coord(36.35, 127.38)
    private fun north(m: Double) = Coord(home.lat + m / 111_130.0, home.lng)

    @Test
    fun gpsSpeedDecidesWithHysteresis() {
        val d = StillDetector()
        d.update(0, home, 5f, speed = 1.3f)
        assertFalse(d.still)
        d.update(1_000, home, 5f, speed = 0.6f)      // 0.4~0.9 사이: 그대로 '움직임'
        assertFalse(d.still)
        d.update(2_000, home, 5f, speed = 0.2f)
        assertTrue(d.still)
        assertEquals(0f, d.speedMps!!, 0f)
        d.update(3_000, home, 5f, speed = 0.7f)      // 아직 '멈춤'
        assertTrue(d.still)
        d.update(4_000, north(1.0), 5f, speed = 1.2f)
        assertFalse(d.still)
    }

    @Test
    fun coarseNetworkFixesDoNotWakeGpsWhileSittingStill() {
        val d = StillDetector()
        d.update(0, home, 8f, speed = 0.1f)          // 실내 GPS: 멈춤
        assertTrue(d.still)
        // 저전력(Wi-Fi·기지국)으로 바꾼 직후 첫 위치가 35m 옆 (속도 없음) — 오차 안이라 그대로 멈춤
        d.update(4_000, north(35.0), 40f, speed = null)
        assertTrue(d.still)
        // 15초마다 이리저리 튀어도(오차 안) 멈춤
        d.update(19_000, north(-20.0), 40f, speed = null)
        d.update(34_000, north(30.0), 45f, speed = null)
        assertTrue(d.still)
    }

    @Test
    fun cellOnlyFixesStillNoticeALongWalk() {
        // Wi-Fi 없이 기지국 위치만(오차 수백 m~1km): 오차를 그대로 더하면 아무리 걸어도 '멈춤'이 되므로 오차는 50m 까지만 친다
        val d = StillDetector()
        d.update(0, home, 8f, speed = 0.1f)
        d.update(60_000, north(30.0), 900f, speed = null)
        assertTrue(d.still)
        d.update(240_000, north(400.0), 1_200f, speed = null)
        assertFalse(d.still)
    }

    @Test
    fun walkingAwayIsNoticedFromNetworkFixes() {
        val d = StillDetector()
        d.update(0, home, 8f, speed = 0.1f)
        d.update(15_000, north(25.0), 35f, speed = null)
        assertTrue(d.still)
        // 멈췄던 곳에서 오차보다 확실히 멀어짐 → 움직임 (GPS 를 다시 켜도 됨)
        d.update(75_000, north(120.0), 35f, speed = null)
        assertFalse(d.still)
        // 움직이는 중 속도 없는 위치: 오차를 뺀 이동으로 셈 — 정말 멈추면 다시 멈춤
        d.update(90_000, north(122.0), 30f, speed = null)
        d.update(105_000, north(121.0), 30f, speed = null)
        assertTrue(d.still)
    }
}
