package com.mywatch.kakaomap.battery

import android.content.Context
import android.os.Build
import com.mywatch.battery.core.Analysis
import com.mywatch.battery.core.AppDrainRank
import com.mywatch.battery.core.BatterySample
import com.mywatch.battery.core.DrainAnalyzer
import com.mywatch.battery.core.KnownIssue
import com.mywatch.battery.core.KnownIssues
import com.mywatch.battery.core.LeverEffect
import com.mywatch.battery.core.LeverEffects
import com.mywatch.battery.core.UsageBreakdown
import com.mywatch.battery.core.UsageSplit
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
)

object BatteryInsights {
    /** 무거운 계산은 부르는 쪽이 IO 스레드에서 */
    fun compute(context: Context, samples: List<BatterySample>, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Insights {
        val c = context.applicationContext
        val analysis = runCatching { DrainAnalyzer(WatchModels.nominalMah(Build.MODEL), zone).analyze(samples, now) }
            .onFailure { ErrorLog.record("배터리·분석", it) }.getOrNull()
        val breakdown = runCatching {
            // 하루 동안 화면 기록이 하나도 없으면 기록을 안 남기는 기기로 보고 표본으로 어림
            val usage = ScreenUsage(c).read(now - 24 * 3_600_000L, now)?.takeIf { it.first.isNotEmpty() }
            UsageSplit.analyze(samples, usage?.first, usage?.second.orEmpty(), HealthStore(c).windows(24, now), now, zone, ownPkg = c.packageName)
        }.onFailure { ErrorLog.record("배터리·사용 내역", it) }.getOrNull()
        val culprits = runCatching { Guardian.culprits(c, now) }.getOrDefault(emptyList())
        val issues = KnownIssues.detect(culprits)
        val effect = runCatching { LeverEffects.nightMode(samples, GuardianJournal(c).nightSessions(now), now, zone) }
            .getOrDefault(LeverEffect(emptyList(), emptyList()))
        return Insights(analysis, breakdown, culprits, issues, effect)
    }
}
