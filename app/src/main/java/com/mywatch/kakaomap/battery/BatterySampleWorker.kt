package com.mywatch.kakaomap.battery

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.mywatch.battery.core.Event
import com.mywatch.kakaomap.data.ErrorLog
import java.util.concurrent.TimeUnit

/**
 * 배터리 표본 기록 작업 — 15분마다(WorkManager 최소 간격) 한 줄. 제약 없음: 충전 중에도 기록해야 충전 곡선으로 용량을 추정한다.
 * 웨이크락을 쥐지 않고 수십 ms 만에 끝난다. 주기 표본 뒤엔 오래된 줄 정리(하루 1번)와 비정상 소모 알림 검사.
 */
class BatterySampleWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val event = inputData.getString(KEY_EVENT)?.let { runCatching { Event.valueOf(it) }.getOrNull() } ?: Event.PERIODIC
        val note = inputData.getString(KEY_NOTE) ?: ""
        try {
            val log = BatteryLog(applicationContext)
            BatteryProbe.read(applicationContext, event, note)?.let { log.append(it) }
            if (event == Event.PERIODIC) {
                log.pruneIfDue()
                // 지킴이: 밤·비정상 소모 대응과 알림 (권한이 없으면 알림만)
                Guardian.tick(applicationContext, log)
                // 배터리 타일이 보일 때 새 값으로 그리게
                com.mywatch.kakaomap.tile.BatteryTileService.refresh(applicationContext)
            }
        } catch (e: Exception) {
            ErrorLog.record("배터리·기록", e)
        }
        return Result.success()
    }

    companion object {
        const val UNIQUE = "battery-sample"
        const val KEY_EVENT = "event"
        const val KEY_NOTE = "note"
        const val PERIOD_MIN = 15L

        /** 앱이 시작될 때·부팅 뒤 — 이미 예약돼 있으면 그대로 둔다 */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BatterySampleWorker>(PERIOD_MIN, TimeUnit.MINUTES).build()
            runCatching { WorkManager.getInstance(context).enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.KEEP, request) }
        }

        /** 충전기 꽂음·뺌, 부팅 같은 계기를 바로 한 줄 남긴다. WorkManager 가 없으면(일부 시험 환경) 그 자리에서 쓴다 */
        fun recordNow(context: Context, event: Event, note: String = "") {
            val request = OneTimeWorkRequestBuilder<BatterySampleWorker>()
                .setInputData(workDataOf(KEY_EVENT to event.name, KEY_NOTE to note))
                .build()
            runCatching { WorkManager.getInstance(context).enqueue(request) }
                .onFailure { runCatching { BatteryProbe.read(context, event, note)?.let { BatteryLog(context).append(it) } } }
        }
    }
}
