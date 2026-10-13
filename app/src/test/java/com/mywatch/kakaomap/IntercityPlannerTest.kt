package com.mywatch.kakaomap

import com.mywatch.kakaomap.model.Coord
import com.mywatch.kakaomap.model.Geo
import com.mywatch.kakaomap.model.IntercityKind
import com.mywatch.kakaomap.model.Place
import com.mywatch.kakaomap.model.ScheduledRun
import com.mywatch.kakaomap.model.TagoNode
import com.mywatch.kakaomap.model.TransitRoute
import com.mywatch.kakaomap.model.TransitStep
import com.mywatch.kakaomap.model.TransitStepType
import com.mywatch.kakaomap.model.Vehicle
import com.mywatch.kakaomap.net.ApiException
import com.mywatch.kakaomap.net.IntercityPlanner
import com.mywatch.kakaomap.net.IntercitySource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Collections

/**
 * 대전시청 → 서울시청 (2026-10-13 화 09:25) 을 가짜 카카오·TAGO 로 재현.
 * 오늘 열차 목록엔 실제처럼 KTX 가 빠져 있고 다음 주 화요일 목록엔 있다.
 */
class IntercityPlannerTest {

    private val seoulZone = ZoneId.of("Asia/Seoul")
    private fun at(h: Int, m: Int, day: Int = 13) = LocalDateTime.of(2026, 10, day, h, m).atZone(seoulZone).toInstant().toEpochMilli()

    private val origin = Coord(36.3504, 127.3849)        // 대전시청
    private val dest = Place("dest", "서울시청", "시청", "서울 중구 태평로1가", "", Coord(37.5663, 126.9779), null)

    private fun place(name: String, category: String, address: String, lat: Double, lng: Double, from: Coord) =
        Place(name, name, category, address, "", Coord(lat, lng), Geo.distanceM(from, Coord(lat, lng)).toInt())

    private val daejeonSt = place("대전역", "KTX정차역", "대전 동구 정동 1", 36.3326, 127.4344, origin)
    private val seoDaejeonSt = place("서대전역", "KTX정차역", "대전 중구 오류동 1", 36.3225, 127.4039, origin)
    private val djTerminal = place("대전복합터미널", "고속,시외버스터미널", "대전 동구 용전동 63", 36.3500, 127.4370, origin)
    private val yuseong = place("유성복합터미널", "고속,시외버스터미널", "대전 유성구 구암동 9", 36.3550, 127.3330, origin)
    private val seoulSt = place("서울역", "KTX정차역", "서울 중구 봉래동2가 122", 37.5547, 126.9707, dest.coord)
    private val yongsanSt = place("용산역", "KTX정차역", "서울 용산구 한강로3가 40", 37.5298, 126.9648, dest.coord)
    private val seoulExp = place("서울고속버스터미널(경부)", "고속,시외버스터미널", "서울 서초구 반포동 19-3", 37.5049, 127.0049, dest.coord)
    private val dongSeoul = place("동서울종합터미널", "고속,시외버스터미널", "서울 광진구 구의동 546-1", 37.5345, 127.0945, dest.coord)

    private fun train(no: String, grade: String, dh: Int, dm: Int, ah: Int, am: Int, day: Int = 13) =
        ScheduledRun(IntercityKind.TRAIN, no, grade, "대전", "서울", at(dh, dm, day), at(ah, am, day), 23700)

    private fun bus(grade: String, dh: Int, dm: Int, ah: Int, am: Int) =
        ScheduledRun(IntercityKind.EXPRESS, "", grade, "대전복합", "서울경부", at(dh, dm), at(ah, am), 18100)

    private fun cityRoute(minutes: Int, from: Coord, to: Coord, fare: Int?) = TransitRoute(
        "SUBWAY", 3000, minutes * 60, 0, fare,
        listOf(TransitStep(TransitStepType.SUBWAY, "지하철 (가 > 나)", 3000, minutes * 60, listOf("가", "나"), listOf(Vehicle("", "1호선")), listOf(from, to))),
    )

