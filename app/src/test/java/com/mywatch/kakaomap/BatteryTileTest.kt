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
        assertTrue(s.title, s.title.startsWith("충전 중 "))
        // 제목이 '충전 중 N%' 이라 소모율 줄은 짧게 '측정 쉼' (방전 기록이 거의 없으면)
        assertEquals(BatteryTileService.RATE_CHARGING, s.rate)
    }

    /** 충전 중엔 지금 소모율 대신 하루 평균(방전 구간만) — 밤에 충전하며 오늘 하루가 어땠는지 본다. 방전이 3시간도 안 되면 말하지 않는다 */
    @Test
    fun chargingShowsTheDaysAverage() {
        val now = at(22)
        drain(now - 2 * q, 17, 71.0, 4.0)                                   // 4시간 시간당 4%
        log.append(BatterySample(now - q, 55, plugged = true))
        log.append(BatterySample(now, 57, plugged = true))
        val s = BatteryTileService.snapshot(context, now, zone)
        assertTrue(s.title, s.title.startsWith("충전 중 "))
        assertTrue(s.rate, s.rate.startsWith("하루 평균 시간당 4."))
        assertEquals(BatteryTileService.FORECAST_CHARGING, s.forecast)
        assertEquals(BatteryTileService.FORECAST_CHARGING, s.forecastSmall)
        // 방전 기록이 짧으면(2시간) 평균 대신 '측정 쉼'
        fresh()
        drain(now - 2 * q, 9, 63.0, 4.0)
        log.append(BatterySample(now - q, 55, plugged = true))
        log.append(BatterySample(now, 57, plugged = true))
        assertEquals(BatteryTileService.RATE_CHARGING, BatteryTileService.snapshot(context, now, zone).rate)
    }

    /** 잔량 표시가 크게 어긋나면 어느 쪽인지와 할 일을 짧게 — 작은 타일 두 줄을 넘던 긴 문장 대신 */
    @Test
    fun aDriftingGaugeSaysWhichWayInAFewWords() {
        val now = at(12)
        for (i in 0..96) log.append(BatterySample(now - (96 - i) * q, (60 - 1.5 * i / 4).toInt(), voltageMv = 3950, currentUa = -15_000))
        val s = BatteryTileService.snapshot(context, now, zone).status
        assertTrue(s, s.startsWith("잔량 표시가") && s.contains("포인트 낮음"))
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

    /**
     * 알려진 문제마다 그 문제의 할 일 — 무거운 시계 화면에 '캐시 지우기'라 하지 않는다. 주의 등급 문제는 지킴이가 지금 바꿔 둔
     * 상태(밤 모드) 다음 (리뷰에서 찾음)
     */
    @Test
    fun aWatchFaceIssueSaysToSwitchFacesNotClearCache() {
        val now = at(12)
        drain(now, 9, 63.0, 4.0)
        com.mywatch.kakaomap.battery.BatteryInsights.watchFace = { "com.example.face" }
        try {
            var cpu = 0L
            val stats = com.mywatch.kakaomap.battery.UidStatsSource { mapOf("com.example.face" to UidUse("com.example.face", cpuMs = cpu)) }
            Guardian.tick(context, log, now - 2 * 3_600_000L, stats = stats, zone = zone)
            cpu = 12 * 60_000L   // 화면 밖 CPU 시간당 6분 — 주의 등급
            Guardian.tick(context, log, now - 60_000L, stats = stats, zone = zone)
        } finally {
            com.mywatch.kakaomap.battery.BatteryInsights.watchFace = com.mywatch.kakaomap.battery.BatteryInsights.DEFAULT_FACE
        }
        val s = BatteryTileService.snapshot(context, now, zone).status
        assertTrue(s, s.contains("시계 화면") && !s.contains("캐시"))
        Guardian.saveState(context, Guardian.state(context).copy(nightActive = true))
        assertTrue(BatteryTileService.snapshot(context, now, zone).status.contains("밤 모드"))
    }

    /** 배터리 화면을 열어 남긴 스냅샷으로도 알려진 문제를 다시 본다 — 화면을 자주 열어 15분 기록의 확인이 밀려도 타일 줄이 사라지지 않게 */
    @Test
    fun openingTheScreenAlsoRefreshesTheTilesIssue() {
        val now = at(12)
        drain(now, 9, 63.0, 4.0)
        var cpu = 0L
        val stats = com.mywatch.kakaomap.battery.UidStatsSource { mapOf("com.google.android.gms" to UidUse("com.google.android.gms", cpuMs = cpu)) }
        Guardian.snapshotIfStale(context, now - 2 * 3_600_000L, source = stats)
        cpu = 40 * 60_000L
        Guardian.snapshotIfStale(context, now, source = stats)
        assertTrue(Guardian.tileIssue(context, now) ?: "", (Guardian.tileIssue(context, now) ?: "").contains("Play 서비스"))
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
        // 절전이 실제로 켜졌을 때만 '절전 켬' (이 워치에서 설정으로 안 켜지거나 못 켰으면 아니다 — 리뷰에서 찾음)
        assertFalse(BatteryTileService.snapshot(context, now, zone).status.contains("절전 켬"))
        shadowOf(context.getSystemService(android.os.PowerManager::class.java)).setIsPowerSaveMode(true)
        assertTrue(BatteryTileService.snapshot(context, now, zone).status.contains("절전 켬"))
        Guardian.saveState(context, Guardian.state(context).copy(saverBroken = true))
        assertFalse(BatteryTileService.snapshot(context, now, zone).status.contains("절전 켬"))
    }

    /**
     * 워치가 타일에 보내는 기기 값(폭·글꼴 배율·렌더러 버전)을 적어 두어 하루 요약에 '[타일]' 줄로 — 맞춤 시험(BatteryTileFitTest)의
     * 어림(렌더러 1.500·글꼴 1배)을 실제 워치 값으로 맞추려고 (리뷰 권고). 같은 값이면 다시 쓰지 않는다
     */
    @Test
    fun theTileDeviceIsNotedForTheDailySummary() {
        val d = androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters.Builder()
            .setScreenWidthDp(226).setScreenHeightDp(226).setFontScale(1.12f)
            .setRendererSchemaVersion(androidx.wear.protolayout.expression.VersionBuilders.VersionInfo.Builder().setMajor(1).setMinor(502).build())
            .build()
        BatteryTileService.noteDevice(context, d)
        val note = com.mywatch.kakaomap.battery.TileDeviceNote.read(context)
        assertEquals("226dp · 글꼴 1.12배 · 렌더러 1.502", note)
        val now = at(12)
        drain(now, 9, 63.0, 4.0)
        val samples = log.readAll()
        val a = com.mywatch.battery.core.DrainAnalyzer(445, zone).analyze(samples, now)
        val ins = com.mywatch.kakaomap.battery.Insights(a, null, emptyList(), emptyList(), com.mywatch.battery.core.LeverEffect(emptyList(), emptyList()), tileDevice = note)
        val text = com.mywatch.kakaomap.battery.BatterySummary.text(a, null, "SM-L350", now, ins)
        assertTrue(text, text.contains("[타일] 226dp · 글꼴 1.12배 · 렌더러 1.502"))
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
