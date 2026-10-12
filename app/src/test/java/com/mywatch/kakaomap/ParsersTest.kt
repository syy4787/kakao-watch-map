package com.mywatch.kakaomap

import com.mywatch.kakaomap.model.KakaoParsers
import com.mywatch.kakaomap.model.RouteStatusException
import com.mywatch.kakaomap.model.TagoParsers
import com.mywatch.kakaomap.model.TransitStepType
import com.mywatch.kakaomap.model.TravelMode
import com.mywatch.kakaomap.update.UpdateInfo
import com.mywatch.kakaomap.update.UpdateManager
import com.mywatch.kakaomap.net.KakaoApi
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 카카오·TAGO 응답 파싱 — 공식 문서의 응답 구조를 그대로 본뜬 예시로 검증 */
class ParsersTest {

    private val walkJson = """
        {"route":{"properties":{"totalDistance":4025,"totalTime":3914,"landingUrl":"https://map.kakao.com/link/by/walk/x"},
         "legs":[{"properties":{"distance":4025,"time":3914},"steps":[
           {"properties":{"distance":93,"guidance":"판교역 1번 출구까지 역사 내 이동","time":84,"x":127.11119783,"y":37.39477573},
            "path":{"points":[[127.11119783,37.39477573],[127.11119779,37.39474869],[127.1114835,37.39474572]]}},
           {"properties":{"distance":200,"guidance":"우회전","time":150,"x":127.1114835,"y":37.39474572},
            "path":{"points":[[127.1114835,37.39474572],[127.1130,37.3950]]}}]}]},
         "status":"OK"}
    """.trimIndent()

    @Test
    fun walkRouteParses() {
        val r = KakaoParsers.simpleRoute(JSONObject(walkJson), TravelMode.WALK)
        assertEquals(4025, r.totalDistanceM)
        assertEquals(3914, r.totalTimeSec)
        assertEquals(2, r.steps.size)
        assertEquals("판교역 1번 출구까지 역사 내 이동", r.steps[0].guidance)
        assertEquals(3, r.steps[0].points.size)
        // [x, y] = [경도, 위도]
        assertEquals(37.39477573, r.steps[0].points[0].lat, 1e-9)
        assertEquals(127.11119783, r.steps[0].points[0].lng, 1e-9)
    }

    @Test
    fun routeStatusErrorsBecomeMessages() {
        try {
            KakaoParsers.simpleRoute(JSONObject("""{"status":"TOO_FAR_AWAY"}"""), TravelMode.BIKE)
            fail("예외가 나야 함")
        } catch (e: RouteStatusException) {
            assertEquals("TOO_FAR_AWAY", e.status)
            assertTrue(e.message!!.contains("자전거"))
        }
    }

    private val transitJson = """
        {"status":"OK","properties":{"total":2,"bus":1,"subway":0,"busAndSubway":1,"landingURL":"u"},
         "routes":[
          {"properties":{"type":"BUS_AND_SUBWAY","totalDistance":9000,"totalTime":2520,"transfers":1,"fare":{"value":1500,"min":1500,"max":1500}},
           "steps":[
            {"properties":{"guidance":"정류장까지 도보","type":"WALKING","distance":300,"time":240,"stops":[],"vehicles":[]},
             "path":{"points":[[127.1,37.4],[127.101,37.401]]}},
            {"properties":{"guidance":"마을 76 (판교역동편 > 성남시청전면)","type":"BUS","distance":5000,"time":1200,
              "stops":[{"name":"판교역동편"},{"name":"중간"},{"name":"성남시청전면"}],"vehicles":[{"type":"마을","name":"76"}]},
             "path":{"points":[[127.101,37.401],[127.12,37.42]]}},
            {"properties":{"guidance":"신분당선 (정자 > 판교)","type":"SUBWAY","distance":3500,"time":900,
              "stops":[{"name":"정자"},{"name":"판교"}],"vehicles":[{"type":"일반","name":"신분당선"}]},
             "path":{"points":[[127.12,37.42],[127.13,37.43]]}}]},
          {"properties":{"type":"BUS","totalDistance":8000,"totalTime":1800,"transfers":0},
           "steps":[{"properties":{"guidance":"g","type":"BUS","distance":8000,"time":1800,"stops":[],"vehicles":[{"type":"간선","name":"102"}]},"path":{"points":[[1,2],[3,4]]}}]}
         ]}
    """.trimIndent()

