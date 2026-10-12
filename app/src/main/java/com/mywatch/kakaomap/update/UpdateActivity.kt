package com.mywatch.kakaomap.update

import android.app.ActivityManager
import android.app.RemoteInput
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.wear.input.RemoteInputIntentHelper
import com.kakao.vectormap.KakaoMapSdk
import com.mywatch.kakaomap.R
import com.mywatch.kakaomap.nav.NavigationService
import com.mywatch.kakaomap.ui.Row
import com.mywatch.kakaomap.ui.WearList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 앱 정보 · 업데이트 화면 */
class UpdateActivity : ComponentActivity() {

    private lateinit var ui: WearList
    private var busy: String? = null

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
    }

    override fun onPause() {
        UpdateManager.prefsForListening(this).unregisterOnSharedPreferenceChangeListener(installListener)
        super.onPause()
    }

    private fun render() {
        val c = this
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
                rows += Row.Action(ACTION_INSTALL, R.drawable.ic_download, "지금 업데이트", accent = true)
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
        val hash = runCatching { KakaoMapSdk.INSTANCE.hashKey }.getOrNull()
        if (hash != null) rows += Row.Note("카카오 키 해시\n$hash")
        rows += Row.Header("이 워치에서의 성능")
        rows += Row.Note(deviceReport())
        ui.rows = rows
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

    private fun check() {
        busy = "확인 중…"
        render()
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching { UpdateManager.check(this@UpdateActivity) }.exceptionOrNull()
            }
            error?.let { UpdateManager.recordError(this@UpdateActivity, it.message ?: "확인 실패") }
            busy = null
            render()
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
        busy = "내려받는 중…"
        render()
        val app = applicationContext
        lifecycleScope.launch {
            var deferred: String? = null
            val error = withContext(Dispatchers.IO) {
                runCatching {
                    val file = UpdateManager.download(app, info) { pct ->
                        runOnUiThread {
                            busy = "내려받는 중… $pct%"
                            render()
                        }
                    }
                    UpdateManager.verify(app, file, info)?.let { throw IllegalStateException(it) }
                    if (!isActive || NavigationService.running) {
                        // 받는 사이 화면을 닫았거나 안내를 시작함 → 지금 설치하면 쓰던 화면·안내가 끊기므로 미룬다
                        // (받은 파일은 남겨 두고, 앱을 다 닫았을 때·안내가 끝났을 때 자동 설치가 이어서 설치)
                        deferred = app.getString(
                            if (NavigationService.running) R.string.update_after_navigation else R.string.update_deferred
                        )
                        UpdateInstallWorker.enqueueSoon(app)
                    } else {
                        UpdateManager.install(app, file)
                    }
                }.exceptionOrNull()
            }
            busy = if (error == null && deferred == null) "$INSTALLING 앱이 잠시 닫혔다가 바뀌어요" else null
            deferred?.let { UpdateManager.recordError(app, it) }
            error?.let { UpdateManager.recordError(app, it.message ?: "업데이트 실패") }
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

    private companion object {
        const val INSTALLING = "설치 중…"
        const val KEY_OWNER = "owner"
        const val ACTION_CHECK = "check"
        const val ACTION_INSTALL = "install"
        const val ACTION_OWNER = "owner"
        const val ACTION_PERMISSION = "permission"
    }
}
