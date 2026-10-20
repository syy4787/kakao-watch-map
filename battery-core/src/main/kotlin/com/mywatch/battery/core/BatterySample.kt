package com.mywatch.battery.core

/** 충전 상태 — 안드로이드 BatteryManager.BATTERY_STATUS_* 를 이름으로 */
enum class Status { CHARGING, DISCHARGING, FULL, NOT_CHARGING, UNKNOWN }

/** 표본을 남긴 계기 */
enum class Event {
    /** 15분마다 */
    PERIODIC,
    POWER_CONNECTED,
    POWER_DISCONNECTED,
    /** 부팅 직후 — 직전 표본보다 잔량이 뛰면 '꺼졌다 켜지며 잔량 점프' */
    BOOT,
    /** 배터리 화면을 열 때 */
    APP_OPEN,
    /** 시스템의 배터리 부족 알림 */
    LOW,
    /** 사용자가 '실험 시작'(note = 비행기 모드 등) */
    EXPERIMENT_START,
    EXPERIMENT_END,
    /** 사용자가 남긴 표시 (note = "강제 재시작" 등) */
    USER_NOTE,
}

/**
 * 배터리 표본 하나. 워치가 15분마다 CSV 한 줄로 남기고, PC 툴킷이 같은 줄을 읽는다.
 * 모르는 값은 null (예: 전하량 계수기를 앱에 열어 주지 않는 기기).
 */
data class BatterySample(
    /** epoch ms */
    val t: Long,
    /** 잔량 % */
    val level: Int,
    /** 남은 전하량 µAh (BATTERY_PROPERTY_CHARGE_COUNTER) */
    val chargeUah: Int? = null,
    val voltageMv: Int? = null,
    /** 온도 0.1℃ */
    val tempDeciC: Int? = null,
    /** 순간 전류 µA — 부호는 기기마다 다르므로 그대로 둔다 */
    val currentUa: Int? = null,
    val status: Status = Status.UNKNOWN,
    /** 충전기 연결 */
    val plugged: Boolean = false,
    /** 표본 시점에 화면 켜짐 */
    val interactive: Boolean = false,
    val event: Event = Event.PERIODIC,
    /** 실험 이름 등 — 쉼표·줄바꿈은 저장할 때 치환된다 */
    val note: String = "",
)
