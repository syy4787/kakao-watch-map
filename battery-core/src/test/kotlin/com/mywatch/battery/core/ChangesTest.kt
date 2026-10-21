package com.mywatch.battery.core

import com.mywatch.battery.core.ChangeNote.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * '그 무렵 바뀐 것': 워치가 15분 표본에 남기는 변화 표시(소프트웨어·앱·시계 화면·설정·지킴이 설정)와,
 * 꺾인 날 무렵의 변화를 가능성 높은 순으로 고르기
 */
class ChangesTest {
    private val zone: ZoneId = ZoneId.of("Asia/Seoul")
    private fun at(d: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 10, d, h, m).atZone(zone).toInstant().toEpochMilli()
    private val gms = "com.google.android.gms"

    @Test
    fun aNoteCarriesEveryKindOfChangeAndComesBack() {
        val note = ChangeNote.encode(listOf(
            Item(ChangeKind.FIRMWARE, "fw", "L350XXU1AZH1", "L350XXU1AZI1"),
            Item(ChangeKind.APP, gms, to = "26.40.31 (190400-812345678)"),
            Item(ChangeKind.APP, "com.example.face", to = "+1.0"),
            Item(ChangeKind.APP, "com.example.gone", to = "-"),
            Item(ChangeKind.FACE, "face", "com.samsung.android.watch.watchface.analogmodular", "com.example.face"),
            Item(ChangeKind.SETTING, "aod", "0", "1"),
            Item(ChangeKind.CONFIG, "night_aod", "0", "1"),
            Item(ChangeKind.MORE_APPS, "apps", to = "3"),
        ))
        assertTrue(note, note.startsWith(ChangeNote.PREFIX))
        assertFalse("CSV 를 깨는 글자 없음: $note", note.contains(',') || note.contains(';') || note.contains('\n'))
        val back = ChangeNote.decode(42L, note)
        assertEquals(8, back.size)
        assertTrue(back.all { it.t == 42L })
        assertEquals(Change(42L, ChangeKind.FIRMWARE, "fw", "L350XXU1AZH1", "L350XXU1AZI1"), back[0])
        assertEquals(Change(42L, ChangeKind.APP, gms, null, "26.40.31"), back[1])
        assertEquals("+1.0", back[2].to)
        assertEquals("-", back[3].to)
        assertEquals(Change(42L, ChangeKind.FACE, "face", "com.samsung.android.watch.watchface.analogmodular", "com.example.face"), back[4])
        assertEquals(Change(42L, ChangeKind.SETTING, "aod", "0", "1"), back[5])
        assertEquals(Change(42L, ChangeKind.CONFIG, "night_aod", "0", "1"), back[6])
        assertEquals(Change(42L, ChangeKind.MORE_APPS, "apps", null, "3"), back[7])
    }

    @Test
    fun theNoteSurvivesTheCsvRoundTrip() {
        val note = ChangeNote.encode(listOf(Item(ChangeKind.APP, gms, to = "26.40.31"), Item(ChangeKind.SETTING, "tilt", "0", "1")))
        val s = BatterySample(t = at(18, 9), level = 80, note = note)
        val parsed = SampleCsv.parseLine(SampleCsv.line(s))!!
        assertEquals(ChangeNote.decode(s.t, note), ChangeNote.decode(parsed.t, parsed.note))
        assertEquals(2, ChangeNote.all(listOf(parsed)).size)
    }

    @Test
    fun otherNotesAndBrokenPartsAreIgnored() {
        assertTrue(ChangeNote.decode(1, "비행기 모드").isEmpty())
        assertTrue(ChangeNote.decode(1, "강제 재시작").isEmpty())
        assertTrue(ChangeNote.decode(1, "").isEmpty())
        // 모르는 종류·깨진 조각은 건너뛰고 나머지는 읽는다
        val c = ChangeNote.decode(1, "chg:zz=1|fw=|set=aod|set=aod:1>0|app=")
        assertEquals(listOf(Change(1, ChangeKind.SETTING, "aod", "1", "0")), c)
        assertEquals("", ChangeNote.encode(emptyList()))
    }

