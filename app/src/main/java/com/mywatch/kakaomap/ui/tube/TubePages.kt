package com.mywatch.kakaomap.ui.tube

import com.mywatch.kakaomap.tube.TubeVideo

/** 유튜브 앱의 화면 하나 */
sealed class Page {
    /** 내 계정(로그인)의 데이터를 보는 화면 — 로그아웃하면 걷어 낸다 */
    open val mine: Boolean get() = false

    /** 홈: 이어서 보기 · 구독 새 동영상 · 인기 음악 · 인기 동영상 · 최근 검색 · 계정 */
    data object Home : Page()
    data class Search(val query: String) : Page()
    /** 구독: 구독 채널의 새 동영상 + 채널 목록 */
    data object Subscriptions : Page() {
        override val mine get() = true
    }
    /** 보관함: 기록 · 좋아요 · 내 재생목록 · 계정 */
    data object Library : Page()
    /** 이 앱에서 본 동영상 (워치에만 저장) */
    data object History : Page()
    data object Liked : Page() {
        override val mine get() = true
    }
    data object MyPlaylists : Page() {
        override val mine get() = true
    }
    /** 인기 차트 전체 ([category] "10" = 음악, null = 전체) */
    data class Chart(val category: String?, val title: String) : Page()
    data class Channel(val id: String, val title: String) : Page()
    /** 재생목록. [own] = 내 재생목록 (비공개일 수 있어 로그인으로 받는다) */
    data class Playlist(val id: String, val title: String, val own: Boolean = false) : Page() {
        override val mine get() = own
    }
    /** 동영상을 길게 누름 */
    data class Menu(val video: TubeVideo) : Page()
    data object Login : Page()
    data object Account : Page() {
        override val mine get() = true
    }
    /** 재생 화면의 '폰에서 이어 보기' — 한 번 묻는다 */
    data class Handoff(val handoff: com.mywatch.kakaomap.tube.Handoff) : Page()
    /** YouTube 약관·개인정보 처리방침 (처음 열 때 동의, 그 뒤엔 보관함에서 언제든) */
    data object Terms : Page()
}

/** 뒤로 가기 쌓기 — 뒤로는 늘 한 칸, 맨 아래는 홈 */
class TubeNav(start: Page = Page.Home) {
    private val stack = ArrayList<Page>().apply { add(start) }

    val top: Page get() = stack.last()
    val depth: Int get() = stack.size

    fun push(p: Page) {
        if (p == top) return
        if (p is Page.Menu && top is Page.Menu) stack.removeAt(stack.lastIndex)   // 메뉴 위에 메뉴는 없다
        stack.add(p)
        while (stack.size > MAX) stack.removeAt(1)   // 너무 깊으면 홈 바로 위의 오래된 것부터
    }

    /** 한 칸 뒤로. 처음 화면이면 false (앱을 닫는다) */
    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun reset(p: Page = Page.Home) {
        stack.clear()
        stack.add(p)
    }

    /** 지금 화면을 [p] 로 바꾼다 (로그인 화면을 마치면 — 로그인 화면은 뒤로 가기에 남지 않게) */
    fun replaceTop(p: Page) {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
        if (p != top) push(p)
    }

    /** 로그아웃: 내 데이터 화면과 그 위의 것(메뉴 등)을 걷어 낸다 */
    fun dropMine() {
        val first = stack.indexOfFirst { it.mine }
        if (first < 1) return
        while (stack.size > first) stack.removeAt(stack.lastIndex)
    }

    companion object {
        const val MAX = 16
    }
}
