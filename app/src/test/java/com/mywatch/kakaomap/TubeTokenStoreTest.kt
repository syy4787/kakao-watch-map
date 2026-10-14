package com.mywatch.kakaomap

import android.content.Context
import com.mywatch.kakaomap.net.Http
import com.mywatch.kakaomap.tube.PrefsTokenStore
import com.mywatch.kakaomap.tube.TokenCipher
import com.mywatch.kakaomap.tube.Tokens
import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
