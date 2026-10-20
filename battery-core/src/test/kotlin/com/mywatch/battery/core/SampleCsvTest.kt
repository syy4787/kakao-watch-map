package com.mywatch.battery.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SampleCsvTest {

    private val full = BatterySample(
        t = 1_760_000_000_000L, level = 57, chargeUah = 250_000, voltageMv = 3_912, tempDeciC = 331, currentUa = -84_000,
        status = Status.DISCHARGING, plugged = false, interactive = true, event = Event.EXPERIMENT_START, note = "비행기 모드",
    )

    @Test
    fun aSampleSurvivesTheRoundTrip() {
        assertEquals(full, SampleCsv.parseLine(SampleCsv.line(full)))
    }

    @Test
    fun unknownValuesStayUnknown() {
        val bare = BatterySample(t = 1L, level = 0)
        val line = SampleCsv.line(bare)
        assertEquals("1,0,,,,,UNKNOWN,0,0,PERIODIC,", line)
        assertEquals(bare, SampleCsv.parseLine(line))
    }

    @Test
    fun commasAndNewlinesInTheNoteAreTamed() {
        val s = full.copy(note = "a,b\nc")
        val parsed = SampleCsv.parseLine(SampleCsv.line(s))!!
        assertEquals("a;b c", parsed.note)
    }

    @Test
    fun headerCommentsAndBrokenLinesAreSkipped() {
        assertNull(SampleCsv.parseLine(SampleCsv.MAGIC))
        assertNull(SampleCsv.parseLine(SampleCsv.HEADER))
        assertNull(SampleCsv.parseLine(""))
        assertNull(SampleCsv.parseLine("12345,ab"))
        assertNull(SampleCsv.parseLine("1,57,,,,,DISCHARGING,0,0"))       // 열 부족 (쓰다 만 줄)
        assertNull(SampleCsv.parseLine("1,157,,,,,DISCHARGING,0,0,PERIODIC,")) // 잔량 범위 밖
        assertNull(SampleCsv.parseLine("1,57,,,,,WHAT,0,0,PERIODIC,"))
    }

    @Test
    fun parseSortsAndDeduplicates() {
        val a = BatterySample(t = 3L, level = 50)
        val b = BatterySample(t = 1L, level = 52)
        val text = listOf(SampleCsv.MAGIC, SampleCsv.HEADER, SampleCsv.line(a), "garbage", SampleCsv.line(b), SampleCsv.line(a)).joinToString("\n")
        assertEquals(listOf(b, a), SampleCsv.parse(text))
    }
}
