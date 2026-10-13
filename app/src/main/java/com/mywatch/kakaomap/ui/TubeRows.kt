package com.mywatch.kakaomap.ui

import android.content.Context
import com.mywatch.kakaomap.R
import com.mywatch.kakaomap.tube.Played
import com.mywatch.kakaomap.tube.TubeSource
import com.mywatch.kakaomap.tube.TubeText
import com.mywatch.kakaomap.tube.TubeVideo

/** 유튜브 음악 화면의 줄 구성 (화면 그림 테스트도 같은 것을 그린다) */
object TubeRows {
    const val A_SEARCH = "search"
    const val A_MIX = "mix"
    const val A_QUERY = "q:"
    const val A_CLEAR = "clear"
    const val A_MORE = "more"
    const val A_PHONE_SEARCH = "phone_search"
    const val A_HOME = "home"
    const val A_PLAY_MIX = "play_mix"
    const val A_PLAY_ONE = "play_one"
    const val A_WATCH_MIX = "watch_mix"
    const val A_WATCH_ONE = "watch_one"
    const val A_FORGET = "forget"
    const val A_CANCEL = "cancel"
    const val A_UPDATE = "update"

    private const val SHOWN_PLAYED = 10
    private const val SHOWN_SEARCHES = 6

    /**
     * 처음 화면: 노래 검색 · 믹스 이어 듣기 · 최근 들은 곡 · 최근 검색.
     * [update] = 새 버전이 나왔음 — 타일로 바로 열어 첫 화면을 안 거쳐도 맨 위에 '새 버전 업데이트'.
     * [watch] = 곡을 워치에서 바로 튼다 (워치에 인터넷 앱이 있음). 아니면 폰 유튜브로 틀고, 워치에서 들으려면 무엇이 필요한지 알려 준다
     */
    fun home(c: Context, played: List<Played>, searches: List<String>, update: Boolean = false, watch: Boolean = false): List<Row> {
        val subtitle = c.getString(if (watch) R.string.tube_subtitle_watch else R.string.tube_subtitle)
        val rows = mutableListOf<Row>(Row.Header(c.getString(R.string.tube_title), subtitle))
        if (update) rows += Row.Action(A_UPDATE, R.drawable.ic_download, c.getString(R.string.hub_update_new), accent = true)
        rows += Row.Action(A_SEARCH, R.drawable.ic_mic, c.getString(R.string.tube_search), accent = true)
        played.firstOrNull()?.let {
            rows += Row.Action(A_MIX, R.drawable.ic_queue_music, c.getString(R.string.tube_continue_mix, it.video.title))
        }
        if (played.isNotEmpty()) {
            rows += Row.Header(c.getString(R.string.tube_recent_played))
            rows += played.take(SHOWN_PLAYED).map { Row.VideoItem(it.video) }
        }
        if (searches.isNotEmpty()) {
            rows += Row.Header(c.getString(R.string.tube_recent_searches))
            rows += searches.take(SHOWN_SEARCHES).map { Row.Action(A_QUERY + it, R.drawable.ic_history, it) }
        }
        if (played.isNotEmpty() || searches.isNotEmpty()) {
            rows += Row.Action(A_CLEAR, R.drawable.ic_delete, c.getString(R.string.tube_clear))
        }
        if (watch) {
            rows += Row.Note(c.getString(R.string.tube_how_watch))
        } else {
            rows += Row.Note(c.getString(R.string.tube_how))
            rows += Row.Note(c.getString(R.string.tube_watch_needs_browser))
        }
        return rows
    }

    /** 검색 결과. [loadingMore] = '더 보기'를 받는 중 (버튼 자리에 '더 불러오는 중…') */
    fun results(
        c: Context, query: String, source: TubeSource?, videos: List<TubeVideo>,
        hasMore: Boolean, note: String?, loadingMore: Boolean = false,
    ): List<Row> {
        val rows = mutableListOf<Row>(
            Row.Header("‘$query’", source?.let { c.getString(R.string.tube_count, it.label, videos.size) }),
        )
        note?.let { rows += Row.Note(it) }
        if (videos.isEmpty()) rows += Row.Note(c.getString(R.string.no_results))
        rows += videos.map { Row.VideoItem(it) }
        when {
            loadingMore -> rows += Row.Note(c.getString(R.string.tube_loading_more))
            hasMore -> rows += Row.Action(A_MORE, R.drawable.ic_expand_more, c.getString(R.string.tube_more))
        }
        rows += tail(c, again = videos.isEmpty())
        return rows
    }

    /** 검색 실패 */
    fun error(c: Context, query: String, message: String): List<Row> =
        listOf(Row.Header("‘$query’"), Row.Note(message)) + tail(c, again = true)

    private fun tail(c: Context, again: Boolean): List<Row> = listOf(
        Row.Action(A_SEARCH, R.drawable.ic_mic, c.getString(R.string.tube_search_again), accent = again),
        Row.Action(A_PHONE_SEARCH, R.drawable.ic_smartphone, c.getString(R.string.tube_search_on_phone)),
        Row.Action(A_HOME, R.drawable.ic_home, c.getString(R.string.tube_home)),
    )

    /**
     * 영상을 길게 눌렀을 때: (워치에서 믹스로 / 이 곡만) · 폰에서 믹스로 / 이 곡만 · (최근 들은 곡이면) 기록에서 지우기.
     * [watch] = 워치에서 틀 수 있음 — 그러면 폰 쪽 버튼은 '폰 유튜브에서 …'
     */
    fun menu(c: Context, video: TubeVideo, fromHome: Boolean, watch: Boolean = false): List<Row> {
        val meta = listOfNotNull(video.channel.ifBlank { null }, video.durationSec?.let { TubeText.clock(it) }).joinToString(" · ")
        return listOfNotNull(
            Row.Header(video.title, meta.ifBlank { null }),
            if (watch) Row.Action(A_WATCH_MIX, R.drawable.ic_queue_music, c.getString(R.string.tube_watch_mix), accent = true) else null,
            if (watch) Row.Action(A_WATCH_ONE, R.drawable.ic_play, c.getString(R.string.tube_watch_one)) else null,
            Row.Action(
                A_PLAY_MIX, if (watch) R.drawable.ic_smartphone else R.drawable.ic_queue_music,
                c.getString(if (watch) R.string.tube_phone_mix else R.string.tube_play_mix), accent = !watch,
            ),
            Row.Action(A_PLAY_ONE, if (watch) R.drawable.ic_smartphone else R.drawable.ic_play, c.getString(if (watch) R.string.tube_phone_one else R.string.tube_play_one)),
            if (fromHome) Row.Action(A_FORGET, R.drawable.ic_delete, c.getString(R.string.tube_forget)) else null,
            Row.Action(A_CANCEL, R.drawable.ic_close, c.getString(R.string.cancel)),
        )
    }
}
