package com.mywatch.battery.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class GuardianRulesTest {
    private val zone: ZoneId = ZoneId.of("Asia/Seoul")
    private val q = 15 * 60_000L
    private fun at(h: Int, m: Int = 0, d: Int = 20) = LocalDateTime.of(2026, 10, d, h, m).atZone(zone).toInstant().toEpochMilli()

    /** [end] 까지 15분 간격 [count]개, 시간당 [pph], 전부 화면 꺼짐 */
    private fun drain(end: Long, count: Int, startLevel: Double, pph: Double, interactive: Boolean = false, plugged: Boolean = false) =
        (0 until count).map { i -> BatterySample(t = end - (count - 1 - i) * q, level = (startLevel - pph * i / 4).toInt(), interactive = interactive, plugged = plugged) }

    private val cfg = GuardianConfig()

    @Test
    fun nightModeStartsOnlyAfterTheScreenHasBeenOffForAWhile() {
        val now = at(0, 30)
        // 자정 반 — 마지막 표본이 15분 전까지 화면 켜짐
        val recent = drain(now, 8, 60.0, 2.0).toMutableList()
        recent[recent.size - 2] = recent[recent.size - 2].copy(interactive = true)
        val (a1, s1) = GuardianRules.decide(now, zone, cfg, GuardianState(), recent, null, saverOnNow = false)
        assertTrue(a1.isEmpty()); assertFalse(s1.nightActive)
        // 30분 넘게 꺼져 있었으면 시작
        val quiet = drain(now, 8, 60.0, 2.0)
        val (a2, s2) = GuardianRules.decide(now, zone, cfg, GuardianState(), quiet, null, saverOnNow = false)
        assertEquals(listOf<GuardianAction>(GuardianAction.NightOn), a2)
        assertTrue(s2.nightActive)
    }

    @Test
    fun nightModeEndsInTheMorningOrOnChargerOrWhenWakingUp() {
        val active = GuardianState(nightActive = true)
        // 07:05 → 끝
        val (a1, s1) = GuardianRules.decide(at(7, 5), zone, cfg, active, drain(at(7, 5), 8, 50.0, 1.0), null, true)
        assertEquals(listOf<GuardianAction>(GuardianAction.NightOff), a1); assertFalse(s1.nightActive)
        // 03:00 에 잠깐 화면 봄 → 그대로
        val glance = drain(at(3, 0), 8, 50.0, 1.0).toMutableList().also { it[it.size - 1] = it.last().copy(interactive = true) }
        val (a2, _) = GuardianRules.decide(at(3, 0), zone, cfg, active, glance, null, true)
        assertTrue(a2.isEmpty())
        // 06:10 에 화면 봄 → 일어난 것 → 끝
        val wake = drain(at(6, 10), 8, 50.0, 1.0).toMutableList().also { it[it.size - 1] = it.last().copy(interactive = true) }
        val (a3, _) = GuardianRules.decide(at(6, 10), zone, cfg, active, wake, null, true)
        assertEquals(listOf<GuardianAction>(GuardianAction.NightOff), a3)
        // 충전기에 올림 → 끝
        val plugged = drain(at(2, 0), 8, 50.0, 1.0).toMutableList().also { it[it.size - 1] = it.last().copy(plugged = true) }
        val (a4, _) = GuardianRules.decide(at(2, 0), zone, cfg, active, plugged, null, true)
        assertEquals(listOf<GuardianAction>(GuardianAction.NightOff), a4)
    }

    @Test
    fun whenTheUserTurnsSaverOffWeBackOffForTwelveHours() {
        val now = at(1, 0)
        // 우리가 켠 절전이 켜진 것을 한 번 본 뒤(saverSeenOn) 꺼져 있으면 사용자가 끈 것
        val seen = GuardianState(nightActive = true, nightSince = now - 3_600_000L, saverSeenOn = true)
        val (a, s) = GuardianRules.decide(now, zone, cfg, seen, drain(now, 8, 50.0, 1.0), null, saverOnNow = false)
        assertEquals(listOf<GuardianAction>(GuardianAction.UserOverride), a)
        assertFalse(s.nightActive)
        assertEquals(now + 12 * 3_600_000L, s.userOverrideUntil)
        // 그 사이엔 밤이어도 다시 켜지 않는다
        val (a2, _) = GuardianRules.decide(now + 3_600_000L, zone, cfg, s, drain(now + 3_600_000L, 8, 48.0, 1.0), null, false)
        assertTrue(a2.isEmpty())
    }

    @Test
    fun aWatchThatIgnoresTheSaverSettingIsDetectedNotMistakenForTheUser() {
        val start = at(1, 0)
        val (a0, s0) = GuardianRules.decide(start, zone, cfg, GuardianState(), drain(start, 8, 50.0, 1.0), null, saverOnNow = false)
        assertEquals(listOf<GuardianAction>(GuardianAction.NightOn), a0)
        // 15분 뒤 아직 절전이 안 켜짐 — 아직은 기다린다
        val (a1, s1) = GuardianRules.decide(start + q, zone, cfg, s0, drain(start + q, 8, 50.0, 1.0), null, saverOnNow = false)
        assertTrue(a1.isEmpty()); assertTrue(s1.nightActive)
        // 45분 뒤에도 안 켜짐 → 이 워치는 설정으로 절전이 안 됨. 밤 모드(AOD 등)는 그대로 유지
        val (a2, s2) = GuardianRules.decide(start + 3 * q, zone, cfg, s1, drain(start + 3 * q, 8, 49.0, 1.0), null, saverOnNow = false)
        assertEquals(listOf<GuardianAction>(GuardianAction.SaverNotWorking), a2)
        assertTrue(s2.saverBroken); assertTrue(s2.nightActive)
        // 그 뒤엔 절전이 꺼져 있어도 사용자 override 로 보지 않는다
        val (a3, _) = GuardianRules.decide(start + 4 * q, zone, cfg, s2, drain(start + 4 * q, 8, 49.0, 1.0), null, saverOnNow = false)
        assertTrue(a3.isEmpty())
        // 절전이 켜진 것을 본 뒤라면 꺼짐 = 사용자
        val (a4, s4) = GuardianRules.decide(start + q, zone, cfg, s0, drain(start + q, 8, 50.0, 1.0), null, saverOnNow = true)
        assertTrue(a4.isEmpty()); assertTrue(s4.saverSeenOn)
        val (a5, _) = GuardianRules.decide(start + 2 * q, zone, cfg, s4, drain(start + 2 * q, 8, 50.0, 1.0), null, saverOnNow = false)
        assertEquals(listOf<GuardianAction>(GuardianAction.UserOverride), a5)
    }

    @Test
    fun abnormalDaytimeDrainTurnsSaverOnOnceAndNotifiesWithCooldown() {
        val now = at(14, 0)
        val fast = drain(now, 9, 80.0, 6.0)   // 2시간 화면 꺼진 채 6%/h
        val (a, s) = GuardianRules.decide(now, zone, cfg, GuardianState(), fast, null, saverOnNow = false)
        assertEquals(1, a.size)
        val on = a[0] as GuardianAction.AbnormalOn
        assertEquals(6.0, on.pph, 0.01); assertTrue(on.notify)
        assertTrue(s.abnormalActive)
        // 15분 뒤: 이미 켜져 있으니 조용
        val (a2, _) = GuardianRules.decide(now + q, zone, cfg, s, drain(now + q, 10, 80.0, 6.0), null, true)
        assertTrue(a2.isEmpty())
        // 6시간 뒤엔 풀고 다시 잰다
        val (a3, s3) = GuardianRules.decide(now + 6 * 3_600_000L, zone, cfg, s, drain(now + 6 * 3_600_000L, 9, 50.0, 1.0), null, true)
        assertEquals(listOf<GuardianAction>(GuardianAction.AbnormalOff), a3); assertFalse(s3.abnormalActive)
        // 알림을 보낸 지 6시간이 안 됐으면 켜되 알림은 생략
        val recentlyNotified = GuardianState(lastAbnormalNotifyAt = now - 3_600_000L)
        val (a4, _) = GuardianRules.decide(now, zone, cfg, recentlyNotified, fast, null, false)
        assertFalse((a4.single() as GuardianAction.AbnormalOn).notify)
    }

    @Test
    fun abnormalModeIsNotEnteredWhileChargingOrAtNight() {
        val day = at(14, 0)
        val charging = drain(day, 9, 80.0, 6.0, plugged = true)
        assertTrue(GuardianRules.decide(day, zone, cfg, GuardianState(), charging, null, false).first.isEmpty())
        val night = at(2, 0)
        val (a, _) = GuardianRules.decide(night, zone, cfg, GuardianState(nightActive = true), drain(night, 9, 80.0, 6.0), null, true)
        assertTrue(a.isEmpty())   // 밤 모드가 이미 절전
    }

    @Test
    fun bedtimeReminderWhenTheNightWillNotBeSurvived() {
        val now = at(22, 45)
        val samples = drain(now, 8, 42.0, 3.0)   // 지금 약 37%
        val analysis = DrainAnalyzer(445, zone).analyze(samples, now)   // 밤 기록이 없어 idle24(3%/h)를 쓴다
        val (a, s) = GuardianRules.decide(now, zone, cfg, GuardianState(), samples, analysis, false)
        val r = a.filterIsInstance<GuardianAction.ChargeReminder>().single()
        // 지금 36%, 07:00 까지 8.25시간 × 약 3.4%/h(정수 잔량이라 조금 높게 잡힘) ≈ 28% 소모 → 약 8% 예상 → 25% 까지 약 17분
        assertTrue(r.predictedPercent.toString(), r.predictedPercent in 5.0..15.0)
        assertTrue(r.minutes.toString(), r.minutes in 10..20)
        assertEquals("2026-10-20", s.lastReminderDay)
        // 같은 날엔 다시 안 함
        val (a2, _) = GuardianRules.decide(now + q, zone, cfg, s, drain(now + q, 9, 42.0, 3.0), analysis, false)
        assertTrue(a2.filterIsInstance<GuardianAction.ChargeReminder>().isEmpty())
        // 충분하면 알림 없음 (잔량 90%)
        val (a3, _) = GuardianRules.decide(now, zone, cfg, GuardianState(), drain(now, 8, 92.0, 3.0), analysis, false)
        assertTrue(a3.filterIsInstance<GuardianAction.ChargeReminder>().isEmpty())
    }

    @Test
    fun windowHelpers() {
        assertTrue(GuardianRules.inWindow(30, 0, 420))
        assertFalse(GuardianRules.inWindow(420, 0, 420))
        assertTrue(GuardianRules.inWindow(1400, 1380, 60))   // 23:20 in 23:00~01:00
        assertTrue(GuardianRules.inWindow(10, 1380, 60))
        assertEquals(390, GuardianRules.minutesUntil(30, 420))
        assertEquals(495, GuardianRules.minutesUntil(1365, 420))   // 22:45 → 07:00
    }

    /** 사용 통계로 정확히 아는 화면 켜짐이 있으면 15분 표본의 화면 상태보다 그것을 믿는다 */
    @Test
    fun exactScreenTimeBeatsTheSampleFlags() {
        val now = at(0, 30)
        val quiet = drain(now, 8, 60.0, 2.0)          // 표본으로는 내내 꺼짐
        // 실제로는 10분 전까지 화면을 보고 있었다 (표본 사이에 켰다 끔)
        val (a1, _) = GuardianRules.decide(now, zone, cfg, GuardianState(), quiet, null, saverOnNow = false, screenOffMs = 10 * 60_000L)
        assertTrue(a1.isEmpty())
        // 마지막 표본은 켜짐이지만 실제로는 40분째 꺼져 있다 (표본 찍는 순간만 켜져 있었음)
        val flag = quiet.toMutableList().also { it[it.size - 1] = it.last().copy(interactive = true) }
        val (a2, _) = GuardianRules.decide(now, zone, cfg, GuardianState(), flag, null, saverOnNow = false, screenOffMs = 40 * 60_000L)
        assertEquals(listOf<GuardianAction>(GuardianAction.NightOn), a2)
    }

    @Test
    fun heavyDrainWhileTheScreenWasActuallyOnIsNotCalledAbnormal() {
        val now = at(14, 0)
        val fast = drain(now, 9, 80.0, 6.0)            // 표본으로는 꺼진 채 시간당 6%
        val (a1, _) = GuardianRules.decide(now, zone, cfg, GuardianState(), fast, null, saverOnNow = false)
        assertTrue(a1.toString(), a1.any { it is GuardianAction.AbnormalOn })
        // 실제로는 2시간 중 1시간 넘게 화면을 썼다 (영상 등) — 비정상이 아니다
        val (a2, _) = GuardianRules.decide(now, zone, cfg, GuardianState(), fast, null, saverOnNow = false, screenOnMs2h = 70 * 60_000L)
        assertTrue(a2.toString(), a2.none { it is GuardianAction.AbnormalOn })
        assertEquals(null, AlertRule.check(fast, now, null, screenOnMs = 70 * 60_000L))
        assertTrue(AlertRule.check(fast, now, null, screenOnMs = 5 * 60_000L) != null)
    }
}
