package com.mywatch.kakaomap

import com.mywatch.kakaomap.model.RouteStatusException
import com.mywatch.kakaomap.model.TransitRoute
import com.mywatch.kakaomap.model.TransitStep
import com.mywatch.kakaomap.model.TransitStepType
import com.mywatch.kakaomap.model.Vehicle
import com.mywatch.kakaomap.net.ApiException
import com.mywatch.kakaomap.net.IntercityPlanner
import com.mywatch.kakaomap.net.TransitAnswers
import com.mywatch.kakaomap.net.TransitAnswers.Error
import com.mywatch.kakaomap.net.TransitAnswers.Note
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 시내 대중교통(카카오)과 기차·버스 시간표를 한 답으로 (점검 A4): 둘 다 경로가 없을 때 시간표를 받지 못한 것을
 * '대중교통 경로가 없어요'로 보여 다시 시도할 생각을 못 하게 했다. 시내 길을 받지 못한 것도 시간표 경로가 있으면 말없이 묻혔다.
 */
class TransitAnswersTest {

    private fun bus(no: String) = TransitRoute("BUS", 1000, 1200, 0, 1500, listOf(TransitStep(TransitStepType.BUS, "", 1000, 1200, emptyList(), listOf(Vehicle("간선", no)), emptyList())))
    private val ktx = TransitRoute("TRAIN", 160_000, 5400, 0, 23700, listOf(TransitStep(TransitStepType.TRAIN, "", 160_000, 3600, emptyList(), listOf(Vehicle("KTX", "105")), emptyList())), arriveAtMs = 1L)

    private val noRoute = RouteStatusException("NO_ROUTE", "대중교통 길이 없어요")
    private val offline = ApiException("네트워크 연결을 확인하세요")

    @Test
    fun noCityRouteAndATimetableFailureSaysTheTimetableFailedSoRetryMakesSense() {
        val a = TransitAnswers.combine(Result.success(emptyList()), Result.failure(ApiException("시간 초과")))
        assertTrue(a.routes.isEmpty())
        assertEquals(Error.Intercity(partial = false), a.error)
        val b = TransitAnswers.combine(Result.failure(noRoute), Result.success(IntercityPlanner.Result(emptyList(), listOf("열차: 받지 못했어요"))))
        assertEquals(Error.Intercity(partial = true), b.error)
    }

    @Test
    fun aCityFailureWithNothingElseShowsItsOwnMessage() {
        val a = TransitAnswers.combine(Result.failure(offline), Result.failure(ApiException("시간 초과")))
        assertEquals(Error.City("네트워크 연결을 확인하세요"), a.error)
        val b = TransitAnswers.combine(Result.failure(offline), null)
        assertEquals(Error.City("네트워크 연결을 확인하세요"), b.error)
    }

    @Test
    fun noRouteIsAnAnswer() {
        assertEquals(Error.None("대중교통 길이 없어요"), TransitAnswers.combine(Result.failure(noRoute), null).error)
        assertEquals(Error.None(null), TransitAnswers.combine(Result.success(emptyList()), Result.success(IntercityPlanner.Result(emptyList(), emptyList()))).error)
    }

    @Test
    fun trainsStillShowWhenTheCityPartFailedAndSaySo() {
        val a = TransitAnswers.combine(Result.failure(offline), Result.success(IntercityPlanner.Result(listOf(ktx), emptyList())))
        assertNull(a.error)
        assertEquals(listOf(ktx), a.routes)
        assertEquals(listOf(Note.CITY_FAILED), a.notes)
        assertTrue(a.incomplete)
    }

    @Test
    fun notesUnderTheCityRoutes() {
        val city = Result.success(listOf(bus("102")))
        assertEquals(listOf(Note.INTERCITY_FAILED), TransitAnswers.combine(city, Result.failure(ApiException("시간 초과"))).notes)
        val partial = TransitAnswers.combine(city, Result.success(IntercityPlanner.Result(listOf(ktx), listOf("고속버스: 받지 못했어요"))))
        assertEquals(listOf(bus("102"), ktx), partial.routes)
        assertEquals(listOf(Note.INTERCITY_PARTIAL), partial.notes)
        assertTrue(partial.incomplete)
        val near = TransitAnswers.combine(city, null)
        assertTrue(near.notes.isEmpty())
        assertTrue(!near.incomplete)
    }
}
