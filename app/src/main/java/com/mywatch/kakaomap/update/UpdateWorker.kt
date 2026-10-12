package com.mywatch.kakaomap.update

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mywatch.kakaomap.MapApp
import com.mywatch.kakaomap.nav.NavigationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * 새 버전 확인 — 6시간마다, 인터넷만 되면(몇 KB 짜리 update.json 이라 배터리 부담이 거의 없음).
 * 새 버전이 있으면 설치 작업([UpdateInstallWorker])을 예약한다.
 */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val c = applicationContext
        if (UpdateManager.repo(c) == null) return@withContext Result.success()
        try {
            if (UpdateManager.check(c) != null) UpdateInstallWorker.enqueue(c)
        } catch (e: Exception) {
            Log.w(MapApp.TAG, "업데이트 확인 실패", e)
        }
        Result.success()
    }

    companion object {
        private const val NAME = "update-check"
        private const val OLD_NAME = "auto-update"

        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork(OLD_NAME)   // 예전 '충전 중 + Wi-Fi 에서만' 작업 정리
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}

/**
 * 내려받기·설치 — Wi-Fi 면 충전 중이 아니어도 바로, 데이터(LTE·폰 블루투스)일 땐 충전 중일 때만.
 * 안내 중이면 설치하지 않고 미룬다 (설치하면 앱이 다시 시작돼 안내가 끊기므로).
 */
class UpdateInstallWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val c = applicationContext
        if (UpdateManager.repo(c) == null || !UpdateManager.hasUpdate(c)) return@withContext Result.success()
        if (!UpdateManager.canInstall(c)) return@withContext Result.success()   // 앱 정보 화면에서 안내
        if (NavigationService.running) return@withContext Result.retry()
        if (!onWifi(c) && !charging(c)) return@withContext Result.retry()
        val info = UpdateManager.latest(c) ?: return@withContext Result.success()
        try {
            val file = UpdateManager.download(c, info)
            UpdateManager.verify(c, file, info)?.let {
                UpdateManager.recordError(c, it)
                return@withContext Result.success()
            }
            if (NavigationService.running) return@withContext Result.retry()   // 받는 사이 안내 시작
            UpdateManager.install(c, file)
            Result.success()
        } catch (e: Exception) {
            Log.w(MapApp.TAG, "자동 업데이트 실패", e)
            UpdateManager.recordError(c, e.message ?: "자동 업데이트 실패")
            if (runAttemptCount < 5) Result.retry() else Result.success()
        }
    }

    private fun onWifi(c: Context): Boolean {
        val cm = c.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        // 폰 블루투스 연결(컴패니언 프록시)도 '무제한'으로 잡히지만 실제로는 폰의 LTE 를 쓰므로 Wi-Fi 만 인정
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun charging(c: Context): Boolean {
        val status = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: return false
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    companion object {
        private const val NAME = "update-install"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<UpdateInstallWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                // 조건이 안 맞으면(데이터망·안내 중) 20분, 40분 … 간격으로 다시 시도
                .setBackoffCriteria(BackoffPolicy.LINEAR, 20, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
