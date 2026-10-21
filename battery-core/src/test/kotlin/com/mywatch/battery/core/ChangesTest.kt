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
        fun advice(c: Change?) = ChangeFinder.advice(worse, c, ::name)
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
    }
}
