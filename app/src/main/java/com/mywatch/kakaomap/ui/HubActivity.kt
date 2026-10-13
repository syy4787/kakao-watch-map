package com.mywatch.kakaomap.ui

import android.app.ActivityManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.mywatch.kakaomap.MainActivity
import com.mywatch.kakaomap.R
import com.mywatch.kakaomap.update.UpdateActivity
import com.mywatch.kakaomap.update.UpdateManager

/**
 * '갤럭시워치 전용앱' 첫 화면 (워치 앱 목록의 아이콘은 이것 하나): 카카오맵(길찾기) / 유튜브(노래 검색) 고르기.
 * 고른 앱은 저마다의 작업(task)으로 열고 이 화면은 닫는다 — 그 앱에서 뒤로 가면 워치 화면으로,
 * 이미 열려 있던 지도(안내 중 등)·유튜브는 보던 그대로 다시 보인다. '앱 정보'에서 뒤로 가면 이 화면으로.
 */
class HubActivity : ComponentActivity() {

    private lateinit var info: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hub)
        info = findViewById(R.id.hub_info)
        findViewById<View>(R.id.open_map).setOnClickListener { open(MainActivity::class.java) }
        findViewById<View>(R.id.open_tube).setOnClickListener { open(TubeActivity::class.java) }
        info.setOnClickListener { startActivity(Intent(this, UpdateActivity::class.java)) }
    }

    override fun onResume() {
        super.onResume()
        // 새 버전이 있으면 아래 글자로 알린다 (누르면 업데이트 화면)
        val update = runCatching { UpdateManager.hasUpdate(this) || UpdateManager.isPendingUserAction(this) }.getOrDefault(false)
        info.setText(if (update) R.string.update_available else R.string.app_info_update)
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
            val info = task.taskInfo
            val base = (info.baseActivity ?: info.baseIntent?.component)?.className
            info.numActivities > 0 && base != null && base != HubActivity::class.java.name &&
                (base == TubeActivity::class.java.name) == wantTube
        }
    }
}
