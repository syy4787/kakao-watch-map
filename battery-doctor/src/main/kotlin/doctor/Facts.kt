package doctor

import com.mywatch.battery.core.Analysis
import com.mywatch.battery.core.BatterySample
import doctor.parse.AlarmStat
import doctor.parse.BatteryNow
import doctor.parse.BatteryStats
import doctor.parse.IdleState
import doctor.parse.LocationUse
import doctor.parse.PackageInfo
import doctor.parse.PowerState
import doctor.parse.SensorUse

enum class Severity { INFO, CAUTION, ABNORMAL;
    val label: String get() = when (this) { INFO -> "참고"; CAUTION -> "주의"; ABNORMAL -> "비정상" }
}

/**
 * 소견 하나. [category] 는 판정 트리가 묶어 보는 갈래:
 * awake / wakelock / alarm / sensor / gms (소프트웨어) · gauge · capacity · usage · drain(소모율) · settings · info
 */
data class Finding(
    val id: String,
    val severity: Severity,
    val title: String,
    val evidence: List<String>,
    val packages: List<String> = emptyList(),
    /** 기준치의 출처 한 줄 */
    val source: String? = null,
    val category: String = "info",
)

enum class Cause { INSUFFICIENT, GAUGE, SOFTWARE, USAGE, HARDWARE, NORMAL;
    val label: String get() = when (this) {
        INSUFFICIENT -> "측정 더 필요"; GAUGE -> "잔량 표시(게이지)·펌웨어 문제 의심"; SOFTWARE -> "소프트웨어(서비스) 과소모"
        USAGE -> "사용 패턴(화면·GPS·무선)"; HARDWARE -> "하드웨어(배터리) 의심"; NORMAL -> "정상 범위"
    }
}

data class DoctorVerdict(
    val cause: Cause,
    /** 높음 / 중간 / 낮음 */
    val confidence: String,
    val headline: String,
    val why: List<String>,
    /** 지금 할 일 3가지 */
    val todo: List<String>,
    val culprits: List<String>,
)

/** 스냅샷에서 읽어 낸 사실 전부. 없는 것은 null·빈 값 */
data class Facts(
    val model: String? = null,
    val build: String? = null,
    val incremental: String? = null,
    val nominalMah: Int? = null,
    val now: BatteryNow? = null,
    val stats: BatteryStats? = null,
    val alarms: List<AlarmStat> = emptyList(),
    /** 알람 통계의 분모(부팅 이후 경과) */
    val alarmElapsedMs: Long? = null,
    val sensors: List<SensorUse> = emptyList(),
    val idle: IdleState? = null,
    val packages: List<PackageInfo> = emptyList(),
    /** global / secure / system → key=value */
    val settings: Map<String, Map<String, String>> = emptyMap(),
    val heldWakeLocks: List<String> = emptyList(),
    val power: PowerState? = null,
    /** 덤프 시점에 화면이 꺼져 있었나(모르면 true 로 본다 — 수집은 보통 손목에 찬 채 PC 에서 한다) */
    val screenOffAtDump: Boolean = true,
    val locations: List<LocationUse> = emptyList(),
    val gmsVersion: String? = null,
    /** battery-core 가 워치 CSV 로 낸 분석 */
    val watch: Analysis? = null,
    val watchSamples: List<BatterySample> = emptyList(),
    /** 워치 앱이 쓴 요약 글 (사용 내역·알려진 문제·밤사이 범인) */
    val watchSummary: String? = null,
    val collectedAt: Long? = null,
    /** 수집에 실패한 항목 이름 */
    val missing: List<String> = emptyList(),
    val snapshotDir: String? = null,
) {
    fun packageByUid(uid: Int?): String? = uid?.let { u -> stats?.packageOf(u) ?: packages.firstOrNull { it.uid == u }?.name }

    /** 서드파티(사용자가 설치한) 앱인가 — 목록이 없으면 이름으로 짐작 */
    fun isThirdParty(pkg: String): Boolean {
        val known = packages.firstOrNull { it.name == pkg }
        if (known != null) return known.thirdParty
        return !SYSTEM_PREFIXES.any { pkg.startsWith(it) } && pkg != "android"
    }

    val hasWatchLog: Boolean get() = watch != null && watchSamples.isNotEmpty()

    companion object {
        val SYSTEM_PREFIXES = listOf("com.android.", "com.google.android.", "com.samsung.", "com.sec.", "com.qualcomm.", "android.")
    }
}

