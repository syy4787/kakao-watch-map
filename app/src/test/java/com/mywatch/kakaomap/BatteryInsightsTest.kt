package com.mywatch.kakaomap

import android.app.AppOpsManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Looper
import android.os.Process
import android.provider.Settings
import androidx.recyclerview.widget.RecyclerView
import com.mywatch.battery.core.AppSpan
import com.mywatch.battery.core.BatterySample
import com.mywatch.battery.core.Span
import com.mywatch.battery.core.UidUse
import com.mywatch.kakaomap.battery.BatteryActivity
import com.mywatch.kakaomap.battery.BatteryLog
import com.mywatch.kakaomap.battery.Guardian
import com.mywatch.kakaomap.battery.GuardianJournal
import com.mywatch.kakaomap.battery.HealthStore
import com.mywatch.kakaomap.battery.ScreenUsage
import com.mywatch.kakaomap.battery.UidStatsSource
import com.mywatch.kakaomap.ui.Row
import com.mywatch.kakaomap.ui.RowAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 배터리 사용 내역(화면 켜짐·꺼짐, 가장 많이 닳은 시간), 알려진 문제(Play 서비스 과소모) 알림, 밤 모드 효과 일지.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BatteryInsightsTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val zone: ZoneId = ZoneId.of("Asia/Seoul")
    private val q = 15 * 60_000L
    private lateinit var log: BatteryLog
    private val nm get() = context.getSystemService(NotificationManager::class.java)

    private fun at(h: Int, m: Int = 0, d: Int = 20) = LocalDateTime.of(2026, 10, d, h, m).atZone(zone).toInstant().toEpochMilli()

    @Before
    fun fresh() {
        log = BatteryLog(context)
        log.dir.deleteRecursively()
        context.getSharedPreferences(BatteryLog.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        Settings.Global.putInt(context.contentResolver, "low_power", 0)
    }

    private fun allowUsage() = shadowOf(context.getSystemService(AppOpsManager::class.java))
        .setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName, AppOpsManager.MODE_ALLOWED)

    private fun event(pkg: String, t: Long, type: Int) = shadowOf(context.getSystemService(UsageStatsManager::class.java)).addEvent(pkg, t, type)

    @Test
    fun screenUsageNeedsThePermissionThenReadsScreenAndApps() {
        val su = ScreenUsage(context)
        // 권한을 막아 둔 상태 (Robolectric 의 기본값은 허용이라 직접 막는다)
        shadowOf(context.getSystemService(AppOpsManager::class.java))
            .setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName, AppOpsManager.MODE_IGNORED)
        assertFalse(su.available)
        assertNull(su.read(at(18), at(21, 30)))
        allowUsage()
        assertTrue(su.available)
        event("android", at(19), UsageEvents.Event.SCREEN_INTERACTIVE)
        event("com.google.android.youtube", at(19, 1), UsageEvents.Event.ACTIVITY_RESUMED)
        event("com.google.android.youtube", at(19, 35), UsageEvents.Event.ACTIVITY_PAUSED)
        event("android", at(19, 40), UsageEvents.Event.SCREEN_NON_INTERACTIVE)
        event("android", at(21), UsageEvents.Event.SCREEN_INTERACTIVE)        // 지금도 켜져 있음
        val (screen, apps) = su.read(at(18), at(21, 30))!!
        assertEquals(listOf(Span(at(19), at(19, 40)), Span(at(21), at(21, 30))), screen)
        assertEquals(listOf(AppSpan("com.google.android.youtube", at(19, 1), at(19, 35))), apps)
    }

    @Test
    fun aScreenAlreadyOnWhenTheWindowStartsCountsFromTheStart() {
        allowUsage()
        event("android", at(18, 10), UsageEvents.Event.SCREEN_NON_INTERACTIVE)
        val (screen, _) = ScreenUsage(context).read(at(18), at(19))!!
        assertEquals(listOf(Span(at(18), at(18, 10))), screen)
    }

    /**
     * 며칠 단위 추세용: 화면 이벤트만 — 시스템이 보관한 가장 이른 화면 이벤트부터 믿는다. 화면 이벤트가 하나도 없으면 null
     * (화면이 켜진 때를 모르는 것이지 안 켜진 것이 아니다 — 모든 구간을 '화면이 안 켜진 대기'로 세지 않게, 리뷰에서 찾음)
     */
    @Test
    fun theScreenRecordForTheTrendStartsAtTheEarliestKeptEvent() {
        allowUsage()
        assertNull("기록이 없으면 null", ScreenUsage(context).readScreen(at(0, d = 11), at(18)))
        event("com.example.app", at(9, d = 13), UsageEvents.Event.ACTIVITY_RESUMED)
        assertNull("화면 이벤트가 없으면 null", ScreenUsage(context).readScreen(at(0, d = 11), at(18)))
        event("android", at(9, 30, d = 13), UsageEvents.Event.SCREEN_NON_INTERACTIVE)   // 기록이 시작될 때 이미 켜져 있던 화면 — 그 앞은 모름
        event("android", at(12, d = 14), UsageEvents.Event.SCREEN_INTERACTIVE)
        event("android", at(12, 5, d = 14), UsageEvents.Event.SCREEN_NON_INTERACTIVE)
        val cover = ScreenUsage(context).readScreen(at(0, d = 11), at(18))!!
        assertEquals(at(9, 30, d = 13), cover.from)
        assertEquals(listOf(Span(at(12, d = 14), at(12, 5, d = 14))), cover.spans)
    }

    /**
     * 사용 통계가 있으면 '최근 며칠'은 화면이 한 번도 안 켜진 구간만 대기로 — 매시 몇 분씩 본 날들이 대기 소모를 부풀려
     * '꺾임'으로 읽히지 않는다
     */
    @Test
    fun theTrendUsesTheScreenRecordSoBusyDaysAreNotAShift() {
        allowUsage()
        val samples = ArrayList<BatterySample>()
        for (i in 0 until 7) {
            val d = 13 + i
            val busy = i >= 4
            var t = at(0, d = d)
            var level = 100
            while (t <= at(10, d = d)) {
                samples += BatterySample(t = t, level = level)
                val onTheHour = ((t - at(0, d = d)) / 60_000L) % 60 == 0L
                level -= 1 + (if (busy && onTheHour) 3 else 0)
                if (busy && onTheHour) {
                    event("android", t + 2 * 60_000L, UsageEvents.Event.SCREEN_INTERACTIVE)
                    event("android", t + 7 * 60_000L, UsageEvents.Event.SCREEN_NON_INTERACTIVE)
                }
                t += q
            }
            while (t < at(0, d = d + 1)) { samples += BatterySample(t = t, level = 100, plugged = true); t += q }
        }
        // 화면 기록이 첫날 앞부터 있음 (전날 밤에 잠깐 본 화면)
        event("android", at(23, 0, d = 12), UsageEvents.Event.SCREEN_INTERACTIVE)
        event("android", at(23, 1, d = 12), UsageEvents.Event.SCREEN_NON_INTERACTIVE)
        val now = at(0, d = 20) - 1
        val trend = com.mywatch.kakaomap.battery.BatteryInsights.compute(context, samples, now, zone).trend!!
        assertTrue(trend.exact)
        assertNull("바쁜 날은 꺾임이 아니다: ${trend.days.map { it.pph }}", trend.shift)
    }

    @Test
    fun journalPairsNightOnAndOff() {
        val j = GuardianJournal(context)
        j.add(GuardianJournal.NIGHT_ON, at(0, 30, 19))
        j.add(GuardianJournal.NIGHT_OFF, at(7, 0, 19))
        j.add(GuardianJournal.NIGHT_ON, at(0, 40, 20))
        assertEquals(listOf(Span(at(0, 30, 19), at(7, 0, 19)), Span(at(0, 40, 20), at(3, 0, 20))), j.nightSessions(at(3, 0, 20)))
        // 끝 표시 없이 오래 지난 것은 10시간까지만
        assertEquals(Span(at(0, 40, 20), at(10, 40, 20)), j.nightSessions(at(23, 0, 20)).last())
    }

    @Test
    fun guardianNightModeIsWrittenToTheJournal() {
        shadowOf(context as Application).grantPermissions("android.permission.WRITE_SECURE_SETTINGS")
        fun fill(end: Long, start: Double, pph: Double) {
            log.file.delete()
            for (i in 0 until 8) log.append(BatterySample(t = end - (7 - i) * q, level = (start - pph * i / 4).toInt()))
        }
        fill(at(0, 30), 60.0, 2.0)
        Guardian.tick(context, log, at(0, 30), stats = UidStatsSource { emptyMap() }, zone = zone)
        fill(at(7, 5), 50.0, 1.0)
        Guardian.tick(context, log, at(7, 5), stats = UidStatsSource { emptyMap() }, zone = zone)
        assertEquals(listOf(Span(at(0, 30), at(7, 5))), GuardianJournal(context).nightSessions(at(8)))
    }

    /** 화면 꺼진 채 Play 서비스가 15분마다 CPU 4분(시간당 16분)을 쓰는 워치 */
    private class GmsLoop : UidStatsSource {
        var cpu = 0L
        override fun take() = mapOf(
            "com.google.android.gms+2" to UidUse("com.google.android.gms+2", cpuMs = cpu, wakelockMs = cpu),
            "com.samsung.android.wear.shealth" to UidUse("com.samsung.android.wear.shealth", sensorMs = cpu / 4),
        )
    }

    /** [start] 부터 15분마다 [from]..[ticks] 번째 기록 (이어서 돌리려면 같은 [stats] 를) */
    private fun playServicesLoop(start: Long, ticks: Int, from: Int = 0, stats: GmsLoop = GmsLoop()): GmsLoop {
        for (k in from until ticks) {
            val t = start + k * q
            log.append(BatterySample(t, 80 - k))
            Guardian.tick(context, log, t, stats = stats, zone = zone)
            stats.cpu += 4 * 60_000L
        }
        return stats
    }

    @Test
    fun aPlayServicesLoopIsNotifiedWithTheFixAndTheAppInfoButton() {
        // 알려진 문제는 1시간치가 쌓여야 판단한다 (짧으면 재시작 직후의 일이 시간당으로 부풀려짐)
        val loop = playServicesLoop(at(14), 5)
        val n = shadowOf(nm).getNotification(Guardian.N_ISSUE)
        assertNotNull(n)
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
        val text = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
        assertTrue(title, title.contains("Play 서비스"))
        assertTrue(text, text.contains("캐시 삭제"))
        assertTrue(n.actions.map { it.title.toString() }.toString(), n.actions.any { it.title.toString() == "Play 서비스 앱 정보" })
        // 12시간에 한 번
        nm.cancel(Guardian.N_ISSUE)
        playServicesLoop(at(14), 7, from = 5, stats = loop)
        assertEquals("문제는 그대로 보이고(타일)", "Play 서비스 과소모 — 캐시 지우고 재시작", Guardian.tileIssue(context, at(15, 30)))
        assertNull("알림만 쉰다", shadowOf(nm).getNotification(Guardian.N_ISSUE))
    }

    private fun open(): Pair<BatteryActivity, RowAdapter> {
        val a = Robolectric.buildActivity(BatteryActivity::class.java).setup().get()
        val adapter = a.findViewById<RecyclerView>(R.id.list).adapter as RowAdapter
        val until = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < until && adapter.rows.none { it is Row.Header && it.title == "배터리 사용 내역 (24시간)" }) {
            shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(20)
        }
        return a to adapter
    }

    @Test
    fun theBatteryScreenShowsWhereTheBatteryWentAndTheKnownIssue() {
        shadowOf(context as Application).grantPermissions("android.permission.BATTERY_STATS")
        allowUsage()
        val now = System.currentTimeMillis()
        // 6시간: 화면 꺼진 동안 시간당 2%, 지금부터 3시간 전 40분 동안 화면 켜짐(시간당 30%)
        val on = Span(now - 3 * 3_600_000L, now - 3 * 3_600_000L + 40 * 60_000L)
        event("android", on.start, UsageEvents.Event.SCREEN_INTERACTIVE)
        event("com.google.android.youtube", on.start + 60_000L, UsageEvents.Event.ACTIVITY_RESUMED)
        event("com.google.android.youtube", on.end - 60_000L, UsageEvents.Event.ACTIVITY_PAUSED)
        event("android", on.end, UsageEvents.Event.SCREEN_NON_INTERACTIVE)
        var level = 90.0
        var t = now - 6 * 3_600_000L
        while (t <= now) {
            log.append(BatterySample(t, level.toInt()))
            for (k in 0 until 15) level -= if (t + k * 60_000L in on.start until on.end) 0.5 else 2.0 / 60
            t += q
        }
        // 앱별 통계: Play 서비스가 멈춘 채 도는 중 (최근 2시간)
        val store = HealthStore(context)
        store.record(now - 2 * 3_600_000L, mapOf("com.google.android.gms" to UidUse("com.google.android.gms")))
        store.record(now - 3_600_000L, mapOf("com.google.android.gms" to UidUse("com.google.android.gms", cpuMs = 20 * 60_000L)))
        store.record(now - 60_000L, mapOf("com.google.android.gms" to UidUse("com.google.android.gms", cpuMs = 40 * 60_000L)))

        val (_, adapter) = open()
        val rows = adapter.rows
        assertTrue(rows.filterIsInstance<Row.Note>().any { it.text.contains("화면(") && it.text.contains("대기(") })
        val idle = rows.filterIsInstance<Row.Step>().first { it.key == "usage:idle" }
        val idlePph = Regex("시간당 ([0-9.]+)%").find(idle.title)!!.groupValues[1].toDouble()
        assertTrue(idle.title, idlePph in 1.5..2.6)
        val hour = rows.filterIsInstance<Row.Step>().first { it.key.startsWith("hour:") }
        assertTrue(hour.sub ?: "", (hour.sub ?: "").contains("화면"))
        val issue = rows.filterIsInstance<Row.Step>().first { it.key == "issue:play-services-loop" }
        assertTrue(issue.sub ?: "", (issue.sub ?: "").contains("캐시 삭제"))
        assertTrue(rows.filterIsInstance<Row.Action>().any { it.id == BatteryActivity.A_APP_INFO + "com.google.android.gms" })
    }

    /** 남은 할 일이 업데이트·오류 보내기뿐인 단계면 화면에도 '앱 정보 열기'를 달지 않는다 — 앱 정보로 할 일이 없다 (리뷰) */
    @Test
    fun theLastStageHasNoAppInfoButton() {
        shadowOf(context as Application).grantPermissions("android.permission.BATTERY_STATS")
        val now = System.currentTimeMillis()
        for (i in 0..8) log.append(BatterySample(now - (8 - i) * q, 80 - i / 4))
        val store = HealthStore(context)
        store.record(now - 2 * 3_600_000L, mapOf("com.google.android.gms" to UidUse("com.google.android.gms")))
        store.record(now - 60_000L, mapOf("com.google.android.gms" to UidUse("com.google.android.gms", cpuMs = 40 * 60_000L)))
        fun appInfo(rows: List<Row>) = rows.filterIsInstance<Row.Action>().any { it.id == BatteryActivity.A_APP_INFO + "com.google.android.gms" }
        assertTrue("처음 단계 — 캐시 삭제가 앱 정보에", appInfo(open().second.rows))
        // 같은 일(방금 봄)에서 다음 단계 뒤에도 계속인 것으로
        context.getSharedPreferences(BatteryLog.PREFS, Context.MODE_PRIVATE).edit()
            .putLong("gs_issue_seen_at_play-services-loop", now).putInt("gs_issue_stage_play-services-loop", 2).commit()
        val rows = open().second.rows
        val issue = rows.filterIsInstance<Row.Step>().first { it.key == "issue:play-services-loop" }
        assertTrue(issue.title, issue.title.endsWith("다음 단계 뒤에도 계속"))
        assertFalse(appInfo(rows))
    }

    /**
     * 재시작 효과: 시킨 대로 강제 재시작한 뒤 대기 소모가 줄었는지 숫자로 — 배터리 화면(알려진 문제 칸)과 하루 요약에.
     * 재시작 직전 대기 3시간 시간당 4%(그 전 12시간 내내 4%), 재시작 30분 뒤부터 3시간 시간당 1% → '줄었습니다'
     */
    @Test
    fun theRestartEffectIsShownOnTheScreenAndInTheSummary() {
        // 앱별 통계 권한이 없어도 (잔량 표시 오차 알림을 보고 재시작한 사람도 본다 — 워치 기록만으로 잼)
        val now = System.currentTimeMillis()
        val boot = now - 3 * 3_600_000L - 30 * 60_000L
        var t = boot - 12 * 3_600_000L
        while (t < boot) { log.append(BatterySample(t, (90 - 4.0 * (t - (boot - 12 * 3_600_000L)) / 3_600_000L).toInt())); t += q }
        log.append(BatterySample(boot, 42, event = com.mywatch.battery.core.Event.BOOT))
        t = boot + q
        while (t <= now) { log.append(BatterySample(t, (42 - 1.0 * (t - boot) / 3_600_000L).toInt())); t += q }
        val notes = open().second.rows.filterIsInstance<Row.Note>().map { it.text }
        assertTrue(notes.toString(), notes.any { it.startsWith("재시작 효과") && it.endsWith("— 줄었습니다") })
        val ins = com.mywatch.kakaomap.battery.BatteryInsights.compute(context, log.readAll(), now, zone)
        val text = com.mywatch.kakaomap.battery.BatterySummary.text(ins.analysis!!, null, "SM-L335", now, ins)
        val line = text.lines().single { it.startsWith("[재시작 효과]") }
        assertTrue(line, line.endsWith("— 줄었습니다") && line.split(" — ").size == 2)
    }

    /**
     * 앱별 소모 칸이 비었거나 짧을 때 왜인지 — 첫 기록 뒤엔 '다음 기록(1시간 안)부터', 재시작 뒤엔 '켜진 지 10분 뒤부터 다시', 기록이
     * 1시간이 안 되면 '알려진 문제는 1시간쯤 쌓이면' (재시작한 사람이 '문제가 사라졌나?' 헷갈리지 않게)
     */
    @Test
    fun theAppSectionSaysWhyItIsEmptyOrShort() {
        shadowOf(context as Application).grantPermissions("android.permission.BATTERY_STATS")
        val now = System.currentTimeMillis()
        for (i in 0..8) log.append(BatterySample(now - (8 - i) * q, 80 - i / 4))
        val store = HealthStore(context)
        fun notes(): List<String> = open().second.rows.filterIsInstance<Row.Note>().map { it.text }
        // 첫 기록뿐
        // 같은 부팅(번호 1, 시험의 켜진 뒤 시간은 0 근처)에서 남긴 첫 기록
        store.record(now - 60_000L, mapOf("a" to UidUse("a", cpuMs = 1_000)), HealthStore.Boot(1, 0L))
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 1)
        assertTrue(notes().toString(), notes().any { it.startsWith("앱별 기록을 모으는 중") })
        // 재시작 뒤 아직 기록 전
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 2)
        assertTrue(notes().toString(), notes().any { it.startsWith("재시작 뒤 — 켜진 지 10분이 지나면") })
        // PC 자동 치료 뒤 기준값 전 (리뷰)
        assertTrue(Guardian.onTreated(context, now, setOf("trim-caches")))
        assertTrue(notes().toString(), notes().any { it.startsWith("PC 치료 뒤 — 10분이 지나면 앱별 기록을 다시 시작합니다") })
    }

    @Test
    fun aShortWindowSaysWhenKnownIssuesWillBeJudged() {
        shadowOf(context as Application).grantPermissions("android.permission.BATTERY_STATS")
        val now = System.currentTimeMillis()
        for (i in 0..8) log.append(BatterySample(now - (8 - i) * q, 80 - i / 4))
        val store = HealthStore(context)
        store.record(now - 30 * 60_000L, mapOf("a" to UidUse("a", cpuMs = 1_000)))
        // 29분에 CPU 12분 — 시간당으로 늘리면 25분('비정상')이지만 짧은 창이라 등급은 매기지 않는다 (재시작 직후 동기화가 빨갛게 보이지 않게)
        store.record(now - 60_000L, mapOf("a" to UidUse("a", cpuMs = 1_000 + 12 * 60_000L)))
        val rows = open().second.rows
        val notes = rows.filterIsInstance<Row.Note>().map { it.text }
        assertTrue(notes.toString(), notes.any { it == "알려진 문제는 기록이 1시간쯤 쌓이면 봅니다 (지금 29분치)" })
        val app = rows.filterIsInstance<Row.Step>().single { it.key == "app:a" }
        assertNull(app.toString(), app.chip)
        // 시간당으로 늘린 표시('CPU 시간당 24분')도 빼고 이 창에서 쓴 만큼만
        assertTrue(app.sub.orEmpty(), app.sub.orEmpty().contains("CPU 12분") && !app.sub.orEmpty().contains("시간당"))
    }

    /** 배터리 화면을 열 때도 One UI Watch 홈 과소모를 짚는다 — 사용 통계로 본 화면 켜짐 시간을 빼고 (15분 기록과 같은 판단) */
    @Test
    fun theWatchHomeHogIsFoundWhenTheScreenOpens() {
        allowUsage()
        val home = com.mywatch.battery.core.KnownIssues.HOME
        val now = at(12)
        event("android", now - 30 * 60_000L, UsageEvents.Event.SCREEN_INTERACTIVE)
        event("android", now - 26 * 60_000L, UsageEvents.Event.SCREEN_NON_INTERACTIVE)   // 화면 4분
        val store = HealthStore(context)
        store.record(now - 2 * 3_600_000L, mapOf(home to UidUse(home)))
        store.record(now - 60_000L, mapOf(home to UidUse(home, cpuMs = 28 * 60_000L, fgMs = 2 * 3_600_000L)))
        val samples = (0..8).map { i -> BatterySample(now - (8 - i) * q, 80 - i / 4) }
        val ins = com.mywatch.kakaomap.battery.BatteryInsights.compute(context, samples, now, zone)
        assertTrue(ins.issues.toString(), ins.issues.any { it.id == com.mywatch.battery.core.KnownIssues.HOME_HOG })
    }

    /** 타사 시계 화면이 웨이크락·CPU 를 많이 쓰면 배터리 화면에 '기본 시계 화면으로 바꿔 비교'를, 맨 위엔 지금 시계 화면 이름을 */
    @Test
    fun aHeavyThirdPartyWatchFaceIsShownWithTheFix() {
        shadowOf(context as Application).grantPermissions("android.permission.BATTERY_STATS")
        val face = "com.jeremysteckling.facerrel"
        com.mywatch.kakaomap.battery.BatteryInsights.watchFace = { face }
        try {
            val now = System.currentTimeMillis()
            for (i in 0..12) log.append(BatterySample(now - (12 - i) * q, 80 - i))
            val store = HealthStore(context)
            store.record(now - 2 * 3_600_000L, mapOf(face to UidUse(face)))
            store.record(now - 3_600_000L, mapOf(face to UidUse(face, cpuMs = 8 * 60_000L, wakelockMs = 6 * 60_000L, fgMs = 60 * 60_000L)))
            store.record(now - 60_000L, mapOf(face to UidUse(face, cpuMs = 16 * 60_000L, wakelockMs = 12 * 60_000L, fgMs = 120 * 60_000L)))
            val (_, adapter) = open()
            val issue = adapter.rows.filterIsInstance<Row.Step>().first { it.key == "issue:watch-face-heavy" }
            assertTrue(issue.sub ?: "", (issue.sub ?: "").contains("기본 삼성 시계 화면"))
            assertTrue(adapter.rows.filterIsInstance<Row.Note>().any { it.text.startsWith("지금 시계 화면") })
        } finally {
            com.mywatch.kakaomap.battery.BatteryInsights.watchFace = com.mywatch.kakaomap.battery.BatteryInsights.DEFAULT_FACE
        }
    }

    /** 화면이 하루 수백 번 켜졌다 금방 꺼지면(손목 올려 켜기 오작동) 그 횟수와 끌 설정을 알려 준다. 알림이 켠 것은 앱별로 */
    @Test
    fun manyShortWakesAndNotificationWakesGetAdvice() {
        allowUsage()
        val now = System.currentTimeMillis()
        // 23시간 내내 5분마다 2초씩 켜졌다 꺼짐 (지금 시각과 상관없이 그 안에 밤 0~6시가 4시간은 들어 있다 — 48번 넘게)
        var t = now - 23 * 3_600_000L
        var count = 0
        while (t < now - 3_600_000L) {
            event("android", t, UsageEvents.Event.SCREEN_INTERACTIVE)
            event("android", t + 2_000L, UsageEvents.Event.SCREEN_NON_INTERACTIVE)
            t += 5 * 60_000L
            count++
        }
        repeat(60) {           // 카카오톡 알림이 화면을 켬 (마지막 한 시간)
            event("com.kakao.talk", t, ScreenUsage.NOTIFICATION_INTERRUPTION)
            event("android", t + 400L, UsageEvents.Event.SCREEN_INTERACTIVE)
            event("android", t + 8_000L, UsageEvents.Event.SCREEN_NON_INTERACTIVE)
            t += 30_000L
        }
        val u = ScreenUsage(context).readAll(now - 24 * 3_600_000L, now)!!
        assertEquals(60, u.notifications.size)
        assertEquals(count + 60, u.screen.size)
        // 표본: 화면 기록과 상관없이 하루치
        var level = 95.0
        var s = now - 22 * 3_600_000L
        while (s <= now) { log.append(BatterySample(s, level.toInt())); level -= 0.6; s += q }
        val (_, adapter) = open()
        val rows = adapter.rows
        assertTrue(rows.filterIsInstance<Row.Note>().any { it.text.startsWith("화면 켜짐 ${count + 60}번") })
        val todo = rows.filterIsInstance<Row.Step>().filter { it.key.startsWith("wake:") }
        assertEquals(2, todo.size)
        // 낮의 짧은 켜짐(시계 보기)은 세지 않고 밤 것만 — 밤에만 끄는 길을 권한다
        assertTrue(todo[0].title, todo[0].title.startsWith("밤에 저절로 켜진 화면"))
        assertTrue(todo[0].sub ?: "", (todo[0].sub ?: "").contains("밤에만"))
        assertTrue(todo[1].title, todo[1].title.contains("알림 화면 켜짐 60번"))
        // 알림은 그대로 받고 화면만 안 켜지게 — 삼성 안내 이름 그대로 (앱 알림을 통째로 끄라고 하지 않는다)
        assertTrue(todo[1].sub ?: "", (todo[1].sub ?: "").contains("'알림 받으면 화면 켜기' 끄기"))
    }

    /** 밤사이 뒤에서 쓴 앱과, 삼성 헬스가 밤새 센서를 켜 두면 측정 설정 할 일 */
    @Test
    fun lastNightShowsWhoWorkedInTheBackgroundAndHealthSensorAdvice() {
        shadowOf(context as Application).grantPermissions("android.permission.BATTERY_STATS")
        val now = System.currentTimeMillis()
        val today0 = java.time.LocalDate.now(ZoneId.systemDefault()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val end = minOf(now, today0 + 7 * 3_600_000L)
        org.junit.Assume.assumeTrue("자정 직후 2시간 안에는 밤 기록이 모자라 건너뜀", end - today0 >= 2 * 3_600_000L)
        val store = HealthStore(context)
        var t = today0
        var sensor = 0L
        store.record(t, mapOf("com.samsung.android.wear.shealth" to UidUse("com.samsung.android.wear.shealth")))
        while (t + q <= end) {
            t += q
            sensor += 30 * 60_000L
            store.record(t, mapOf("com.samsung.android.wear.shealth" to UidUse("com.samsung.android.wear.shealth", sensorMs = sensor)))
        }
        var level = 90.0
        var s = now - 10 * 3_600_000L
        while (s <= now) { log.append(BatterySample(s, level.toInt())); level -= 0.5; s += q }
        val (_, adapter) = open()
        val rows = adapter.rows
        assertTrue(rows.filterIsInstance<Row.Note>().any { it.text.startsWith("밤사이 뒤에서") && it.text.contains("com.samsung.android.wear.shealth") })
        val todo = rows.filterIsInstance<Row.Step>().first { it.key == "night:0" }
        assertTrue(todo.title, todo.title.contains("삼성 헬스"))
        assertTrue(todo.sub ?: "", (todo.sub ?: "").contains("혈중 산소"))
    }
}
