package com.mywatch.battery.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDrainTest {
    private val h = 3_600_000L

    @Test
    fun deltaSubtractsAndClampsAfterAReset() {
        val prev = mapOf("a" to UidUse("a", wakelockMs = 10_000, cpuMs = 5_000), "b" to UidUse("b", wakelockMs = 50_000))
        val now = mapOf(
            "a" to UidUse("a", wakelockMs = 25_000, cpuMs = 6_000),   // 그대로 늘어남
            "b" to UidUse("b", wakelockMs = 3_000),                   // 충전 뒤 통계가 비워져 줄어듦 → 지금 값
            "c" to UidUse("c", sensorMs = 7_000),                     // 새 이름 → 이번엔 기준값만
            "d" to UidUse("d"),                                        // 아무것도 안 씀 → 빠짐
        )
        val d = AppDrain.delta(prev, now).associateBy { it.pkg }
        assertEquals(15_000L, d["a"]!!.wakelockMs)
        assertEquals(1_000L, d["a"]!!.cpuMs)
        assertEquals(3_000L, d["b"]!!.wakelockMs)
        assertTrue("c" !in d)
        assertTrue("d" !in d)
    }

    /**
     * 지난 스냅샷에 없던 이름은 이번 구간엔 기준값만 — 공유 uid 묶음에 앱이 하나 늘거나(“+2” → “+3”) 이름 짓는 법이 바뀌면 부팅 뒤
     * 누적값 전체가 한 구간 사용으로 잡혀 엉뚱한 과소모(예: Play 서비스 비정상 알림)로 짚었다 (리뷰에서 찾음)
     */
    @Test
    fun aRenamedSharedGroupIsABaselineNotASpike() {
        val prev = mapOf("com.google.android.gms+2" to UidUse("com.google.android.gms+2", cpuMs = 90 * 60_000L, wakelockMs = 3 * h))
        val now = mapOf("com.google.android.gms+3" to UidUse("com.google.android.gms+3", cpuMs = 91 * 60_000L, wakelockMs = 3 * h + 1_000))
        assertTrue(AppDrain.delta(prev, now).isEmpty())
        // 첫 스냅샷이면(지난 것이 없으면) 차를 내지 않는 것은 HealthStore 가 한다 — 여기선 빈 지난 것과 비교해도 다 기준값
        assertTrue(AppDrain.delta(emptyMap(), now).isEmpty())
    }

    /**
     * One UI Watch 홈은 화면을 그리는 프로세스라 '화면에 거의 안 떠 있었는데 CPU' 표시를 붙이지 않는다 — 꺼진 화면에서 홈의 상태가
     * '포그라운드 서비스'로 잡혀 화면 시간이 적게 보이면 화면을 그린 CPU 까지 뒤에서 쓴 것으로 셌다(리뷰에서 찾음). 홈은 화면 켜짐 몫을
     * 빼고 보는 알려진 문제([KnownIssues.HOME_HOG])가 맡고, '화면 N분'도 홈엔 붙이지 않는다(어느 쪽으로 잡힐지 몰라)
     */
    @Test
    fun theWatchHomeIsNotGradedByForegroundTime() {
        val r = AppDrain.rank(listOf(UidUse(KnownIssues.HOME, cpuMs = 25 * 60_000L, fgMs = 10 * 60_000L)), 2 * h).single()
        assertEquals(Grade.NORMAL, r.grade)
        assertTrue(r.flags.toString(), r.flags.none { it.contains("CPU") })
        assertTrue(r.summary, !r.summary.contains("화면"))
        // 웨이크락을 멈춘 채 쥐면 그건 그대로 짚는다
        val stuck = AppDrain.rank(listOf(UidUse(KnownIssues.HOME, wakelockMs = 70 * 60_000L)), 2 * h).single()
        assertEquals(Grade.ABNORMAL, stuck.grade)
    }

    @Test
    fun sumMergesWindows() {
        val s = AppDrain.sum(listOf(listOf(UidUse("a", wakelockMs = 1_000, syncs = 1)), listOf(UidUse("a", wakelockMs = 2_000, jobs = 2), UidUse("b", cpuMs = 5))))
        val a = s.first { it.pkg == "a" }
        assertEquals(3_000L, a.wakelockMs); assertEquals(1, a.syncs); assertEquals(2, a.jobs)
        assertEquals(2, s.size)
    }

    @Test
    fun rankingFlagsStuckWakelocksAndVitalsThreshold() {
        val deltas = listOf(
            UidUse("stuck", wakelockMs = 70 * 60_000L),          // 2시간 중 70분
            UidUse("chatty", wakelockMs = 60_000L),              // 시간당 30초 > 18.5초
            UidUse("quiet", wakelockMs = 10_000L, cpuMs = 2_000), // 시간당 5초
            UidUse("gps", gpsMs = 40 * 60_000L),
            UidUse("sensor", sensorMs = 90 * 60_000L, fgMs = 0),
            UidUse("me", cpuMs = 1_000),
        )
        val r = AppDrain.rank(deltas, 2 * h, ownPkg = "me")
        assertEquals("gps", r[0].use.pkg)      // GPS ×3 가중치
        val stuck = r.first { it.use.pkg == "stuck" }
        assertEquals(Grade.ABNORMAL, stuck.grade)
        assertTrue(stuck.flags[0], stuck.flags[0].contains("stuck"))
        val chatty = r.first { it.use.pkg == "chatty" }
        assertEquals(Grade.CAUTION, chatty.grade)
        assertTrue(chatty.flags[0], chatty.flags[0].contains("시간당 30초"))
        assertEquals(Grade.NORMAL, r.first { it.use.pkg == "quiet" }.grade)
        assertTrue(r.first { it.use.pkg == "sensor" }.flags.any { it.contains("센서") })
        assertTrue(r.first { it.use.pkg == "me" }.flags.contains("이 앱"))
        assertEquals("웨이크락 1분", r.first { it.use.pkg == "chatty" }.summary)
    }
}
