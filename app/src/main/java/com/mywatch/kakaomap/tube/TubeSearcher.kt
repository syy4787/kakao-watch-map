package com.mywatch.kakaomap.tube

import com.mywatch.kakaomap.net.ApiException

/** 어디서 찾았는지 */
enum class TubeSource(val label: String) { YOUTUBE("유튜브 검색"), KAKAO("카카오 동영상 검색") }

/** 검색 결과 + 어디서 찾았는지 + 바꿔서 찾았으면 그 이유 */
class TubeResult(val page: TubePage, val source: TubeSource, val note: String? = null)

/** 오늘(미국 서부 기준) 유튜브 검색 한도를 다 씀 */
class YouTubeQuotaException : ApiException("오늘 유튜브 검색 한도를 다 썼어요")

/** 인터넷이 안 됨 — 카카오로 바꿔도 안 되므로 그대로 알린다 */
class NetworkException : ApiException("네트워크 연결을 확인하세요")

/**
 * 어디서 어떻게 찾을지 (네트워크는 밖에서 넣어 준다 — 단위 테스트로 검증).
 *  · 유튜브 API 키가 있으면 유튜브 자체 검색(유튜브의 모든 영상), 없거나 오늘 한도를 다 썼으면 카카오 동영상 검색
 *  · 유튜브가 잠깐 안 되면 그 검색만 카카오로(이유를 note 로), 한도를 다 쓰면 그날은 유튜브에 묻지 않는다
 *  · '더 보기'는 처음 찾은 곳에서 이어서 (유튜브 목록 뒤에 카카오 결과를 섞지 않는다)
 */
class TubeSearcher(
    private val youtubeKey: String,
    private val youtubeBlocked: () -> Boolean,
    private val blockYoutube: () -> Unit,
    private val youtube: (query: String, pageToken: String?) -> TubePage,
    private val kakao: (query: String, page: Int) -> TubeParsers.KakaoPage,
    private val log: (String, Throwable) -> Unit = { _, _ -> },
) {

    /** [next]·[source] = '더 보기'일 때 앞 결과의 다음 쪽과 출처 */
    fun search(query: String, next: String? = null, source: TubeSource? = null): TubeResult {
        val useYoutube = when (source) {
            TubeSource.YOUTUBE -> true
            TubeSource.KAKAO -> false
            null -> youtubeKey.isNotBlank() && !youtubeBlocked()
        }
        var note: String? = null
        if (useYoutube) {
            // 오늘 한도를 다 쓴 걸 알면 '더 보기'는 보내지도 않는다
            if (next != null && youtubeBlocked()) throw YouTubeQuotaException()
            try {
                return TubeResult(youtube(query, next), TubeSource.YOUTUBE)
            } catch (e: YouTubeQuotaException) {
                blockYoutube()
                if (next != null) throw e
                note = "오늘 유튜브 검색 한도를 다 써서 카카오 동영상 검색으로 찾았어요"
            } catch (e: NetworkException) {
                throw e
            } catch (e: ApiException) {
                if (next != null) throw e
                log("유튜브 검색", e)
                note = "${e.message} · 카카오 동영상 검색으로 찾았어요"
            }
        }
        return TubeResult(kakaoPages(query, next?.toIntOrNull() ?: 1), TubeSource.KAKAO, note)
    }

    /**
     * 카카오 결과에는 유튜브 아닌 영상도 섞여 있어, 유튜브 영상이 10개 이상 모이거나 끝날 때까지 최대 3쪽.
     * 둘째 쪽부터 실패하면 모은 것까지 보여 주고 '더 보기'는 실패한 쪽부터 다시
     */
    private fun kakaoPages(query: String, startPage: Int): TubePage {
        val videos = LinkedHashMap<String, TubeVideo>()
        var page = startPage
        var end = false
        var fetched = 0
        while (!end && fetched < KAKAO_PAGES && videos.size < KAKAO_ENOUGH) {
            val r = try {
                kakao(query, page)
            } catch (e: Exception) {
                if (fetched == 0) throw e
                log("유튜브 검색·카카오 다음 쪽", e)
                break
            }
            r.videos.forEach { videos.putIfAbsent(it.id, it) }
            end = r.isEnd || page >= KAKAO_LAST_PAGE
            page++
            fetched++
        }
        return TubePage(videos.values.toList(), if (end) null else page.toString())
    }

    companion object {
        const val KAKAO_LAST_PAGE = 15
        private const val KAKAO_PAGES = 3
        private const val KAKAO_ENOUGH = 10
    }
}
