package com.mywatch.kakaomap.ui

import java.net.URI

/**
 * 앱 안 재생 화면(GeckoView)이 어디로 갈 수 있는지 (단위 테스트로 검증):
 *  · 재생 화면 자신(주소의 # 만 바뀜)과 유튜브 플레이어(iframe) 안의 https 이동만 그대로
 *  · 워치 앱 링크(kakaowatch://tube…, 인터넷 앱용 intent://…scheme=kakaowatch)는 앱이 받는다 — 목록으로·폰에서 이어 보기
 *  · 유튜브 로고·광고 같은 바깥 https 는 사용자가 눌렀을 때만 폰에서 연다 (워치에서 새 창을 열지 않는다)
 *  · 그 밖(파일·스크립트·다른 앱·http)은 막는다
 */
object PlayerNav {

    sealed interface Decision {
        data object Allow : Decision
        data object Block : Decision
        /** 워치 앱이 받을 링크 (kakaowatch://tube 또는 kakaowatch://tube/phone?…) */
        data class App(val link: String) : Decision
        /** 폰에서 열 주소 */
        data class Phone(val url: String) : Decision
    }

    private const val PACKAGE = "com.mywatch.kakaomap"

    /** 화면 전체(최상위)의 이동. [userGesture] = 사용자가 눌러서 가는 것 */
    fun top(uri: String, pageBase: String, userGesture: Boolean): Decision {
        val u = uri.trim()
        if (u == "about:blank" || isPage(u, pageBase)) return Decision.Allow
        appLink(u)?.let { return it }
        return if (isHttps(u) && userGesture) Decision.Phone(u) else Decision.Block
    }

    /** 플레이어(iframe) 안의 이동 — 광고·동의 화면 등 유튜브가 하는 이동은 막지 않는다 (https 만) */
    fun frame(uri: String): Decision {
        val u = uri.trim()
        return if (u == "about:blank" || u == "about:srcdoc" || isHttps(u)) Decision.Allow else Decision.Block
    }

    /** 새 창(유튜브에서 보기·광고) — 워치에선 열지 않고 폰에서 */
    fun newWindow(uri: String, pageBase: String): Decision {
        val u = uri.trim()
        if (isPage(u, pageBase)) return Decision.Block
        appLink(u)?.let { return it }
        return if (isHttps(u)) Decision.Phone(u) else Decision.Block
    }

    private fun isPage(u: String, base: String): Boolean {
        val a = runCatching { URI(u) }.getOrNull() ?: return false
        val b = runCatching { URI(base) }.getOrNull() ?: return false
        if (!"https".equals(a.scheme, ignoreCase = true) || !b.host.equals(a.host, ignoreCase = true) || a.port != b.port) return false
        val path = a.rawPath ?: return false
        return path == b.rawPath || path == b.rawPath + "index.html"
    }

    private fun isHttps(u: String): Boolean {
        val a = runCatching { URI(u) }.getOrNull() ?: return false
        return "https".equals(a.scheme, ignoreCase = true) && !a.host.isNullOrEmpty()
    }

    /** 워치 앱 링크면 그 뜻(앱으로·막기), 아니면 null */
    private fun appLink(u: String): Decision? {
        val link = when {
            u.startsWith("kakaowatch://", ignoreCase = true) -> u
            u.startsWith("intent://", ignoreCase = true) -> fromIntent(u) ?: return Decision.Block
            else -> return null
        }
        val uri = runCatching { URI(link) }.getOrNull() ?: return Decision.Block
        if (!"kakaowatch".equals(uri.scheme, ignoreCase = true) || uri.host != "tube") return Decision.Block
        return when (uri.rawPath.orEmpty()) {
            "", "/" -> Decision.App("kakaowatch://tube")
            "/phone" -> Decision.App(link)
            else -> Decision.Block
        }
    }

    /** intent://tube/phone?…#Intent;scheme=kakaowatch;package=이 앱;end → kakaowatch://tube/phone?… (다른 앱·다른 이름은 null) */
    private fun fromIntent(u: String): String? {
        val mark = u.indexOf("#Intent;")
        if (mark < 0 || !u.endsWith(";end")) return null
        // 'intent://x#Intent;end' 처럼 추가 정보가 없으면 두 표시가 ';' 를 함께 써 범위가 거꾸로 된다 — 막기
        val from = mark + "#Intent;".length
        val to = u.length - ";end".length
        if (from > to) return null
        val extras = u.substring(from, to).split(';')
            .mapNotNull { kv -> kv.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
            .toMap()
        if (extras["scheme"] != "kakaowatch") return null
        if (extras["package"].let { it != null && it != PACKAGE }) return null
        return "kakaowatch://" + u.substring("intent://".length, mark)
    }
}
