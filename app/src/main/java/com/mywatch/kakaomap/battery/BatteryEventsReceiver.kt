package com.mywatch.kakaomap.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mywatch.battery.core.Event

/**
 * 부팅 — 15분 주기와 별개로 그 순간을 한 줄 남긴다 (부팅 뒤 잔량이 직전보다 뛰어 있으면 '꺼졌다 켜지며 잔량 점프'로 잡힌다).
 * 부팅 뒤엔 주기 기록과 충전 시작 기록([ChargeWatchWorker])도 다시 건다. 충전기 꽂음·뺌·배터리 부족 방송은 targetSdk 26+ 앱의
 * 매니페스트로는 오지 않아 받지 않는다(충전 시작은 [ChargeWatchWorker], 뺀 것은 15분 표본이 안다).
 */
class BatteryEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        BatterySampleWorker.schedule(context)
        ChargeWatchWorker.arm(context)
        BatterySampleWorker.recordNow(context, Event.BOOT)
    }
}