    private open inner class Fake : IntercitySource {
        val transitCalls: MutableList<String> = Collections.synchronizedList(ArrayList())
        val runCalls: MutableList<String> = Collections.synchronizedList(ArrayList())

        private fun nearOrigin(c: Coord) = Geo.distanceM(c, origin) < 1000

        override fun places(query: String, near: Coord, radiusM: Int): List<Place> = when (query) {
            "기차역" -> if (nearOrigin(near)) listOf(seoDaejeonSt, daejeonSt) else listOf(seoulSt, yongsanSt)
            "KTX정차역" -> if (nearOrigin(near)) listOf(seoDaejeonSt, daejeonSt) else listOf(seoulSt, yongsanSt)
            "고속,시외버스터미널" -> if (nearOrigin(near)) listOf(djTerminal, yuseong) else listOf(seoulExp, dongSeoul)
            else -> emptyList()
        }

        override fun trainStations(cityCode: Int): List<TagoNode> = when (cityCode) {
            25 -> listOf(TagoNode("NAT011668", "대전"), TagoNode("NAT030057", "서대전"))
            11 -> listOf(TagoNode("NAT010000", "서울"), TagoNode("NAT010032", "용산"))
            else -> emptyList()
        }

        override fun expressTerminals() = listOf(
            TagoNode("NAEK010", "서울경부"), TagoNode("NAEK030", "동서울"), TagoNode("NAEK300", "대전복합"), TagoNode("NAEK360", "유성복합"),
        )

        override fun intercityTerminals() = listOf(
            TagoNode("NAI0511601", "동서울", "서울특별시"), TagoNode("NAI3455101", "대전복합", "대전광역시"),
            TagoNode("NAI3417501", "유성복합", "대전광역시"),
        )

        override fun runs(kind: IntercityKind, depId: String, arrId: String, day: LocalDate): List<ScheduledRun> {
            runCalls += "$kind $depId>$arrId ${day.dayOfMonth}"
            return when {
                kind == IntercityKind.TRAIN && depId == "NAT011668" && arrId == "NAT010000" && day.dayOfMonth == 13 -> listOf(
                    train("1004", "ITX-새마을", 9, 40, 11, 20), train("1302", "무궁화호", 10, 0, 12, 0), train("9038", "KTX-산천", 14, 45, 15, 47),
                )
                kind == IntercityKind.TRAIN && depId == "NAT011668" && arrId == "NAT010000" && day.dayOfMonth == 20 -> listOf(
                    train("105", "KTX", 9, 58, 10, 51, 20), train("107", "KTX", 10, 12, 11, 5, 20), train("109", "KTX", 10, 30, 11, 23, 20),
                )
                kind == IntercityKind.EXPRESS && depId == "NAEK300" && arrId == "NAEK010" ->
                    listOf(bus("고속", 7, 25, 9, 25), bus("우등", 10, 5, 12, 5), bus("고속", 10, 20, 12, 20))
                else -> emptyList()
            }
        }

        override fun transit(from: Coord, to: Coord, toName: String): TransitRoute? {
            val fromName = listOf(daejeonSt, seoDaejeonSt, djTerminal, yuseong, seoulSt, yongsanSt, seoulExp, dongSeoul)
                .firstOrNull { Geo.distanceM(it.coord, from) < 50 }?.name ?: "출발"
            transitCalls += "$fromName>$toName"
            return when (toName) {
                "대전역" -> cityRoute(20, from, to, 1500)
                "서대전역" -> cityRoute(15, from, to, 1500)
                "대전복합터미널" -> cityRoute(25, from, to, 1500)
                "유성복합터미널" -> cityRoute(30, from, to, 1500)
                "서울시청" -> when (fromName) {
                    "서울역" -> cityRoute(10, from, to, 1400)
                    "서울고속버스터미널(경부)" -> cityRoute(35, from, to, 1400)
                    else -> cityRoute(30, from, to, 1400)
                }
                else -> null
            }
        }
    }

