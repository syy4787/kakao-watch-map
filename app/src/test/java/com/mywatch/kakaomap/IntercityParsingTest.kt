package com.mywatch.kakaomap

import com.mywatch.kakaomap.model.IntercityKind
import com.mywatch.kakaomap.model.Regions
import com.mywatch.kakaomap.model.ScheduleParsers
import com.mywatch.kakaomap.model.TagoMatching
import com.mywatch.kakaomap.model.TagoNode
import com.mywatch.kakaomap.model.TagoParsers
import com.mywatch.kakaomap.net.TagoCatalog
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 공공데이터 TAGO 열차·고속버스·시외버스 시간표와 역·터미널 이름.
 * 응답 모양은 2026-10-13 실제 응답 그대로 (열차는 모두 문자열·열차 번호 앞에 0, 고속은 12자리 숫자에 '24시',
 * 시외는 14자리 숫자이고 순서가 섞여 옴). 터미널 이름도 실제 TAGO 목록·카카오 장소 이름.
 */
class IntercityParsingTest {

    private val seoul = ZoneId.of("Asia/Seoul")
    private fun at(day: Int, h: Int, m: Int) = LocalDateTime.of(2026, 10, day, h, m).atZone(seoul).toInstant().toEpochMilli()

    @Test
    fun trainTimetableReadsTheRealStringShape() {
        val json = JSONObject("""{"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},"body":{"items":{"item":[
            {"trainno":"00138","traingradename":"KTX","depplacename":"대전","arrplacename":"서울","depplandtime":"20261014063500","arrplandtime":"20261014074900","adultcharge":"21600"},
            {"trainno":"01172","traingradename":"무궁화호","depplacename":"대전","arrplacename":"서울","depplandtime":"20261014052800","arrplandtime":"20261014074000","adultcharge":"16000"},
            {"trainno":"00252","traingradename":"KTX-산천(A-type)","depplacename":"대전","arrplacename":"서울","depplandtime":"20261014070000","arrplandtime":"20261014080100","adultcharge":"21600"}
            ]},"numOfRows":200,"pageNo":1,"totalCount":3}}}""")
        assertNull(TagoParsers.errorMessage(json))
        val runs = ScheduleParsers.trains(json)
        // 출발 시각 순, 열차 번호 앞의 0 은 떼고, 등급 꼬리 "(A-type)" 도 뗀다
        assertEquals(listOf("1172", "138", "252"), runs.map { it.number })
        assertEquals(listOf("무궁화호", "KTX", "KTX-산천"), runs.map { it.grade })
        val ktx = runs[1]
        assertEquals(IntercityKind.TRAIN, ktx.kind)
        assertEquals(at(14, 6, 35), ktx.depAtMs)
        assertEquals(at(14, 7, 49), ktx.arrAtMs)
        assertEquals(21600, ktx.fare)
        assertEquals("대전", ktx.depName)
        assertEquals("서울", ktx.arrName)
        // 하나뿐이면 객체로, 없으면 빈 문자열로 온다
        val one = JSONObject("""{"response":{"header":{"resultCode":"00"},"body":{"items":{"item":
            {"trainno":"09038","traingradename":"KTX-산천","depplacename":"대전","arrplacename":"서울","depplandtime":"20261013144500","arrplandtime":"20261013154700","adultcharge":"21600"}},"totalCount":1}}}""")
        assertEquals(listOf("9038"), ScheduleParsers.trains(one).map { it.number })
        val none = JSONObject("""{"response":{"header":{"resultCode":"00"},"body":{"items":"","numOfRows":200,"pageNo":1,"totalCount":0}}}""")
        assertTrue(ScheduleParsers.trains(none).isEmpty())
    }

