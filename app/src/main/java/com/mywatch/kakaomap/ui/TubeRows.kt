package com.mywatch.kakaomap.ui

import android.content.Context
import com.mywatch.kakaomap.R
import com.mywatch.kakaomap.tube.Handoff
import com.mywatch.kakaomap.tube.PlayPlaces
import com.mywatch.kakaomap.tube.PlayTarget
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
    /** 곡 메뉴: 워치 YouTube Music · 폰 YouTube Music (프리미엄) */
    const val A_WATCH_MUSIC = "watch_music"
    const val A_PHONE_MUSIC = "phone_music"
    /** 곡 메뉴: 워치 화면 플레이어 (믹스 / 이 곡만) */
    const val A_WATCH_MIX = "watch_mix"
    const val A_WATCH_ONE = "watch_one"
    const val A_HANDOFF = "handoff"
    const val A_FORGET = "forget"
    const val A_CANCEL = "cancel"
    const val A_UPDATE = "update"
    /** 처음 화면의 '듣는 곳' → 고르는 화면 */
    const val A_TARGET = "target"
    /** 듣는 곳 고르기: "to:" + [PlayTarget.key] */
    const val A_TO = "to:"
    const val A_PREMIUM = "premium"
    const val A_OPEN_MUSIC = "open_music"
    const val A_INSTALL_MUSIC = "install_music"

    private const val SHOWN_PLAYED = 10
    private const val SHOWN_SEARCHES = 6

    /** 워치에 아무 앱도 없고 아직 안 고름 (화면 그림 테스트·업데이트 테스트용 기본값) */
    val PHONE_ONLY = PlayPlaces.of(null, watchMusic = false, musicApp = false, watchWeb = false)

    /**
     * 처음 화면 (유튜브 뮤직처럼): 제목 · 노래 검색(흰 버튼) · 듣는 곳 · 믹스 이어 듣기 · 최근 들은 곡 · 최근 검색 ·
     * (워치 YouTube Music 열기) · 프리미엄으로 듣는 법.
     * [update] = 새 버전이 나왔음 — 타일로 바로 열어 첫 화면을 안 거쳐도 맨 위에 '새 버전 업데이트'
     */
    fun home(c: Context, played: List<Played>, searches: List<String>, update: Boolean = false, places: PlayPlaces = PHONE_ONLY): List<Row> {
        val rows = mutableListOf<Row>(Row.Header(c.getString(R.string.tube_title)))
        if (update) rows += Row.Action(A_UPDATE, R.drawable.ic_download, c.getString(R.string.hub_update_new), accent = true)
        rows += Row.Action(A_SEARCH, R.drawable.ic_mic, c.getString(R.string.tube_search), accent = !update)
        rows += if (places.chosen) {
            Row.Action(A_TARGET, targetIcon(places.target), targetName(c, places.target), sub = c.getString(R.string.tube_target_label))
        } else {
            Row.Action(A_TARGET, R.drawable.ic_queue_music, c.getString(R.string.tube_target_pick), sub = c.getString(R.string.tube_target_pick_sub))
        }
        played.firstOrNull()?.let {
            rows += Row.Action(A_MIX, R.drawable.ic_queue_music, c.getString(R.string.tube_mix), sub = it.video.title, art = it.video.id)
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
        if (places.musicApp) {
            rows += Row.Action(A_OPEN_MUSIC, R.drawable.ic_watch, c.getString(R.string.tube_open_music), sub = c.getString(R.string.tube_open_music_home_sub))
        }
        rows += Row.Action(A_PREMIUM, R.drawable.ic_info, c.getString(R.string.tube_premium))
        rows += Row.Note(c.getString(if (places.chosen) howText(places.target) else R.string.tube_how_unset))
        return rows
    }

    /** '듣는 곳' 고르기 (처음 곡을 틀 때도 이 화면): 되는 곳만 — 프리미엄 되는 곳부터, 지금 고른 곳은 흰 버튼(✓) */
    fun targets(c: Context, places: PlayPlaces): List<Row> {
        val rows = mutableListOf<Row>(Row.Header(c.getString(R.string.tube_targets_title), c.getString(R.string.tube_targets_sub)))
        val offered = listOfNotNull(
            if (places.watchMusic) PlayTarget.WATCH_MUSIC else null,
            PlayTarget.PHONE_MUSIC,
            if (places.watchWeb) PlayTarget.WATCH_WEB else null,
        )
        offered.forEach { t ->
            val chosen = places.chosen && t == places.target
            rows += Row.Action(
                A_TO + t.key, if (chosen) R.drawable.ic_check else targetIcon(t), targetName(c, t),
                accent = chosen, sub = c.getString(targetSub(t)), selected = chosen,
            )
        }
        if (!places.watchWeb) rows += Row.Note(c.getString(R.string.tube_watch_needs_browser))
        rows += Row.Action(A_PREMIUM, R.drawable.ic_info, c.getString(R.string.tube_premium))
        return rows
    }

    /**
     * 프리미엄으로 듣는 법: 왜 워치 화면 플레이어는 안 되는지 · 폰 유튜브 뮤직으로 바꾸기 · 워치에 유튜브 뮤직 설치/열기(계정 전환).
     * 워치 앱이 이 앱에서 고른 곡(곡 링크)을 받지 않으면 그렇다고 알린다
     */
    fun premium(c: Context, places: PlayPlaces): List<Row> {
        val rows = mutableListOf<Row>(
            Row.Header(c.getString(R.string.tube_premium_title), c.getString(R.string.tube_premium_sub)),
            Row.Note(c.getString(R.string.tube_premium_why)),
        )
        if (!places.chosen || places.target != PlayTarget.PHONE_MUSIC) {
            rows += Row.Action(A_TO + PlayTarget.PHONE_MUSIC.key, R.drawable.ic_smartphone, c.getString(R.string.tube_premium_phone),
                sub = c.getString(R.string.tube_premium_phone_sub))
        }
        if (places.watchMusic && (!places.chosen || places.target != PlayTarget.WATCH_MUSIC)) {
            rows += Row.Action(A_TO + PlayTarget.WATCH_MUSIC.key, R.drawable.ic_watch, c.getString(R.string.tube_premium_watch_music),
                sub = c.getString(R.string.tube_target_watch_music_sub))
        }
        // 워치에서 (화면이 꺼져도): 설치 → 열어서 프리미엄 계정으로 전환
        rows += Row.Header(c.getString(R.string.tube_premium_on_watch))
        rows += Row.Note(c.getString(R.string.tube_premium_steps))
        if (places.musicApp) {
            rows += Row.Action(A_OPEN_MUSIC, R.drawable.ic_watch, c.getString(R.string.tube_open_music), sub = c.getString(R.string.tube_open_music_sub))
            if (!places.watchMusic) rows += Row.Note(c.getString(R.string.tube_premium_no_search))
        } else {
            rows += Row.Action(A_INSTALL_MUSIC, R.drawable.ic_download, c.getString(R.string.tube_install_music), sub = c.getString(R.string.tube_install_music_sub))
        }
        rows += Row.Action(A_CANCEL, R.drawable.ic_close, c.getString(R.string.tube_close))
        return rows
    }

    /**
     * 재생 화면의 '폰에서 이어 듣기': 무엇을 어디서부터 틀지 보여 주고 한 번 묻는다 (웹 페이지가 보낸 일이라 바로 틀지 않는다).
     * 큰 그림 없이 — 버튼이 첫 화면에 보이게
     */
    fun handoff(c: Context, h: Handoff): List<Row> = listOf(
        Row.Header(
            h.video.title.ifBlank { c.getString(R.string.tube_handoff_untitled) },
            if (h.startSec > 0) c.getString(R.string.tube_handoff_sub, TubeText.clock(h.startSec)) else c.getString(R.string.tube_handoff_sub_start),
        ),
        Row.Action(A_HANDOFF, R.drawable.ic_smartphone, c.getString(R.string.tube_handoff_go), accent = true),
        Row.Action(A_CANCEL, R.drawable.ic_close, c.getString(R.string.cancel)),
    )

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
     * 곡을 길게 눌렀을 때 (맨 위에 그 곡의 큰 그림): 틀 수 있는 곳마다 — 워치 YouTube Music · 폰 YouTube Music ·
     * 워치 화면 플레이어(믹스 / 이 곡만). 평소 듣는 곳(골랐으면)은 흰 버튼. (최근 들은 곡이면) 기록에서 지우기
     */
    fun menu(c: Context, video: TubeVideo, fromHome: Boolean, places: PlayPlaces = PHONE_ONLY): List<Row> {
        val meta = listOfNotNull(video.channel.ifBlank { null }, video.durationSec?.let { TubeText.clock(it) }).joinToString(" · ")
        val target = places.target.takeIf { places.chosen }
        return listOfNotNull(
            Row.Header(video.title, meta.ifBlank { null }, art = video.id),
            if (places.watchMusic) {
                Row.Action(A_WATCH_MUSIC, R.drawable.ic_watch, c.getString(R.string.tube_play_watch_music),
                    accent = target == PlayTarget.WATCH_MUSIC, sub = c.getString(R.string.tube_premium_mix))
            } else null,
            Row.Action(A_PHONE_MUSIC, R.drawable.ic_smartphone, c.getString(R.string.tube_play_phone_music),
                accent = target == PlayTarget.PHONE_MUSIC, sub = c.getString(R.string.tube_premium_mix)),
            if (places.watchWeb) {
                Row.Action(A_WATCH_MIX, R.drawable.ic_queue_music, c.getString(R.string.tube_watch_mix),
                    accent = target == PlayTarget.WATCH_WEB, sub = c.getString(R.string.tube_watch_mix_sub))
            } else null,
            if (places.watchWeb) {
                Row.Action(A_WATCH_ONE, R.drawable.ic_play, c.getString(R.string.tube_watch_one), sub = c.getString(R.string.tube_watch_one_sub))
            } else null,
            if (fromHome) Row.Action(A_FORGET, R.drawable.ic_delete, c.getString(R.string.tube_forget)) else null,
            Row.Action(A_CANCEL, R.drawable.ic_close, c.getString(R.string.cancel)),
        )
    }

    fun targetName(c: Context, t: PlayTarget): String = c.getString(
        when (t) {
            PlayTarget.WATCH_MUSIC -> R.string.tube_target_watch_music
            PlayTarget.PHONE_MUSIC -> R.string.tube_target_phone_music
            PlayTarget.WATCH_WEB -> R.string.tube_target_watch_web
        }
    )

    private fun targetSub(t: PlayTarget): Int = when (t) {
        PlayTarget.WATCH_MUSIC -> R.string.tube_target_watch_music_sub
        PlayTarget.PHONE_MUSIC -> R.string.tube_target_phone_music_sub
        PlayTarget.WATCH_WEB -> R.string.tube_target_watch_web_sub
    }

    private fun targetIcon(t: PlayTarget): Int = when (t) {
        PlayTarget.WATCH_MUSIC -> R.drawable.ic_watch
        PlayTarget.PHONE_MUSIC -> R.drawable.ic_smartphone
        PlayTarget.WATCH_WEB -> R.drawable.ic_play
    }

    private fun howText(t: PlayTarget): Int = when (t) {
        PlayTarget.WATCH_MUSIC -> R.string.tube_how_watch_music
        PlayTarget.PHONE_MUSIC -> R.string.tube_how_phone_music
        PlayTarget.WATCH_WEB -> R.string.tube_how_watch_web
    }
}
