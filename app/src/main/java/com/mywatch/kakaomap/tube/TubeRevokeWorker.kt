package com.mywatch.kakaomap.tube

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * 로그아웃할 때 인터넷이 없어 구글에 보내지 못한 접근 권한 철회를 인터넷이 되면 보낸다 (정책 III.D.2 — 점검 C4).
 * 철회할 토큰은 암호화해 둔 것([TubeAuth.forget])이고, 다 보낼 때까지 점점 길게 다시 시도한다
 */
class TubeRevokeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (TubeAccount.auth(applicationContext).retryRevokes()) Result.success() else Result.retry()
    }

    companion object {
        private const val NAME = "tube-revoke"

        /** 남은 철회를 인터넷이 되면 보내도록 맡긴다 (이미 맡겼으면 그대로) */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<TubeRevokeWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            runCatching { WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request) }
        }
    }
}
