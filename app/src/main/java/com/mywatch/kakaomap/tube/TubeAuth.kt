package com.mywatch.kakaomap.tube

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import com.mywatch.kakaomap.BuildConfig
import com.mywatch.kakaomap.data.ErrorLog
import com.mywatch.kakaomap.net.ApiException
import com.mywatch.kakaomap.net.Http
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 폰에서 넣을 코드. [userCode] 는 대소문자까지 받은 그대로 보여 준다 (구글 안내) */
data class DeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val expiresAtMs: Long,
    val intervalSec: Int,
)

/** 승인을 기다린 결과 */
sealed interface Poll {
    /** 아직 승인 안 함 — [DeviceCode.intervalSec] 뒤 다시 */
    data object Pending : Poll
    /** 너무 자주 물음 — [intervalSec] 초 간격으로 */
    data class SlowDown(val intervalSec: Int) : Poll
    /** 거절함 (또는 유튜브 보기 권한을 빼고 줌) */
    data object Denied : Poll
    /** 코드 시간이 지남·이미 쓴 코드 — 새 코드로 다시 */
    data object Expired : Poll
    /** 로그인됨 */
    data object Granted : Poll
}

data class Tokens(val access: String, val refresh: String, val expiresAtMs: Long)

interface TokenStore {
    fun load(): Tokens?
    fun save(t: Tokens)
    fun clear()
}

/**
 * 유튜브 로그인 — 구글 OAuth 2.0 기기 흐름('TV 및 입력 제한 기기' 클라이언트). 비밀번호는 워치에 넣지 않는다:
 * 워치가 코드를 보여 주면 폰(또는 PC)의 google.com/device 에서 코드를 넣고 승인한다.
 * 권한은 유튜브 '보기'(youtube.readonly) 하나만 — 구독·재생목록·좋아요를 보기만 하고 바꾸지 않는다.
 * 네트워크([post])·저장소([store])·시계([now])는 밖에서 넣어 준다 (단위 테스트로 검증). 백그라운드 스레드에서 부른다.
 * 오류 글에는 비밀값·토큰·코드를 넣지 않고 원인 예외도 달지 않는다.
 */
