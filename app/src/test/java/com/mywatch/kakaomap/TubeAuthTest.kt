package com.mywatch.kakaomap

import com.mywatch.kakaomap.net.ApiException
import com.mywatch.kakaomap.net.Http
import com.mywatch.kakaomap.tube.DeviceCode
import com.mywatch.kakaomap.tube.NetworkException
import com.mywatch.kakaomap.tube.Poll
import com.mywatch.kakaomap.tube.TokenStore
import com.mywatch.kakaomap.tube.Tokens
import com.mywatch.kakaomap.tube.TubeAuth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 유튜브 로그인 (구글 OAuth 2.0 기기 흐름 — 'TV 및 입력 제한 기기'): 코드 받기 → 폰에서 승인을 기다림(천천히·거절·만료) →
 * 토큰 저장 → 끝나기 전에 새로 받기 → 계정에서 끊으면 로그아웃 → 로그아웃은 구글에 철회까지. 가짜 구글 서버로
 */
class TubeAuthTest {

    private class Req(val url: String, val form: Map<String, String>)

    private class FakeGoogle {
        val reqs = mutableListOf<Req>()
        var reply: (Req) -> Http.Response = { Http.Response(500, "") }
        fun post(url: String, form: Map<String, String>): Http.Response = Req(url, form).let { reqs += it; reply(it) }
    }

    private class MemoryStore : TokenStore {
        var t: Tokens? = null
        var revokes = mutableListOf<String>()
        override fun load() = t
        override fun save(t: Tokens) { this.t = t }
        override fun clear() { t = null }
        override fun pendingRevokes(): List<String> = revokes.toList()
        override fun setPendingRevokes(tokens: List<String>) { revokes = tokens.toMutableList() }
    }

    private var now = 1_760_000_000_000L
    private val g = FakeGoogle()
    private val store = MemoryStore()
    private val secret = "GOCSPX-client-secret"

    private fun auth(id: String = "123-abc.apps.googleusercontent.com", secret: String = this.secret) =
        TubeAuth(id, secret, g::post, store) { now }

    private fun err(code: Int, error: String) = Http.Response(code, """{"error":"$error","error_description":"x"}""")

    private val code get() = DeviceCode("DEVICE-CODE-SECRET", "GQVQ-JKEC", "https://www.google.com/device", now + 1_800_000, 5)

    @Test
    fun startGivesTheCodeToTypeOnThePhone() {
        g.reply = {
            Http.Response(200, """{"device_code":"DEVICE-CODE-SECRET","user_code":"GQVQ-JKEC","verification_url":"https://www.google.com/device","expires_in":1800,"interval":5}""")
        }
        val c = auth().start()
        assertEquals("GQVQ-JKEC", c.userCode)
        assertEquals("https://www.google.com/device", c.verificationUrl)
        assertEquals(now + 1_800_000, c.expiresAtMs)
        assertEquals(5, c.intervalSec)
        val r = g.reqs.single()
        assertEquals("https://oauth2.googleapis.com/device/code", r.url)
        assertEquals("123-abc.apps.googleusercontent.com", r.form["client_id"])
        assertEquals("유튜브 '보기'만 (구독·재생목록을 바꾸지 못함)", "https://www.googleapis.com/auth/youtube.readonly", r.form["scope"])
        assertFalse("코드를 받을 땐 비밀값을 보내지 않는다", r.form.containsKey("client_secret"))
    }

    @Test
    fun withoutAClientThereIsNoSignInAndNothingIsSent() {
        val a = auth(id = " ")
        assertFalse(a.configured)
        try {
            a.start()
            fail()
        } catch (e: ApiException) {
            assertTrue(g.reqs.isEmpty())
        }
        assertFalse(auth(secret = "").configured)
        assertTrue(auth().configured)
    }

    @Test
    fun tooManyCodeRequestsAskToWait() {
        g.reply = { Http.Response(403, """{"error_code":"rate_limit_exceeded"}""") }
        try {
            auth().start()
            fail()
        } catch (e: ApiException) {
            assertTrue(e.message!!.contains("잠시"))
        }
        g.reply = { Http.Response(200, """{"user_code":"A"}""") }
        try {
            auth().start()
            fail("모양이 이상한 응답")
        } catch (e: ApiException) {
            // ok
        }
    }

