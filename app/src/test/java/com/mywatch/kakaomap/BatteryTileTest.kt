package com.mywatch.kakaomap

import android.app.Application
import android.content.Context
import com.mywatch.battery.core.BatterySample
import com.mywatch.battery.core.UidUse
import com.mywatch.kakaomap.battery.BatteryLog
import com.mywatch.kakaomap.battery.Guardian
import com.mywatch.kakaomap.battery.HealthStore
import com.mywatch.kakaomap.tile.BatteryTileService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

/** 타일 '배터리': 잔량 · 소모율과 판정 · 이 페이스면 언제 15% · 지킴이 상태(알려진 문제가 있으면 그것) */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BatteryTileTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val zone: ZoneId = ZoneId.of("Asia/Seoul")
    private val q = 15 * 60_000L
    private lateinit var log: BatteryLog
    private fun at(h: Int, m: Int = 0) = LocalDateTime.of(2026, 10, 21, h, m).atZone(zone).toInstant().toEpochMilli()

    @Before
    fun fresh() {
        log = BatteryLog(context)
        log.dir.deleteRecursively()
        context.getSharedPreferences(BatteryLog.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun drain(end: Long, count: Int, start: Double, pph: Double, plugged: Boolean = false) {
        for (i in 0 until count) log.append(BatterySample(end - (count - 1 - i) * q, (start - pph * i / 4).toInt(), plugged = plugged))
    }

    @Test
    fun showsLevelRatePaceAndGuardianState() {
        val now = at(12)
        drain(now, 9, 63.0, 4.0)        // 2시간 시간당 4% → 55%
        val s = BatteryTileService.snapshot(context, now, zone)
        assertEquals("배터리 55%", s.title)
        assertTrue(s.rate, s.rate.contains("시간당 4."))
        // 소모율은 위 줄에 있으니 언제 15%·0% 인지만 짧게 — 작은 글씨 두 줄에 0% 시각이 잘리지 않게 (점검에서 찾음)
        assertTrue(s.forecast, s.forecast.contains("15% ") && s.forecast.contains("0% ") && !s.forecast.contains("시간당"))
        assertTrue(s.forecast, com.mywatch.battery.core.TodoText.width(s.forecast) <= 22)
        assertTrue(s.status, s.status.contains("지킴이"))
    }

    @Test
    fun chargingSaysSo() {
        val now = at(12)
        drain(now, 9, 40.0, -8.0, plugged = true)
        val s = BatteryTileService.snapshot(context, now, zone)
        assertTrue(s.title, s.title.endsWith("충전 중"))
        assertTrue(s.rate, s.rate.contains("충전 중"))
    }

    /** 알려진 문제는 15분 기록(지킴이)이 찾아 적어 둔 것을 보인다 — 타일이 앱별 통계 파일을 읽지 않게 (점검에서 찾음) */
    @Test
    fun aKnownIssueTakesTheStatusLine() {
        val now = at(12)
        drain(now, 9, 63.0, 4.0)
        var cpu = 0L
        val stats = com.mywatch.kakaomap.battery.UidStatsSource { mapOf("com.google.android.gms" to UidUse("com.google.android.gms", cpuMs = cpu)) }
        Guardian.tick(context, log, now - 2 * 3_600_000L, stats = stats, zone = zone)
        cpu = 40 * 60_000L
        Guardian.tick(context, log, now - 60_000L, stats = stats, zone = zone)
        val s = BatteryTileService.snapshot(context, now, zone)
        assertTrue(s.status, s.status.contains("Play 서비스") && s.status.contains("캐시"))
        // 오래된 것(3시간 넘게 다시 확인 못 함)은 보이지 않는다
        assertFalse(BatteryTileService.snapshot(context, now + 4 * 3_600_000L, zone).status.contains("캐시"))
    }

    /** 지킴이가 지금 설정을 바꿔 둔 상태(밤 모드·비정상 절전)는 '언제부터 더 닳음' 줄보다 먼저 — 지금 무엇이 바뀌어 있는지가 급하다 */
    @Test
    fun theGuardiansCurrentStateComesBeforeTheTrendLine() {
        val now = at(12)
        drain(now, 9, 63.0, 1.0)
        context.getSharedPreferences(BatteryLog.PREFS, Context.MODE_PRIVATE).edit()
            .putString(com.mywatch.kakaomap.battery.BatteryAlerts.KEY_TREND_LINE, "소프트웨어 업데이트 뒤 더 닳음")
            .putLong(com.mywatch.kakaomap.battery.BatteryAlerts.KEY_TREND_UNTIL, now + 3_600_000L).commit()
        Guardian.saveState(context, Guardian.state(context).copy(abnormalActive = true))
        assertTrue(BatteryTileService.snapshot(context, now, zone).status.contains("비정상 소모"))
    }

    @Test
    fun emptyLogStillDrawsSomething() {
        val s = BatteryTileService.snapshot(context, at(12), zone)
        assertTrue(s.title.startsWith("배터리"))
        assertTrue(s.rate.isNotBlank() && s.forecast.isNotBlank() && s.status.isNotBlank())
    }

    /** 하루 요약이 정해 둔 '언제부터 더 닳음' 줄이 있으면 지킴이 상태 대신 그것 (알려진 문제 다음 차례) */
    @Test
    fun aRecentRiseShowsOnTheTile() {
        val now = at(12)
        drain(now, 9, 63.0, 1.0)
        context.getSharedPreferences(BatteryLog.PREFS, Context.MODE_PRIVATE).edit()
            .putString(com.mywatch.kakaomap.battery.BatteryAlerts.KEY_TREND_LINE, "소프트웨어 업데이트 뒤 더 닳음")
            .putLong(com.mywatch.kakaomap.battery.BatteryAlerts.KEY_TREND_UNTIL, now + 3_600_000L).commit()
        assertEquals("소프트웨어 업데이트 뒤 더 닳음", BatteryTileService.snapshot(context, now, zone).status)
        // 기한이 지나면 다시 지킴이 상태
        assertTrue(BatteryTileService.snapshot(context, now + 2 * 3_600_000L, zone).status.contains("지킴이"))
    }
}
