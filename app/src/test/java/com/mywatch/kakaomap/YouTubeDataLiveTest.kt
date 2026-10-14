package com.mywatch.kakaomap

import com.mywatch.kakaomap.net.Http
import com.mywatch.kakaomap.tube.TubeParsers
import com.mywatch.kakaomap.tube.YouTubeData
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.URI

/**
 * 실제 유튜브 Data API 가 앱이 묻는 그대로 대답하는지 (받는 칸·글 모양·채널·재생목록).
 * 키가 있을 때만 돈다 — 보통 단위 테스트·배포 빌드에선 건너뛴다. GitHub Actions 'YouTube API check' 가
 * YOUTUBE_LIVE_TEST_KEY 로 돌리고 요약(build/youtube-live.md)을 실행 요약에 붙인다. 키·주소는 어디에도 쓰지 않는다.
 * 쓰는 한도: 검색 1번 + 약 8단위 (하루 1만 중)
 */
class YouTubeDataLiveTest {

    private val key = System.getenv("YOUTUBE_LIVE_TEST_KEY")?.trim().orEmpty()
    private val report = StringBuilder("## 유튜브 Data API 실제 확인\n\n| 확인 | 결과 |\n|---|---|\n")
    /** 마지막으로 받은 원래 응답 (경로별) — 글 모양을 보려고 */
    private val raw = mutableMapOf<String, String>()

    @Before
    fun needsKey() = Assume.assumeTrue("유튜브 키가 없으면 건너뜀", key.isNotEmpty())

    private fun line(what: String, result: String) {
        report.append("| ").append(what).append(" | ").append(result.replace("|", "/")).append(" |\n")
    }

    @Test
    fun theRealApiAnswersWhatTheAppAsksFor() {
        val yt = YouTubeData(key, { null }, { url, headers ->
            Http.get(url, headers).also { raw[URI(url).rawPath.substringAfterLast('/')] = it.body }
        })
        try {
            // 1. 인기 음악 (한국)
            val music = yt.popular("10", null)
            line(
                "인기 음악",
                "${music.videos.size}개 · 조회수 ${music.videos.count { it.views != null }} · 길이 ${music.videos.count { it.durationSec != null }} · " +
                    "올린 때 ${music.videos.count { it.publishedAt != null }} · 채널 번호 ${music.videos.count { it.channelId != null }} · " +
                    "워치에서 못 틂 ${music.videos.count { !it.embeddable }} · 다음 쪽 ${music.next != null} · 예: ${music.videos.firstOrNull()?.title}",
            )
            assertTrue("인기 음악이 와야 한다", music.videos.size >= 5)
            assertTrue(music.videos.all { it.title.isNotBlank() && it.publishedAt != null && it.channelId != null })
            assertTrue("조회수가 대부분 있어야 한다", music.videos.count { it.views != null } * 2 >= music.videos.size)

            // 2. 검색 — 검색 결과 글을 &amp; 로 바꿔 보내는지, 영상 정보는 그대로인지 원래 응답으로 확인
            val page = yt.search("Tom & Jerry", null)
            val searchRaw = raw["search"].orEmpty()
            val videosRaw = raw["videos"].orEmpty()
            fun escaped(body: String) = listOf("&amp;", "&#39;", "&quot;", "&lt;").filter { body.contains(it) }
            line("검색 결과 글 모양", "바꿔 보낸 표시: ${escaped(searchRaw).ifEmpty { listOf("없음") }}")
            line("영상 정보 글 모양", "바꿔 보낸 표시: ${escaped(videosRaw).ifEmpty { listOf("없음") }}")
            val fromSearch = TubeParsers.youtubeSearchAll(JSONObject(searchRaw)).videos.associate { it.id to it.title }
            val same = page.videos.count { fromSearch[it.id] == it.title }
            val differ = page.videos.filter { fromSearch[it.id] != null && fromSearch[it.id] != it.title }
            line(
                "검색",
                "동영상 ${page.videos.size} (검색 ${fromSearch.size}) · 채널 ${page.channels.size} · 재생목록 ${page.playlists.size} · " +
                    "제목 같음 ${same}/${page.videos.size}" + differ.take(2).joinToString("") { " · 다름: '${fromSearch[it.id]}' ↔ '${it.title}'" },
            )
            assertTrue("검색 결과가 와야 한다", page.videos.size >= 3)
            assertTrue("영상 정보로 길이를 채운다", page.videos.count { it.durationSec != null || it.live } * 2 >= page.videos.size)
            assertTrue("검색 제목(되돌린 것)과 영상 정보 제목이 같아야 한다", same * 10 >= page.videos.size * 8)

            // 3. 채널 (1theK) · 업로드 · 재생목록
            val ch = yt.channel("UCweOkPb1wVVH0Q0Tlj4a5Pw")
            line("채널", "${ch?.title} · 구독자 ${ch?.subscribers} · 동영상 ${ch?.videoCount} · 업로드 ${ch?.uploads} · 사진 ${ch?.thumb?.let { URI(it).host }}")
            assertTrue(ch != null && ch.title.isNotBlank() && ch.thumb != null)
            assertEquals("UUweOkPb1wVVH0Q0Tlj4a5Pw", ch!!.uploads)

            val uploads = yt.videosOf(ch.uploads!!, null)
            val dates = uploads.videos.mapNotNull { it.publishedAt }
            line("채널 업로드", "${uploads.videos.size}개 · 새것 먼저 ${dates.zipWithNext().all { (a, b) -> a >= b }} · 다음 쪽 ${uploads.next != null}")
            assertTrue(uploads.videos.size >= 5)

            val lists = yt.channelPlaylists(ch.id, null)
            line("채널 재생목록", "${lists.playlists.size}개 · 개수 있음 ${lists.playlists.count { it.count != null }} · 예: ${lists.playlists.firstOrNull()?.title}")
            assertTrue(lists.playlists.isNotEmpty())
            val first = lists.playlists.first { (it.count ?: 0) > 0 }
            val inList = yt.videosOf(first.id, null)
            line("재생목록 속 동영상", "${inList.videos.size}개 (목록엔 ${first.count}개)")
            assertTrue(inList.videos.isNotEmpty())
            line("결과", "통과")
        } catch (e: Throwable) {
            line("결과", "실패: ${e.javaClass.simpleName} ${e.message}")
            throw e
        } finally {
            File("build/youtube-live.md").writeText(report.toString().replace(key, "***"))
        }
    }
}
