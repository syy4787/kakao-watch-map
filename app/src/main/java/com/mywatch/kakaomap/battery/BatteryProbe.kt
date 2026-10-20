package com.mywatch.kakaomap.battery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import com.mywatch.battery.core.BatterySample
import com.mywatch.battery.core.Event
import com.mywatch.battery.core.Status

/**
 * 지금 배터리 표본 하나 읽기 — 시스템이 들고 있는 마지막 배터리 상태(sticky ACTION_BATTERY_CHANGED)와
 * BatteryManager 의 전하량·전류. 기기가 안 주는 값(Int.MIN_VALUE·0)은 모름(null)으로 둔다.
 * 배터리 상태 자체가 없으면(실제 기기엔 늘 있음 — 일부 시험 환경) null: 엉터리 0% 줄을 남기지 않는다.
 */
object BatteryProbe {

    fun read(context: Context, event: Event = Event.PERIODIC, note: String = ""): BatterySample? {
        val c = context.applicationContext
        val i = runCatching { c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull() ?: return null
        val bm = c.getSystemService(BatteryManager::class.java)
        val level = percent(i, bm)
        val status = when (i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> Status.CHARGING
            BatteryManager.BATTERY_STATUS_DISCHARGING -> Status.DISCHARGING
            BatteryManager.BATTERY_STATUS_FULL -> Status.FULL
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> Status.NOT_CHARGING
            else -> Status.UNKNOWN
        }
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val voltage = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0).takeIf { it > 0 }
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE && it != 0 }
        val interactive = runCatching { c.getSystemService(PowerManager::class.java)?.isInteractive }.getOrNull() ?: false
        return BatterySample(
            t = System.currentTimeMillis(),
            level = level,
            chargeUah = property(bm, BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)?.takeIf { it > 0 },
            voltageMv = voltage,
            tempDeciC = temp,
            currentUa = property(bm, BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)?.takeIf { it != 0 },
            status = status,
            plugged = plugged,
            interactive = interactive,
            event = event,
            note = note,
        )
    }

    private fun percent(i: Intent, bm: BatteryManager?): Int {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level >= 0 && scale > 0) return (level * 100 / scale).coerceIn(0, 100)
        return property(bm, BatteryManager.BATTERY_PROPERTY_CAPACITY)?.coerceIn(0, 100) ?: 0
    }

    private fun property(bm: BatteryManager?, id: Int): Int? =
        runCatching { bm?.getIntProperty(id) }.getOrNull()?.takeIf { it != Int.MIN_VALUE }
}
