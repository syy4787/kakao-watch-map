package com.mywatch.kakaomap.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.core.content.IntentCompat
import com.mywatch.kakaomap.MapApp

/** PackageInstaller 설치 결과 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Log.i(MapApp.TAG, "설치 결과 status=$status msg=$message")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // 확인 창이 필요한 경우: 앱이 화면에 떠 있으면 바로 띄우고, 아니면 다음에 앱을 열 때 안내
                UpdateManager.markPendingUserAction(context, true)
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
                    .onFailure { Log.w(MapApp.TAG, "확인 창을 띄우지 못함", it) }
            }
            PackageInstaller.STATUS_SUCCESS -> UpdateManager.markPendingUserAction(context, false)
            else -> UpdateManager.recordError(
                context,
                when (status) {
                    PackageInstaller.STATUS_FAILURE_ABORTED -> "설치를 취소했어요"
                    PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                        "서명이 달라 설치할 수 없어요 — 앱을 지우고 새로 설치해 주세요"
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "저장 공간이 부족해요"
                    else -> "설치 실패: ${message ?: status}"
                },
            )
        }
    }
}