    @Test
    fun transitRoutesParseAndSortByTime() {
        val routes = KakaoParsers.transitRoutes(JSONObject(transitJson))
        assertEquals(2, routes.size)
        // 더 빠른 경로(30분)가 먼저
        assertEquals(1800, routes[0].totalTimeSec)
        assertNull(routes[0].fare)
        val r = routes[1]
        assertEquals(1500, r.fare)
        assertEquals(1, r.transfers)
        assertEquals(240, r.walkTimeSec)
        val bus = r.steps[1]
        assertEquals(TransitStepType.BUS, bus.type)
        assertEquals("판교역동편", bus.boardingStop)
        assertEquals("성남시청전면", bus.alightingStop)
        assertEquals(2, bus.stopCount)
        assertEquals("76", bus.vehicles[0].name)
        assertEquals(TransitStepType.SUBWAY, r.steps[2].type)
        assertEquals(r.steps[1], r.firstRide)
    }

    @Test
    fun placesParse() {
        val json = """{"documents":[{"id":"1","place_name":"대전역","category_name":"교통,수송 > 기차,철도 > 기차역",
            "address_name":"대전 동구 정동 1-1","road_address_name":"대전 동구 중앙로 215","x":"127.434","y":"36.332","distance":"1234"}],
            "meta":{"total_count":1}}"""
        val p = KakaoParsers.places(JSONObject(json)).single()
        assertEquals("대전역", p.name)
        assertEquals("기차역", p.category)
        assertEquals(1234, p.distanceM)
        assertEquals("대전 동구 중앙로 215", p.displayAddress)
    }

    @Test
    fun tagoItemsHandleArrayObjectAndEmpty() {
        val arr = JSONObject("""{"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},
            "body":{"items":{"item":[{"routeno":102,"routetp":"간선버스","arrtime":245,"arrprevstationcnt":3},
                                      {"routeno":"급행1","arrtime":60,"arrprevstationcnt":1}]}}}}""")
        val a = TagoParsers.arrivals(arr)
        assertEquals(2, a.size)
        assertEquals("급행1", a[0].routeNo)        // 더 빨리 오는 버스가 먼저
        assertEquals("102", a[1].routeNo)          // 숫자로 와도 문자열로
        assertNull(TagoParsers.errorMessage(arr))

        val single = JSONObject("""{"response":{"header":{"resultCode":"00"},
            "body":{"items":{"item":{"citycode":25,"gpslati":36.35,"gpslong":127.38,"nodeid":"DJB8001793","nodenm":"둔산동"}}}}}""")
        val s = TagoParsers.stops(single).single()
        assertEquals(25, s.cityCode)
        assertEquals("DJB8001793", s.nodeId)

        val empty = JSONObject("""{"response":{"header":{"resultCode":"00"},"body":{"items":"","totalCount":0}}}""")
        assertTrue(TagoParsers.arrivals(empty).isEmpty())
    }

    @Test
    fun tagoErrorsAreReadable() {
        val err = JSONObject("""{"OpenAPI_ServiceResponse":{"cmmMsgHeader":{"errMsg":"SERVICE_KEY_IS_NOT_REGISTERED_ERROR",
            "returnAuthMsg":"등록되지 않은 서비스키","returnReasonCode":"30"}}}""")
        assertEquals("공공데이터 키가 이 기능에 아직 등록되지 않았어요", TagoParsers.errorMessage(err))
        // 모르는 오류는 포털이 준 한국어 문구 그대로
        val other = JSONObject("""{"OpenAPI_ServiceResponse":{"cmmMsgHeader":{"errMsg":"SERVICE_ACCESS_DENIED_ERROR",
            "returnAuthMsg":"서비스 접근거부","returnReasonCode":"20"}}}""")
        assertEquals("서비스 접근거부", TagoParsers.errorMessage(other))
    }

    @Test
    fun updateInfoParses() {
        val info = UpdateInfo.parse("""{"versionCode":105,"versionName":"2.5","apkUrl":"https://x/a.apk","sha256":"ABCDEF","notes":"버그 수정"}""")
        assertEquals(105L, info.versionCode)
        assertEquals("abcdef", info.sha256)   // 소문자로 맞춤
        assertEquals(info, UpdateInfo.parse(info.toJson()))
    }

