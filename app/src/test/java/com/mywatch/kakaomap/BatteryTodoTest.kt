package com.mywatch.kakaomap

import com.mywatch.battery.core.Grade
import com.mywatch.battery.core.KnownIssue
import com.mywatch.battery.core.LeverEffect
import com.mywatch.battery.core.WakeStats
import com.mywatch.kakaomap.battery.BatteryTodo
import com.mywatch.kakaomap.battery.Insights
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 배터리 화면 맨 위 '지금 할 일' — 여러 칸에 흩어진 조언 중 가장 급한 셋을 차례대로 */
class BatteryTodoTest {
    private val none = LeverEffect(emptyList(), emptyList())

    private fun insights(issues: List<KnownIssue> = emptyList(), wakes: WakeStats? = null) =
        Insights(analysis = null, breakdown = null, culprits = emptyList(), issues = issues, nightEffect = none, wakes = wakes)

    private val gms = KnownIssue("play-services-loop", "com.google.android.gms", "Google Play 서비스 과소모", "CPU 시간당 16분",
        listOf("Play 서비스 앱 정보 > 저장공간 > 캐시 지우기", "강제 재시작"), Grade.ABNORMAL)
    private val face = KnownIssue("watch-face-heavy", "com.example.face", "지금 시계 화면이 배터리를 많이 씀", "CPU",
        listOf("기본 삼성 시계 화면으로 바꿔 비교"), Grade.CAUTION)
    private val busyWrist = WakeStats(wakes = 300, shortWakes = 200, notificationWakes = 10, gestureLikeWakes = 190, gestureLikeMs = 190 * 3_000L,
        notifications = 40, topNotifiers = emptyList(), screenPph = 20.0)

    @Test
    fun anAbnormalKnownIssueComesFirstThenWristWakesThenTheRest() {
        val t = BatteryTodo.pick(insights(listOf(face, gms), busyWrist), permitted = true)
        assertEquals(3, t.size)
        assertEquals("Google Play 서비스 과소모", t[0].title)
        assertEquals("Play 서비스 앱 정보 > 저장공간 > 캐시 지우기", t[0].detail)
        assertEquals("지금 시계 화면이 배터리를 많이 씀", t[1].title)
        assertTrue(t[2].title, t[2].title.contains("5초 안에 꺼진 화면"))
    }

    /** 최근 7일 안에 대기 소모가 늘어 꺾였으면 비정상 알려진 문제 다음에 — 그 무렵 가장 그럴듯한 변화(여기선 Play 서비스 업데이트)의 할 일 */
    @Test
    fun aRecentRiseComesRightAfterAbnormalIssuesWithTheSuspectsAdvice() {
        val shift = com.mywatch.battery.core.Shift(java.time.LocalDate.of(2026, 10, 17), java.time.LocalDate.of(2026, 10, 18), 1.0, 2.5, 4, 3)
        val gmsUpdate = com.mywatch.battery.core.Change(1L, com.mywatch.battery.core.ChangeKind.APP, "com.google.android.gms", null, "26.40.31")
        val trend = com.mywatch.battery.core.TrendReport(emptyList(), shift, listOf(gmsUpdate), emptyList(), null, fresh = true)
        val t = BatteryTodo.pick(insights(listOf(face, gms), busyWrist).copy(trend = trend), permitted = true)
        assertEquals("Google Play 서비스 과소모", t[0].title)
        assertEquals("Play 서비스 업데이트 뒤 더 닳음", t[1].title)
        assertTrue(t[1].detail ?: "", (t[1].detail ?: "").contains("캐시 지우기"))
        assertEquals(Grade.CAUTION, t[1].grade)
        // 오래된 꺾임(7일 넘음)·줄어든 꺾임은 할 일이 아니다
        val old = BatteryTodo.pick(insights().copy(trend = trend.copy(fresh = false)), permitted = true)
        assertTrue(old.isEmpty())
    }

