package com.mywatch.kakaomap

import android.app.Application
import android.content.Context
import com.mywatch.battery.core.BatterySample
import com.mywatch.battery.core.UidUse
import com.mywatch.kakaomap.battery.BatteryLog
import com.mywatch.kakaomap.battery.HealthStore
import com.mywatch.kakaomap.tile.BatteryTileService
import org.junit.Assert.assertEquals
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
        assertTrue(s.forecast, s.forecast.startsWith("이 페이스") && s.forecast.contains("15%"))
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

    @Test
    fun aKnownIssueTakesTheStatusLine() {
        shadowOf(context as Application).grantPermissions("android.permission.BATTERY_STATS")
        val now = at(12)
        drain(now, 9, 63.0, 4.0)
        val store = HealthStore(context)
        store.record(now - 2 * 3_600_000L, mapOf("com.google.android.gms" to UidUse("com.google.android.gms")))
        store.record(now - 60_000L, mapOf("com.google.android.gms" to UidUse("com.google.android.gms", cpuMs = 40 * 60_000L)))
        val s = BatteryTileService.snapshot(context, now, zone)
        assertTrue(s.status, s.status.contains("Play 서비스") && s.status.contains("캐시"))
    }

    @Test
    fun emptyLogStillDrawsSomething() {
        val s = BatteryTileService.snapshot(context, at(12), zone)
        assertTrue(s.title.startsWith("배터리"))
        assertTrue(s.rate.isNotBlank() && s.forecast.isNotBlank() && s.status.isNotBlank())
    }
}