    @Test
    fun pollingWaitsSlowsDownAndThenSignsIn() {
        val a = auth()
        g.reply = { err(428, "authorization_pending") }
        assertEquals(Poll.Pending, a.poll(code))
        g.reply = { err(403, "slow_down") }
        assertEquals("간격을 5초 늘린다", Poll.SlowDown(10), a.poll(code))
        assertFalse(a.signedIn)

        g.reply = {
            Http.Response(200, """{"access_token":"AT1","expires_in":3599,"refresh_token":"RT1","scope":"https://www.googleapis.com/auth/youtube.readonly","token_type":"Bearer"}""")
        }
        assertEquals(Poll.Granted, a.poll(code))
        assertEquals(Tokens("AT1", "RT1", now + 3_599_000), store.t)
        assertTrue(a.signedIn)
        val r = g.reqs.last()
        assertEquals("https://oauth2.googleapis.com/token", r.url)
        assertEquals("urn:ietf:params:oauth:grant-type:device_code", r.form["grant_type"])
        assertEquals("DEVICE-CODE-SECRET", r.form["device_code"])
        assertEquals(secret, r.form["client_secret"])
    }

    @Test
    fun deniedExpiredAndUsedCodesEndTheWait() {
        val a = auth()
        g.reply = { err(403, "access_denied") }
        assertEquals(Poll.Denied, a.poll(code))
        g.reply = { err(400, "expired_token") }
        assertEquals(Poll.Expired, a.poll(code))
        g.reply = { err(400, "invalid_grant") }
        assertEquals("이미 쓴·잘못된 코드 → 처음부터", Poll.Expired, a.poll(code))
        val before = g.reqs.size
        assertEquals("시간이 지나면 묻지 않고 끝", Poll.Expired, a.poll(code.copy(expiresAtMs = now)))
        assertEquals(before, g.reqs.size)
        // 승인했는데 유튜브 보기 권한을 빼고 줬으면 로그인하지 않는다
        g.reply = { Http.Response(200, """{"access_token":"AT","expires_in":3599,"refresh_token":"RT","scope":"openid"}""") }
        assertEquals(Poll.Denied, a.poll(code))
        assertNull(store.t)
        assertFalse(a.signedIn)
    }

    @Test
    fun brokenClientSettingsSaySoWithoutSecrets() {
        g.reply = { err(401, "invalid_client") }
        try {
            auth().poll(code)
            fail()
        } catch (e: ApiException) {
            assertTrue(e.message!!.contains("설정"))
            assertFalse(e.message!!.contains(secret))
            assertFalse(e.message!!.contains("DEVICE-CODE-SECRET"))
            assertNull(e.cause)
        }
        g.reply = { throw IOException("connect https://oauth2.googleapis.com/token?x=$secret") }
        try {
            auth().poll(code)
            fail()
        } catch (e: NetworkException) {
            assertNull("기다리는 중 잠깐 끊김 — 화면은 계속 기다린다", e.cause)
        }
    }

    @Test
    fun accessTokenIsRenewedShortlyBeforeItEnds() {
        val a = auth()
        assertNull("로그인 안 했으면 없음", a.accessToken())
        store.t = Tokens("AT1", "RT1", now + 10 * 60_000)
        assertEquals("AT1", a.accessToken())
        assertTrue(g.reqs.isEmpty())

        store.t = Tokens("AT1", "RT1", now + 30_000)
        g.reply = { Http.Response(200, """{"access_token":"AT2","expires_in":3599,"scope":"https://www.googleapis.com/auth/youtube.readonly","token_type":"Bearer"}""") }
        assertEquals("1분 안에 끝나면 새로 받는다", "AT2", a.accessToken())
        assertEquals(Tokens("AT2", "RT1", now + 3_599_000), store.t)
        val r = g.reqs.single()
        assertEquals("refresh_token", r.form["grant_type"])
        assertEquals("RT1", r.form["refresh_token"])
        assertEquals(secret, r.form["client_secret"])

        // 구글이 새 refresh 토큰을 주면 그것으로 바꾼다
        store.t = Tokens("AT2", "RT1", now)
        g.reply = { Http.Response(200, """{"access_token":"AT3","expires_in":3599,"refresh_token":"RT2"}""") }
        assertEquals("AT3", a.accessToken())
        assertEquals("RT2", store.t?.refresh)
    }