    /** 대기 소모가 높은데 재시작한 지 7일이 넘었으면 '한 번 재시작' — 2026년 광탈 글들의 공통 해결담. 소모가 정상이거나 최근 재시작이면 없음 */
    @Test
    fun aLongUptimeWithHighIdleDrainSuggestsARestart() {
        val now = 1_792_500_000_000L
        val day = 24 * 3_600_000L
        fun idle(pph: Double) = com.mywatch.battery.core.DrainAnalyzer(445, java.time.ZoneId.of("Asia/Seoul"))
            .analyze((0..96).map { i -> com.mywatch.battery.core.BatterySample(t = now - (96 - i) * 15 * 60_000L, level = (100 - pph * i / 4).toInt()) }, now)
        val high = insights().copy(analysis = idle(4.0), bootAt = now - 9 * day)
        val t = BatteryTodo.pick(high, permitted = true, now = now)
        val restart = t.single { it.title.contains("재시작") }
        assertTrue(restart.title, restart.title.contains("9일"))
        assertTrue(restart.detail ?: "", (restart.detail ?: "").contains("홈"))
        assertTrue("재시작한 지 사흘", BatteryTodo.pick(high.copy(bootAt = now - 3 * day), permitted = true, now = now).none { it.title.contains("재시작") })
        assertTrue("소모가 정상", BatteryTodo.pick(high.copy(analysis = idle(1.0)), permitted = true, now = now).none { it.title.contains("재시작") })
    }

    /** 할 일마다 바로 여는 화면: 알려진 문제 → 그 앱 정보, 업데이트 뒤 → Play 서비스 앱 정보, AOD·손목 올림 → 디스플레이, 배터리 보호 → 배터리 설정 */
    @Test
    fun eachToDoKnowsWhichScreenToOpen() {
        val t = BatteryTodo.pick(insights(listOf(gms), busyWrist), permitted = true)
        assertEquals(BatteryTodo.Action.AppInfo("com.google.android.gms", "Play 서비스"), t[0].action)
        assertEquals("Play 서비스 앱 정보", t[0].action!!.label)
        assertEquals(BatteryTodo.Action.Display, t.first { it.title.contains("5초 안에 꺼진 화면") }.action)
        val shift = com.mywatch.battery.core.Shift(java.time.LocalDate.of(2026, 10, 17), java.time.LocalDate.of(2026, 10, 18), 1.0, 2.5, 4, 3)
        fun trendWith(c: com.mywatch.battery.core.Change) =
            insights().copy(trend = com.mywatch.battery.core.TrendReport(emptyList(), shift, listOf(c), emptyList(), null, fresh = true))
        val fw = com.mywatch.battery.core.Change(1L, com.mywatch.battery.core.ChangeKind.FIRMWARE, "fw", "A", "B")
        assertEquals(BatteryTodo.Action.AppInfo("com.google.android.gms", "Play 서비스"), BatteryTodo.pick(trendWith(fw), permitted = true).single().action)
        val aod = com.mywatch.battery.core.Change(1L, com.mywatch.battery.core.ChangeKind.SETTING, "aod", "0", "1")
        assertEquals(BatteryTodo.Action.Display, BatteryTodo.pick(trendWith(aod), permitted = true).single().action)
        val face = com.mywatch.battery.core.Change(1L, com.mywatch.battery.core.ChangeKind.FACE, "face", null, "com.example.face")
        assertEquals(null, BatteryTodo.pick(trendWith(face), permitted = true).single().action)
        val charging = com.mywatch.battery.core.ChargeHabits(7.0, 7, 8.0, false, 33.0, listOf("충전기에 100%로 하루 8시간 머뭅니다 — 설정 > 배터리 > 배터리 보호를 켜면 90%에서 멈춰 배터리가 덜 늙습니다"))
        assertEquals(BatteryTodo.Action.Battery, BatteryTodo.pick(insights().copy(charging = charging), permitted = true).single().action)
    }

    @Test
    fun withoutThePermissionsTheToolkitIsSuggested() {
        val t = BatteryTodo.pick(insights(), permitted = false)
        assertEquals(1, t.size)
        assertTrue(t[0].title, t[0].title.contains("권한"))
        assertTrue(t[0].detail ?: "", (t[0].detail ?: "").contains("메뉴 8"))
        assertTrue("권한이 있으면 할 일 없음", BatteryTodo.pick(insights(), permitted = true).isEmpty())
    }
}
