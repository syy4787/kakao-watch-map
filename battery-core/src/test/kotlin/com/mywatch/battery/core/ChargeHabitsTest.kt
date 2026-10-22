package com.mywatch.battery.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 충전 습관: 충전기에 100%로 오래 머무는지(배터리 보호 90% 권함 — 워치8부터 설정 > 배터리 > 배터리 보호), 충전 중 온도, 하루 충전 횟수.
 * 배터리 수명(용량이 줄어드는 속도)에 관한 것 — 하루 소모와는 따로
 */
class ChargeHabitsTest {
    private val zone: ZoneId = ZoneId.of("Asia/Seoul")
    private val q = 15 * 60_000L
    private fun at(d: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 10, d, h, m).atZone(zone).toInstant().toEpochMilli()

    /** [days]일 동안 매일 밤 22시~07시 충전(빨리 100% 가 되어 그대로 [fullTo]%), 낮엔 15분마다 1%씩 */
    private fun nights(days: Int, fullTo: Int = 100, tempDeciC: Int = 330): List<BatterySample> {
        val out = ArrayList<BatterySample>()
        for (d in 0 until days) {
            var level = 95
            var t = at(10 + d, 7)
            while (t < at(10 + d, 22)) { out += BatterySample(t, level, tempDeciC = 300); level = (level - 1).coerceAtLeast(20); t += q }
            while (t < at(11 + d, 7)) {
                level = minOf(fullTo, level + 10)
                out += BatterySample(t, level, plugged = true, status = if (level >= 100) Status.FULL else Status.CHARGING, tempDeciC = tempDeciC)
                t += q
            }
        }
        return out
    }

    @Test
    fun hoursAtFullOnTheChargerAskForBatteryProtection() {
        val s = nights(7)
        val h = ChargeHabits.analyze(s, at(17, 7))!!
        assertEquals(7, h.sessions)
        assertTrue(h.hoursAtFullPerDay.toString(), h.hoursAtFullPerDay in 6.0..9.0)
        assertFalse(h.protectLikely)
        assertTrue(h.advice.joinToString(), h.advice.any { it.contains("배터리 보호") })
        // 할 일 줄에 맞춘 짧은 글이 같은 차례로
        assertTrue(h.todos.toString(), h.todos.size == h.advice.size && h.todos.all { (t, d) -> TodoText.fitsTitle(t) && TodoText.fitsDetail(d) })
    }

    @Test
    fun aWatchThatStopsAtNinetyAlreadyHasProtectionOn() {
        val h = ChargeHabits.analyze(nights(5, fullTo = 90), at(15, 7))!!
        assertTrue(h.protectLikely)
        assertEquals(0.0, h.hoursAtFullPerDay, 0.01)
        assertTrue(h.advice.none { it.contains("배터리 보호를 켜") })
    }

    @Test
    fun hotChargingIsCalledOut() {
        val h = ChargeHabits.analyze(nights(3, tempDeciC = 445), at(13, 7))!!
        assertEquals(44.5, h.maxChargeTempC!!, 0.01)
        assertTrue(h.advice.joinToString(), h.advice.any { it.contains("44.5℃") })
        assertTrue(h.todos.toString(), h.todos.size == h.advice.size && h.todos.all { (t, d) -> TodoText.fitsTitle(t) && TodoText.fitsDetail(d) })
    }

    /**
     * [days]일 동안 하루 한 번 20시에 [from]%에서 충전 — 분당 [perMin]%씩 오르고(100%까지) 그동안 [tempDeciC], 낮엔 95%에서 떨어짐.
     * [screenOn] 이면 충전 중 화면이 켜져 있음 (충전기 위에서 씀)
     */
    private fun charges(days: Int, perMin: Double, tempDeciC: Int = 330, from: Int = 15, screenOn: Boolean = false): List<BatterySample> =
        chargesOf(List(days) { Charge(perMin, tempDeciC, from, screenOn) })

    /** 하루의 충전 — 분당 [perMin]%씩 [upTo]%까지 오르고 그동안 [tempDeciC], [holdHours] 시간 그 잔량에 머물다(배터리 보호) [driftTo]%까지 내려감 */
    private data class Charge(val perMin: Double, val tempDeciC: Int = 330, val from: Int = 15, val screenOn: Boolean = false,
                              val upTo: Int = 100, val holdHours: Int = 0, val driftTo: Int? = null)

