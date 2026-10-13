package com.mywatch.kakaomap.tube

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.concurrent.futures.await
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.mywatch.kakaomap.BuildConfig
import com.mywatch.kakaomap.data.ErrorLog
import com.mywatch.kakaomap.net.ApiException
import com.mywatch.kakaomap.net.Http
import com.mywatch.kakaomap.net.KakaoApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 검색어·들은 곡 기록 (워치 안에만 저장) */
class TubeState(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("tube", Context.MODE_PRIVATE)

    val searches: List<String> get() = SearchHistory.decode(prefs.getString(K_SEARCHES, null))

    fun addSearch(query: String) = saveSearches(SearchHistory.add(searches, query))

    fun removeSearch(query: String) = saveSearches(SearchHistory.remove(searches, query))

    private fun saveSearches(list: List<String>) = prefs.edit { putString(K_SEARCHES, SearchHistory.encode(list)) }

    /**
     * 최근 들은 곡. 30일이 지난 곡은 보여 주지 않을 뿐 아니라 저장소에서도 지운다
     * (유튜브 API 로 받은 정보는 30일까지만 보관)
     */
    fun played(nowMs: Long = System.currentTimeMillis()): List<Played> = synchronized(LOCK) {
        val all = PlayedHistory.decodeAll(prefs.getString(K_PLAYED, null))
        val kept = PlayedHistory.prune(all, nowMs).take(PlayedHistory.MAX)
        if (kept.size != all.size) prefs.edit { putString(K_PLAYED, PlayedHistory.encode(kept)) }
        kept
    }

    fun lastPlayed(nowMs: Long = System.currentTimeMillis()): TubeVideo? = played(nowMs).firstOrNull()?.video

    fun addPlayed(video: TubeVideo, nowMs: Long = System.currentTimeMillis()) = synchronized(LOCK) {
        prefs.edit { putString(K_PLAYED, PlayedHistory.encode(PlayedHistory.add(played(nowMs), video, nowMs))) }
    }

    fun removePlayed(id: String, nowMs: Long = System.currentTimeMillis()) = synchronized(LOCK) {
        prefs.edit { putString(K_PLAYED, PlayedHistory.encode(played(nowMs).filterNot { it.video.id == id })) }
    }

    fun clear() = synchronized(LOCK) {
        prefs.edit {
            remove(K_SEARCHES)
            remove(K_PLAYED)
        }
    }

    /** 오늘(유튜브 기준 날짜) 유튜브 검색 한도를 다 썼으면 카카오 검색만 쓴다 — 실패할 요청을 계속 보내지 않게 */
    fun youtubeBlocked(day: String = quotaDay(Instant.now()).toString()): Boolean = prefs.getString(K_YT_BLOCKED, null) == day

    fun blockYoutube(day: String = quotaDay(Instant.now()).toString()) = prefs.edit { putString(K_YT_BLOCKED, day) }

    companion object {
        private const val K_SEARCHES = "searches"
        private const val K_PLAYED = "played"
        private const val K_YT_BLOCKED = "youtube_blocked_day"
        private val LOCK = Any()

        /** 유튜브 API 하루 한도는 미국 태평양 시간 자정에 다시 채워진다 */
        fun quotaDay(now: Instant): LocalDate = now.atZone(ZoneId.of("America/Los_Angeles")).toLocalDate()
    }
}

/** 폰에서 유튜브 링크 열기 — 폰의 유튜브 앱(로그인된 내 계정)이 받아서 재생한다 */
class PhoneOpener(context: Context) {

    enum class Result { OPENED, NO_PHONE, FAILED }

    private val app = context.applicationContext

    /** 한 번 만들어 계속 쓴다 (만들 때마다 작업 스레드가 하나씩 생김). 만들다 실패하면 null */
    private val helper: RemoteActivityHelper? by lazy {
        try {
            RemoteActivityHelper(app)
        } catch (e: Exception) {
            ErrorLog.record("유튜브·폰에서 열기", e)
            null
        }
    }