    @Test
    fun aRejectedTokenIsRenewedOnRequest() {
        val a = auth()
        store.t = Tokens("AT1", "RT1", now + 30 * 60_000)
        a.invalidateAccess()
        g.reply = { Http.Response(200, """{"access_token":"AT2","expires_in":3599}""") }
        assertEquals("AT2", a.accessToken())
        assertEquals(1, g.reqs.size)
    }

    @Test
    fun removingAccessInTheGoogleAccountSignsOut() {
        val a = auth()
        store.t = Tokens("AT1", "RT1", now)
        g.reply = { err(400, "invalid_grant") }
        assertNull(a.accessToken())
        assertNull(store.t)
        assertFalse(a.signedIn)
    }

    @Test
    fun beingOfflineOrAGoogleHiccupKeepsTheSignIn() {
        val a = auth()
        store.t = Tokens("AT1", "RT1", now)
        g.reply = { throw IOException("offline") }
        try {
            a.accessToken()
            fail()
        } catch (e: NetworkException) {
            assertNotNull(store.t)
        }
        g.reply = { Http.Response(503, "") }
        try {
            a.accessToken()
            fail()
        } catch (e: ApiException) {
            assertTrue(a.signedIn)
        }
    }

    @Test
    fun signingOutDoesNotWaitForATokenRenewalAndItsResultIsDropped() {
        // 토큰을 새로 받는 중(느린 블루투스)에 로그아웃 → 화면(메인 스레드)이 그 응답을 기다리면 멈춘다
        store.t = Tokens("OLD", "RT", now + 10_000)   // 곧 끝남 → 새로 받는다
        val inFlight = CountDownLatch(1)
        val release = CountDownLatch(1)
        g.reply = {
            inFlight.countDown()
            release.await(5, TimeUnit.SECONDS)
            Http.Response(200, """{"access_token":"NEW","expires_in":3599}""")
        }
        val a = auth()
        var got: String? = "unset"
        val t = thread { got = a.accessToken() }
        assertTrue(inFlight.await(2, TimeUnit.SECONDS))
        val start = System.nanoTime()
        val forgotten = a.forget()
        assertTrue("로그아웃은 바로", System.nanoTime() - start < 500_000_000L)
        assertEquals("RT", forgotten?.refresh)
        release.countDown()
        t.join(3_000)
        assertNull("로그아웃 뒤 도착한 새 토큰은 버린다", got)
        assertNull("다시 로그인된 것처럼 되지 않는다", store.t)
        assertFalse(a.signedIn)
    }

    @Test
    fun slowDownKeepsAddingFiveSeconds() {
        // RFC 8628 3.5: slow_down 마다 간격을 5초씩 더 (되돌리지 않음)
        var interval = 5
        interval = TubeAuth.nextInterval(interval, Poll.SlowDown(10))
        assertEquals(10, interval)
        interval = TubeAuth.nextInterval(interval, Poll.SlowDown(10))
        assertEquals(15, interval)
        assertEquals("다른 대답엔 그대로", 15, TubeAuth.nextInterval(interval, Poll.Pending))
    }

    /**
     * 점검 C4: 인터넷이 없을 때 로그아웃하면 철회를 못 보낸 채 잊어 구글 계정엔 접근 권한이 남았다 →
     * 못 보낸 철회는 암호화해 두었다가 인터넷이 되면 다시 ([TubeRevokeWorker]). 이미 끊긴 토큰(400)은 끝난 것으로
     */
    @Test
    fun aRevokeThatCouldNotBeSentIsKeptAndSentLater() {
        val a = auth()
        store.t = Tokens("AT1", "RT1", now + 3_600_000)
        g.reply = { throw IOException("offline") }
        assertFalse("나중에 다시", a.signOut())
        assertNull(store.t)
        assertEquals(listOf("RT1"), store.revokes)
        assertTrue(a.hasPendingRevokes)
        g.reply = { Http.Response(503, "") }
        assertFalse("구글이 잠깐 안 되면 남겨 둔다", a.retryRevokes())
        assertEquals(listOf("RT1"), store.revokes)
        g.reply = { Http.Response(200, "") }
        assertTrue(a.retryRevokes())
        assertTrue(store.revokes.isEmpty())
        assertFalse(a.hasPendingRevokes)
        assertEquals("RT1", g.reqs.last().form["token"])
        store.revokes += "RT2"
        g.reply = { err(400, "invalid_token") }
        assertTrue("이미 끊긴 토큰은 끝난 것으로", a.retryRevokes())
        assertTrue(store.revokes.isEmpty())
    }