    /** 날마다 08~20시 [Charge.from]%까지 떨어지고 20시부터 그날의 충전 */
    private fun chargesOf(days: List<Charge>): List<BatterySample> {
        val out = ArrayList<BatterySample>()
        days.forEachIndexed { d, c ->
            var t = at(10 + d, 8)
            while (t < at(10 + d, 20)) { out += BatterySample(t, 95 - ((t - at(10 + d, 8)) / q).toInt() * (95 - c.from) / 48); t += q }
            var lv = c.from.toDouble()
            while (lv < c.upTo) {
                out += BatterySample(t, lv.toInt(), plugged = true, status = Status.CHARGING, tempDeciC = c.tempDeciC, interactive = c.screenOn)
                t += q; lv += c.perMin * 15
            }
            out += BatterySample(t, c.upTo, plugged = true, status = if (c.upTo >= 100) Status.FULL else Status.NOT_CHARGING, tempDeciC = c.tempDeciC)
            repeat(c.holdHours * 4) { t += q; out += BatterySample(t, c.upTo, plugged = true, status = Status.NOT_CHARGING, tempDeciC = 300) }
            c.driftTo?.let { to -> var l = c.upTo; while (l > to) { t += q; l -= 1; out += BatterySample(t, l, plugged = true, status = Status.NOT_CHARGING, tempDeciC = 300) } }
        }
        return out
    }

    /**
     * 워치9 충전이 느림 — 삼성 공식 0→100% 40mm 80분·44mm 90분인데 사용자들은 2~4시간(충전 중 44℃까지 뜨거워져 느려짐 — Android Authority
     * 2026-08-12, Notebookcheck). 10~85% 구간(빠른 충전 구간)으로 0→100% 걸릴 시간을 어림해 공식의 1.3배를 넘으면 느림 — 뜨거웠으면 식히는
     * 길(선풍기 바람: 2시간 10분 → 1시간 15분 사용자 보고, 얼음은 습기 때문에 X), 안 뜨거웠으면 충전기 쪽
     */
    @Test
    fun slowHotChargingOnAWatch9PointsAtTheHeat() {
        val h = ChargeHabits.analyze(charges(3, perMin = 0.6, tempDeciC = 440), at(13, 8), model = "SM-L350")!!
        val sp = h.speed!!
        assertEquals(3, sp.measured)
        assertEquals(90, sp.specMinutes)
        assertEquals(166.7, sp.minutesPerFull, 0.5)
        assertTrue(sp.slow)
        assertEquals(44.0, sp.maxTempC!!, 0.01)
        val slow = h.advice.single { it.contains("충전이 공식보다 느립니다") }
        assertTrue(slow, slow.contains("2시간 47분") && slow.contains("공식 고속 충전 90분") && slow.contains("44℃") && slow.contains("선풍기") && slow.contains("얼음"))
        // 뜨거운 충전은 이 줄 하나로 (같은 말을 두 번 하지 않게)
        assertEquals(1, h.advice.count { it.contains("℃") })
        assertTrue(h.todos.toString(), h.todos.size == h.advice.size && h.todos.all { (t, d) -> TodoText.fitsTitle(t) && TodoText.fitsDetail(d) })
    }

    @Test
    fun slowCoolChargingPointsAtTheCharger() {
        val h = ChargeHabits.analyze(charges(3, perMin = 0.6), at(13, 8), model = "SM-L340")!!
        assertEquals(80, h.speed!!.specMinutes)
        val slow = h.advice.single { it.contains("충전이 공식보다 느립니다") }
        assertTrue(slow, slow.contains("공식 고속 충전 80분") && slow.contains("충전기") && !slow.contains("선풍기"))
        assertTrue(h.todos.toString(), h.todos.size == h.advice.size && h.todos.all { (t, d) -> TodoText.fitsTitle(t) && TodoText.fitsDetail(d) })
    }

    @Test
    fun aNormalChargeSaysNothingAboutSpeed() {
        val h = ChargeHabits.analyze(charges(3, perMin = 1.3), at(13, 8), model = "SM-L350")!!
        assertFalse(h.speed!!.slow)
        assertEquals(77.6, h.speed!!.minutesPerFull, 1.0)
        assertTrue(h.advice.joinToString(), h.advice.none { it.contains("느립니다") })
    }