class TubeAuth(
    private val clientId: String,
    private val clientSecret: String,
    private val post: (url: String, form: Map<String, String>) -> Http.Response,
    private val store: TokenStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** 이 빌드에 로그인용 구글 클라이언트가 들어 있음 (없으면 '로그인 준비 중') */
    val configured: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()

    /** 저장소를 만지는 동안만 잠깐 (네트워크를 기다리는 동안엔 잡지 않는다 — 로그아웃이 화면을 멈추지 않게) */
    private val lock = Any()
    /** 토큰 새로 받기는 한 번에 하나 */
    private val renewing = Any()
    /** 로그아웃할 때마다 바뀐다 — 그 전에 시작한 새로 받기의 결과는 버린다 */
    private var generation = 0

    val signedIn: Boolean get() = synchronized(lock) { store.load() != null }

    /** 폰에서 넣을 코드를 받는다 */
    fun start(): DeviceCode {
        if (!configured) throw ApiException("유튜브 로그인은 아직 준비 중이에요")
        val res = send(DEVICE_CODE, linkedMapOf("client_id" to clientId.trim(), "scope" to SCOPE))
        val json = json(res.body)
        if (res.code !in 200..299) {
            throw ApiException(
                if (res.code == 403 || json?.optString("error_code") == "rate_limit_exceeded") "로그인 요청이 몰렸어요 — 잠시 뒤 다시 해 주세요"
                else problem(json?.optString("error"), res.code),
            )
        }
        val device = json?.optString("device_code").orEmpty()
        val user = json?.optString("user_code").orEmpty()
        val url = json?.optString("verification_url").orEmpty().ifBlank { json?.optString("verification_uri").orEmpty() }
        val expires = json?.optLong("expires_in", 0) ?: 0
        if (device.isBlank() || user.isBlank() || !url.startsWith("http") || expires <= 0) throw ApiException("구글 응답을 읽지 못했어요")
        return DeviceCode(device, user, url, now() + expires * 1000, (json?.optInt("interval", 5) ?: 5).coerceIn(1, 60))
    }

    /** 폰에서 승인했는지 한 번 묻는다. 끊기면 [NetworkException] (화면은 계속 기다리면 된다) */
    fun poll(code: DeviceCode): Poll {
        if (now() >= code.expiresAtMs) return Poll.Expired
        val res = send(TOKEN, linkedMapOf(
            "client_id" to clientId.trim(), "client_secret" to clientSecret.trim(),
            "device_code" to code.deviceCode, "grant_type" to DEVICE_GRANT,
        ))
        val json = json(res.body)
        if (res.code in 200..299) {
            val access = json?.optString("access_token").orEmpty()
            val refresh = json?.optString("refresh_token").orEmpty()
            if (access.isBlank() || refresh.isBlank()) throw ApiException("구글 응답을 읽지 못했어요")
            // 승인 화면에서 유튜브 권한을 빼고 줬으면 로그인하지 않는다 (scope 가 없으면 요청한 그대로 준 것)
            val scope = json?.optString("scope").orEmpty()
            if (scope.isNotBlank() && SCOPE !in scope.split(' ')) return Poll.Denied
            synchronized(lock) { store.save(Tokens(access, refresh, now() + expiresMs(json))) }
            return Poll.Granted
        }
        return when (val error = json?.optString("error")) {
            "authorization_pending" -> Poll.Pending
            "slow_down" -> Poll.SlowDown(code.intervalSec + 5)
            "access_denied" -> Poll.Denied
            "expired_token", "invalid_grant" -> Poll.Expired
            else -> throw ApiException(problem(error, res.code))
        }
    }

    /**
     * 유튜브에 보낼 토큰. 1분 안에 끝나면 새로 받는다. 로그인 안 했거나 계정에서 앱 접근을 끊었으면(invalid_grant) null —
     * 그땐 로그아웃된 것으로. 인터넷이 안 되거나 구글이 잠깐 안 되면 예외 (로그인은 그대로)
     */
    fun accessToken(): String? {
        val (first, gen) = synchronized(lock) { (store.load() ?: return null) to generation }
        if (first.expiresAtMs - now() > RENEW_BEFORE_MS) return first.access
        synchronized(renewing) {
            // 기다리는 사이 다른 스레드가 새로 받았거나, 로그아웃했을 수 있다
            val t = synchronized(lock) { if (gen != generation) return null else store.load() } ?: return null
            if (t.expiresAtMs - now() > RENEW_BEFORE_MS) return t.access
            val res = send(TOKEN, linkedMapOf(
                "client_id" to clientId.trim(), "client_secret" to clientSecret.trim(),
                "refresh_token" to t.refresh, "grant_type" to "refresh_token",
            ))
            val json = json(res.body)
            val access = json?.optString("access_token").orEmpty()
            synchronized(lock) {
                if (gen != generation) return null   // 받는 사이 로그아웃 → 새 토큰은 버린다
                if (res.code in 200..299 && access.isNotBlank()) {
                    val renewed = Tokens(access, json?.optString("refresh_token").orEmpty().ifBlank { t.refresh }, now() + expiresMs(json))
                    store.save(renewed)
                    return renewed.access
                }
                if (json?.optString("error") in SIGNED_OUT) {
                    store.clear()
                    generation++
                    return null
                }
            }
            throw ApiException("로그인을 새로 고치지 못했어요 (${res.code})")
        }
    }

    /** 유튜브가 토큰을 거절함(401) — 다음 [accessToken] 에서 새로 받게 */
    fun invalidateAccess() = synchronized(lock) {
        store.load()?.let { store.save(it.copy(expiresAtMs = 0)) }
    }

    /** 로그아웃: 워치에서 먼저 지우고(인터넷이 없어도), 구글에 접근 권한 철회를 보낸다 (정책 III.D.2) */
    fun signOut() = revoke(forget())

    /**
     * 워치에서 토큰을 지운다 (바로 — 화면이 곧장 로그아웃된 모습이 되게, 새로 받는 중이어도 기다리지 않음).
     * 지운 토큰을 돌려준다 → [revoke]
     */
    fun forget(): Tokens? = synchronized(lock) {
        generation++
        store.load().also { store.clear() }
    }

    /** 구글에 접근 권한 철회 (네트워크 — 백그라운드에서). 실패해도 그만 */
    fun revoke(t: Tokens?) {
        if (t == null) return
        try {
            post(REVOKE, mapOf("token" to t.refresh))
        } catch (e: Exception) {
            // 철회는 최선만 — 남은 권한은 구글 계정 > 보안 > 서드 파티 연결에서 끊을 수 있다 (개인정보 처리방침 안내)
        }
    }

    private fun send(url: String, form: Map<String, String>): Http.Response = try {
        post(url, form)
    } catch (e: IOException) {
        throw NetworkException()
    }

    private fun json(body: String): JSONObject? = try {
        JSONObject(body)
    } catch (e: JSONException) {
        null
    }

    private fun expiresMs(json: JSONObject?): Long = (json?.optLong("expires_in", 3600) ?: 3600).coerceIn(60, 86_400) * 1000

    private fun problem(error: String?, code: Int): String = when (error) {
        "invalid_client", "unauthorized_client" -> "유튜브 로그인 설정(구글 클라이언트)이 올바르지 않아요"
        "admin_policy_enforced" -> "이 구글 계정은 관리자가 이 앱을 막았어요"
        "org_internal" -> "이 앱은 지정된 조직 계정만 로그인하게 되어 있어요"
        else -> "구글 로그인 오류 ($code)"
    }

    companion object {
        /** 다음에 묻기까지 (초): slow_down 이면 5초씩 더 늘린다 (RFC 8628 3.5 — 되돌리지 않음) */
        fun nextInterval(current: Int, p: Poll): Int = if (p is Poll.SlowDown) current + SLOW_DOWN_STEP_SEC else current

        private const val SLOW_DOWN_STEP_SEC = 5
        const val SCOPE = "https://www.googleapis.com/auth/youtube.readonly"
        const val DEVICE_CODE = "https://oauth2.googleapis.com/device/code"
        const val TOKEN = "https://oauth2.googleapis.com/token"
        const val REVOKE = "https://oauth2.googleapis.com/revoke"
        private const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
        private const val RENEW_BEFORE_MS = 60_000L
        /** refresh 가 이렇게 거절되면 로그인이 끝난 것 (계정에서 끊음·오래 안 씀·클라이언트를 지움) */
        private val SIGNED_OUT = setOf("invalid_grant", "invalid_client", "unauthorized_client")
    }
}