    @Test
    fun expressBusTimesAreTwelveDigitNumbersAndMidnightIsHour24() {
        val json = JSONObject("""{"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},"body":{"items":{"item":[
            {"routeId":"NAEK300010","depPlaceNm":"대전복합","arrPlaceNm":"서울경부","depPlandTime":202610130600,"arrPlandTime":202610130800,"charge":18100,"gradeNm":"우등"},
            {"routeId":"NAEK300010","depPlaceNm":"대전복합","arrPlaceNm":"서울경부","depPlandTime":202610132400,"arrPlandTime":202610140200,"charge":21700,"gradeNm":"심야우등"}
            ]},"numOfRows":200,"pageNo":1,"totalCount":2}}}""")
        val runs = ScheduleParsers.buses(json, IntercityKind.EXPRESS)
        assertEquals(2, runs.size)
        assertEquals(IntercityKind.EXPRESS, runs[0].kind)
        assertEquals("", runs[0].number)
        assertEquals("우등", runs[0].grade)
        assertEquals(at(13, 6, 0), runs[0].depAtMs)
        assertEquals(18100, runs[0].fare)
        // '24시 00분' = 다음 날 0시
        assertEquals(at(14, 0, 0), runs[1].depAtMs)
        assertEquals(at(14, 2, 0), runs[1].arrAtMs)
        assertEquals(at(14, 0, 0), ScheduleParsers.time("202610132400"))
    }

    @Test
    fun intercityBusTimesAreSortedAndFourteenDigits() {
        val json = JSONObject("""{"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},"body":{"items":{"item":[
            {"routeId":"NAIRT201603283068490","depPlaceNm":"대전복합","arrPlaceNm":"청주","depPlandTime":20261013220000,"arrPlandTime":20261013230600,"charge":6300,"gradeNm":"심야일반"},
            {"routeId":"NAIRT201603283068490","depPlaceNm":"대전복합","arrPlaceNm":"청주","depPlandTime":20261013071200,"arrPlandTime":20261013081800,"charge":5200,"gradeNm":"일반"},
            {"routeId":"NAIRT201603283068490","depPlaceNm":"대전복합","arrPlaceNm":"청주","depPlandTime":20261013083900,"arrPlandTime":20261013094500,"charge":5200,"gradeNm":"일반"}
            ]},"numOfRows":200,"pageNo":1,"totalCount":3}}}""")
        val runs = ScheduleParsers.buses(json, IntercityKind.INTERCITY)
        assertEquals(listOf(at(13, 7, 12), at(13, 8, 39), at(13, 22, 0)), runs.map { it.depAtMs })
        assertEquals(listOf("일반", "일반", "심야일반"), runs.map { it.grade })
        assertEquals(IntercityKind.INTERCITY, runs[0].kind)
        assertEquals("청주", runs[0].arrName)
    }

    @Test
    fun oddTimesAreRejected() {
        assertNull(ScheduleParsers.time("2026-10-14"))
        assertNull(ScheduleParsers.time(""))
        assertNull(ScheduleParsers.time("20261013256100"))   // 61분
        assertNull(ScheduleParsers.time("20261313060000"))   // 13월
        assertEquals(at(14, 2, 10), ScheduleParsers.time("20261014021000"))
        assertEquals(at(14, 2, 10), ScheduleParsers.time("2.0261014021E13"))   // 숫자가 지수 표기로 와도
        // 도착이 출발보다 이르거나 시각이 없는 편은 버린다
        val bad = JSONObject("""{"response":{"header":{"resultCode":"00"},"body":{"items":{"item":[
            {"depPlaceNm":"A","arrPlaceNm":"B","depPlandTime":202610131000,"arrPlandTime":202610130900,"charge":1000,"gradeNm":"일반"},
            {"depPlaceNm":"A","arrPlaceNm":"B","arrPlandTime":202610131100,"charge":1000,"gradeNm":"일반"}]}}}}""")
        assertTrue(ScheduleParsers.buses(bad, IntercityKind.INTERCITY).isEmpty())
    }

    @Test
    fun stationAndTerminalLists() {
        val stations = ScheduleParsers.trainStations(JSONObject("""{"response":{"header":{"resultCode":"00"},"body":{"items":{"item":[
            {"nodeid":"NAT011668","nodename":"대전"},{"nodeid":"NAT030057","nodename":"서대전"},{"nodeid":"NAT011524","nodename":"신탄진"}]}}}}"""))
        assertEquals(listOf("NAT011668", "NAT030057", "NAT011524"), stations.map { it.id })
        val express = ScheduleParsers.terminals(JSONObject("""{"response":{"header":{"resultCode":"00"},"body":{"items":{"item":[
            {"terminalId":"NAEK010","terminalNm":"서울경부"},{"terminalId":"NAEK300","terminalNm":"대전복합"}]}}}}"""))
        assertEquals(listOf(TagoNode("NAEK010", "서울경부"), TagoNode("NAEK300", "대전복합")), express)
        val intercity = ScheduleParsers.terminals(JSONObject("""{"response":{"header":{"resultCode":"00"},"body":{"items":{"item":[
            {"terminalId":"NAI3455101","cityName":"대전광역시","terminalNm":"대전복합"}]}}}}"""))
        assertEquals(listOf(TagoNode("NAI3455101", "대전복합", "대전광역시")), intercity)
    }

