package com.mywatch.kakaomap.nav

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/** 배터리 상태 (등록 없이 마지막 방송값만 읽어서 전력 0) */
object BatteryInfo {
    /** 15% 이하이면서 충전 중이 아님 → 절약 안내 */
    fun isLow(context: Context): Boolean {
        val b = runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull() ?: return false
        val level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        if (level < 0 || scale <= 0 || charging) return false
        return level * 100 / scale <= LOW_PERCENT
    }

    const val LOW_PERCENT = 15
}
