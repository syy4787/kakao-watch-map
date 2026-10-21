package com.mywatch.kakaomap.battery

import android.content.Context
import android.os.Build
import com.mywatch.battery.core.Analysis
import com.mywatch.battery.core.AppDrainRank
import com.mywatch.battery.core.BatterySample
import com.mywatch.battery.core.ChargeHabits
import com.mywatch.battery.core.DrainAnalyzer
import com.mywatch.battery.core.DrainChart
import com.mywatch.battery.core.DrainChartModel
import com.mywatch.battery.core.KnownIssue
import com.mywatch.battery.core.KnownIssues
import com.mywatch.battery.core.LeverEffect
import com.mywatch.battery.core.LeverEffects
import com.mywatch.battery.core.NightCulprits
import com.mywatch.battery.core.NightReport
import com.mywatch.battery.core.TrendReport
import com.mywatch.battery.core.UsageBreakdown
import com.mywatch.battery.core.UsageSplit
import com.mywatch.battery.core.WakeStats
import com.mywatch.battery.core.Wakes
import com.mywatch.battery.core.WatchModels
import com.mywatch.kakaomap.data.ErrorLog
import java.time.ZoneId

/** 배터리 화면·요약·지킴이가 같이 쓰는 분석 묶음 */
data class Insights(
    val analysis: Analysis?,
    /** 하루 사용 내역(화면·대기, 가장 많이 닳은 시간) */
    val breakdown: UsageBreakdown?,
    /** 최근 2시간 앱별 순위 */
    val culprits: List<AppDrainRank>,
    val issues: List<KnownIssue>,
    /** 밤 모드 효과 */
    val nightEffect: LeverEffect,
    /** 화면이 켜진 횟수·알림이 켠 횟수 (사용 통계가 있을 때) */
    val wakes: WakeStats? = null,
    /** 밤사이 뒤에서 쓴 앱 (앱별 통계가 있을 때) */
    val night: NightReport? = null,
    /** 레버 점검 한 줄 ([Levers.checkLine]) — 요약 파일에 */
    val levers: String? = null,
    /** 24시간 그래프 (표본이 2개 이상일 때) */
    val chart: DrainChartModel? = null,
    /** 지금 시계 화면의 패키지 (모르면 null) */
    val watchFace: String? = null,
    /** 충전 습관 (최근 7일, 충전 기록이 있을 때) */
    val charging: ChargeHabits? = null,
    /** 최근 며칠 대기 소모·꺾인 날·그 무렵 바뀐 것 */
    val trend: TrendReport? = null,
)


object BatteryInsights {
    /** 지금 시계 화면(라이브 배경화면)의 패키지 — 모든 앱 보기 권한(QUERY_ALL_PACKAGES)이 있어 읽을 수 있다 */
    val DEFAULT_FACE: (Context) -> String? = { c ->
        runCatching { android.app.WallpaperManager.getInstance(c).wallpaperInfo?.packageName }.getOrNull()
    }

    /** 시험에선 바꿔 끼운다 */
    @Volatile
    var watchFace: (Context) -> String? = DEFAULT_FACE

    /** 무거운 계산은 부르는 쪽이 IO 스레드에서 */
    fun compute(context: Context, samples: List<BatterySample>, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Insights {
        val c = context.applicationContext
        val analysis = runCatching { DrainAnalyzer(WatchModels.nominalMah(Build.MODEL), zone).analyze(samples, now) }
            .onFailure { ErrorLog.record("배터리·분석", it) }.getOrNull()
        // 하루 동안 화면 기록이 하나도 없으면 기록을 안 남기는 기기로 보고 표본으로 어림
        val usage = runCatching { ScreenUsage(c).readAll(now - 24 * 3_600_000L, now) }.getOrNull()?.takeIf { it.screen.isNotEmpty() }
        val breakdown = runCatching {
            UsageSplit.analyze(samples, usage?.screen, usage?.apps.orEmpty(), HealthStore(c).windows(24, now), now, zone, ownPkg = c.packageName)
        }.onFailure { ErrorLog.record("배터리·사용 내역", it) }.getOrNull()
        val wakes = usage?.let { u -> runCatching { Wakes.analyze(u.screen, u.notifications, now - 24 * 3_600_000L, now, breakdown?.screenPph) }.getOrNull() }
        val (culprits, window) = runCatching { Guardian.culpritsInWindow(c, now) }.getOrDefault(emptyList<AppDrainRank>() to 0L)
        val night = runCatching { NightCulprits.lastNight(HealthStore(c).windows(24, now), now, zone, c.packageName) }.getOrNull()
        val face = runCatching { watchFace(c) }.getOrNull()
        val issues = KnownIssues.detect(culprits, face, window.takeIf { it > 0 })
        val effect = runCatching { LeverEffects.nightMode(samples, GuardianJournal(c).nightSessions(now), now, zone) }
            .getOrDefault(LeverEffect(emptyList(), emptyList()))
        val levers = runCatching { Levers(c).checkLine(Guardian.state(c).saverBroken) }.getOrNull()
        val chart = runCatching {
            DrainChart.buildOrNull(samples, now, zone, screen = usage?.screen, hot = breakdown?.steepest.orEmpty().map { it.start to it.end })
        }.onFailure { ErrorLog.record("배터리·그래프", it) }.getOrNull()
        val charging = runCatching { ChargeHabits.analyze(samples, now) }.getOrNull()
        val trend = runCatching { TrendReport.of(samples, now, zone) }.onFailure { ErrorLog.record("배터리·추세", it) }.getOrNull()
        return Insights(analysis, breakdown, culprits, issues, effect, wakes, night, levers, chart, face, charging, trend)
    }

    /** 패키지 → 앱 이름 (모르면 null — 지운 앱 등) */
    fun appName(context: Context): (String) -> String? {
        val pm = context.packageManager
        return { pkg -> runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull()?.takeIf { it.isNotBlank() && it != pkg } }
    }
}