    /**
     * 누르자마자 보낸다 — Wear OS 6+ 는 앱이 앞에 보일 때 보낸 요청만 받으므로 미리 확인하느라 기다리지 않는다.
     * 무슨 일이 있어도 예외를 밖으로 내지 않는다(같은 프로세스의 길안내까지 꺼지지 않게). 취소만 그대로 전한다
     */
    suspend fun open(url: String): Result {
        val h = helper ?: return Result.FAILED
        try {
            withTimeout(15_000) { h.startRemoteActivity(intent(url)).await() }
            return Result.OPENED
        } catch (e: TimeoutCancellationException) {
            ErrorLog.record("유튜브·폰에서 열기", "폰이 15초 안에 응답하지 않음")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorLog.record("유튜브·폰에서 열기", e)
        }
        // 실패했을 때만: 폰과 연결이 끊긴 건지 확인해서 알맞게 알린다 (예전 워치는 '모름'만 알려 줌)
        val status = try {
            withTimeoutOrNull(1_500) { h.availabilityStatus.first() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        return if (status == RemoteActivityHelper.STATUS_UNAVAILABLE || status == RemoteActivityHelper.STATUS_TEMPORARILY_UNAVAILABLE) {
            Result.NO_PHONE
        } else {
            Result.FAILED
        }
    }

    companion object {
        /** 원격 실행은 '열 수 있는 링크'(VIEW + BROWSABLE + 주소)만 받는다 */
        fun intent(url: String): Intent = Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
    }
}

/**
 * 노래 검색 (네트워크). 순서·대체 판단은 [TubeSearcher]. 백그라운드 스레드에서 부른다.
 */
object TubeApi {
    private const val YT = "https://www.googleapis.com/youtube/v3"

    /** [next]·[source] = '더 보기'일 때 앞 결과의 다음 쪽과 출처 (같은 곳에서 이어서) */
    fun search(context: Context, query: String, next: String? = null, source: TubeSource? = null): TubeResult {
        val state = TubeState(context)
        val key = BuildConfig.YOUTUBE_API_KEY
        return TubeSearcher(
            youtubeKey = key,
            youtubeBlocked = { state.youtubeBlocked() },
            blockYoutube = { state.blockYoutube() },
            youtube = { q, token -> youtube(context, key, q, token) },
            kakao = { q, page -> KakaoApi.videos(q, page) },
            log = { where, e -> ErrorLog.record(where, e) },
        ).search(query, next, source)
    }

    private fun youtube(context: Context, key: String, query: String, pageToken: String?): TubePage {
        val params = linkedMapOf(
            "part" to "snippet", "type" to "video", "maxResults" to "20", "q" to query,
            "regionCode" to "KR", "relevanceLanguage" to "ko",
            // 워치는 블루투스로 폰 인터넷을 빌려 쓰므로 필요한 칸만 받는다
            "fields" to "nextPageToken,items(id/videoId,snippet(title,channelTitle,liveBroadcastContent))",
            "key" to key,
        )
        if (pageToken != null) params["pageToken"] = pageToken
        val page = TubeParsers.youtubeSearch(call(context, "/search", params))
        if (page.videos.isEmpty()) return page
        // 길이(4:54)는 영상 정보에만 있다 (1단위로 싸다). 못 받아도 결과는 보여 준다
        val durations = try {
            TubeParsers.youtubeDurations(call(context, "/videos", linkedMapOf(
                "part" to "contentDetails", "id" to page.videos.joinToString(",") { it.id },
                "fields" to "items(id,contentDetails/duration)", "key" to key,
            )))
        } catch (e: ApiException) {
            emptyMap()
        }
        return page.copy(videos = TubeParsers.withDurations(page.videos, durations))
    }

    private fun call(context: Context, path: String, params: Map<String, String>): JSONObject {
        val res = try {
            Http.get("$YT$path?${Http.query(params)}", appHeaders(context))
        } catch (e: IOException) {
            throw NetworkException()
        }
        if (res.code !in 200..299) {
            throw when (val reason = TubeParsers.youtubeErrorReason(res.body)) {
                // 하루 한도 — 그날은 더 묻지 않는다
                "quotaExceeded", "dailyLimitExceeded" -> YouTubeQuotaException()
                // 잠깐 몰림 — 이번 검색만 카카오로
                "rateLimitExceeded", "userRateLimitExceeded" -> ApiException("유튜브 검색 요청이 잠깐 몰렸어요")
                else -> ApiException(errorMessage(res.code, reason, res.body))
            }
        }
        return try {
            JSONObject(res.body)
        } catch (e: JSONException) {
            throw ApiException("유튜브 응답을 읽지 못했어요")
        }
    }

    fun errorMessage(code: Int, reason: String?, body: String): String = when {
        body.contains("API_KEY_INVALID") || reason == "keyInvalid" -> "유튜브 API 키가 올바르지 않아요"
        reason == "accessNotConfigured" || body.contains("SERVICE_DISABLED") -> "YouTube Data API 사용 설정이 꺼져 있어요"
        body.contains("API_KEY_ANDROID_APP_BLOCKED") || body.contains("API_KEY_HTTP_REFERRER_BLOCKED") -> "유튜브 API 키의 앱 제한 설정을 확인하세요"
        code >= 500 -> "유튜브 서버 오류 ($code)"
        else -> "유튜브 검색 오류 ($code)"
    }

    /**
     * 키를 'Android 앱'으로 제한해 둔 경우 구글이 확인하는 값 (패키지 이름 + 서명 인증서 SHA-1, 구글 예제처럼 소문자 16진수).
     * 키를 'YouTube Data API v3 만'으로 제한하는 게 핵심이고, 이건 덤 (패키지·SHA-1 은 공개라 흉내 낼 수는 있음)
     */
    private fun appHeaders(context: Context): Map<String, String> {
        val sha1 = signingSha1(context) ?: return emptyMap()
        return mapOf("X-Android-Package" to context.packageName, "X-Android-Cert" to sha1)
    }

    @Volatile private var cachedSha1: String? = null

    private fun signingSha1(context: Context): String? = cachedSha1 ?: runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signer = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return null
        MessageDigest.getInstance("SHA-1").digest(signer.toByteArray()).joinToString("") { "%02x".format(it) }
    }.getOrNull()?.also { cachedSha1 = it }
}
