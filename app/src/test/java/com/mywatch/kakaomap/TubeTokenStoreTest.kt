package com.mywatch.kakaomap

import android.content.Context
import com.mywatch.kakaomap.net.Http
import com.mywatch.kakaomap.tube.PrefsTokenStore
import com.mywatch.kakaomap.tube.TokenCipher
import com.mywatch.kakaomap.tube.TokenStore
import com.mywatch.kakaomap.tube.Tokens
import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 로그인 토큰은 암호화해서 앱 안에만 (워치에선 안드로이드 키 저장소의 키로 — 여기선 같은 방식의 보통 AES 키로 대신).
 * 못 읽게 된 값(백업에서 되살려 키가 없어짐 등)은 버리고 다시 로그인. 그리고 구글에 보내는 POST(폼) 도우미
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TubeTokenStoreTest {

    /** 안드로이드 키 저장소와 같은 AES-GCM (Robolectric 엔 키 저장소가 없다) */
    private class JvmCipher(private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()) : TokenCipher {
        override fun encrypt(plain: ByteArray): ByteArray {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
            return iv + c.doFinal(plain)
        }

        override fun decrypt(blob: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 0, 12)) }
            return c.doFinal(blob, 12, blob.size - 12)
        }
    }

    private class BrokenCipher : TokenCipher {
        override fun encrypt(plain: ByteArray): ByteArray = throw java.security.KeyStoreException("no keystore")
        override fun decrypt(blob: ByteArray): ByteArray = throw java.security.KeyStoreException("no keystore")
    }

    private val prefs get() = RuntimeEnvironment.getApplication().getSharedPreferences("tube_account", Context.MODE_PRIVATE)

    @Test
    fun tokensAreKeptEncryptedAndReadBackAfterARestart() {
        val cipher = JvmCipher()
        PrefsTokenStore(prefs, cipher).save(Tokens("ACCESS-123", "REFRESH-456", 42))
        val stored = prefs.all.values.joinToString()
        assertFalse(stored.contains("ACCESS-123"))
        assertFalse(stored.contains("REFRESH-456"))
        assertEquals(Tokens("ACCESS-123", "REFRESH-456", 42), PrefsTokenStore(prefs, cipher).load())
    }

    @Test
    fun unreadableTokensAreDroppedSoYouSimplySignInAgain() {
        PrefsTokenStore(prefs, JvmCipher()).save(Tokens("A", "R", 1))
        // 다른 키(백업에서 되살린 워치 등)로는 못 읽는다
        assertNull(PrefsTokenStore(prefs, JvmCipher()).load())
        assertTrue("못 읽는 값은 지운다", prefs.all.isEmpty())
        prefs.edit().putString("tokens", "not base64 !!").commit()
        assertNull(PrefsTokenStore(prefs, JvmCipher()).load())
        assertTrue(prefs.all.isEmpty())
    }

    /**
     * 점검 C10: 키 저장소가 잠깐 안 될 때(부팅 직후 등)도 '못 읽는 값'으로 보고 지워 로그인이 풀렸다 →
     * 키가 바뀌었거나(AEADBadTag) 망가진 값만 지우고, 잠깐의 오류면 그대로 두었다가 다음에 다시 읽는다
     */
    @Test
    fun aKeystoreHiccupDoesNotSignYouOut() {
        val cipher = JvmCipher()
        PrefsTokenStore(prefs, cipher).save(Tokens("A", "R", 1))
        var hiccup = true
        val flaky = object : TokenCipher {
            override fun encrypt(plain: ByteArray) = cipher.encrypt(plain)
            override fun decrypt(blob: ByteArray): ByteArray =
                if (hiccup) throw java.security.ProviderException("Keystore operation failed") else cipher.decrypt(blob)
        }
        val s = PrefsTokenStore(prefs, flaky)
        assertNull("이번엔 못 읽음", s.load())
        assertFalse("그래도 지우지 않는다", prefs.all.isEmpty())
        hiccup = false
        assertEquals(Tokens("A", "R", 1), s.load())
    }

    @Test
    fun revokesThatWaitForTheInternetAreKeptEncrypted() {
        val cipher = JvmCipher()
        PrefsTokenStore(prefs, cipher).addRevoke("REFRESH-OLD-1")
        PrefsTokenStore(prefs, cipher).addRevoke("REFRESH-OLD-2")
        PrefsTokenStore(prefs, cipher).addRevoke("REFRESH-OLD-2")
        val stored = prefs.all.keys.joinToString() + prefs.all.values.joinToString()
        assertFalse(stored, stored.contains("REFRESH-OLD"))
        assertEquals(setOf("REFRESH-OLD-1", "REFRESH-OLD-2"), PrefsTokenStore(prefs, cipher).pendingRevokes().toSet())
        assertEquals(2, PrefsTokenStore(prefs, cipher).pendingRevokes().size)
        PrefsTokenStore(prefs, cipher).removeRevoke("REFRESH-OLD-1")
        assertEquals(listOf("REFRESH-OLD-2"), PrefsTokenStore(prefs, cipher).pendingRevokes())
        PrefsTokenStore(prefs, cipher).removeRevoke("REFRESH-OLD-2")
        assertTrue(PrefsTokenStore(prefs, cipher).pendingRevokes().isEmpty())
        assertTrue(prefs.all.isEmpty())
    }

    /**
     * 리뷰에서 찾음: 적어 둔 철회 목록을 잠깐 못 읽으면(키 저장소) 빈 목록으로 보고 새 것만 적어 덮어써서 예전 것을 잃었다 →
     * 철회는 하나씩 따로 적고 지운다(읽고 고쳐 쓰지 않음). 못 읽을 땐 '못 읽음'을 알린다 (빈 목록이 아니라)
     */
    @Test
    fun aKeystoreHiccupNeverLosesARevoke() {
        val cipher = JvmCipher()
        PrefsTokenStore(prefs, cipher).addRevoke("REFRESH-1")
        var hiccup = true
        val flaky = object : TokenCipher {
            override fun encrypt(plain: ByteArray) = cipher.encrypt(plain)
            override fun decrypt(blob: ByteArray): ByteArray =
                if (hiccup) throw java.security.ProviderException("Keystore operation failed") else cipher.decrypt(blob)
        }
        val s = PrefsTokenStore(prefs, flaky)
        try {
            s.pendingRevokes()
            fail("못 읽음을 알려야 한다")
        } catch (e: TokenStore.Unreadable) {
            // ok
        }
        s.addRevoke("REFRESH-2")   // 그 사이 또 로그아웃
        hiccup = false
        assertEquals(setOf("REFRESH-1", "REFRESH-2"), s.pendingRevokes().toSet())
    }

    @Test
    fun withoutAWorkingKeyNewRevokesStayInMemoryAndEarlierOnesAreKept() {
        val cipher = JvmCipher()
        PrefsTokenStore(prefs, cipher).addRevoke("REFRESH-1")
        val broken = PrefsTokenStore(prefs, BrokenCipher())
        broken.addRevoke("REFRESH-2")
        broken.removeRevoke("REFRESH-1")   // 보냈음 (지우는 데는 키가 필요 없다)
        assertEquals("이번에 켠 동안은 기억", listOf("REFRESH-2"), broken.pendingRevokes())
        assertTrue("암호화 못 한 것은 파일에 남기지 않는다", PrefsTokenStore(prefs, cipher).pendingRevokes().isEmpty())
    }

    @Test
    fun aRevokeThatCanNeverBeReadIsDropped() {
        PrefsTokenStore(prefs, JvmCipher()).addRevoke("REFRESH-1")
        // 다른 키(백업에서 되살린 워치 등)로는 영영 못 읽는다 — 지운다
        assertTrue(PrefsTokenStore(prefs, JvmCipher()).pendingRevokes().isEmpty())
        assertTrue(prefs.all.isEmpty())
    }

    @Test
    fun withoutAWorkingKeyTokensStayOnlyInMemory() {
        val s = PrefsTokenStore(prefs, BrokenCipher())
        s.save(Tokens("A", "R", 1))
        assertEquals("이번에 켠 동안은 로그인된 채로", Tokens("A", "R", 1), s.load())
        assertTrue("암호화 못 하면 파일에 남기지 않는다", prefs.all.isEmpty())
        assertNull(PrefsTokenStore(prefs, BrokenCipher()).load())
    }

    @Test
    fun clearForgetsEverything() {
        val cipher = JvmCipher()
        val s = PrefsTokenStore(prefs, cipher)
        s.save(Tokens("A", "R", 1))
        s.clear()
        assertNull(s.load())
        assertNull(PrefsTokenStore(prefs, cipher).load())
        assertTrue(prefs.all.isEmpty())
    }

    @Test
    fun postSendsAFormAndReadsTheErrorBody() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var method = ""
        var type = ""
        var body = ""
        server.createContext("/token") { ex ->
            method = ex.requestMethod
            type = ex.requestHeaders.getFirst("Content-Type").orEmpty()
            body = ex.requestBody.readBytes().decodeToString()
            val out = """{"error":"authorization_pending"}""".toByteArray()
            ex.sendResponseHeaders(428, out.size.toLong())
            ex.responseBody.use { it.write(out) }
        }
        server.start()
        try {
            val r = Http.post(
                "http://127.0.0.1:${server.address.port}/token",
                linkedMapOf("client_id" to "a b", "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"),
            )
            assertEquals(428, r.code)
            assertEquals("""{"error":"authorization_pending"}""", r.body)
            assertEquals("POST", method)
            assertEquals("application/x-www-form-urlencoded", type)
            assertEquals("client_id=a+b&grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code", body)
        } finally {
            server.stop(0)
        }
    }
}
