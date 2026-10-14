package com.mywatch.kakaomap.net

import com.mywatch.kakaomap.model.RouteStatusException
import com.mywatch.kakaomap.model.TransitRoute

/**
 * 시내 대중교통(카카오)과 기차·고속·시외버스 시간표를 길찾기 화면의 한 답으로 합친다.
 *  · 둘 다 경로가 없으면 [Answer.error]: 받지 못한 것(끊김·시간표 실패)은 그렇게 말하고 다시 시도하게, '길이 없다'는 답은 답대로
 *  · 경로가 있으면 받지 못한 쪽을 [Answer.notes] 로 알린다 ([Answer.incomplete] 면 화면에 '다시 찾기')
 * 예전엔 시내 길이 없고 시간표를 받지 못하면 '대중교통 경로가 없어요'로 보였고, 시내 길을 받지 못한 것은 열차가 있으면 묻혔다.
 */
object TransitAnswers {

    sealed interface Error {
        /** 카카오 대중교통을 받지 못함 (끊김·사용량 등) — 그 문구 */
        data class City(val message: String) : Error
        /** 시내 길은 없고 기차·버스 시간표를 (다 / 일부) 받지 못함 */
        data class Intercity(val partial: Boolean) : Error
        /** 대중교통 길이 없다는 답 (카카오 문구, 없으면 기본 문구) */
        data class None(val message: String?) : Error
    }

    enum class Note {
        /** 시내 대중교통을 받지 못함 (기차·버스만 보임) */
        CITY_FAILED,
        INTERCITY_FAILED,
        INTERCITY_PARTIAL,
        /** 다음 주 같은 요일 시간표로 KTX 를 채움 */
        INTERCITY_WEEKLY,
    }

    class Answer(val routes: List<TransitRoute>, val notes: List<Note>, val error: Error?) {
        /** 받지 못한 것이 있어 다시 찾아 볼 만함 */
        val incomplete: Boolean get() = notes.any { it != Note.INTERCITY_WEEKLY }
    }

    /** [intercity] = 기차·버스 시간표 찾기 결과 (가까워서 찾지 않았으면 null) */
    fun combine(city: Result<List<TransitRoute>>, intercity: Result<IntercityPlanner.Result>?): Answer {
        val cityError = city.exceptionOrNull()
        val cityFailed = cityError != null && cityError !is RouteStatusException
        val found = intercity?.getOrNull()
        val intercityFailed = intercity?.isFailure == true
        val intercityPartial = found != null && found.problems.isNotEmpty()
        val routes = city.getOrNull().orEmpty() + found?.routes.orEmpty()
        if (routes.isEmpty()) {
            val error = when {
                cityFailed -> Error.City(cityError?.message ?: "경로를 찾지 못했어요")
                intercityFailed -> Error.Intercity(partial = false)
                intercityPartial -> Error.Intercity(partial = true)
                else -> Error.None(cityError?.message)
            }
            return Answer(emptyList(), emptyList(), error)
        }
        val notes = buildList {
            if (cityFailed) add(Note.CITY_FAILED)
            if (intercityFailed) add(Note.INTERCITY_FAILED)
            if (intercityPartial) add(Note.INTERCITY_PARTIAL)
            if (found?.weekly == true) add(Note.INTERCITY_WEEKLY)
        }
        return Answer(routes, notes, null)
    }
}
