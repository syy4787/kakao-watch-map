package com.mywatch.kakaomap

import com.mywatch.kakaomap.model.AccessLeg
import com.mywatch.kakaomap.model.Coord
import com.mywatch.kakaomap.model.IntercityCandidates
import com.mywatch.kakaomap.model.IntercityItineraries
import com.mywatch.kakaomap.model.IntercityKind
import com.mywatch.kakaomap.model.IntercitySplice
import com.mywatch.kakaomap.model.LongRide
import com.mywatch.kakaomap.model.Place
import com.mywatch.kakaomap.model.ScheduledRun
import com.mywatch.kakaomap.model.StationPair
import com.mywatch.kakaomap.model.TimetableFill
import com.mywatch.kakaomap.model.TransitFilter
import com.mywatch.kakaomap.model.TransitGuide
import com.mywatch.kakaomap.model.TransitOptions
import com.mywatch.kakaomap.model.TransitRoute
import com.mywatch.kakaomap.model.TransitStep
import com.mywatch.kakaomap.model.TransitStepType
import com.mywatch.kakaomap.model.Vehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 역·터미널 고르기 · 비어 있는 KTX 시간표 채우기 · 역까지+기차+역에서를 한 경로로 · 안내 중 역까지만 다시 찾기.
 * 역·터미널 이름·카테고리·거리는 2026-10-13 카카오 장소 검색('기차역' · 'KTX정차역' · '고속,시외버스터미널')에서
 * 본 공공 역 이름 그대로, 좌표는 역의 대략 위치.
 */
class IntercityRoutesTest {

    private val seoul = ZoneId.of("Asia/Seoul")
    private fun at(h: Int, m: Int, day: Int = 13) = LocalDateTime.of(2026, 10, day, h, m).atZone(seoul).toInstant().toEpochMilli()

    private fun p(name: String, category: String, m: Int, lat: Double = 36.33, lng: Double = 127.43) =
        Place(name, name, category, "대전 동구", "", Coord(lat, lng), m)

    // ───────── 역 고르기 ─────────