    @Test
    fun daejeonToSeoulFindsKtxFromNextWeeksTimetableAndExpressBuses() = runBlocking {
        val fake = Fake()
        val result = IntercityPlanner(fake).plan(origin, dest, at(9, 25))
        assertTrue(result.problems.isEmpty())
        val trains = result.routes.filter { it.type == "TRAIN" }
        val buses = result.routes.filter { it.type == "EXPRESS" }
        // 대전역 10:00 전에 닿으면(지하철 20분 + 여유 5분) 탈 수 있는 KTX 3편 — 오늘 목록엔 없어 다음 주 화요일 것으로
        assertEquals(listOf("105", "107", "109"), trains.map { r -> r.steps.first { it.type == TransitStepType.TRAIN }.vehicles.first().name })
        assertTrue(result.weekly)
        val first = trains.first()
        // 9:25 출발 → 서울역 10:51 + 지하철 10분 = 11:01 → 96분
        assertEquals(96 * 60, first.totalTimeSec)
        assertEquals(1500 + 23700 + 1400, first.fare)
        assertEquals(at(9, 58), first.steps.first { it.type == TransitStepType.TRAIN }.departAtMs)
        // 고속버스는 터미널까지 25분 → 10:05·10:20 편 (7:25 편은 지남)
        assertEquals(listOf(at(10, 5), at(10, 20)), buses.map { r -> r.steps.first { it.type == TransitStepType.COACH }.departAtMs })
        // 대전→서울에 일반 열차만 있고 KTX 가 빠짐 → 자료가 빠진 날: KTX 역끼리 4쌍 모두 다음 주 시간표를 한 번 더
        assertEquals(4, fake.runCalls.count { it.startsWith("TRAIN") && it.endsWith(" 20") })
        assertTrue(fake.runCalls.contains("TRAIN NAT011668>NAT010000 20"))
        assertTrue("버스는 다음 주 것을 묻지 않음", fake.runCalls.none { !it.startsWith("TRAIN") && it.endsWith(" 20") })
        // 역까지 실제 길은 고른 경로에 쓰인 역·터미널만 (서대전역·유성은 탈 편이 없어서 묻지 않음)
        assertEquals(
            setOf("출발>대전역", "출발>대전복합터미널", "서울역>서울시청", "서울고속버스터미널(경부)>서울시청"),
            fake.transitCalls.toSet(),
        )
        // 열차 4쌍(대전·서대전 × 서울·용산) + 고속 + 시외 쌍들 — 같은 역끼리는 묻지 않음
        assertTrue(fake.runCalls.none { it.contains("NAT011668>NAT011668") })
    }

    @Test
    fun aFailingKindIsReportedAndTheOthersStillCome() = runBlocking {
        val fake = object : Fake() {
            override fun runs(kind: IntercityKind, depId: String, arrId: String, day: LocalDate): List<ScheduledRun> {
                if (kind == IntercityKind.TRAIN) throw ApiException("공공데이터 키가 이 기능에 아직 등록되지 않았어요")
                return super.runs(kind, depId, arrId, day)
            }
        }
        val result = IntercityPlanner(fake).plan(origin, dest, at(9, 25))
        assertEquals(listOf("열차: 공공데이터 키가 이 기능에 아직 등록되지 않았어요"), result.problems)
        assertTrue(result.routes.isNotEmpty())
        assertTrue(result.routes.all { it.type == "EXPRESS" })
        assertFalse(result.weekly)
    }

    @Test
    fun nothingNearbyMeansNoRoutesAndNoComplaints() = runBlocking {
        val fake = object : Fake() {
            override fun places(query: String, near: Coord, radiusM: Int): List<Place> = emptyList()
        }
        val result = IntercityPlanner(fake).plan(origin, dest, at(9, 25))
        assertTrue(result.routes.isEmpty())
        assertTrue(result.problems.isEmpty())
        assertTrue(fake.runCalls.isEmpty())
        assertTrue(fake.transitCalls.isEmpty())
    }
}