/** 토큰을 암호화하는 방법 — 워치에선 [KeystoreCipher] */
interface TokenCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}

/**
 * 토큰을 암호화해서 앱 저장소에. 못 읽는 값(백업에서 되살려 키가 없어짐 등)은 지우고 다시 로그인하게.
 * 암호화가 안 되면 파일에 남기지 않고 이번에 켠 동안만 기억한다
 */
class PrefsTokenStore(private val prefs: SharedPreferences, private val cipher: TokenCipher) : TokenStore {
    private var memory: Tokens? = null

    @Synchronized
    override fun load(): Tokens? {
        memory?.let { return it }
        val blob = prefs.getString(KEY, null) ?: return null
        val t = try {
            decode(String(cipher.decrypt(Base64.decode(blob, Base64.NO_WRAP)), Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }
        if (t == null) prefs.edit { remove(KEY) }
        memory = t
        return t
    }

    @Synchronized
    override fun save(t: Tokens) {
        memory = t
        try {
            val blob = cipher.encrypt(encode(t).toByteArray(Charsets.UTF_8))
            prefs.edit { putString(KEY, Base64.encodeToString(blob, Base64.NO_WRAP)) }
        } catch (e: Exception) {
            prefs.edit { remove(KEY) }
            ErrorLog.record("유튜브·로그인 저장", "토큰을 암호화하지 못해 이번에 켠 동안만 로그인: ${e.javaClass.simpleName}")
        }
    }

    @Synchronized
    override fun clear() {
        memory = null
        prefs.edit { remove(KEY) }
    }

    private fun encode(t: Tokens) = JSONObject().put("a", t.access).put("r", t.refresh).put("e", t.expiresAtMs).toString()

    private fun decode(s: String): Tokens? {
        val o = JSONObject(s)
        val a = o.optString("a")
        val r = o.optString("r")
        return if (a.isBlank() || r.isBlank()) null else Tokens(a, r, o.optLong("e"))
    }

    private companion object {
        const val KEY = "tokens"
    }
}

/** 안드로이드 키 저장소의 AES-256 키(밖으로 꺼낼 수 없음)로 AES-GCM. 앞 12바이트 = IV */
class KeystoreCipher(private val alias: String = "tube_account") : TokenCipher {

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return c.iv + c.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, 12)) }
        return c.doFinal(blob, 12, blob.size - 12)
    }
}

/** 앱 전체에서 하나인 로그인 (구글 클라이언트는 빌드에 들어감 — GitHub Secrets) */
object TubeAccount {
    @Volatile private var instance: TubeAuth? = null

    /** 테스트·시뮬레이션에서 진짜 대신 */
    @VisibleForTesting
    @Volatile internal var override: TubeAuth? = null

    fun auth(context: Context): TubeAuth = override ?: instance ?: synchronized(this) {
        instance ?: TubeAuth(
            clientId = BuildConfig.YOUTUBE_OAUTH_CLIENT_ID,
            clientSecret = BuildConfig.YOUTUBE_OAUTH_CLIENT_SECRET,
            post = { url, form -> Http.post(url, form) },
            store = PrefsTokenStore(
                context.applicationContext.getSharedPreferences("tube_account", Context.MODE_PRIVATE),
                KeystoreCipher(),
            ),
        ).also { instance = it }
    }
}
