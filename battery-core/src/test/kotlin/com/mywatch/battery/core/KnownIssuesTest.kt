package com.mywatch.battery.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 알려진 문제: 2026년 갤럭시워치(6·7·8·울트라, 워치9 사용자 보고 포함)에서 4·6·7월에 되풀이된 'Google Play 서비스가 멈춘 채
 * 돌며 배터리를 먹는' 문제 — 화면이 꺼져 있는데 CPU·웨이크락을 크게 쓰면 짚어 내고, 해결 순서(캐시 지우기 → 재시작)를 준다.
 */
class KnownIssuesTest {
    private val h = 3_600_000L

    @Test
    fun backgroundCpuIsFlaggedButForegroundUseIsNot() {
        val r = AppDrain.rank(
            listOf(
                UidUse("busy", cpuMs = 25 * 60_000L),                       // 2시간에 25분 = 시간당 12분
                UidUse("warm", cpuMs = 8 * 60_000L),                        // 시간당 4분
                UidUse("player", cpuMs = 30 * 60_000L, fgMs = 100 * 60_000L), // 화면에 띄워 쓴 앱
            ),
            2 * h,
        ).associateBy { it.use.pkg }
        assertEquals(Grade.ABNORMAL, r["busy"]!!.grade)
        assertTrue(r["busy"]!!.flags.toString(), r["busy"]!!.flags.any { it.contains("CPU 시간당 12분") })
        assertEquals(Grade.CAUTION, r["warm"]!!.grade)
        assertEquals(Grade.NORMAL, r["player"]!!.grade)
    }

    @Test
    fun playServicesBurningCpuIsAKnownIssue() {
        val ranks = AppDrain.rank(
            listOf(
                UidUse("com.google.android.gms+2", cpuMs = 30 * 60_000L, wakelockMs = 40 * 60_000L),
                UidUse("com.samsung.android.wear.shealth", sensorMs = 60 * 60_000L, cpuMs = 2 * 60_000L),
            ),
            2 * h,
        )
        val issues = KnownIssues.detect(ranks)
        val i = issues.single()
        assertEquals(KnownIssues.PLAY_SERVICES_LOOP, i.id)
        assertEquals("com.google.android.gms", i.pkg)
        assertTrue(i.title, i.title.contains("Play 서비스"))
        assertTrue(i.steps.toString(), i.steps.first().contains("캐시 지우기"))
        assertTrue(i.steps.toString(), i.steps.any { it.contains("재시작") })
        assertTrue(i.why, i.why.contains("CPU"))
    }

    @Test
    fun playServicesDoingNormalWorkIsNotAnIssue() {
        val ranks = AppDrain.rank(listOf(UidUse("com.google.android.gms", cpuMs = 90_000L, syncs = 3), UidUse("x", wakelockMs = 70 * 60_000L)), 2 * h)
        assertTrue(KnownIssues.detect(ranks).isEmpty())
    }

    @Test
    fun aSmallShareBehindABiggerCulpritIsNotPinnedOnPlayServices() {
        // Play 서비스도 조금 높지만 다른 앱이 훨씬 많이 씀 — 엉뚱한 해결책을 주지 않는다
        val ranks = AppDrain.rank(
            listOf(UidUse("com.google.android.gms", cpuMs = 8 * 60_000L), UidUse("com.example.drain", wakelockMs = 110 * 60_000L, cpuMs = 30 * 60_000L)),
            2 * h,
        )
        assertTrue(KnownIssues.detect(ranks).isEmpty())
    }
}