    @Test
    fun tagoRouteListAndStopsInRouteOrder() {
        val routes = TagoParsers.routes(JSONObject("""{"response":{"header":{"resultCode":"00","resultMsg":"OK"},
            "body":{"items":{"item":[{"routeid":"DJB30300004","routeno":707,"routetp":"간선버스"},
                                     {"routeid":"DJB30300005","routeno":"707-1"}]}}}}"""))
        assertEquals(listOf("DJB30300004", "DJB30300005"), routes.map { it.routeId })
        assertEquals("707", routes[0].routeNo)
        // 응답 순서가 섞여 와도 nodeord(노선 순서)로 정렬
        val stops = TagoParsers.routeStops(JSONObject("""{"response":{"header":{"resultCode":"00","resultMsg":"OK"},
            "body":{"items":{"item":[
              {"nodeid":"DJB2","nodenm":"시청역","nodeord":2,"gpslati":36.351,"gpslong":127.386},
              {"nodeid":"DJB1","nodenm":"대전광역시청","nodeord":1,"gpslati":36.350,"gpslong":127.384},
              {"nodeid":"DJB3","nodenm":"목련아파트","nodeord":3,"gpslati":36.352}]}}}}"""))
        assertEquals(listOf("대전광역시청", "시청역"), stops.map { it.name })
    }

    @Test
    fun kakaoPlacesKeepCategoryGroupAndQuotaIsExplained() {
        val places = KakaoParsers.places(JSONObject("""{"documents":[
            {"id":"1","place_name":"탄방역 대전1호선","category_name":"교통,수송 > 지하철,전철 > 대전1호선",
             "category_group_code":"SW8","address_name":"","road_address_name":"","x":"127.38456","y":"36.34554"}]}"""))
        assertEquals("SW8", places[0].group)
        assertEquals("오늘 사용량을 다 썼어요", KakaoApi.errorMessage(400, """{"code":-10,"msg":"API limit has been exceeded."}"""))
        assertEquals("오늘 사용량을 다 썼어요", KakaoApi.errorMessage(429, ""))
    }

    @Test
    fun updatePicksTheApkBuiltForThisWatchsCpu() {
        val json = """{"versionCode":106,"versionName":"2.6","apkUrl":"https://x/all.apk","sha256":"AAA","notes":"n",
            "apks":{"armeabi-v7a":{"url":"https://x/v7.apk","sha256":"BBB"},"arm64-v8a":{"url":"https://x/v8.apk","sha256":"CCC"}}}"""
        // 갤럭시 워치9: 32비트 → 절반 크기 APK
        val watch = UpdateInfo.parse(json, abis = listOf("armeabi-v7a", "armeabi"))
        assertEquals("https://x/v7.apk", watch.apkUrl)
        assertEquals("bbb", watch.sha256)
        assertEquals("https://x/v8.apk", UpdateInfo.parse(json, abis = listOf("arm64-v8a", "armeabi-v7a")).apkUrl)
        // 모르는 CPU·예전 형식 → 모두 들어 있는 APK
        assertEquals("https://x/all.apk", UpdateInfo.parse(json, abis = listOf("x86_64")).apkUrl)
        val old = """{"versionCode":105,"versionName":"2.5","apkUrl":"https://x/all.apk","sha256":"AAA","notes":""}"""
        assertEquals("aaa", UpdateInfo.parse(old, abis = listOf("armeabi-v7a")).sha256)
        // 저장했다 다시 읽어도 고른 것이 유지됨
        assertEquals("https://x/v7.apk", UpdateInfo.parse(watch.toJson(), abis = listOf("armeabi-v7a")).apkUrl)
    }

    @Test
    fun aDifferentApkOfTheSameVersionNeverResumesTheOtherPartialFile() {
        // 받다 만 파일은 이어받는데, 같은 버전이라도 다른 APK(모든 CPU 용 ↔ 워치 전용)면 다른 파일이어야 섞이지 않는다
        val universal = UpdateInfo(107, "2.7", "https://x/all.apk", "aaaa1111bbbb2222cccc", "")
        val v7a = universal.copy(apkUrl = "https://x/v7.apk", sha256 = "dddd3333eeee4444ffff")
        val a = UpdateManager.cacheFileName(universal)
        val b = UpdateManager.cacheFileName(v7a)
        assertTrue(a != b)
        assertTrue(b.startsWith("app-107-") && b.endsWith(".apk"))
        assertEquals(b, UpdateManager.cacheFileName(v7a.copy(notes = "다른 설명")))
    }
}
