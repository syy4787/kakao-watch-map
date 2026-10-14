package com.mywatch.kakaomap.ui

import android.app.ActivityManager
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.mywatch.kakaomap.MainActivity
import com.mywatch.kakaomap.R
import com.mywatch.kakaomap.tube.TubeAccount
import com.mywatch.kakaomap.update.UpdateActivity
import com.mywatch.kakaomap.update.UpdateManager
import com.mywatch.kakaomap.update.UpdateWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * '갤럭시워치 전용앱' 첫 화면 (워치 앱 목록의 아이콘은 이것 하나): 카카오맵(길찾기) / 유튜브(노래 검색) 고르기.
 * 고른 앱은 저마다의 작업(task)으로 열고 이 화면은 닫는다 — 그 앱에서 뒤로 가면 워치 화면으로,
 * 이미 열려 있던 지도(안내 중 등)·유튜브는 보던 그대로 다시 보인다. '앱 정보'에서 뒤로 가면 이 화면으로.
 */
class HubActivity : ComponentActivity() {

    private lateinit var updateButton: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hub)
        updateButton = findViewById(R.id.hub_update)
        findViewById<View>(R.id.open_map).setOnClickListener { open(MainActivity::class.java) }
        findViewById<View>(R.id.open_tube).setOnClickListener { open(TubeActivity::class.java) }
        // 누르면 확인하고 새 버전이면 곧바로 받아 설치 (버전·오류 기록 같은 앱 정보도 그 화면에)
        updateButton.setOnClickListener { startActivity(UpdateActivity.updateNowIntent(this)) }
        // 유튜브 로그인 토큰은 키 저장소로 풀어 읽는다 (워치에선 수십 ms) — 유튜브를 누르기 전에 미리 읽어 둔다
        val app = applicationContext
        lifecycleScope.launch(Dispatchers.IO) { runCatching { TubeAccount.auth(app).signedIn } }
    }

    /** 맡긴 확인이 첫 화면을 보는 사이에 새 버전을 찾으면 버튼을 바로 파랗게 바꾼다 */
    private val updateListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> showUpdateLabel() }

    override fun onResume() {
        super.onResume()
        showUpdateLabel()
        UpdateManager.prefsForListening(this).registerOnSharedPreferenceChangeListener(updateListener)
        // 마지막 확인이 5분 넘었으면 새 버전 확인 → 있으면 버튼이 파랗게 (집 밖에서도 — 6시간마다 하는 확인만으론 한참 모름)
        UpdateWorker.onAppOpened(this)
    }

    override fun onPause() {
        UpdateManager.prefsForListening(this).unregisterOnSharedPreferenceChangeListener(updateListener)
        super.onPause()
    }

    /**
     * 새 버전이 있으면 파란 버튼(읽어 주기는 '새 버전 업데이트'), 없으면 회색. 글자는 둘 다 짧게 '업데이트' —
     * 둥근 화면 아래쪽은 좁아서, 글자를 키운 워치에선 '새 버전 업데이트'가 양 끝이 잘렸다
     */
    private fun showUpdateLabel() {
        val update = runCatching { UpdateManager.hasUpdate(this) || UpdateManager.isPendingUserAction(this) }.getOrDefault(false)
        updateButton.setBackgroundResource(if (update) R.drawable.bg_action_accent else R.drawable.bg_action)
        updateButton.contentDescription = getString(if (update) R.string.hub_update_new else R.string.hub_update)
    }

    /**
     * 그 앱이 이미 열려 있으면(안내 중인 지도, 타일로 연 검색 → 길찾기 → 지도 등) 그 작업을 보던 그대로 앞으로 —
     * 새로 열면 지도(카카오 지도 엔진)가 하나 더 생긴다. 없을 때만 새 작업으로 연다
     */
    private fun open(target: Class<*>) {
        val running = runCatching { runningTask(target) }.getOrNull()
        if (running == null || runCatching { running.moveToFront() }.isFailure) {
            startActivity(Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }

    /**
     * 이 앱의 작업 중 [target] 쪽(유튜브 / 그 밖의 지도 화면들) 것. 화면이 하나도 없이 '최근 앱'에만 남은 작업은 빼고
     * (되살리면 처음 열 때의 요청 — 타일의 '집으로' 등 — 이 다시 실행될 수 있음)
     */
    private fun runningTask(target: Class<*>): ActivityManager.AppTask? {
        val wantTube = target == TubeActivity::class.java
        return getSystemService(ActivityManager::class.java)?.appTasks?.firstOrNull { task ->
            // 작업이 막 끝나는 중이면 정보가 없을 수 있다 (API 37 부터 null 로 표시)
            val info = runCatching { task.taskInfo }.getOrNull() ?: return@firstOrNull false
            val base = (info.baseActivity ?: info.baseIntent?.component)?.className
            info.numActivities > 0 && base != null && base != HubActivity::class.java.name &&
                (base == TubeActivity::class.java.name) == wantTube
        }
    }
}
