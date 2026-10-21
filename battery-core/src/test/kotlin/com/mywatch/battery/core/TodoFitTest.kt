package com.mywatch.battery.core

import org.junit.Assert.assertTrue
import org.junit.Test

/** 할 일로 오르는 글은 모두 워치 '지금 할 일' 줄(제목 두 줄·설명 세 줄)에 들어간다 — 잘려 다른 뜻으로 읽히지 않게 (점검에서 찾음) */
class TodoFitTest {
    private fun fits(p: Pair<String, String>?) {
        val (t, d) = p ?: return
        assertTrue("제목: $t (${TodoText.lines(t, TodoText.TITLE_LINE)}줄)", TodoText.fitsTitle(t))
        assertTrue("설명: $d (${TodoText.lines(d, TodoText.DETAIL_LINE)}줄)", TodoText.fitsDetail(d))
    }

    @Test
    fun knownIssues() {
        val gms = AppDrainRank(UidUse(ChangeFinder.GMS, cpuMs = 40 * 60_000L), 1e7, listOf("stuck"), Grade.ABNORMAL)
        val face = AppDrainRank(UidUse("com.example.face", cpuMs = 30 * 60_000L), 1e6, emptyList(), Grade.CAUTION)
        val issues = KnownIssues.detect(listOf(gms, face), "com.example.face", 2 * 3_600_000L)
        assertTrue(issues.size == 2 && issues.all { it.todo != null })
        issues.forEach { fits(it.todo) }
        // 캐시만 — '데이터'가 잘려 '데이터 지…'로 읽히지 않게
        assertTrue(issues[0].todo!!.second.contains("캐시만"))
        val home = AppDrainRank(UidUse(KnownIssues.HOME, cpuMs = 40 * 60_000L, fgMs = 2 * 3_600_000L), 1e8, emptyList(), Grade.NORMAL)
        val homeIssue = KnownIssues.detect(listOf(home), windowMs = 2 * 3_600_000L, screenOnMs = 60_000L).single()
        fits(homeIssue.todo)
        // 재시작 뒤 다시 나타난 문제의 다음 단계 줄도
        val all = setOf(KnownIssues.HOME_HOG, KnownIssues.PLAY_SERVICES_LOOP)
        fits(KnownIssues.detect(listOf(home), windowMs = 2 * 3_600_000L, screenOnMs = 60_000L, cameBack = all).single().todo)
        KnownIssues.detect(listOf(gms, face), "com.example.face", 2 * 3_600_000L, cameBack = all).forEach { fits(it.todo) }
        // 그다음 단계(오류 보고·업데이트) 줄도
        fits(KnownIssues.detect(listOf(home), windowMs = 2 * 3_600_000L, screenOnMs = 60_000L, cameBack = all, again = all).single().todo)
        KnownIssues.detect(listOf(gms, face), "com.example.face", 2 * 3_600_000L, cameBack = all, again = all).forEach { fits(it.todo) }
    }

    @Test
    fun gaugeDrift() {
        for (d in listOf(-38, -12, 12, 38)) fits(GaugeDrift(50, 50 - d, d, "높음", 8, 3900).todo)
    }

    @Test
    fun wakes() {
        val w = WakeStats(400, 300, 120, 200, 600_000L, 150, listOf("com.example.very.long.notifier" to 120), 20.0, 40, 120_000L)
        fits(w.gestureTodo())
        fits(w.notificationTodo())
    }
}
