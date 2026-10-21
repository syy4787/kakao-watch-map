package com.mywatch.kakaomap.update

import android.app.ActivityManager
import android.app.RemoteInput
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.wear.input.RemoteInputIntentHelper
import com.kakao.vectormap.KakaoMapSdk
import com.mywatch.kakaomap.R
import com.mywatch.kakaomap.data.ErrorLog
import com.mywatch.kakaomap.data.ErrorLogFormat
import com.mywatch.kakaomap.nav.NavigationService
import com.mywatch.kakaomap.net.TagoApi
import com.mywatch.kakaomap.tube.PlayerPresence
import com.mywatch.kakaomap.ui.Row
import com.mywatch.kakaomap.ui.WearList
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 앱 정보 · 업데이트 화면 */
class UpdateActivity : ComponentActivity() {

    private lateinit var ui: WearList
    private var busy: String? = null
    /** '업데이트' 버튼으로 열었음 — 열자마자 확인하고 새 버전이면 바로 받아 설치 (한 번만) */
    private var updateNow = false

    private val ownerInput = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val text = res.data?.let { RemoteInput.getResultsFromIntent(it)?.getCharSequence(KEY_OWNER) }?.toString()
        if (!text.isNullOrBlank()) {
            UpdateManager.setOwner(this, text)
            check()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        ui = WearList(this, ::onRow)
        // 화면을 돌려 다시 만들어진 경우엔 또 시작하지 않는다
        updateNow = savedInstanceState == null && intent.getBooleanExtra(EXTRA_UPDATE_NOW, false)
    }