    @Test
    fun nearestStationNearestKtxAndNearbyBigStations() {
        // 대전시청 근처
        val djAny = listOf(
            p("서대전역", "KTX정차역", 3510), p("대전역", "KTX정차역", 4897), p("가수원역 (폐역)", "폐역", 6197),
            p("회덕화물역", "기차역", 6625), p("세천신호장", "기차역", 9592), p("신탄진역", "기차역", 11693),
            p("계룡역", "KTX정차역", 13757), p("스타벅스 신촌기차역점", "커피전문점", 300),
        )
        val djKtx = listOf(p("서대전역", "KTX정차역", 3510), p("대전역", "KTX정차역", 4897), p("계룡역", "KTX정차역", 13757))
        assertEquals(listOf("서대전역", "대전역"), IntercityCandidates.stations(djAny, djKtx).map { it.name })

        // 서울시청 근처: 서울역 + 용산역(호남선 KTX) — 청량리(6.4km)는 서울역(1.5km)보다 훨씬 멀어서 뺌
        val seAny = listOf(
            p("서울역", "KTX정차역", 1498), p("투루카 신촌기차역 2번출구", "투루카", 3218), p("신촌역 경의중앙선", "경의중앙선", 3248),
            p("용산역", "KTX정차역", 4183), p("청량리역", "KTX정차역", 6353), p("영등포역", "KTX정차역", 8357),
        )
        val seKtx = listOf(
            p("서울역", "KTX정차역", 1498), p("용산역", "KTX정차역", 4183), p("청량리역", "KTX정차역", 6353),
            p("영등포역", "KTX정차역", 8357), p("상봉역", "KTX정차역", 10105), p("행신역", "KTX정차역", 13670),
            p("수서역", "KTX정차역", 14331), p("광명역", "KTX정차역", 18587),
        )
        assertEquals(listOf("서울역", "용산역"), IntercityCandidates.stations(seAny, seKtx).map { it.name })

        // 부산시청 근처: 가장 가까운 KTX 역은 부전역(중앙선)이지만 경부선 KTX 는 부산역 — 큰 역이라 함께
        val bsAny = listOf(
            p("부전역", "KTX정차역", 2160), p("1934 기차 동래역", "전시관", 2413), p("동래역 (폐역)", "폐역", 2420),
            p("가야화물역", "기차역", 3845), p("센텀역", "KTX정차역", 4521), p("구포역", "KTX정차역", 7647),
            p("부산역", "KTX정차역", 7791), p("사상역", "기차역", 8091),
        )
        val bsKtx = listOf(
            p("부전역", "KTX정차역", 2160), p("센텀역", "KTX정차역", 4521), p("구포역", "KTX정차역", 7647),
            p("부산역", "KTX정차역", 7791), p("신해운대역", "KTX정차역", 9311),
        )
        assertEquals(listOf("부전역", "부산역"), IntercityCandidates.stations(bsAny, bsKtx).map { it.name })

        // 청주시청 근처: 가까운 일반 역 + KTX 오송역
        val cjAny = listOf(
            p("오근장역", "기차역", 6471), p("청주역", "기차역", 8660), p("청주공항역", "기차역", 8798),
            p("내수역 (폐역)", "폐역", 10419), p("오송역", "KTX정차역", 14646),
        )
        assertEquals(listOf("오근장역", "오송역"), IntercityCandidates.stations(cjAny, listOf(p("오송역", "KTX정차역", 14646))).map { it.name })

        // 강남역 근처: 큰 역 셋 (가게 이름에 '기차역'이 든 것은 뺌)
        val gnAny = listOf(
            p("용산역", "KTX정차역", 6599), p("수서역", "KTX정차역", 6927), p("서울역", "KTX정차역", 8009),
            p("청량리역", "KTX정차역", 9280), p("메가MGC커피 신촌기차역점", "메가MGC커피", 10110),
        )
        val gnKtx = gnAny.take(4) + listOf(p("영등포역", "KTX정차역", 10767), p("판교역(경기)", "KTX정차역", 13618), p("광명역", "KTX정차역", 15560))
        assertEquals(listOf("용산역", "수서역", "서울역"), IntercityCandidates.stations(gnAny, gnKtx).map { it.name })

        assertTrue(IntercityCandidates.stations(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun terminalsAreDedupedAndOnlyRealTerminals() {
        val found = listOf(
            p("대전서남부터미널", "고속,시외버스터미널", 4173), p("대전복합터미널", "고속,시외버스터미널", 4654),
            p("대전복합터미널 서관", "고속,시외버스터미널", 4655), p("대전복합터미널 동관", "고속,시외버스터미널", 4714),
            p("유성복합터미널", "고속,시외버스터미널", 4930),
            p("롯데렌터카 픽업존 유성시외버스터미널지점", "롯데렌터카 G car", 5003),
            p("세종고속시외버스터미널", "고속,시외버스터미널", 16535),
            p("투루카 세종고속시외버스터미널 앞(노상주차장)", "투루카", 16561),
        )
        assertEquals(
            listOf("대전서남부터미널", "대전복합터미널", "유성복합터미널", "세종고속시외버스터미널"),
            IntercityCandidates.terminals(found).map { it.name },
        )
    }

    // ───────── 비어 있는 KTX 시간표 ─────────

    private fun run(no: String, grade: String, depH: Int, depM: Int, arrH: Int, arrM: Int, day: Int = 13, fare: Int = 21600) =
        ScheduledRun(IntercityKind.TRAIN, no, grade, "대전", "서울", at(depH, depM, day), at(arrH, arrM, day), fare)

    @Test
    fun missingKtxAreFilledFromTheSameWeekdayNextWeek() {
        // 2026-10-13 실제: 무궁화·ITX 와 임시 KTX(9038·9062)만 — 다음 주 화요일(10-20)엔 KTX 가 다 있음
        val today = listOf(
            run("1172", "무궁화호", 5, 28, 7, 40, fare = 16000), run("1122", "ITX-새마을", 6, 14, 8, 19, fare = 16000),
            run("9038", "KTX-산천", 14, 45, 15, 47), run("9062", "KTX-산천", 19, 37, 20, 40),
        )
        val nextWeek = listOf(
            run("1172", "무궁화호", 5, 28, 7, 40, day = 20, fare = 16000), run("136", "KTX", 5, 55, 7, 9, day = 20),
            run("138", "KTX", 6, 35, 7, 49, day = 20), run("38", "KTX-산천", 14, 45, 15, 47, day = 20),
        )
        assertTrue(TimetableFill.needsWeekly(today, bothHighSpeed = true))
        assertFalse("일반 역끼리는 원래 KTX 가 없다", TimetableFill.needsWeekly(today, bothHighSpeed = false))
        assertFalse(TimetableFill.needsWeekly(nextWeek + run("140", "KTX", 7, 28, 8, 43), bothHighSpeed = true))

        val merged = TimetableFill.merge(today, nextWeek)
        assertEquals(listOf("1172", "136", "1122", "138", "9038", "9062"), merged.map { it.number })
        val ktx136 = merged[1]
        assertEquals("오늘 날짜로 옮김", at(5, 55), ktx136.depAtMs)
        assertEquals(at(7, 9), ktx136.arrAtMs)
        assertTrue(ktx136.weekly)
        assertFalse(merged[0].weekly)
        // 같은 시각에 이미 있는 열차(9038 ↔ 38)는 더하지 않는다
        assertEquals(1, merged.count { it.depAtMs == at(14, 45) })
    }

    // ───────── 경로 만들기 ─────────

    private val cityHall = Coord(36.3504, 127.3849)
    private val daejeonSt = p("대전역", "KTX정차역", 4897, 36.3326, 127.4344)
    private val seoulSt = p("서울역", "KTX정차역", 1523, 37.5547, 126.9707)
    private val seoulCityHall = Place("seoul", "서울시청", "시청", "서울 중구", "", Coord(37.5663, 126.9779), null)

    /** 시청역 → 대전역 지하철 (카카오 경로처럼 앞뒤 도보 포함) */
    private val toDaejeonSt = TransitRoute(
        type = "SUBWAY", totalDistanceM = 5600, totalTimeSec = 20 * 60, transfers = 0, fare = 1500,
        steps = listOf(
            TransitStep(TransitStepType.WALK, "시청역까지 도보", 300, 300, emptyList(), emptyList(), listOf(cityHall, Coord(36.3510, 127.3880)), approx = true),
            TransitStep(TransitStepType.SUBWAY, "대전 1호선 (시청 > 대전역)", 5000, 600, listOf("시청", "중구청", "대전역"),
                listOf(Vehicle("도시철도", "대전 1호선")), listOf(Coord(36.3510, 127.3880), Coord(36.3320, 127.4330))),
            TransitStep(TransitStepType.WALK, "대전역까지 도보", 300, 300, emptyList(), emptyList(), listOf(Coord(36.3320, 127.4330), daejeonSt.coord), approx = true),
        ),
    )

    private fun trainPair(runs: List<ScheduledRun>) = StationPair(
        IntercityKind.TRAIN,
        dep = AccessLeg(daejeonSt, toDaejeonSt, 20 * 60, 1500),
        arr = AccessLeg(seoulSt, null, 15 * 60, 0),
        runs = runs,
    )

    @Test
    fun itinerariesStartWhenYouCanReachTheStationAndArriveSoonestFirst() {
        val now = at(9, 25)
        val runs = listOf(
            run("1004", "ITX-새마을", 9, 40, 11, 20, fare = 16000),   // 못 탐: 역까지 20분 + 여유 5분 → 9:50 이후만
            run("105", "KTX", 9, 58, 10, 51, fare = 23700),
            run("107", "KTX", 10, 12, 11, 5, fare = 23700),
            run("1302", "무궁화호", 10, 0, 12, 0, fare = 8400),
            run("109", "KTX", 10, 30, 11, 23, fare = 23700),          // 종류마다 3개까지
        )
        val list = IntercityItineraries.build(now, cityHall, seoulCityHall, listOf(trainPair(runs)))
        assertEquals(listOf("105", "107", "109"), list.map { r -> r.steps.first { it.type == TransitStepType.TRAIN }.vehicles.first().name })
        val first = list.first()
        // 지금(9:25)부터 서울역 10:51 + 걸어서 15분 = 11:06 → 1시간 41분 (역에서 기다리는 시간 포함)
        assertEquals(101 * 60, first.totalTimeSec)
        assertEquals(1500 + 23700, first.fare)
        assertEquals("TRAIN", first.type)
        assertEquals(1, first.transfers)
        // 지하철로 역까지 → 기차 → 걸어서 목적지
        assertEquals(
            listOf(TransitStepType.WALK, TransitStepType.SUBWAY, TransitStepType.WALK, TransitStepType.TRAIN, TransitStepType.WALK),
            first.steps.map { it.type },
        )
        val train = first.steps[3]
        assertEquals("대전역", train.boardingStop)
        assertEquals("서울역", train.alightingStop)
        assertEquals(53 * 60, train.timeSec)
        assertEquals(at(9, 58), train.departAtMs)
        assertEquals(at(10, 51), train.arriveAtMs)
        assertEquals(listOf(daejeonSt.coord, seoulSt.coord), train.points)
        val egress = first.steps[4]
        assertTrue(egress.approx)
        assertEquals(listOf(seoulSt.coord, seoulCityHall.coord), egress.points)
        assertEquals(15 * 60, egress.timeSec)
        // 카드·안내 문구
        assertEquals("KTX 105 09:58 출발 · 대전역", TransitGuide.headline(first))
        assertEquals("KTX 105", TransitGuide.lineName(train))
        val guide = TransitGuide.steps(first)
        assertTrue(guide[3].ride && !guide[3].subway)
        assertEquals(LongRide.TRAIN, guide[3].longRide)
        assertEquals("서울역", guide[3].alight)
        assertEquals(at(9, 58), guide[3].departAtMs)
        assertNull(guide[1].longRide)
        assertEquals(TransitFilter.TRAIN, TransitOptions.kindOf(first))
        // 탈 수 있는 편이 없으면 빈 목록
        assertTrue(IntercityItineraries.build(at(23, 50), cityHall, seoulCityHall, listOf(trainPair(runs))).isEmpty())

        // 시간표 경로는 '언제 도착'이 정해져 있다: 9:25 에 찾았어도 9:40 에 보면 남은 시간은 86분, 늦어도 9:33 엔 나가야 함
        assertEquals(at(11, 6), first.arriveAtMs)
        assertEquals(at(9, 33), first.leaveByMs)
        assertEquals(86 * 60, first.secondsFrom(at(9, 40)))
        assertFalse(first.expired(at(9, 33)))
        assertTrue(first.expired(at(9, 34)))
        // 시내 경로(카카오)는 시각이 정해져 있지 않음 — 그대로
        val city = TransitRoute("BUS", 1, 600, 0, null, emptyList())
        assertEquals(600, city.secondsFrom(at(9, 40)))
        assertFalse(city.expired(at(23, 0)))
        // 실시간 버스 도착은 기차 타기 전 버스만 (내려서 탈 버스는 몇 시간 뒤)
        val withEgressBus = first.copy(steps = first.steps + TransitStep(TransitStepType.BUS, "", 1, 1, emptyList(), listOf(Vehicle("간선", "402")), emptyList()))
        assertTrue(TransitGuide.liveBusIndexes(withEgressBus).isEmpty())
        val busFirst = TransitRoute("TRAIN", 1, 1, 1, null, listOf(TransitStep(TransitStepType.BUS, "", 1, 1, emptyList(), listOf(Vehicle("간선", "106")), emptyList())) + first.steps)
        assertEquals(listOf(0), TransitGuide.liveBusIndexes(busFirst))
    }

    @Test
    fun expressAndIntercityBusesBecomeCoachSteps() {
        val now = at(6, 0)
        val terminal = p("대전복합터미널", "고속,시외버스터미널", 4654, 36.3500, 127.4370)
        val seoulExp = Place("x", "서울고속버스터미널(경부)", "고속,시외버스터미널", "서울 서초구", "", Coord(37.5049, 127.0049), null)
        val walkThere = AccessLeg(terminal, null, 20 * 60, 0)
        val express = StationPair(
            IntercityKind.EXPRESS, walkThere, AccessLeg(seoulExp, null, 40 * 60, null),
            listOf(
                ScheduledRun(IntercityKind.EXPRESS, "", "고속", "대전복합", "서울경부", at(7, 25), at(9, 25), 12400),
                ScheduledRun(IntercityKind.EXPRESS, "", "우등", "대전복합", "서울경부", at(7, 45), at(9, 45), 18100),
            ),
        )
        val trains = trainPair(listOf(run("105", "KTX", 9, 58, 10, 51)))
        val all = IntercityItineraries.build(now, cityHall, seoulCityHall, listOf(express, trains), perKind = 1)
        assertEquals(setOf("EXPRESS", "TRAIN"), all.map { it.type }.toSet())
        val bus = all.first { it.type == "EXPRESS" }
        val coach = bus.steps.first { it.type == TransitStepType.COACH }
        assertEquals(Vehicle("고속", "일반"), coach.vehicles.single())   // 고속버스의 '고속' 등급 = 일반
        assertEquals("고속 일반", TransitGuide.label(coach))
        assertEquals("고속버스 일반", TransitGuide.lineName(coach))
        assertEquals("고속버스 일반 07:25 출발 · 대전복합터미널", TransitGuide.headline(bus))
        assertEquals(LongRide.COACH, TransitGuide.steps(bus).first { it.ride }.longRide)
        assertNull("도착 쪽 요금을 모르면 합계도 모름", bus.fare)
        assertEquals(TransitFilter.EXPRESS, TransitOptions.kindOf(bus))
        assertEquals(listOf(TransitStepType.WALK, TransitStepType.COACH, TransitStepType.WALK), bus.steps.map { it.type })

        val intercity = TransitRoute("INTERCITY", 1, 1, 0, null, listOf(coach.copy(vehicles = listOf(Vehicle("시외", "우등")))))
        assertEquals(TransitFilter.INTERCITY, TransitOptions.kindOf(intercity))
        assertEquals("시외버스 우등", TransitGuide.lineName(intercity.steps.single()))

        assertEquals("일반", IntercityItineraries.gradeLabel(IntercityKind.EXPRESS, "고속"))
        assertEquals("심야", IntercityItineraries.gradeLabel(IntercityKind.EXPRESS, "심야고속"))
        assertEquals("우등", IntercityItineraries.gradeLabel(IntercityKind.EXPRESS, "우등"))
        assertEquals("일반", IntercityItineraries.gradeLabel(IntercityKind.INTERCITY, "일반"))
        assertEquals("KTX", IntercityItineraries.gradeLabel(IntercityKind.TRAIN, "KTX"))
    }

    @Test
    fun sameTrainListedTwiceIsShownOnce() {
        // 2026-10-14 실제 목록엔 14:45 KTX-산천이 '00038'·'09038' 두 번 있음 (묶어 다니는 두 편성)
        val runs = listOf(run("9038", "KTX-산천", 14, 45, 15, 47), run("38", "KTX-산천", 14, 45, 15, 47))
        val list = IntercityItineraries.build(at(9, 0), cityHall, seoulCityHall, listOf(trainPair(runs)))
        assertEquals(1, list.size)
    }

    @Test
    fun walkingAndRoughTransitEstimates() {
        val a = Coord(37.0, 127.0)
        val b = Coord(37.0, 127.0 + 1100 / 88_900.0)   // 동쪽 약 1.1km
        assertEquals(1300.0, IntercityItineraries.walkSec(a, b).toDouble(), 15.0)
        val near = Coord(37.0, 127.0 + 500 / 88_900.0)
        assertEquals(IntercityItineraries.walkSec(a, near), IntercityItineraries.estimateSec(a, near))
        val far = Coord(37.0, 127.0 + 4900 / 88_900.0)
        assertEquals(480 + 4900 * 1.35 / 5, IntercityItineraries.estimateSec(a, far).toDouble(), 20.0)
    }

    @Test
    fun kindsFilterIncludesTrainsAndBuses() {
        val city = TransitRoute("BUS", 1, 1, 0, null, listOf(TransitStep(TransitStepType.BUS, "", 1, 1, emptyList(), listOf(Vehicle("간선", "102")), emptyList())))
        val train = IntercityItineraries.build(at(9, 25), cityHall, seoulCityHall, listOf(trainPair(listOf(run("105", "KTX", 9, 58, 10, 51))))).single()
        val filters = TransitOptions.filters(listOf(city, train, train))
        assertEquals(listOf(TransitFilter.ALL to 3, TransitFilter.BUS to 1, TransitFilter.TRAIN to 2), filters)
        assertEquals("열차", TransitFilter.TRAIN.label)
        assertEquals("고속버스", TransitFilter.EXPRESS.label)
        assertEquals("시외버스", TransitFilter.INTERCITY.label)
    }

    @Test
    fun expiredTimetableOptionsAreDroppedAndOnlyARunOutKindIsFetchedAgain() {
        val runs = listOf(run("105", "KTX", 9, 58, 10, 51), run("107", "KTX", 10, 12, 11, 5))
        val trains = IntercityItineraries.build(at(9, 25), cityHall, seoulCityHall, listOf(trainPair(runs)))
        val city = TransitRoute("BUS", 1, 600, 0, null, listOf(TransitStep(TransitStepType.BUS, "", 1, 1, emptyList(), listOf(Vehicle("간선", "102")), emptyList())))
        val all = listOf(city) + trains
        // 9:40: 105(9:33 까지 나가야 함)만 못 탐 → 빼고, 열차는 107 이 남아 다시 찾을 필요 없음
        TransitOptions.dropExpired(all, at(9, 40)).let { (kept, ranOut) ->
            assertEquals(listOf(city, trains[1]), kept)
            assertFalse(ranOut)
        }
        // 9:50: 열차가 다 지나감 → 다시 찾기
        TransitOptions.dropExpired(all, at(9, 50)).let { (kept, ranOut) ->
            assertEquals(listOf(city), kept)
            assertTrue(ranOut)
        }
        TransitOptions.dropExpired(all, at(9, 20)).let { (kept, ranOut) ->
            assertEquals(all, kept)
            assertFalse(ranOut)
        }
    }

    // ───────── 안내 중 다시 찾기 ─────────

    @Test
    fun rerouteKeepsTheTrainAndOnlyReplansTheWayToTheStation() {
        val route = IntercityItineraries.build(at(9, 25), cityHall, seoulCityHall, listOf(trainPair(listOf(run("105", "KTX", 9, 58, 10, 51))))).single()
        // [도보, 지하철, 도보, KTX, 도보]
        assertEquals(3, IntercitySplice.nextScheduled(route, stepIndex = 0, onRide = false))
        assertEquals(3, IntercitySplice.nextScheduled(route, stepIndex = 2, onRide = false))
        assertEquals("역에서 기다리는 중(아직 안 탐)", 3, IntercitySplice.nextScheduled(route, stepIndex = 3, onRide = false))
        assertNull("이미 탔음", IntercitySplice.nextScheduled(route, stepIndex = 3, onRide = true))
        assertNull(IntercitySplice.nextScheduled(route, stepIndex = 4, onRide = false))

        val train = route.steps[3]
        assertTrue(IntercitySplice.canMake(train, accessSec = 20 * 60, nowMs = at(9, 30)))    // 9:52 도착 → 9:58 출발
        assertFalse(IntercitySplice.canMake(train, accessSec = 30 * 60, nowMs = at(9, 30)))   // 10:02 → 놓침

        val walk = TransitStep(TransitStepType.WALK, "대전역까지 도보", 900, 12 * 60, emptyList(), emptyList(), listOf(Coord(36.34, 127.42), daejeonSt.coord), approx = true)
        val spliced = IntercitySplice.splice(route, 3, listOf(walk), nowMs = at(9, 40))
        assertEquals(listOf(TransitStepType.WALK, TransitStepType.TRAIN, TransitStepType.WALK), spliced.steps.map { it.type })
        // 서울역 10:51 + 걸어서 15분 = 11:06 → 지금 9:40 부터 86분
        assertEquals(86 * 60, spliced.totalTimeSec)
        assertEquals(0, spliced.transfers)
        assertEquals("TRAIN", spliced.type)
        assertEquals(at(11, 6), spliced.arriveAtMs)
        assertEquals(at(9, 58) - 12 * 60_000L, spliced.leaveByMs)
    }
}
