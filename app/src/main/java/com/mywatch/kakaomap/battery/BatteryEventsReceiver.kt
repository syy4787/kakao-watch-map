package com.mywatch.kakaomap.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mywatch.battery.core.Event

/**
 * 충전기 꽂음·뺌, 부팅, 배터리 부족 — 15분 주기와 별개로 그 순간을 한 줄 남긴다
 * (부팅 뒤 잔량이 직전보다 뛰어 있으면 '꺼졌다 켜지며 잔량 점프'로 잡힌다). 부팅 뒤엔 주기 기록도 다시 예약.
 */
class BatteryEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> Event.POWER_CONNECTED
            Intent.ACTION_POWER_DISCONNECTED -> Event.POWER_DISCONNECTED
            Intent.ACTION_BOOT_COMPLETED -> Event.BOOT
            Intent.ACTION_BATTERY_LOW -> Event.LOW
            else -> return
        }
        if (event == Event.BOOT) BatterySampleWorker.schedule(context)
        BatterySampleWorker.recordNow(context, event)
    }
}