    /** 설치 결과가 오면(실패·취소·확인 대기) '설치 중…' 을 풀고 다시 그린다 */
    private val installListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if (busy?.startsWith(INSTALLING) == true) busy = null
        if (!isFinishing) render()
    }

    override fun onResume() {
        super.onResume()
        UpdateManager.prefsForListening(this).registerOnSharedPreferenceChangeListener(installListener)
        // 설치에 성공하면 앱이 새로 시작되므로, 이 화면으로 돌아왔다면 설치는 끝났거나(실패·취소) 확인을 기다리는 중
        if (busy?.startsWith(INSTALLING) == true) busy = null
        render()
        if (busy != null || UpdateManager.repo(this) == null) return
        if (updateNow) {
            // '업데이트' 버튼: 지금 바로 확인 → 새 버전이면 그대로 받아 설치
            updateNow = false
            check(thenInstall = true)
            return
        }
        // 열면 바로 새 버전을 확인한다 — 예전 확인 결과만 보여 주면 새 버전이 나왔어도 '최신 버전이에요'로 보이고
        // '지금 업데이트' 버튼도 안 나온다 (방금 확인했으면 다시 묻지 않음)
        if (System.currentTimeMillis() - UpdateManager.lastCheckAt(this) >= SCREEN_CHECK_EVERY_MS) check()
    }

    override fun onPause() {
        UpdateManager.prefsForListening(this).unregisterOnSharedPreferenceChangeListener(installListener)
        super.onPause()
    }

    private fun render() {
        val c = this
        // 확인·내려받는 동안엔 화면을 켜 둔다 — 밖에서 폰 블루투스로 13MB 를 받는 데 몇 분 걸릴 수 있는데,
        // 그사이 화면이 꺼지면 받기가 멈출 수 있다
        if (busy != null) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val rows = mutableListOf<Row>(
            Row.Header(
                getString(R.string.app_name),
                "버전 ${UpdateManager.currentVersionName(c)} (${UpdateManager.currentVersionCode(c)})",
            )
        )
        busy?.let { rows += Row.Note(it) }
        val repo = UpdateManager.repo(c)
        if (repo == null) {
            rows += Row.Note("자동 업데이트를 켜려면 GitHub 아이디를 입력하세요 (저장소 이름: ${UpdateManager.REPO_NAME})")
            rows += Row.Action(ACTION_OWNER, R.drawable.ic_edit, "GitHub 아이디 입력", accent = true)
        } else {
            val latest = UpdateManager.latest(c)
            val status = when {
                UpdateManager.hasUpdate(c) -> "새 버전 ${latest?.versionName} (${latest?.versionCode})" +
                    (latest?.notes?.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: "")
                UpdateManager.lastCheckAt(c) > 0 -> "최신 버전이에요"
                else -> "아직 확인하지 않았어요"
            }
            rows += Row.Note(status)
            if (UpdateManager.hasUpdate(c)) {
                // 얼마나 받는지 — 깔린 앱으로 바뀐 부분만 받을 수 있으면(작은 업데이트) 몇 MB, 아니면 전체(재생 엔진이 들어 있어 큼 —
                // 워치 Wi-Fi 가 있으면 그쪽으로 받음)
                val size = latest?.let { sizeLabel(UpdateManager.knownDownloadSize(c, it), UpdateManager.knownSmallUpdate(c, it)) }
                rows += Row.Action(ACTION_INSTALL, R.drawable.ic_download, "지금 업데이트", accent = true, sub = size)
            }
            rows += Row.Action(ACTION_CHECK, R.drawable.ic_refresh, "업데이트 확인")
            UpdateManager.lastError(c)?.let { rows += Row.Note("⚠ $it") }
            if (!UpdateManager.canInstall(c)) {
                rows += Row.Note(
                    "설치 권한이 아직 없어요. 처음 한 번만 폰의 ADB 앱(GeminiMan 등) 셸에서 실행:\n" +
                        "appops set ${packageName} REQUEST_INSTALL_PACKAGES allow"
                )
                rows += Row.Action(ACTION_PERMISSION, R.drawable.ic_info, "권한 설정 열어 보기")
            }
            val checked = UpdateManager.lastCheckAt(c)
            if (checked > 0) {
                rows += Row.Note("마지막 확인 " + SimpleDateFormat("M/d HH:mm", Locale.KOREA).format(Date(checked)))
            }
            rows += Row.Note("저장소 github.com/$repo")
            rows += Row.Action(ACTION_OWNER, R.drawable.ic_edit, "GitHub 아이디 바꾸기")
        }
        val hash = runCatching { com.mywatch.kakaomap.MapApp.ensureMapSdk(c); KakaoMapSdk.INSTANCE.hashKey }.getOrNull()
        if (hash != null) rows += Row.Note("카카오 키 해시\n$hash")
        // 무엇이 안 됐는지(검색·길찾기·실시간 버스·위치·지도·앱 멈춤) — 최근 것부터
        rows += Row.Header("최근 오류 기록")
        val errors = ErrorLog.entries()
        if (errors.isEmpty()) {
            rows += Row.Note("오류 기록 없음")
        } else {
            errors.take(SHOWN_ERRORS).forEach { rows += Row.Note(ErrorLogFormat.line(it)) }
            if (errors.size > SHOWN_ERRORS) rows += Row.Note("… 그 전 ${errors.size - SHOWN_ERRORS}개")
            rows += Row.Action(ACTION_CLEAR_ERRORS, R.drawable.ic_delete, "오류 기록 지우기")
        }
        rows += Row.Note("실시간 버스(공공데이터) 키: ${if (TagoApi.isConfigured) "들어 있음" else "없음"}")
        rows += Row.Header("이 워치에서의 성능")
        rows += Row.Note(deviceReport())
        ui.rows = rows
    }

    private fun sizeLabel(bytes: Long?, small: Boolean): String? {
        bytes ?: return null
        val mb = if (bytes < 1_000_000) "1MB 미만" else "약 ${(bytes + 500_000) / 1_000_000}MB"
        return if (small) "$mb · 바뀐 부분만 받아요" else "$mb · Wi-Fi 가 있으면 그쪽으로"
    }

    /** 워치가 이 앱을 감당하는지 실제 기기에서 확인할 수 있는 수치 */
    private fun deviceReport(): String {
        val am = getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val totalGb = mem.totalMem / 1_000_000_000.0
        val availMb = mem.availMem / 1_000_000
        val appMb = Debug.getPss() / 1024
        return buildString {
            append("${Build.MANUFACTURER} ${Build.MODEL} · API ${Build.VERSION.SDK_INT}\n")
            append("CPU ${Build.SUPPORTED_ABIS.joinToString()}\n")
            append("OpenGL ES ${am.deviceConfigurationInfo.glEsVersion}\n")
            append(String.format(Locale.KOREA, "RAM %.1fGB · 여유 %,dMB\n", totalGb, availMb))
            append("이 앱 사용 메모리 ${appMb}MB · 자바 힙 한도 ${am.memoryClass}MB")
            if (mem.lowMemory) append("\n⚠ 지금 메모리가 부족한 상태예요")
        }
    }

    private fun onRow(row: Row) {
        if (row !is Row.Action || busy != null) return
        when (row.id) {
            ACTION_CHECK -> check()
            ACTION_INSTALL -> install()
            ACTION_OWNER -> askOwner()
            ACTION_CLEAR_ERRORS -> {
                ErrorLog.clear()
                render()
            }
            ACTION_PERMISSION -> try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                )
            } catch (e: ActivityNotFoundException) {
                UpdateManager.recordError(this, "이 워치에는 설정 화면이 없어요 — ADB 명령으로 허용해 주세요")
                render()
            }
        }
    }

    /** [thenInstall] = '업데이트' 버튼으로 연 경우 — 새 버전이면(확인이 실패해도 이미 알던 새 버전이면) 그대로 받아 설치 */
    private fun check(thenInstall: Boolean = false) {
        busy = "확인 중…"
        render()
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching { UpdateManager.check(this@UpdateActivity) }.exceptionOrNull()
            }
            error?.let { UpdateManager.recordError(this@UpdateActivity, it.message ?: "확인 실패") }
            busy = null
            render()
            if (thenInstall && UpdateManager.hasUpdate(this@UpdateActivity)) install()
        }
    }

    private fun install() {
        val info = UpdateManager.latest(this) ?: return
        if (!UpdateManager.canInstall(this)) {
            UpdateManager.recordError(this, "설치 권한이 없어요 (아래 안내 참고)")
            render()
            return
        }
        if (NavigationService.running) {
            // 설치하면 앱이 다시 시작돼 안내가 끊긴다 → 안내가 끝나면 알아서 설치
            UpdateManager.recordError(this, getString(R.string.update_after_navigation))
            render()
            return
        }
        // 자동 업데이트가 이미 받고 있으면 그것이 끝나길 기다렸다가 그 파일로 설치한다 (같은 파일을 두 번 받지 않음)
        busy = if (UpdateManager.downloading) "자동 업데이트가 받는 중이에요 — 끝나면 바로 설치해요" else "내려받는 중…"
        render()
        val app = applicationContext
        lifecycleScope.launch {
            var deferred: String? = null
            val error = runCatching {
                val file = withContext(Dispatchers.IO) {
                    // 기다리는 사람이 있으니 Wi-Fi 는 조금만 기다려 본다 (없으면 바로 폰으로). 화면이 없어지면 멈춘다(다음에 이어받기)
                    val got = UpdateManager.download(app, info, UpdateManager.MANUAL_WIFI_WAIT_MS, cancelled = { !isActive }) { pct ->
                        runOnUiThread {
                            busy = "내려받는 중… $pct%"
                            render()
                        }
                    }
                    verifyApk(app, got, info)?.let { throw IllegalStateException(it) }
                    got
                }
                // 받는 사이 홈으로 나가 다른 것(동영상 등)을 하고 있거나 안내를 시작함 → 지금 설치하면 그것이 끊기므로 미룬다
                // (받은 파일은 남겨 두고, 앱을 다 닫았을 때·안내가 끝났을 때 자동 설치가 이어서 설치)
                val stillHere = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                if (!stillHere || NavigationService.running || PlayerPresence.of(app).busy(0)) {
                    deferred = app.getString(
                        if (NavigationService.running) R.string.update_after_navigation else R.string.update_deferred
                    )
                    UpdateInstallWorker.enqueueSoon(app)
                } else {
                    withContext(Dispatchers.IO) { installApk(app, file) }
                }
            }.exceptionOrNull()
            if (error is CancellationException || error is UpdateManager.Cancelled) {
                // 받는 중에 화면을 닫아 받기가 멈춤 — 실패가 아니다. 받은 데까지는 남았으니 뒤에서 이어받아(자동 설치 규칙대로) 끝내게 맡긴다
                UpdateInstallWorker.enqueue(app)
                return@launch
            }
            busy = if (error == null && deferred == null) "$INSTALLING 앱이 잠시 닫혔다가 바뀌어요" else null
            deferred?.let { UpdateManager.recordError(app, it) }
            error?.let { UpdateManager.recordFailure(app, "업데이트", it.message ?: "업데이트 실패") }
            render()
        }
    }

    private fun askOwner() {
        val input = RemoteInput.Builder(KEY_OWNER).setLabel("GitHub 아이디").build()
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(input))
        RemoteInputIntentHelper.putTitleExtra(intent, "GitHub 아이디")
        try {
            ownerInput.launch(intent)
        } catch (e: ActivityNotFoundException) {
            UpdateManager.recordError(this, "입력 화면을 열 수 없어요")
            render()
        }
    }

    companion object {
        /** 받은 APK 확인 (시험에선 가짜 APK 를 통과시키려고 바꿔 끼운다) */
        @VisibleForTesting
        internal var verifyApk: (Context, File, UpdateInfo) -> String? = UpdateManager::verify

        /** 설치 (시험에선 설치 대신 기록) */
        @VisibleForTesting
        internal var installApk: (Context, File) -> Unit = UpdateManager::install

        /** 첫 화면·지도·유튜브의 '업데이트' 버튼으로 열 때: 확인하고 새 버전이면 바로 받아 설치 (버튼을 또 누르지 않게) */
        const val EXTRA_UPDATE_NOW = "update_now"

        /** '업데이트' 버튼이 여는 화면 — 확인하고 새 버전이면 곧바로 받아 설치 */
        fun updateNowIntent(context: Context): Intent =
            Intent(context, UpdateActivity::class.java).putExtra(EXTRA_UPDATE_NOW, true)

        private const val INSTALLING = "설치 중…"
        private const val KEY_OWNER = "owner"
        private const val ACTION_CHECK = "check"
        private const val ACTION_INSTALL = "install"
        private const val ACTION_OWNER = "owner"
        private const val ACTION_PERMISSION = "permission"
        private const val ACTION_CLEAR_ERRORS = "clear_errors"
        private const val SHOWN_ERRORS = 10
        /** 이 화면을 열 때, 마지막 확인이 이보다 오래됐으면 다시 확인 */
        private const val SCREEN_CHECK_EVERY_MS = 60_000L
    }
}