/** 소견 규칙의 수치와 출처 — 보고서가 기준치마다 같이 보여 준다 */
object Rules {
    const val AWAKE_CAUTION = 0.10
    const val AWAKE_ABNORMAL = 0.20
    const val SOURCE_AWAKE = "경험칙 — 화면 꺼짐 중 CPU 가 깨어 있던 비율(screen-off uptime ÷ screen-off realtime)의 공식 기준은 없음. 10% 초과 주의, 20% 초과 비정상"

    const val WAKELOCK_CAUTION_S_PER_H = 18.5
    const val WAKELOCK_ABNORMAL_S_PER_H = 300.0
    const val WAKELOCK_ABNORMAL_TOTAL_MS = 3_600_000L
    const val SOURCE_WAKELOCK = "Android vitals: 워치페이스 세션에서 partial wakelock 18.5초/시간 이상은 과소모 기여 요인 → 주의; 백그라운드에서 1시간 연속 wakelock = stuck(합계 1시간 초과 또는 시간당 5분 초과 → 비정상)"

    const val ALARM_CAUTION_PER_H = 20.0
    const val ALARM_ABNORMAL_PER_H = 60.0
    const val SOURCE_ALARM = "경험칙 — Battery Historian 가이드의 '10초 이하 간격 wakeup 알람은 이상'(=360회/시간)을 보수적으로 낮춘 값: 20회/시간 주의, 60회/시간 비정상"

    const val SOURCE_SENSOR = "Wear OS 전력 가이드: 화면을 끈 뒤에도 센서 리스너가 남아 있으면 안 됨(unregistered 확인) — 시스템(헬스·시스템 UI) 외 앱의 가속도·심박 연속 등록은 주의"

    const val GMS_SHARE_PERCENT = 15.0
    const val SOURCE_GMS = "2026년 4~7월 Galaxy Watch 6/7/8 의 Play 서비스 과소모 사례: 배터리 사용량 1위, 10~16.5% (9to5Google·Android Authority·SamMobile 보도)"

    const val GAUGE_LOW_RATIO = 0.60
    const val GAUGE_HIGH_RATIO = 1.40
    const val SOURCE_GAUGE = "경험칙 — 전하량 계수기(Charge counter µAh) ÷ 표시 잔량으로 역산한 100% 용량이 공칭의 60% 미만 또는 140% 초과면 잔량 표시 불일치"

    const val SOURCE_DRIFT = "battery-core GaugeDriftRule — 화면 꺼짐·충전 안 함·전류 60mA 이하인 쉬는 표본의 전압을 리튬폴리머 OCV 근사표(4.2V/4.35V 셀, 완충 전압으로 고름)로 잔량으로 바꿔 표시 잔량과 비교. 전하량 계수기(mAh/h)와 표시(%/h)의 차이는 가짜 소모"

    const val CAPACITY_LOW_PERCENT = 80.0
    const val SOURCE_CAPACITY = "삼성케어플러스 스마트폰 배터리 교체 기준 '효율 80% 미만'을 준용 (워치의 공식 기준은 미공개)"

    const val SCREEN_SHARE = 0.15
    const val SOURCE_USAGE = "경험칙 — 배터리 사용 시간 중 화면 켜짐 비율 15% 초과면 사용량이 소모를 지배. 삼성 공식 측정 조건(알림 226건·통화 11분·운동 60분/24시간)보다 훨씬 많은 사용"
    const val GPS_LONG_MS = 30 * 60_000L
    const val RADIO_SHARE = 0.10
    const val DRAIN_GAP_RATIO = 0.30

    const val FORCED_RESTART = "홈 + 뒤로 가기 버튼을 꺼질 때까지(7초 이상) 눌러 강제 재시작하세요. 효과가 없으면 30초까지 누르고 있으세요"
}