    @Test
    fun valuesAreCleanedAndCut() {
        val note = ChangeNote.encode(listOf(Item(ChangeKind.APP, "com.x|y,z", to = "1.2=3>4;5 beta")))
        val c = ChangeNote.decode(1, note).single()
        assertEquals("com.xyz", c.subject)
        assertEquals("1.2345", c.to)
        val long = ChangeNote.decode(1, ChangeNote.encode(listOf(Item(ChangeKind.FIRMWARE, "fw", "A".repeat(80), "B")))).single()
        assertEquals(ChangeNote.MAX_VALUE, long.from!!.length)
    }

    @Test
    fun allCollectsChangesFromSamplesInTimeOrder() {
        val samples = listOf(
            BatterySample(at(18, 9), 80, note = ChangeNote.encode(listOf(Item(ChangeKind.APP, gms, to = "26.40.31")))),
            BatterySample(at(18, 3), 90, event = Event.BOOT, note = ChangeNote.encode(listOf(Item(ChangeKind.FIRMWARE, "fw", "A", "B")))),
            BatterySample(at(18, 4), 89, event = Event.EXPERIMENT_START, note = "비행기 모드"),
        )
        val all = ChangeNote.all(samples)
        assertEquals(listOf(ChangeKind.FIRMWARE, ChangeKind.APP), all.map { it.kind })
    }

    private val names = mapOf(gms to "Google Play 서비스", "com.example.face" to "Neon Face", "com.example.game" to "게임")
    private fun name(pkg: String) = names[pkg]

    private val worse = Shift(lastBefore = LocalDate.of(2026, 10, 17), firstAfter = LocalDate.of(2026, 10, 18), before = 1.0, after = 2.4, beforeDays = 5, afterDays = 3)

    private val changes = listOf(
        Change(at(16, 9), ChangeKind.APP, "com.example.early", null, "1.0"),        // 창 밖 (앞)
        Change(at(17, 22), ChangeKind.APP, "com.example.game", null, "+2.0"),       // 새로 깖
        Change(at(18, 3), ChangeKind.FIRMWARE, "fw", "L350XXU1AZH1", "L350XXU1AZI1"),
        Change(at(18, 10), ChangeKind.APP, gms, null, "26.40.31"),
        Change(at(18, 12), ChangeKind.SETTING, "aod", "0", "1"),
        Change(at(18, 13), ChangeKind.SETTING, "hotword", "1", "0"),               // 끈 것 — 늘어난 까닭은 아님
        Change(at(19, 9), ChangeKind.FACE, "face", null, "com.example.face"),       // 창 밖 (뒤)
    )

    @Test
    fun changesAroundAWorseShiftComeFirmwareFirstThenWhatWasTurnedOn() {
        val near = ChangeFinder.around(worse, changes, zone)
        assertEquals(listOf("fw", "aod", gms, "com.example.game", "hotword"), near.map { it.subject })
    }

    @Test
    fun aBetterShiftPutsWhatWasTurnedOffFirst() {
        val better = worse.copy(before = 2.4, after = 1.0)
        val list = listOf(
            Change(at(18, 1), ChangeKind.APP, "com.example.game", null, "2.1"),
            Change(at(18, 8), ChangeKind.CONFIG, "night_aod", "0", "1"),
            Change(at(18, 9), ChangeKind.SETTING, "tilt", "1", "0"),
            Change(at(18, 10), ChangeKind.SETTING, "aod", "0", "1"),
        )
        assertEquals(listOf("tilt", "night_aod", "com.example.game", "aod"), ChangeFinder.around(better, list, zone).map { it.subject })
    }

