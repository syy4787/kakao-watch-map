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
            // 어린이용인지(status.madeForKids — 정책 III.E.4.j)는 모든 동영상에 와야 한다 (모르면 앱이 따로 찾아보고 폰으로 보낸다)
            line("어린이용 표시", "모름 ${music.videos.count { it.madeForKids == null }} · 어린이용 ${music.videos.count { it.madeForKids == true }} / ${music.videos.size}")
            assertTrue("어린이용인지가 와야 한다", music.videos.all { it.madeForKids != null })

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

    /**
     * 새 홈(칩·알고리즘·Shorts)이 기대는 것이 실제로 되는지 — 앱 코드 없이 그대로 물어본다 (결과는 요약에만, 실패로 치지 않는다).
     *  · 채널의 자동 재생목록 UUSH(Shorts)·UULF(긴 동영상)·UULV(생방송) — 문서에 없는 것이라 실제로 확인
     *  · videos.list part=player + maxHeight → embedWidth/embedHeight (세로면 Shorts) · snippet.categoryId
     *  · 인기 차트 분류 20(게임)·25(뉴스) · 검색 eventType=live · videoCategoryId=25 · videoDuration=short
     * 쓰는 한도: 약 10단위 + 검색 3번
     */
    @Test
    fun theApiHasWhatTheNewHomeNeeds() {
        val home = StringBuilder("## 새 홈에 필요한 것 (실제 API)\n\n| 확인 | 결과 |\n|---|---|\n")
        fun row(what: String, result: String) {
            home.append("| ").append(what).append(" | ").append(result.replace("|", "/").replace("\n", " ")).append(" |\n")
        }
        fun ask(path: String, params: Map<String, String>): Pair<Int, JSONObject?> {
            val res = Http.get("${YouTubeData.BASE}$path?" + Http.query(params + ("key" to key)))
            return res.code to runCatching { JSONObject(res.body) }.getOrNull()
        }
        fun reason(j: JSONObject?) = j?.optJSONObject("error")?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason").orEmpty()
        fun items(j: JSONObject?) = j?.optJSONArray("items")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
        try {
            val channels = linkedMapOf("1theK" to "UCweOkPb1wVVH0Q0Tlj4a5Pw", "YTN" to "UChlgI3UHCOnwUGzWzbJ3H5w")
            val firsts = mutableMapOf<String, String>()
            for ((name, id) in channels) {
                for (prefix in listOf("UU", "UUSH", "UULF", "UULV")) {
                    val (code, j) = ask("/playlistItems", mapOf(
                        "part" to "contentDetails", "playlistId" to prefix + id.substring(2), "maxResults" to "5",
                        "fields" to "pageInfo/totalResults,items/contentDetails/videoId",
                    ))
                    val ids = items(j).map { it.optJSONObject("contentDetails")?.optString("videoId").orEmpty() }
                    ids.firstOrNull()?.let { firsts["$name/$prefix"] = it }
                    row("$name $prefix", "$code ${reason(j)} · ${ids.size}개 (전체 ${j?.optJSONObject("pageInfo")?.optInt("totalResults")}) · 첫 ${ids.firstOrNull()}")
                }
            }
            val probe = listOfNotNull(firsts["1theK/UUSH"], firsts["1theK/UULF"], firsts["YTN/UUSH"], firsts["YTN/UULV"]).distinct()
            if (probe.isNotEmpty()) {
                val (code, j) = ask("/videos", mapOf(
                    "part" to "snippet,contentDetails,player", "id" to probe.joinToString(","), "maxHeight" to "360",
                    "fields" to "items(id,snippet(categoryId,liveBroadcastContent),contentDetails/duration,player(embedWidth,embedHeight))",
                ))
                items(j).forEach { v ->
                    val p = v.optJSONObject("player")
                    val sn = v.optJSONObject("snippet")
                    val from = firsts.entries.filter { it.value == v.optString("id") }.joinToString(",") { it.key }
                    row(
                        "모양 $from",
                        "$code · ${p?.optInt("embedWidth")}×${p?.optInt("embedHeight")} · 길이 ${v.optJSONObject("contentDetails")?.optString("duration")} · " +
                            "분류 ${sn?.optString("categoryId")} · ${sn?.optString("liveBroadcastContent")}",
                    )
                }
            }
            for (cat in listOf("10", "20", "1", "17", "25")) {
                val (code, j) = ask("/videos", mapOf(
                    "part" to "snippet", "chart" to "mostPopular", "regionCode" to "KR", "videoCategoryId" to cat, "maxResults" to "10",
                    "fields" to "items(id,snippet/categoryId)",
                ))
                val cats = items(j).map { it.optJSONObject("snippet")?.optString("categoryId") }.groupingBy { it }.eachCount()
                row("인기 분류 $cat", "$code ${reason(j)} · ${items(j).size}개 · 받은 분류 $cats")
            }
            val (allCode, all) = ask("/videos", mapOf(
                "part" to "snippet", "chart" to "mostPopular", "regionCode" to "KR", "maxResults" to "20", "fields" to "items(id,snippet/categoryId)",
            ))
            row("인기 전체", "$allCode · 받은 분류 ${items(all).map { it.optJSONObject("snippet")?.optString("categoryId") }.groupingBy { it }.eachCount()}")
            val searches = linkedMapOf(
                "실시간" to mapOf("eventType" to "live"),
                "뉴스" to mapOf("videoCategoryId" to "25", "publishedAfter" to java.time.Instant.now().minusSeconds(48 * 3600).toString()),
                "Shorts" to mapOf("videoDuration" to "short", "q" to "#shorts"),
            )
            for ((name, extra) in searches) {
                val (code, j) = ask("/search", mapOf(
                    "part" to "snippet", "type" to "video", "regionCode" to "KR", "relevanceLanguage" to "ko", "maxResults" to "10",
                    "fields" to "items(id/videoId,snippet(title,liveBroadcastContent))",
                ) + extra)
                row("검색 $name", "$code ${reason(j)} · ${items(j).size}개 · 예: ${items(j).firstOrNull()?.optJSONObject("snippet")?.optString("title")}")
            }
        } catch (e: Throwable) {
            row("오류", "${e.javaClass.simpleName} ${e.message}")
        } finally {
            File("build/youtube-live-home.md").writeText(home.toString().replace(key, "***"))
        }
    }
}
