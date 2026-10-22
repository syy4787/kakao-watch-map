package com.mywatch.kakaomap.battery

import android.content.Context
import com.mywatch.battery.core.BatterySample
import com.mywatch.battery.core.Event

/**
 * 부팅 줄 — 켜진 뒤 처음 남는 줄이 그 부팅의 줄이 된다. 부팅 방송(BOOT_COMPLETED)이 건 한 번짜리 작업은 늦게 돌 수 있어, 그 사이 켜진 뒤의
 * 15분·충전 시작 기록이 먼저 남으면 꺼지기 전 기록과의 잔량 점프('방전 뒤 충전기에서 켜니 48%')를 게이지 이상·신고문 모두 못 봤다 (리뷰).
 * 그래서 지난 부팅 줄 뒤에 다시 켜졌으면 15분·충전 시작 줄을 부팅 줄로 남기고, 늦게 돈 부팅 작업은 이 부팅의 줄을 다시 남기지 않는다.
 * 시각과 잔량은 늘 같은 때의 것 — 시각만 켜진 때로 앞당기면 늦게 읽은 잔량이 켜진 때의 것처럼 적혀 없던 점프가 생겼다 (리뷰).
 * 이 기능 전부터 쓰던 워치는 처음 한 번은 예전처럼(부팅 작업이 부팅 줄을 남김) — 그 뒤부터 지난 부팅을 안다
 */
object BootRows {
    private const val K_COUNT = "boot_row_count"
    private const val K_ELAPSED = "boot_row_elapsed"
    private const val K_WALL = "boot_row_wall"

    /**
     * [s] 를 어떻게 남길지 — 부팅 줄이면 그대로(이 부팅의 줄을 이미 남겼으면 null: 남기지 않는다), 15분·충전 시작 줄이면 지난 부팅 줄 뒤에
     * 다시 켜졌을 때 부팅 줄로, 그 밖(사용자 표시·실험 등)은 그대로
     */
    fun shape(c: Context, s: BatterySample, boot: HealthStore.Boot = HealthStore.Boot.now(c)): BatterySample? {
        val known = stored(c)
        return when (s.event) {
            Event.BOOT -> s.takeIf { known == null || boot.rebootedSince(known) }
            Event.PERIODIC, Event.POWER_CONNECTED -> if (known != null && boot.rebootedSince(known)) s.copy(event = Event.BOOT) else s
            else -> s
        }
    }

    /** 줄을 남긴 뒤 — 부팅 줄이면 이 부팅은 남긴 것으로 */
    fun wrote(c: Context, s: BatterySample, boot: HealthStore.Boot = HealthStore.Boot.now(c)) {
        if (s.event != Event.BOOT) return
        prefs(c).edit().putInt(K_COUNT, boot.count ?: -1).putLong(K_ELAPSED, boot.elapsedMs).putLong(K_WALL, boot.wallMs ?: -1L).apply()
    }

    private fun stored(c: Context): HealthStore.Boot? {
        val p = prefs(c)
        val elapsed = p.getLong(K_ELAPSED, -1L).takeIf { it >= 0 } ?: return null
        return HealthStore.Boot(p.getInt(K_COUNT, -1).takeIf { it >= 0 }, elapsed, p.getLong(K_WALL, -1L).takeIf { it >= 0 })
    }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(BatteryLog.PREFS, Context.MODE_PRIVATE)
}
