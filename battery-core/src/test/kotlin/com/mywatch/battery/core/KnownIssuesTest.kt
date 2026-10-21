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
    /**
     * 창이 짧으면(55분 미만) 어떤 문제도 짚지 않는다 — 재시작 직후·첫 스냅샷처럼 짧은 창에선 잠깐의 일(재시작 뒤 Play 서비스 동기화 등)이
     * 시간당으로 부풀려진다 (홈에만 있던 기준을 모든 문제에 — 리뷰에서 찾음). 창을 모르면(시험) 그대로 본다
     */
    @Test
    fun noIssueIsJudgedOnAShortWindow() {
        val face = "com.jeremysteckling.facerrel"
        val uses = listOf(UidUse("com.google.android.gms", cpuMs = 8 * 60_000L, wakelockMs = 10 * 60_000L), UidUse(face, cpuMs = 4 * 60_000L, wakelockMs = 3 * 60_000L))
        val short = 30 * 60_000L
        assertTrue(KnownIssues.detect(AppDrain.rank(uses, short), face, short).isEmpty())
        assertEquals(
            setOf(KnownIssues.PLAY_SERVICES_LOOP, KnownIssues.WATCH_FACE_HEAVY),
            KnownIssues.detect(AppDrain.rank(uses.map { it + it }, h), face, h).map { it.id }.toSet(),
        )
        assertEquals(KnownIssues.PLAY_SERVICES_LOOP, KnownIssues.detect(AppDrain.rank(uses, short)).first().id)
        // 경계는 55분 — 앱별 통계를 남기는 간격(55분이 지나면)과 같게: 재시작 뒤 기준값 다음의 첫 한 줄로 볼 수 있게
        val min = KnownIssues.MIN_WINDOW_MS
        assertEquals(55 * 60_000L, min)
        assertTrue(KnownIssues.detect(AppDrain.rank(uses, min - 60_000L), face, min - 60_000L).isEmpty())
        assertTrue(KnownIssues.detect(AppDrain.rank(uses, min), face, min).isNotEmpty())
    }

    /**
     * 타사 시계 화면이 뒤에서(화면이 꺼진 동안에도) CPU·웨이크락을 많이 쓰면 짚는다 — 기본 삼성 시계 화면으로 바꿔 비교하라고.
     * 삼성·구글 기본 시계 화면(Watch Face Format 렌더러 포함)은 짚지 않는다
     */
    @Test
    fun aHeavyThirdPartyWatchFaceIsAKnownIssue() {
        val face = "com.jeremysteckling.facerrel"
        val ranks = AppDrain.rank(
            listOf(
                UidUse(face, cpuMs = 14 * 60_000L, wakelockMs = 12 * 60_000L),
                UidUse("com.samsung.android.wear.shealth", sensorMs = 30 * 60_000L),
            ),
            2 * h,
        )
        val i = KnownIssues.detect(ranks, watchFace = face).single()
        assertEquals(KnownIssues.WATCH_FACE_HEAVY, i.id)
        assertEquals(face, i.pkg)
        assertTrue(i.steps.first(), i.steps.first().contains("기본"))
        assertTrue(i.grade != Grade.NORMAL)
        // 같은 앱이라도 지금 시계 화면이 아니면 시계 화면 문제가 아니다
        assertTrue(KnownIssues.detect(ranks, watchFace = "com.other.face").none { it.id == KnownIssues.WATCH_FACE_HEAVY })
        // 삼성 기본 시계 화면·WFF 렌더러는 짚지 않는다
        val samsung = AppDrain.rank(listOf(UidUse("com.samsung.android.watch.watchface.analogmodular", cpuMs = 30 * 60_000L)), 2 * h)
        assertTrue(KnownIssues.detect(samsung, watchFace = "com.samsung.android.watch.watchface.analogmodular").isEmpty())
        // 조금만 쓰면 그대로
        val light = AppDrain.rank(listOf(UidUse(face, cpuMs = 60_000L)), 2 * h)
        assertTrue(KnownIssues.detect(light, watchFace = face).isEmpty())
        assertTrue(KnownIssues.isSystemFace("com.google.wear.watchface.runtime"))
        assertTrue(!KnownIssues.isSystemFace(face))
    }

    /**
     * One UI Watch 홈(시스템 UI, com.samsung.android.wearable.sysui)이 화면이 거의 꺼져 있던 동안 CPU 를 많이 쓰면 짚는다 —
     * 2026년 9~10월 워치9·One UI 9 업데이트 뒤 '배터리 1위가 One UI Watch 홈', 보통 재시작으론 안 풀리고 강제 재시작으로 나아졌다는
     * 보고(삼성 커뮤니티). 홈은 늘 '보이는' 프로세스라 화면 밖 CPU 표시가 안 붙으므로, 화면이 켜져 있던 시간만큼은 빼고 본다
     */
    @Test
    fun theWatchHomeBurningCpuWithTheScreenOffIsAKnownIssue() {
        val home = KnownIssues.HOME
        val ranks = AppDrain.rank(
            listOf(
                UidUse(home, cpuMs = 24 * 60_000L, fgMs = 2 * h),
                UidUse("com.samsung.android.wear.shealth", sensorMs = 60 * 60_000L, cpuMs = 2 * 60_000L),
            ),
            2 * h,
        )
        // 홈 자체의 등급은 '정상' (화면 밖 CPU 표시가 안 붙는다) — 그래서 따로 본다
        assertEquals(Grade.NORMAL, ranks.first { it.use.pkg == home }.grade)
        val i = KnownIssues.detect(ranks, windowMs = 2 * h, screenOnMs = 6 * 60_000L).single()
        assertEquals(KnownIssues.HOME_HOG, i.id)
        assertEquals(home, i.pkg)
        assertEquals(Grade.ABNORMAL, i.grade)                            // (24분 − 화면 6분의 절반) ÷ 2시간 ≈ 시간당 10.5분
        assertTrue(i.steps.first(), i.steps.first().contains("강제 재시작"))
        assertTrue(i.steps.toString(), i.steps.none { it.contains("데이터 지우기") && !it.contains("하지 마세요") })
        assertTrue(i.why, i.why.contains("CPU") && i.why.contains("화면 켜짐 6분"))
        assertTrue(i.tile!!, i.tile!!.contains("강제 재시작"))
        // 화면을 오래 켜고 썼으면(2시간 중 40분) 그 CPU 는 홈 화면·알림·타일을 그린 것
        assertTrue(KnownIssues.detect(ranks, windowMs = 2 * h, screenOnMs = 40 * 60_000L).isEmpty())
        // 화면 켜진 시간을 모르면(사용 통계 권한 없음) 짚지 않는다
        assertTrue(KnownIssues.detect(ranks, windowMs = 2 * h).isEmpty())
        // 시간당 6분이면 주의, 4분(AOD·알림 표시로 있을 법함)이면 그대로
        val warm = AppDrain.rank(listOf(UidUse(home, cpuMs = 14 * 60_000L, fgMs = 2 * h)), 2 * h)
        assertEquals(Grade.CAUTION, KnownIssues.detect(warm, windowMs = 2 * h, screenOnMs = 4 * 60_000L).single().grade)
        val light = AppDrain.rank(listOf(UidUse(home, cpuMs = 10 * 60_000L, fgMs = 2 * h)), 2 * h)
        assertTrue(KnownIssues.detect(light, windowMs = 2 * h, screenOnMs = 4 * 60_000L).isEmpty())
        // 알림·진동으로 웨이크락을 조금(시간당 30초) 쥐는 것은 흔하다 — CPU 가 적으면 짚지 않는다. 멈춘 채 쥐면(창의 절반 넘게) 비정상
        val wakes = AppDrain.rank(listOf(UidUse(home, cpuMs = 4 * 60_000L, wakelockMs = 60_000L, fgMs = 2 * h)), 2 * h)
        assertEquals(Grade.CAUTION, wakes.single().grade)
        assertTrue(KnownIssues.detect(wakes, windowMs = 2 * h, screenOnMs = 4 * 60_000L).isEmpty())
        val stuck = AppDrain.rank(listOf(UidUse(home, cpuMs = 4 * 60_000L, wakelockMs = 80 * 60_000L, fgMs = 2 * h)), 2 * h)
        assertEquals(Grade.ABNORMAL, KnownIssues.detect(stuck, windowMs = 2 * h, screenOnMs = 4 * 60_000L).single().grade)
        // 다른 앱이 훨씬 많이 쓰면 홈 탓을 하지 않는다
        val behind = AppDrain.rank(listOf(UidUse(home, cpuMs = 10 * 60_000L, fgMs = 2 * h), UidUse("com.example.drain", wakelockMs = 110 * 60_000L, cpuMs = 40 * 60_000L)), 2 * h)
        assertTrue(KnownIssues.detect(behind, windowMs = 2 * h, screenOnMs = 4 * 60_000L).none { it.id == KnownIssues.HOME_HOG })
    }

    /** 홈 판단의 경계 — 창이 1시간은 넘어야(짧으면 잠깐의 일이 시간당으로 부풀려짐), 화면이 창의 30% 까지면 본다 (리뷰에서 찾음) */
    @Test
    fun theWatchHomeNeedsAnHourAndAtMostThirtyPercentScreen() {
        val home = KnownIssues.HOME
        val busy = AppDrain.rank(listOf(UidUse(home, cpuMs = 8 * 60_000L)), 30 * 60_000L)
        assertTrue("30분 창은 짧다", KnownIssues.detect(busy, windowMs = 30 * 60_000L, screenOnMs = 0).isEmpty())
        val ranks = AppDrain.rank(listOf(UidUse(home, cpuMs = 40 * 60_000L)), 2 * h)
        assertEquals(1, KnownIssues.detect(ranks, windowMs = 2 * h, screenOnMs = 36 * 60_000L).size)        // 딱 30%
        assertTrue(KnownIssues.detect(ranks, windowMs = 2 * h, screenOnMs = 36 * 60_000L + 1).isEmpty())  // 넘음
    }

    /** 다른 앱의 알림을 받아 보여 주는 일도 홈이 한다 — 알림 하나에 2초씩은 홈 탓이 아니다 (채팅 폭주·운동 중 알림) */
    @Test
    fun notificationsTheHomeHadToShowAreNotBlamedOnIt() {
        val home = KnownIssues.HOME
        val ranks = AppDrain.rank(listOf(UidUse(home, cpuMs = 24 * 60_000L)), 2 * h)
        assertEquals(Grade.ABNORMAL, KnownIssues.detect(ranks, windowMs = 2 * h, screenOnMs = 6 * 60_000L).single().grade)
        // 알림 300개 × 2초 = 10분 → (24 − 3 − 10)분 ÷ 2시간 = 시간당 5.5분 → 주의
        val withNotes = KnownIssues.detect(ranks, windowMs = 2 * h, screenOnMs = 6 * 60_000L, notifications = 300).single()
        assertEquals(Grade.CAUTION, withNotes.grade)
        assertTrue(withNotes.why, withNotes.why.contains("알림 300개"))
    }

    /** 해결 단계의 앱 이름은 이 워치에 보이는 이름으로 (모르면 'One UI Watch 홈') */
    @Test
    fun theHomeStepsUseTheWatchsOwnAppName() {
        val ranks = AppDrain.rank(listOf(UidUse(KnownIssues.HOME, cpuMs = 24 * 60_000L)), 2 * h)
        val named = KnownIssues.detect(ranks, windowMs = 2 * h, screenOnMs = 0, label = { if (it == KnownIssues.HOME) "One UI Watch Home" else null }).single()
        assertTrue(named.steps.toString(), named.steps.any { it.contains("One UI Watch Home") })
        val plain = KnownIssues.detect(ranks, windowMs = 2 * h, screenOnMs = 0).single()
        assertTrue(plain.steps.toString(), plain.steps.any { it.contains("One UI Watch 홈") })
    }
}