    @Test
    fun savedStationListsRoundTrip() {
        val nodes = listOf(TagoNode("NAI3455101", "대전복합", "대전광역시"), TagoNode("NAEK020", "센트럴시티(서울)"), TagoNode("NAT011668", "대전"))
        assertEquals(nodes, TagoCatalog.decode(TagoCatalog.encode(nodes)))
        assertEquals(listOf(TagoNode("A", "가")), TagoCatalog.decode("""[["A","가",""],["","빈 번호",""],"이상한 줄"]"""))
    }

    @Test
    fun regionsFromKakaoAddressesAndTagoCityNames() {
        assertEquals("대전", Regions.ofAddress("대전 동구 용전동 63"))
        assertEquals("세종", Regions.ofAddress("세종특별자치시 대평동 263"))
        assertEquals("충북", Regions.ofAddress("충북 청주시 흥덕구 가경동"))
        assertEquals("강원", Regions.ofAddress("강원특별자치도 강릉시 교동"))
        assertEquals("전북", Regions.ofAddress("전북특별자치도 전주시 덕진구"))
        assertEquals("서울", Regions.ofAddress("서울 서초구 반포동 19-3"))
        assertNull(Regions.ofAddress(""))
        assertEquals("충북", Regions.ofTagoCity("충청북도"))
        assertEquals("충남", Regions.ofTagoCity("충청남도"))
        assertEquals("서울", Regions.ofTagoCity("서울시"))
        assertEquals("서울", Regions.ofTagoCity("서울특별시"))
        assertEquals("부산", Regions.ofTagoCity("부산광영식"))   // 실제 목록의 오타
        assertEquals("경북", Regions.ofTagoCity("경상북도"))
        assertEquals("전북", Regions.ofTagoCity("전북특별자치도"))
        assertEquals("강원", Regions.ofTagoCity("강원도"))
        assertNull(Regions.ofTagoCity("반계.마점"))
        assertEquals(25, Regions.trainCityCode("대전"))
        assertEquals(11, Regions.trainCityCode("서울"))
        assertEquals(12, Regions.trainCityCode("세종"))
        assertEquals(31, Regions.trainCityCode("경기"))
        assertEquals(32, Regions.trainCityCode("강원"))
        assertEquals(38, Regions.trainCityCode("경남"))
        assertNull(Regions.trainCityCode("제주"))
    }

    @Test
    fun kakaoStationNamesMatchTagoStations() {
        val daejeon = listOf(TagoNode("NAT011668", "대전"), TagoNode("NAT030057", "서대전"), TagoNode("NAT011524", "신탄진"))
        assertEquals("NAT011668", TagoMatching.station("대전역", daejeon)?.id)
        assertEquals("NAT030057", TagoMatching.station("서대전역", daejeon)?.id)
        assertNull(TagoMatching.station("옥천역", daejeon))
        val ulsan = listOf(TagoNode("NAT750726", "태화강"), TagoNode("NATH13717", "울산"))
        assertEquals("NATH13717", TagoMatching.station("울산역(통도사)", ulsan)?.id)
        assertEquals("광명", TagoMatching.stationKey("광명역 KTX"))
        assertEquals("판교", TagoMatching.stationKey("판교역(경기)"))
        assertEquals("김천구미", TagoMatching.stationKey("김천구미역"))
    }

