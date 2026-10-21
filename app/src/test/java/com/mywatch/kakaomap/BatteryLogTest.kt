package com.mywatch.kakaomap

import android.app.Application
import android.content.Context
import com.mywatch.battery.core.BatterySample
import com.mywatch.battery.core.Event
import com.mywatch.battery.core.SampleCsv
import com.mywatch.kakaomap.battery.BatteryLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 배터리 표본 파일: 덧붙이고, 깨진 줄은 건너뛰고, 30일 지난 줄은 잘라낸다 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BatteryLogTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var log: BatteryLog
    private val now = 1_760_000_000_000L

    @Before
    fun fresh() {
        log = BatteryLog(context)
        log.dir.deleteRecursively()
    }

    @Test
    fun appendsAndReadsBackWithTheHeader() {
        val a = BatterySample(now, 57, chargeUah = 250_000, event = Event.PERIODIC)
        val b = BatterySample(now + 900_000, 56, event = Event.POWER_CONNECTED, plugged = true)
        log.append(a)
        log.append(b)
        assertEquals(listOf(a, b), log.readAll())
        val lines = log.file.readLines()
        assertEquals(SampleCsv.MAGIC, lines[0])
        assertEquals(SampleCsv.HEADER, lines[1])
        assertEquals(4, lines.size)
        assertTrue("PC 에서 adb pull 로 가져갈 수 있는 앱 전용 바깥 폴더", log.file.path.contains("/battery/"))
    }

    @Test
    fun brokenLinesDoNotBreakReading() {
        log.append(BatterySample(now, 57))
        log.file.appendText("12,ab\n")           // 쓰다 꺼진 줄
        log.append(BatterySample(now + 1, 56))
        assertEquals(listOf(57, 56), log.readAll().map { it.level })
    }

    @Test
    fun pruningDropsRowsOlderThanThirtyDays() {
        val old = BatterySample(now - 31L * 86_400_000L, 90)
        val fresh = BatterySample(now - 29L * 86_400_000L, 80)
        log.append(old)
        log.append(fresh)
        assertEquals(1, log.pruneOlderThan(30, now))
        assertEquals(listOf(fresh), log.readAll())
        assertEquals(SampleCsv.MAGIC, log.file.readLines()[0])
    }

    @Test
    fun pruneRunsAtMostOnceADay() {
        log.append(BatterySample(now - 40L * 86_400_000L, 90))
        log.pruneIfDue(now)
        assertEquals(0, log.readAll().size)
        log.append(BatterySample(now - 40L * 86_400_000L, 90))
        log.pruneIfDue(now + 3_600_000L)   // 한 시간 뒤엔 다시 하지 않는다
        assertEquals(1, log.readAll().size)
        log.pruneIfDue(now + 25 * 3_600_000L)
        assertEquals(0, log.readAll().size)
    }

    /** 시계가 뒤로 가 '마지막으로 자른 때'가 미래가 돼도 하루 한 번 자르기는 이어진다 — 시계가 따라잡을 때까지 멈추지 않게 (시나리오 점검) */
    @Test
    fun aPruneStampFromTheFutureDoesNotStopPruning() {
        context.getSharedPreferences(BatteryLog.PREFS, android.content.Context.MODE_PRIVATE).edit().putLong("pruned_at", now + 5 * 86_400_000L).commit()
        log.append(BatterySample(now - 40L * 86_400_000L, 90))
        log.pruneIfDue(now)
        assertEquals(0, log.readAll().size)
    }

    @Test
    fun writesASummaryNextToTheLog() {
        val f = log.writeSummary("요약")
        assertEquals("요약", f.readText())
        assertEquals(log.dir, f.parentFile)
    }

    /** 지킴이는 15분마다 최근 며칠만 본다 — 30일 파일 전체를 읽지 않고 끝부분만 읽어도 같은 표본 */
    @Test
    fun readRecentReadsOnlyTheTailButGetsTheSameSamples() {
        val q = 15 * 60_000L
        val day = 86_400_000L
        val start = now - 10 * day
        var t = start
        var k = 0
        while (t <= now) {
            // 가끔 이벤트 줄(메모 포함)도 섞어 줄 길이를 고르지 않게
            val s = if (k % 37 == 0) BatterySample(t, 50 + k % 40, event = Event.USER_NOTE, note = "강제 재시작") else BatterySample(t, 50 + k % 40, chargeUah = 200_000 + k, voltageMv = 3_900, tempDeciC = 300, currentUa = -40_000)
            log.append(s)
            t += q; k++
        }
        val all = log.readAll()
        assertEquals(all.filter { it.t >= now - 3 * day }, log.readRecent(3, now))
        assertEquals(all, log.readRecent(30, now))       // 파일보다 길게 달라면 전체
        assertTrue(log.readRecent(3, now).size in 280..300)
    }
}
