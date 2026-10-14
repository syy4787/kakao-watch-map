package com.mywatch.kakaomap.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.VisibleForTesting
import androidx.core.net.toUri
import androidx.core.view.ViewConfigurationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.mywatch.kakaomap.BuildConfig
import com.mywatch.kakaomap.R
import com.mywatch.kakaomap.data.ErrorLog
import com.mywatch.kakaomap.tube.PhoneOpener
import com.mywatch.kakaomap.tube.PlayerAudio
import com.mywatch.kakaomap.tube.PlayerGuard
import com.mywatch.kakaomap.tube.PlayerPresence
import com.mywatch.kakaomap.tube.PlayerRecovery
import com.mywatch.kakaomap.tube.WatchPlayer
import kotlinx.coroutines.launch
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.MediaSession
import org.mozilla.geckoview.ScreenLength
import org.mozilla.geckoview.WebRequestError

/**
 * 앱 안에서 유튜브 재생 — 워치엔 시스템 WebView 가 없어 앱에 넣은 브라우저 엔진(GeckoView)으로 재생 화면(web/tube, GitHub Pages)을 연다.
 * 재생은 그 화면 안의 유튜브 공식 플레이어(임베드)가 한다. 우리 것은 플레이어 위에 겹치지 않는다.
 *  · 따로 된 프로세스(:player) — 엔진이 죽어도 앱 본체(길 안내 등)는 산다. 엔진이 죽으면 [PlayerGuard] 에 남기고 인터넷 앱으로 이어서
 *  · 화면이 꺼지거나 다른 앱으로 가면 setActive(false) → 재생 화면이 '숨김'을 보고 멈춘다 (보이지 않는 재생 금지)
 *  · 재생 중엔 화면을 켜 둔다 (엔진의 미디어 알림). 소리 자동 재생만 허용 — 방금 고른 동영상을 바로 틀려고
 *  · 링크는 [PlayerNav] 가 정한다: 목록으로·폰에서 이어 보기는 앱이, 바깥 링크는 폰에서
 *  · 베젤·크라운 → 재생 화면 스크롤
 */
class TubePlayerActivity : ComponentActivity() {

    private var view: GeckoView? = null
    private var session: GeckoSession? = null
    private lateinit var errorBox: View
    private lateinit var errorText: TextView
    private val guard by lazy { PlayerGuard.of(this) }
    private val presence by lazy { PlayerPresence.of(this) }
    private val handler = Handler(Looper.getMainLooper())
    private var pageBase = ""
    private var url = ""
    private var painted = false
    private val recovery = PlayerRecovery()
    /** 지금 재생 중인 미디어 — 이어폰이 끊기거나 전화가 오면 이것을 멈춘다 */
    private var mediaNow: MediaSession? = null
    private val audio by lazy { PlayerAudio(this) { mediaNow?.pause() } }
    /** 화면이 안 보일 때 엔진이 꺼졌음 — 다시 보일 때 연다 */
    private var reopenOnStart = false

