package com.mywatch.kakaomap.battery

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.mywatch.battery.core.Event
import com.mywatch.kakaomap.data.ErrorLog
import java.util.concurrent.TimeUnit

/**
 * 충전 시작을 기록한다. targetSdk 26+ 앱은 충전기 꽂음·뺌 방송을 매니페스트로 받지 못한다(Android 8 의 암시적 방송 제한) —
 * 그래서 '충전 중' 조건이 걸린 한 번짜리 작업을 걸어 두면, 시스템이 충전 중이라고 할 때(꽂고 몇 분 안) 돌아 한 줄 남긴다.
 * 15분 표본 사이의 짧은 충전이 '충전 없이 잔량 상승'(게이지 이상)으로 읽히지 않게. 돌고 나면 [REARM_MIN] 분 뒤를 다시 건다 —
 * 충전 중이면 그때 또 돌지만 이미 충전 중 줄이 있으면 쓰지 않는다 (충전기 위라 배터리 걱정이 없다). 뺀 것은 15분 표본이 안다.
 * 돌 때마다 충전 중 온도를 본다 — 뜨거우면 그 자리에서 식히는 길 ([BatteryAlerts.hotCharge], 새 충전이면 지난 충전의 알림을 거두고 다시 셈).
 * 켜진 뒤 처음 남는 줄이면 부팅 줄로 ([BootRows])
 */
class ChargeWatchWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        runCatching {
            val log = BatteryLog(applicationContext)
            val last = log.readRecent(1).maxByOrNull { it.t }
            val now = BatteryProbe.read(applicationContext, Event.POWER_CONNECTED)?.takeIf { it.plugged }
            if (now != null) {
                if (last == null || !last.plugged) {
                    BatteryAlerts.chargeStarted(applicationContext)
                    BootRows.shape(applicationContext, now)?.let { row -> log.append(row); BootRows.wrote(applicationContext, row) }
                }
                runCatching { BatteryAlerts.hotCharge(applicationContext, now) }
            }
        }.onFailure { ErrorLog.record("배터리·충전 기록", it) }
        arm(applicationContext, REARM_MIN)
        return Result.success()
    }

    companion object {
        const val UNIQUE = "battery-charge-watch"
        /** 돌고 나서 다시 걸 때 기다리는 시간 (충전기 위에서 너무 자주 돌지 않게) */
        const val REARM_MIN = 10L

        /**
         * 충전이 시작되면 돌 작업을 건다. 앱이 시작될 때·부팅 뒤([delayMin] = 0)엔 이미 걸려 있으면 그대로, 작업 안에서 다시 걸 땐
         * 지금 작업 뒤에 잇는다
         */
        fun arm(context: Context, delayMin: Long = 0L) {
            val request = OneTimeWorkRequestBuilder<ChargeWatchWorker>()
                .setConstraints(Constraints.Builder().setRequiresCharging(true).build())
                .apply { if (delayMin > 0) setInitialDelay(delayMin, TimeUnit.MINUTES) }
                .build()
            val policy = if (delayMin > 0) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP
            runCatching { WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, policy, request) }
        }
    }
}
