package com.mywatch.kakaomap

import android.app.Application
import com.mywatch.battery.core.BatterySample
import com.mywatch.battery.core.DrainAnalyzer
import com.mywatch.battery.core.Grade
import com.mywatch.battery.core.KnownIssue
import com.mywatch.battery.core.KnownIssues
import com.mywatch.battery.core.LeverEffect
import com.mywatch.battery.core.SummaryMetrics
import com.mywatch.kakaomap.battery.BatteryLog
import com.mywatch.kakaomap.battery.BatterySummary
import com.mywatch.kakaomap.battery.Guardian
import com.mywatch.kakaomap.battery.Insights
import com.mywatch.kakaomap.battery.ShellRestoreReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.ZoneId

/**
 * PC 툴킷이 수집 직전에 워치 앱 요약을 새로 쓰게 하고(셸만 보낼 수 있는 받는 곳), 요약의 '[지표]' 줄에 워치가 최근 2시간 앱별 통계로
 * 짚은 알려진 문제를 적는다 — PC 자동 치료가 하루 묵은 요약이 아니라 지금의 판단으로 캐시 정리·Play 서비스 다시 시작을 고르게
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SummaryForPcTest {
    private val context = RuntimeEnvironment.getApplication()
    private val zone = ZoneId.of("Asia/Seoul")
    private val now = 1_792_414_865_000L
    private lateinit var log: BatteryLog

    @Before
    fun fresh() {
        log = BatteryLog(context)
        log.dir.deleteRecursively()
    }

    @Test
    fun theMetricsCarryTheWatchsKnownIssues() {
        val a = DrainAnalyzer(445, zone).analyze(emptyList(), now)
        val home = KnownIssue(KnownIssues.HOME_HOG, KnownIssues.HOME, "t", "w", emptyList(), Grade.ABNORMAL, cameBack = true)
        val gms = KnownIssue(KnownIssues.PLAY_SERVICES_LOOP, KnownIssues.GMS, "t", "w", emptyList(), Grade.CAUTION)
        val ins = Insights(a, null, emptyList(), listOf(home, gms), LeverEffect(emptyList(), emptyList()), appWindowMs = 2 * 3_600_000L)
        val m = SummaryMetrics.parse(BatterySummary.metricsLine(a, ins, now))
        assertEquals(2.0, m["issue_home"])
        assertEquals(1.0, m["issue_gms"])
        assertEquals(0.0, m["issue_face"])
        assertEquals(1.0, m["issue_back"])
        assertEquals(120.0, m["app_window_min"])
        // 판단할 만큼 기록이 없으면(55분 미만) 문제 칸은 적지 않는다 — '없음'과 '모름'을 가르게
        val short = SummaryMetrics.parse(BatterySummary.metricsLine(a, ins.copy(issues = emptyList(), appWindowMs = 30 * 60_000L), now))
        assertNull(short["issue_gms"])
        assertNull(short["issue_back"])
        assertEquals(30.0, short["app_window_min"])
        val none = SummaryMetrics.parse(BatterySummary.metricsLine(a, ins.copy(issues = emptyList()), now))
        assertEquals(0.0, none["issue_gms"])
        assertEquals(0.0, none["issue_home"])
        assertEquals(0.0, none["issue_back"])
    }

    /** 셸이 '지금 요약'을 부르면(PC 수집 직전) 하루 한 번을 기다리지 않고 새로 쓴다 */
    @Test
    fun theShellCanAskForAFreshSummary() {
        val t = System.currentTimeMillis()
        for (i in 0..12) log.append(BatterySample(t - (12 - i) * 15 * 60_000L, 80 - i / 2))
        log.summaryFile.parentFile?.mkdirs()
        log.summaryFile.writeText("어제 요약")
        ShellRestoreReceiver().onReceive(context, android.content.Intent(Guardian.ACTION_SHELL_SUMMARY))
        val until = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < until && log.summaryFile.readText() == "어제 요약") Thread.sleep(50)
        val text = log.summaryFile.readText()
        assertTrue(text, text.contains("[판정]"))
        val at = SummaryMetrics.parse(text)["at"]
        assertNotNull(text, at)
        assertTrue(at!! >= t - 1_000)
    }
}