    @Test
    fun eachChangeReadsAsAShortKoreanLine() {
        fun l(c: Change) = ChangeFinder.label(c, ::name)
        assertEquals("워치 소프트웨어 업데이트 (L350XXU1AZI1)", l(changes[2]))
        assertEquals("Google Play 서비스 업데이트 (26.40.31)", l(changes[3]))
        assertEquals("게임 설치", l(changes[1]))
        assertEquals("AOD 켬", l(changes[4]))
        assertEquals("'Hey Google' 끔", l(changes[5]))
        assertEquals("시계 화면 바꿈 (Neon Face)", l(changes[6]))
        assertEquals("com.example.early 업데이트 (1.0)", l(changes[0]))
        assertEquals("com.example.gone 지움", l(Change(1, ChangeKind.APP, "com.example.gone", null, "-")))
        assertEquals("지킴이: 밤에 AOD 끄기 켬", l(Change(1, ChangeKind.CONFIG, "night_aod", "0", "1")))
        assertEquals("앱 3개 더 업데이트", l(Change(1, ChangeKind.MORE_APPS, "apps", null, "3")))
        // 이름을 모르는 잘 알려진 패키지는 core 가 안다
        assertEquals("Samsung Health 업데이트 (6.30)", ChangeFinder.label(Change(1, ChangeKind.APP, "com.samsung.android.wear.shealth", null, "6.30")) { null })
    }

    @Test
    fun theHeadlineSaysWhenAndHowMuch() {
        assertEquals("10/18부터 대기 소모가 시간당 1.0% → 2.4%로 늘었습니다", ChangeFinder.headline(worse))
        assertEquals("10/18부터 대기 소모가 시간당 2.4% → 1.0%로 줄었습니다", ChangeFinder.headline(worse.copy(before = 2.4, after = 1.0)))
        // 워치 화면은 제목(두 줄 안)과 숫자를 나눠서
        assertEquals("10/18부터 대기 소모가 늘었습니다", ChangeFinder.title(worse))
        assertEquals("시간당 1.0% → 2.4%", ChangeFinder.numbers(worse))
    }

    @Test
    fun theFirstSuspectGivesTheThingToDo() {
        fun advice(c: Change?) = ChangeFinder.advice(worse, listOfNotNull(c), ::name)
        val fw = advice(changes[2])
        assertTrue(fw.first, fw.first.contains("소프트웨어 업데이트"))
        assertTrue(fw.second, fw.second.contains("1주") && fw.second.contains("강제 재시작"))
        assertTrue(advice(changes[3]).second.contains("캐시 지우기"))
        assertTrue(advice(changes[4]).first.contains("AOD"))
        assertTrue(advice(changes[6]).second.contains("기본"))
        assertTrue(advice(changes[1]).first.contains("게임"))
        val none = advice(null)
        assertTrue(none.first, none.first.startsWith("10/18부터"))
        assertTrue(none.second, none.second.contains("Play 서비스"))
        // 워치 '지금 할 일' 줄(제목 두 줄·설명 세 줄)에 들어가게 짧게
        for (c in changes + listOf(null)) {
            // (이름을 모르는 앱은 패키지 이름이 길 수 있다 — 워치는 앱 이름을 찾아 쓴다)
            if (c?.subject != "com.example.early") assertTrue(advice(c).first, TodoText.fitsTitle(advice(c).first))
            assertTrue(advice(c).second, TodoText.fitsDetail(advice(c).second))
        }
    }

