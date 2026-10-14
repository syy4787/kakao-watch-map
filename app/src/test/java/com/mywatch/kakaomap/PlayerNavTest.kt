package com.mywatch.kakaomap

import com.mywatch.kakaomap.ui.PlayerNav
import com.mywatch.kakaomap.ui.PlayerNav.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 앱 안 재생 화면(GeckoView)이 어디로 가게 둘지: 재생 화면 안 이동만 그대로, 워치 앱 링크(kakaowatch://, intent://)는 앱이 받고,
 * 유튜브·광고 같은 바깥 링크는 사용자가 눌렀을 때만 폰에서 연다. 그 밖(파일·스크립트·다른 앱)은 막는다
 */
class PlayerNavTest {

    private val base = "https://syy4787.github.io/kakao-watch-map/tube/"

    @Test
    fun thePlayerPageItselfIsAllowed() {
        assertEquals(Decision.Allow, PlayerNav.top(base + "#v=BzYnNdJhZQw&ids=pSUydWEqKwE&app=1", base, userGesture = false))
        assertEquals(Decision.Allow, PlayerNav.top(base, base, userGesture = false))
        assertEquals(Decision.Allow, PlayerNav.top(base + "index.html#v=BzYnNdJhZQw", base, userGesture = false))
        assertEquals(Decision.Allow, PlayerNav.top("about:blank", base, userGesture = false))
        // 비슷하지만 다른 곳은 재생 화면이 아니다
        assertEquals(Decision.Block, PlayerNav.top("https://syy4787.github.io/kakao-watch-map/other/", base, userGesture = false))
        assertEquals(Decision.Block, PlayerNav.top("https://syy4787.github.io.evil.com/kakao-watch-map/tube/", base, userGesture = false))
        assertEquals(Decision.Block, PlayerNav.top("http://syy4787.github.io/kakao-watch-map/tube/", base, userGesture = false))
    }

    @Test
    fun watchAppLinksGoBackToTheApp() {
        assertEquals(Decision.App("kakaowatch://tube"), PlayerNav.top("kakaowatch://tube", base, userGesture = true))
        assertEquals(
            Decision.App("kakaowatch://tube/phone?v=BzYnNdJhZQw&t=83&list=PLx"),
            PlayerNav.top("kakaowatch://tube/phone?v=BzYnNdJhZQw&t=83&list=PLx", base, userGesture = true),
        )
        // 인터넷 앱용 링크(intent://)도 같은 뜻으로
        assertEquals(
            Decision.App("kakaowatch://tube/phone?v=BzYnNdJhZQw&t=5"),
            PlayerNav.top("intent://tube/phone?v=BzYnNdJhZQw&t=5#Intent;scheme=kakaowatch;package=com.mywatch.kakaomap;end", base, userGesture = true),
        )
        assertEquals(Decision.App("kakaowatch://tube"), PlayerNav.top("intent://tube#Intent;scheme=kakaowatch;package=com.mywatch.kakaomap;end", base, userGesture = true))
        // 다른 앱을 여는 intent·모르는 워치 앱 링크는 막는다
        assertEquals(Decision.Block, PlayerNav.top("intent://scan#Intent;scheme=zxing;package=com.google.zxing.client.android;end", base, userGesture = true))
        assertEquals(Decision.Block, PlayerNav.top("intent://tube#Intent;scheme=kakaowatch;package=com.evil;end", base, userGesture = true))
        assertEquals(Decision.Block, PlayerNav.top("kakaowatch://map/home", base, userGesture = true))
    }

    @Test
    fun malformedIntentLinksAreBlockedInsteadOfCrashingThePlayer() {
        // 'intent://x#Intent;end' 처럼 추가 정보가 없으면 "#Intent;" 와 ";end" 가 ';' 를 함께 써서 잘라 낼 범위가 거꾸로 —
        // 예외가 GeckoView 콜백 밖으로 나가 재생 화면 프로세스가 죽고 6시간 동안 인터넷 앱으로 틀었다
        for (u in listOf("intent://x#Intent;end", "intent://#Intent;end", "intent://tube#Intent;;end", "intent://#Intent;scheme=kakaowatch;end")) {
            assertEquals(u, Decision.Block, PlayerNav.top(u, base, userGesture = true))
            assertEquals(u, Decision.Block, PlayerNav.newWindow(u, base))
        }
    }

    @Test
    fun outsideLinksOpenOnThePhoneOnlyWhenTapped() {
        assertEquals(Decision.Phone("https://www.youtube.com/watch?v=BzYnNdJhZQw"), PlayerNav.top("https://www.youtube.com/watch?v=BzYnNdJhZQw", base, userGesture = true))
        assertEquals("광고를 누르면 폰에서", Decision.Phone("https://www.googleadservices.com/pagead/aclk?x=1"), PlayerNav.top("https://www.googleadservices.com/pagead/aclk?x=1", base, userGesture = true))
        assertEquals("누르지 않았는데 바깥으로 가려 함", Decision.Block, PlayerNav.top("https://www.youtube.com/watch?v=BzYnNdJhZQw", base, userGesture = false))
        for (bad in listOf("javascript:alert(1)", "file:///sdcard/x", "data:text/html,hi", "http://example.com/", "content://x", "market://details?id=x")) {
            assertEquals(bad, Decision.Block, PlayerNav.top(bad, base, userGesture = true))
        }
    }

    @Test
    fun theYouTubePlayerFrameMayMoveFreelyOverHttps() {
        assertEquals(Decision.Allow, PlayerNav.frame("https://www.youtube.com/embed/BzYnNdJhZQw?enablejsapi=1&origin=https%3A%2F%2Fsyy4787.github.io"))
        assertEquals(Decision.Allow, PlayerNav.frame("https://www.youtube-nocookie.com/embed/x"))
        assertEquals("광고·동의 화면 등 플레이어 안의 이동은 막지 않는다", Decision.Allow, PlayerNav.frame("https://consent.youtube.com/m?x"))
        assertEquals(Decision.Allow, PlayerNav.frame("about:blank"))
        assertEquals(Decision.Allow, PlayerNav.frame("about:srcdoc"))
        for (bad in listOf("http://www.youtube.com/embed/x", "intent://x#Intent;scheme=vnd.youtube;end", "kakaowatch://tube", "javascript:1", "file:///x")) {
            assertEquals(bad, Decision.Block, PlayerNav.frame(bad))
        }
    }

    @Test
    fun newWindowsNeverOpenOnTheWatch() {
        // 플레이어의 '유튜브에서 보기'·광고는 새 창 → 폰에서
        assertEquals(Decision.Phone("https://www.youtube.com/watch?v=BzYnNdJhZQw"), PlayerNav.newWindow("https://www.youtube.com/watch?v=BzYnNdJhZQw", base))
        assertEquals(Decision.Block, PlayerNav.newWindow("javascript:open()", base))
        assertEquals(Decision.Block, PlayerNav.newWindow(base + "#v=x", base))
    }
}