    private val visible: Boolean get() = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    private val heartbeat = object : Runnable {
        override fun run() {
            presence.shown()
            handler.postDelayed(this, PlayerPresence.HEARTBEAT_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tube_player)
        errorBox = findViewById(R.id.player_error)
        errorText = findViewById(R.id.player_error_text)
        findViewById<View>(R.id.player_retry).setOnClickListener { retry() }
        findViewById<View>(R.id.player_close).setOnClickListener { finish() }

        val base = WatchPlayer.pageBase()
        val target = intent?.getStringExtra(EXTRA_URL)
        if (base == null || target == null || !target.startsWith(base)) {
            finish()
            return
        }
        pageBase = base
        url = target
        guard.starting()
        val runtime = try {
            engine(applicationContext)
        } catch (t: Throwable) {
            engineFailed("엔진을 띄우지 못함", t)
            return
        }
        try {
            val v = GeckoView(this).apply {
                isFocusable = true
                isFocusableInTouchMode = true
                coverUntilFirstPaint(Color.BLACK)
                setOnGenericMotionListener { _, e -> rotary(e) }
            }
            findViewById<FrameLayout>(R.id.player_host).addView(v, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            view = v
            openSession(runtime)
        } catch (t: Throwable) {
            engineFailed("재생 화면을 열지 못함", t)
        }
    }

    private fun openSession(runtime: GeckoRuntime) {
        val s = GeckoSession(
            GeckoSessionSettings.Builder()
                .usePrivateMode(false)
                // 광고·플레이어 부품을 막지 않는다 (유튜브 정책: 광고를 막거나 바꾸지 않기)
                .useTrackingProtection(false)
                // 화면이 꺼지면 소리도 멈춘다 (보이지 않는 재생 금지)
                .suspendMediaWhenInactive(true)
                .allowJavascript(true)
                .build(),
        )
        s.navigationDelegate = navigation
        s.contentDelegate = content
        s.progressDelegate = progress
        s.permissionDelegate = permissions
        s.mediaSessionDelegate = media
        s.open(runtime)
        view?.setSession(s)
        session = s
        s.loadUri(url)
        view?.requestFocus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val target = intent.getStringExtra(EXTRA_URL) ?: return
        if (pageBase.isEmpty() || !target.startsWith(pageBase)) return
        url = target
        hideError()
        // 같은 재생 화면에 주소의 # 만 바뀜 → 화면이 새 동영상을 연다 (방금 고른 것이라 바로 틂)
        session?.loadUri(target)
    }

    override fun onStart() {
        super.onStart()
        if (reopenOnStart) {
            reopenOnStart = false
            reopen()
        }
        session?.setActive(true)
        session?.setFocused(true)
        handler.removeCallbacks(heartbeat)
        handler.post(heartbeat)
    }

    override fun onStop() {
        handler.removeCallbacks(heartbeat)
        presence.hidden()
        // 그리기 전에 화면을 떠남(뒤로·홈) — 엔진이 죽은 게 아니다
        if (!painted) guard.stopped()
        session?.setFocused(false)
        session?.setActive(false)
        keepScreenOn(false)
        audio.stopped()
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching {
            view?.releaseSession()
            session?.close()
        }
        session = null
        super.onDestroy()
    }

    /** 베젤·크라운을 돌리면 재생 화면을 위아래로 */
    private fun rotary(e: MotionEvent): Boolean {
        if (e.action != MotionEvent.ACTION_SCROLL || !e.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) return false
        val factor = ViewConfigurationCompat.getScaledVerticalScrollFactor(ViewConfiguration.get(this), this)
        val dy = -e.getAxisValue(MotionEvent.AXIS_SCROLL) * factor
        view?.panZoomController?.scrollBy(ScreenLength.zero(), ScreenLength.fromPixels(dy.toDouble()))
        return true
    }

    private fun keepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun showError(text: Int) {
        errorText.setText(text)
        errorBox.visibility = View.VISIBLE
        findViewById<View>(R.id.player_retry).requestFocus()
    }

    private fun hideError() {
        errorBox.visibility = View.GONE
    }

    private fun retry() {
        hideError()
        recovery.retried()
        val s = session
        if (s == null || !s.isOpen) {
            try {
                openSession(engine(applicationContext))
            } catch (t: Throwable) {
                engineFailed("다시 열지 못함", t)
            }
        } else {
            s.loadUri(url)
        }
    }

    /** 엔진의 화면 프로세스가 꺼진 뒤 다시 연다 — 보이는지에 맞게 활성(안 보이면 재생 화면이 '숨김'을 보고 틀지 않는다) */
    private fun reopen() {
        val s = session ?: return
        try {
            if (!s.isOpen) s.open(engine(applicationContext))
            s.setActive(visible)
            s.setFocused(visible)
            s.loadUri(url)
        } catch (t: Throwable) {
            engineFailed("재생 엔진을 다시 열지 못함", t)
        }
    }

    private fun apply(d: PlayerRecovery.Do, where: String) {
        when (d) {
            PlayerRecovery.Do.REOPEN -> reopen()
            PlayerRecovery.Do.REOPEN_WHEN_VISIBLE -> reopenOnStart = true
            PlayerRecovery.Do.SHOW_ERROR -> showError(R.string.player_load_failed)
            PlayerRecovery.Do.FAIL_TO_BROWSER -> engineFailed(where, null)
            PlayerRecovery.Do.FAIL_QUIETLY -> {
                // 안 보이는 동안 죽음: 기억만 하고(다음엔 인터넷 앱으로) 닫는다 — 다른 앱을 띄우지 않는다
                guard.failed()
                ErrorLog.record("유튜브·앱 안 재생", "$where (화면이 꺼진 동안)")
                finish()
            }
        }
    }

    /** 엔진이 죽거나 못 뜸 → 기억하고(한동안 인터넷 앱으로), 지금 고른 것은 인터넷 앱으로 이어서. 그것도 없으면 알리고 목록으로 */
    private fun engineFailed(where: String, t: Throwable?) {
        guard.failed()
        ErrorLog.record("유튜브·앱 안 재생", where + (t?.let { " (${it.javaClass.simpleName})" } ?: ""))
        val browser = url.takeIf { it.isNotEmpty() }?.let { WatchPlayer.browserIntent(this, it.replace("&app=1", "")) }
        if (browser != null && runCatching { startActivity(browser) }.isSuccess) {
            Toast.makeText(this, R.string.player_engine_to_browser, Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, R.string.player_engine_failed, Toast.LENGTH_LONG).show()
        }
        finish()
    }

    private fun act(d: PlayerNav.Decision): GeckoResult<AllowOrDeny> = when (d) {
        PlayerNav.Decision.Allow -> GeckoResult.allow()
        PlayerNav.Decision.Block -> GeckoResult.deny()
        is PlayerNav.Decision.App -> {
            handler.post { openApp(d.link) }
            GeckoResult.deny()
        }
        is PlayerNav.Decision.Phone -> {
            handler.post { openOnPhone(d.url) }
            GeckoResult.deny()
        }
    }

    /**
     * 목록으로 → 이 화면을 닫는다. 폰에서 이어 보기 → 목록 화면(앱 본체)이 받아 폰에서 연다 —
     * 같은 작업 안에서 이 화면을 걷어 내고 아래의 목록 화면에 건넨다 (새 작업을 만들지 않음)
     */
    @SuppressLint("WearRecents")
    private fun openApp(link: String) {
        if (link != "kakaowatch://tube") {
            val i = Intent(Intent.ACTION_VIEW, link.toUri()).setClass(this, TubeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            runCatching { startActivity(i) }.onFailure { ErrorLog.record("유튜브·앱 안 재생", it) }
        }
        finish()
    }

    private fun openOnPhone(target: String) {
        lifecycleScope.launch {
            val r = PhoneOpener(this@TubePlayerActivity).open(target)
            val msg = when (r) {
                PhoneOpener.Result.OPENED -> R.string.player_opened_on_phone
                PhoneOpener.Result.NO_PHONE -> R.string.player_no_phone
                PhoneOpener.Result.FAILED -> R.string.player_phone_failed
            }
            Toast.makeText(this@TubePlayerActivity, msg, Toast.LENGTH_SHORT).show()
        }
    }

    /** 이동 판단 중 예상 못 한 예외가 엔진 콜백 밖으로 나가면 재생 화면 프로세스가 죽는다 — 그때는 막고 기록만 */
    private fun decide(what: String, d: () -> PlayerNav.Decision): PlayerNav.Decision =
        runCatching(d).getOrElse {
            ErrorLog.record("유튜브·앱 안 재생", "$what 판단 실패 (${it.javaClass.simpleName})")
            PlayerNav.Decision.Block
        }

    private val navigation = object : GeckoSession.NavigationDelegate {
        override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny> =
            act(decide("이동") { PlayerNav.top(request.uri, pageBase, request.hasUserGesture) })

        override fun onSubframeLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny> =
            if (decide("플레이어 안 이동") { PlayerNav.frame(request.uri) } == PlayerNav.Decision.Allow) GeckoResult.allow() else GeckoResult.deny()

        override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
            act(decide("새 창") { PlayerNav.newWindow(uri, pageBase) })
            return null
        }

        override fun onLoadError(session: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String>? {
            if (uri != null && uri.startsWith(pageBase)) {
                handler.post {
                    showError(if (error.category == WebRequestError.ERROR_CATEGORY_NETWORK) R.string.player_offline else R.string.player_load_failed)
                }
            }
            return null
        }
    }

    private val progress = object : GeckoSession.ProgressDelegate {
        override fun onPageStart(session: GeckoSession, url: String) {
            if (url.startsWith(pageBase)) hideError()
        }
    }

    private val content = object : GeckoSession.ContentDelegate {
        override fun onFirstContentfulPaint(session: GeckoSession) {
            painted = true
            guard.started()
        }

        override fun onCrash(session: GeckoSession) {
            handler.post { if (!isFinishing && !isDestroyed) apply(recovery.onCrash(visible), "재생 엔진이 멈춤") }
        }

        /** 메모리가 모자라 엔진의 화면 프로세스가 꺼짐 — 보이면 다시 열고, 안 보이면 다시 보일 때 ([PlayerRecovery]) */
        override fun onKill(session: GeckoSession) {
            handler.post { if (!isFinishing && !isDestroyed) apply(recovery.onKill(visible), "재생 엔진이 꺼짐") }
        }

        override fun onCloseRequest(session: GeckoSession) {
            finish()
        }
    }

    /** 소리 자동 재생만 허용 (재생 화면은 방금 고른 동영상만, 보일 때만 튼다) — 위치·알림·저장소 등은 거부 */
    private val permissions = object : GeckoSession.PermissionDelegate {
        override fun onContentPermissionRequest(session: GeckoSession, perm: ContentPermission): GeckoResult<Int> {
            val autoplay = perm.permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE ||
                perm.permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE
            return GeckoResult.fromValue(if (autoplay) ContentPermission.VALUE_ALLOW else ContentPermission.VALUE_DENY)
        }

        override fun onAndroidPermissionsRequest(
            session: GeckoSession,
            permissions: Array<out String>?,
            callback: GeckoSession.PermissionDelegate.Callback,
        ) {
            callback.reject()
        }

        override fun onMediaPermissionRequest(
            session: GeckoSession,
            uri: String,
            video: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
            audio: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
            callback: GeckoSession.PermissionDelegate.MediaCallback,
        ) {
            callback.reject()
        }
    }

    /**
     * 재생 중엔 화면을 켜 두고 오디오 포커스를 받는다 (다른 음악 앱과 같이 나오지 않게, 이어폰이 끊기면 멈추게 — [PlayerAudio]).
     * 멈추면 둘 다 놓는다
     */
    private val media = object : MediaSession.Delegate {
        override fun onActivated(session: GeckoSession, mediaSession: MediaSession) {
            mediaNow = mediaSession
        }

        override fun onPlay(session: GeckoSession, mediaSession: MediaSession) {
            mediaNow = mediaSession
            keepScreenOn(true)
            audio.playing()
        }

        override fun onPause(session: GeckoSession, mediaSession: MediaSession) = idle()
        override fun onStop(session: GeckoSession, mediaSession: MediaSession) = idle()
        override fun onDeactivated(session: GeckoSession, mediaSession: MediaSession) {
            idle()
            if (mediaNow === mediaSession) mediaNow = null
        }

        private fun idle() {
            keepScreenOn(false)
            audio.stopped()
        }
    }

    /** 프로세스마다 하나인 엔진 (만들기만 해 두면 다음 재생이 빠르다) */
    private object Engine {
        private var runtime: GeckoRuntime? = null

        fun runtime(context: Context): GeckoRuntime = runtime ?: GeckoRuntime.create(
            context,
            GeckoRuntimeSettings.Builder()
                .consoleOutput(BuildConfig.DEBUG)
                .remoteDebuggingEnabled(false)
                .aboutConfigEnabled(false)
                .javaScriptEnabled(true)
                .loginAutofillEnabled(false)
                .doubleTapZoomingEnabled(false)
                .inputAutoZoomEnabled(false)
                .preferredColorScheme(GeckoRuntimeSettings.COLOR_SCHEME_DARK)
                .build(),
        ).also { runtime = it }
    }

    companion object {
        const val EXTRA_URL = "url"

        /** 엔진 만들기 (시험에선 못 뜨는 엔진으로 바꿔 끼운다) */
        @VisibleForTesting
        internal var engine: (Context) -> GeckoRuntime = { Engine.runtime(it) }

        fun intent(context: Context, url: String): Intent =
            Intent(context, TubePlayerActivity::class.java).putExtra(EXTRA_URL, url)
    }
}