    /** 늘어난 꺾임인데 그 무렵 바뀐 것이 끈 설정·지운 앱뿐이면 '바뀐 것 없음'이 아니라 '늘릴 만한 것은 없음' — 목록과 어긋나지 않게 */
    @Test
    fun adviceSkipsChangesThatCannotExplainTheRise() {
        val onlyOffs = listOf(
            Change(at(18, 9), ChangeKind.SETTING, "aod", "1", "0"),
            Change(at(18, 10), ChangeKind.APP, "com.example.gone", null, "-"),
            Change(at(18, 11), ChangeKind.CONFIG, "reminder", "1", "0"),   // 충전 알림을 꺼도 대기 소모는 안 는다
        )
        assertTrue(onlyOffs.none { ChangeFinder.explains(it, worse = true) })
        val a = ChangeFinder.advice(worse, ChangeFinder.around(worse, onlyOffs, zone), ::name)
        assertTrue(a.second, a.second.contains("늘릴 만한 것은 없습니다"))
        // 설명할 수 있는 것이 뒤에 있으면 그것으로
        val mixed = ChangeFinder.around(worse, onlyOffs + Change(at(18, 12), ChangeKind.FACE, "face", null, "com.example.face"), zone)
        assertEquals(ChangeKind.FACE, mixed.first().kind)
        // 적기 시작 전이면 '모름'
        assertTrue(ChangeFinder.advice(worse, emptyList(), ::name, known = false).second.contains("모릅니다"))
        // 지킴이의 절약 설정을 끈 것은 늘어난 까닭이 된다 — 조사는 받침 따라
        val off = Change(at(18, 13), ChangeKind.CONFIG, "night_aod", "1", "0")
        val a2 = ChangeFinder.advice(worse, listOf(off), ::name)
        // 제목 두 줄에 이름까지는 안 들어가 '지킴이 설정' — 어느 것인지는 설명에 (조사는 받침 따라)
        assertEquals("지킴이 설정을 끈 뒤 더 닳음", a2.first)
        assertEquals("지킴이에서 '밤에 AOD 끄기'를 다시 켜 보세요", a2.second)
    }

    @Test
    fun objectParticleFollowsTheLastSound() {
        assertEquals("손목 올려 켜기를", ChangeFinder.obj("손목 올려 켜기"))
        assertEquals("'잠자기 전 충전 알림'을", ChangeFinder.obj("'잠자기 전 충전 알림'"))
        assertEquals("'비정상 소모면 바로 절전'을", ChangeFinder.obj("'비정상 소모면 바로 절전'"))
        assertEquals("AOD를", ChangeFinder.obj("AOD"))
        assertEquals("'Hey Google'을", ChangeFinder.obj("'Hey Google'"))
        assertEquals("위치를", ChangeFinder.obj("위치"))
        assertEquals("앱 3을", ChangeFinder.obj("앱 3"))
        assertEquals("앱 2를", ChangeFinder.obj("앱 2"))
    }

    /** 충전기 위에서 보낸 날이 사이에 끼면 그 어느 날 바뀌었는지 모르므로 '사이부터' */
    @Test
    fun aGapBeforeTheFirstWorseDayIsSaidAsARange() {
        val gap = worse.copy(lastBefore = LocalDate.of(2026, 10, 12), firstAfter = LocalDate.of(2026, 10, 16))
        assertEquals("10/13~10/16 사이부터 대기 소모가 늘었습니다", ChangeFinder.title(gap))
        assertTrue(ChangeFinder.headline(gap).startsWith("10/13~10/16 사이부터"))
    }

    /** 삼성 모드(물놀이 잠금·운동·낮잠)가 잠깐 바꿨다 12시간 안에 되돌린 설정은 짝으로 뺀다 */
    @Test
    fun aSettingFlippedBackWithinHalfADayIsDropped() {
        val list = listOf(
            Change(at(18, 9), ChangeKind.SETTING, "aod", "0", "1"),
            Change(at(18, 15), ChangeKind.SETTING, "aod", "1", "0"),
            Change(at(18, 10), ChangeKind.SETTING, "tilt", "1", "0"),
            Change(at(19, 11), ChangeKind.SETTING, "tilt", "0", "1"),      // 하루 넘게 — 둘 다 남음
            Change(at(18, 11), ChangeKind.APP, gms, null, "26.40.31"),
        )
        assertEquals(listOf("tilt", gms, "tilt"), ChangeNote.settled(list).map { it.subject })
    }