    // 실제 TAGO 고속버스 터미널 목록 일부 (같은 이름에 번호가 여럿인 것 포함)
    private val express = listOf(
        "NAEK010" to "서울경부", "NAEK020" to "센트럴시티(서울)", "NAEK021" to "센트럴시티(서울)", "NAEK030" to "동서울",
        "NAEK031" to "동서울", "NAEK050" to "서울남부", "NAEK300" to "대전복합", "NAEK301" to "대전복합", "NAEK302" to "대전시외",
        "NAEK305" to "대전청사(샘머리)", "NAEK360" to "유성복합", "NAEK348" to "세종시외", "NAEK352" to "세종터미널",
        "NAEK400" to "청주(고속)", "NAEK401" to "청주(센트럴)", "NAEK700" to "부산", "NAEK703" to "부산사상",
    ).map { TagoNode(it.first, it.second) }

    // 실제 TAGO 시외버스 터미널 목록 일부 (전남에도 '대전'이 있음)
    private val intercity = listOf(
        TagoNode("NAI0511601", "동서울", "서울특별시"), TagoNode("NAI0654501", "서울고속버스터미널(경부)", "서울특별시"),
        TagoNode("NAI0654502", "서울경부", "서울특별시"), TagoNode("NAI0671801", "서울남부", "서울특별시"),
        TagoNode("NAI2839701", "청주", "충청북도"), TagoNode("NAI2812001", "청주북부터미널", "충청북도"),
        TagoNode("NAI3455101", "대전복합", "대전광역시"), TagoNode("NAI3498701", "대전서남부", "대전광역시"),
        TagoNode("NAI3417501", "유성복합", "대전광역시"), TagoNode("NAI5732602", "대전", "전라남도"),
        TagoNode("NAI4696801", "부산심야(사상)", "부산광역시"), TagoNode("NAI4696901", "부산서부(사상)", "부산광역시"),
    )

    private fun expressId(kakao: String) = TagoMatching.terminals(kakao, null, express).firstOrNull()?.id
    private fun intercityId(kakao: String, region: String) = TagoMatching.terminals(kakao, region, intercity).firstOrNull()?.id

    @Test
    fun kakaoTerminalsMatchExpressBusTerminals() {
        assertEquals("NAEK300", expressId("대전복합터미널"))           // '대전시외'(NAEK302)가 아니라
        assertEquals("NAEK300", expressId("대전복합터미널 서관"))
        assertEquals("NAEK010", expressId("서울고속버스터미널(경부)"))
        assertEquals("NAEK020", expressId("센트럴시티터미널(호남)"))
        assertEquals("NAEK030", expressId("동서울종합터미널"))
        assertEquals("NAEK050", expressId("서울남부터미널"))
        assertEquals("NAEK400", expressId("청주고속버스터미널"))       // 청주(고속)
        assertEquals("NAEK400", expressId("청주시외버스터미널"))       // 옆 건물 — 무거운 키로
        assertEquals("NAEK703", expressId("부산서부버스터미널"))       // 고속버스 목록엔 '부산사상'
        assertEquals("NAEK700", expressId("부산종합버스터미널"))
        assertEquals("NAEK360", expressId("유성복합터미널"))
        assertNull(expressId("대전서남부터미널"))
        assertEquals(listOf("서울고속", "서울고속경부"), TagoMatching.terminalKeys("서울고속버스터미널(경부)", heavy = false))
        assertEquals(listOf("서울", "서울경부"), TagoMatching.terminalKeys("서울고속버스터미널(경부)", heavy = true))
    }

    @Test
    fun kakaoTerminalsMatchIntercityTerminalsInTheSameRegion() {
        assertEquals("NAI3455101", intercityId("대전복합터미널", "대전"))   // 전남 '대전'이 아니라
        assertEquals("NAI2839701", intercityId("청주시외버스터미널", "충북"))
        assertEquals("NAI4696901", intercityId("부산서부버스터미널", "부산"))
        assertEquals("NAI0511601", intercityId("동서울종합터미널", "서울"))
        assertEquals("NAI3498701", intercityId("대전서남부터미널", "대전"))
        assertEquals("NAI0654501", intercityId("서울고속버스터미널(경부)", "서울"))
        // 시도를 모르면 이름만으로
        assertEquals("NAI3417501", TagoMatching.terminals("유성복합터미널", null, intercity).firstOrNull()?.id)
        assertNull(intercityId("대전복합터미널", "부산"))
    }
}