    @Test
    fun signingOutKeepsTheRevokeUntilItIsSentSoAnAppKillInBetweenLosesNothing() {
        val a = auth()
        store.t = Tokens("AT1", "RT1", now + 3_600_000)
        val t = a.forget()
        assertEquals("철회를 보내기 전에 먼저 적어 둔다", listOf("RT1"), store.revokes)
        g.reply = { Http.Response(200, "") }
        assertTrue(a.revoke(t))
        assertTrue(store.revokes.isEmpty())
    }

    /** 점검 C7: 승인을 기다리는 중 구글이 잠깐 안 되면(5xx·429) '로그인 오류'로 끝났다 → 천천히 계속 기다린다 */
    @Test
    fun googleHiccupsWhileWaitingKeepWaitingMoreSlowly() {
        val a = auth()
        g.reply = { Http.Response(503, "") }
        assertEquals(Poll.SlowDown(10), a.poll(code))
        g.reply = { Http.Response(500, "<html>error</html>") }
        assertEquals(Poll.SlowDown(10), a.poll(code))
        g.reply = { Http.Response(429, """{"error":"rate_limit_exceeded"}""") }
        assertEquals(Poll.SlowDown(10), a.poll(code))
        assertFalse(a.signedIn)
    }

    @Test
    fun signingInOverAnOldSignInRevokesTheOldGrant() {
        val a = auth()
        store.t = Tokens("AT0", "RT0", now + 3_600_000)
        g.reply = { r ->
            if (r.url.endsWith("/token")) Http.Response(200, """{"access_token":"AT1","expires_in":3599,"refresh_token":"RT1"}""") else Http.Response(200, "")
        }
        assertEquals(Poll.Granted, a.poll(code))
        assertEquals("RT1", store.t?.refresh)
        assertTrue("예전 로그인의 접근 권한은 철회", g.reqs.any { it.url.endsWith("/revoke") && it.form["token"] == "RT0" })
        assertTrue(store.revokes.isEmpty())
    }

    @Test
    fun aRenewalStartedBeforeANewSignInDoesNotOverwriteIt() {
        val a = auth()
        store.t = Tokens("AT0", "RT0", now + 30_000)   // 곧 끝남 → 새로 받는다
        val inRenew = CountDownLatch(1)
        val release = CountDownLatch(1)
        g.reply = { r ->
            when {
                r.form["grant_type"] == "refresh_token" -> {
                    inRenew.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    Http.Response(200, """{"access_token":"AT0b","expires_in":3599}""")
                }
                r.url.endsWith("/token") -> Http.Response(200, """{"access_token":"AT1","expires_in":3599,"refresh_token":"RT1"}""")
                else -> Http.Response(200, "")
            }
        }
        var got: String? = "x"
        val t = thread { got = a.accessToken() }
        assertTrue(inRenew.await(5, TimeUnit.SECONDS))
        assertEquals(Poll.Granted, a.poll(code))
        release.countDown()
        t.join(5_000)
        assertEquals("예전 로그인으로 새로 받은 것은 버리고 새 로그인의 것으로", "AT1", got)
        assertEquals(Tokens("AT1", "RT1", now + 3_599_000), store.t)
    }

    @Test
    fun signOutRevokesAtGoogleAndForgetsEvenOffline() {
        val a = auth()
        store.t = Tokens("AT1", "RT1", now + 3_600_000)
        g.reply = { Http.Response(200, "") }
        a.signOut()
        assertNull(store.t)
        val r = g.reqs.single()
        assertEquals("https://oauth2.googleapis.com/revoke", r.url)
        assertEquals("refresh 토큰을 철회하면 접근 권한이 통째로 없어진다", "RT1", r.form["token"])

        store.t = Tokens("AT1", "RT1", now + 3_600_000)
        g.reply = { throw IOException("offline") }
        a.signOut()
        assertNull("인터넷이 없어도 워치에선 지운다", store.t)
        a.signOut() // 로그인 안 했으면 아무것도 안 함
        assertEquals(2, g.reqs.size)
    }
}