    /**
     * 되돌림 짝은 가장 짧은 것부터 — 아침 9시에 켜고, 저녁 8시에 끄고, 30분 뒤 다시 켰으면 짝은 저녁 30분(끔·켬)이고
     * 남는 것은 아침에 켠 것이다 (앞에서부터 짝지으면 아침 켬·저녁 끔이 짝이 되어 '켬'이 저녁 8시 반으로 밀린다 — 리뷰에서 찾음)
     */
    @Test
    fun theShortestFlipBackIsPairedFirst() {
        val list = listOf(
            Change(at(18, 9), ChangeKind.SETTING, "aod", "0", "1"),
            Change(at(18, 20), ChangeKind.SETTING, "aod", "1", "0"),
            Change(at(18, 20, 30), ChangeKind.SETTING, "aod", "0", "1"),
        )
        assertEquals(listOf(list[0]), ChangeNote.settled(list))
    }

    /** 할 일 글은 어떤 설정·지킴이 설정·잘 알려진 앱이든 워치 줄(제목 22자·설명 50자)에 들어가고, 따옴표가 겹치지 않는다 */
    @Test
    fun everyAdviceFitsTheWatchLine() {
        val all = ChangeFinder.SETTING_NAMES.keys.map { Change(at(18, 9), ChangeKind.SETTING, it, "0", "1") } +
            ChangeFinder.CONFIG_NAMES.keys.map { Change(at(18, 9), ChangeKind.CONFIG, it, "1", "0") } +
            ChangeFinder.KNOWN.keys.flatMap { listOf(Change(at(18, 9), ChangeKind.APP, it, null, "26.40.31"), Change(at(18, 9), ChangeKind.APP, it, null, "+1.0")) } +
            listOf(Change(at(18, 9), ChangeKind.FIRMWARE, "fw", "A", "B"), Change(at(18, 9), ChangeKind.FACE, "face", null, "x"),
                Change(at(18, 9), ChangeKind.MORE_APPS, "apps", null, "3"),
                // 이름이 긴 앱
                Change(at(18, 9), ChangeKind.APP, "com.example.long", null, "2.0"))
        val names = mapOf("com.example.long" to "Samsung Galaxy Wearable Plugin Service")
        assertEquals("삼성 헬스 업데이트 뒤 더 닳음", ChangeFinder.advice(worse, listOf(all.first { it.subject == ChangeFinder.SHEALTH }), { null }).first)
        assertTrue(ChangeFinder.advice(worse, listOf(all.last()), { names[it] }).first.let { it.startsWith("Samsung Galaxy") && it.contains("… 업데이트 뒤 더 닳음") })
        // 너비는 한글 한 글자 = 1, 영문은 그보다 좁게
        assertEquals(6.6, TodoText.width("업데이트 뒤 더"), 0.01)   // 한글 6 + 띄어쓰기 2
        assertTrue(TodoText.width("Samsung Health") < 10)
        for (c in all) {
            val (title, detail) = ChangeFinder.advice(worse, listOf(c), { names[it] })
            assertTrue("$title (${TodoText.lines(title, TodoText.TITLE_LINE)}줄)", TodoText.fitsTitle(title))
            assertTrue("$detail (${TodoText.lines(detail, TodoText.DETAIL_LINE)}줄)", TodoText.fitsDetail(detail))
            assertFalse(title, title.contains("''") || Regex("'[^']*'[^']*'[^']*'").containsMatchIn(title))
        }
    }

    @Test
    fun theStartOfTrackingIsKeptApartFromTheChanges() {
        val start = BatterySample(at(17, 9), 80, note = ChangeNote.encode(listOf(Item(ChangeKind.TRACKING, "on"))))
        val later = BatterySample(at(18, 9), 70, note = ChangeNote.encode(listOf(Item(ChangeKind.APP, gms, to = "26.40.31"))))
        assertEquals("chg:on=1", start.note)
        assertEquals(at(17, 9), ChangeNote.trackedSince(listOf(later, start)))
        assertEquals(listOf(ChangeKind.APP), ChangeNote.all(listOf(start, later)).map { it.kind })
        assertEquals(null, ChangeNote.trackedSince(listOf(later)))
    }
}