    /** 한 번뿐이거나(그날만 그랬을 수 있다), 모르는 모델(공식 시간을 모름), 충전기 위에서 화면을 켜고 쓴 충전은 느림을 말하지 않는다 */
    @Test
    fun slowIsOnlySaidWithEnoughCleanCharges() {
        assertFalse(ChargeHabits.analyze(charges(1, perMin = 0.6), at(11, 8), model = "SM-L350")!!.speed?.slow ?: false)
        val unknown = ChargeHabits.analyze(charges(3, perMin = 0.6), at(13, 8), model = "SM-R960")!!
        assertFalse(unknown.speed!!.slow)
        assertTrue(unknown.advice.none { it.contains("느립니다") })
        assertNull(ChargeHabits.analyze(charges(3, perMin = 0.6, screenOn = true), at(13, 8), model = "SM-L350")!!.speed)
        // 충전 중 기록이 30분 넘게 빈 충전도 빼고 잰다 (그 사이를 모른다) — 날마다 충전 셋째·넷째 기록을 지움
        val gappy = charges(3, perMin = 0.6).let { all ->
            val drop = all.filter { it.plugged }.groupBy { it.t / (24 * 3_600_000L) }.values.flatMap { day -> day.drop(2).take(2) }.toSet()
            all.filterNot { it in drop }
        }
        assertNull(ChargeHabits.analyze(gappy, at(13, 8), model = "SM-L350")!!.speed)
    }

    /**
     * 잰 충전이 둘이면 둘 다 느려야 느림 — 가운데 값을 아래쪽으로 잡는다(둘의 평균이면 한 번만 느려도 '느림'이고, '그런 충전이 2번 이상'이라는
     * 기준과 어긋났다 · 리뷰)
     */
    @Test
    fun withTwoChargesBothMustBeSlow() {
        val h = ChargeHabits.analyze(chargesOf(listOf(Charge(1.3), Charge(0.6))), at(12, 8), model = "SM-L350")!!
        assertEquals(2, h.speed!!.measured)
        assertEquals(77.6, h.speed!!.minutesPerFull, 1.0)
        assertFalse(h.speed!!.slow)
    }

    /**
     * 빠르기는 처음 오른 구간만 — 배터리 보호(90%에서 멈추고 85% 아래로 내려가면 다시 충전)로 충전기 위에서 몇 시간 머물다 85%로 내려와도
     * 그 충전을 빼지 않고, 85%에 머무는 보호(적응형 등)도 몇 시간짜리 '느린 충전'으로 읽지 않는다 (리뷰)
     */
    @Test
    fun aProtectedChargeIsMeasuredOnItsFirstRiseOnly() {
        val drift = ChargeHabits.analyze(chargesOf(List(3) { Charge(0.6, upTo = 90, holdHours = 3, driftTo = 85) }), at(13, 8), model = "SM-L350")!!
        assertEquals(3, drift.speed!!.measured)
        assertTrue(drift.speed!!.slow)
        val hold85 = ChargeHabits.analyze(chargesOf(List(3) { Charge(1.3, upTo = 85, holdHours = 6) }), at(13, 8), model = "SM-L350")!!
        assertFalse(hold85.speed.toString(), hold85.speed!!.slow)
        // 85% 아래에서 멈추는 보호(휴대폰의 '최대 80%'처럼)도 — 다 오른 뒤 머문 시간은 빼고 잰다
        val hold80 = ChargeHabits.analyze(chargesOf(List(3) { Charge(1.3, upTo = 80, holdHours = 6) }), at(13, 8), model = "SM-L350")!!
        assertEquals(3, hold80.speed!!.measured)
        assertFalse(hold80.speed.toString(), hold80.speed!!.slow)
    }

    /**
     * 열 탓으로 보는지는 느린 충전들로만 — 빠르지만 따뜻했던 충전 한 번 때문에 충전기 대신 열을 짚지 않게 (리뷰). 그때 온도도 느린 충전의 것
     */
    @Test
    fun heatIsJudgedFromTheSlowChargesOnly() {
        val h = ChargeHabits.analyze(chargesOf(listOf(Charge(1.3, tempDeciC = 410), Charge(0.6), Charge(0.6))), at(13, 8), model = "SM-L350")!!
        val sp = h.speed!!
        assertTrue(sp.slow)
        assertFalse(sp.hot)
        assertEquals(33.0, sp.maxTempC!!, 0.01)
        assertTrue(h.advice.joinToString(), h.advice.single { it.contains("충전이 공식보다 느립니다") }.contains("충전기") && h.advice.none { it.contains("선풍기") })
    }

    @Test
    fun withoutAnyChargingThereIsNothingToSay() {
        val s = (0..40).map { BatterySample(at(10, 7) + it * q, 90 - it / 2) }
        assertNull(ChargeHabits.analyze(s, at(10, 18)))
    }
}
