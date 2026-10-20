package com.mywatch.battery.core

enum class Grade {
    NORMAL, CAUTION, ABNORMAL,
    /** 잴 수 없음(표본 부족) */
    UNKNOWN;

    val label: String get() = when (this) { NORMAL -> "정상"; CAUTION -> "주의"; ABNORMAL -> "비정상"; UNKNOWN -> "모름" }

    companion object {
        /** UNKNOWN 은 무시하고 가장 나쁜 등급 */
        fun worst(grades: Iterable<Grade>): Grade = grades.filter { it != UNKNOWN }.maxByOrNull { it.ordinal } ?: UNKNOWN
    }
}

/**
 * %/h 기준표. 갤럭시 워치9 44mm(445 mAh) 기준이며 40mm 도 같은 퍼센트 기준을 쓴다(보고서에 그렇게 밝힌다).
 * 대기 기준은 Google 의 Android vitals 워치페이스 기준을 그대로 가져왔고, 나머지는 공식 사용시간·리뷰 실측에서 역산한 값.
 */
object Thresholds {
    const val SOURCE_IDLE = "Android vitals 워치페이스 기준: 시간당 4.44% 초과는 과소모, 3.2% 미만이 목표"
    const val SOURCE_MIXED = "삼성 공칭 30~40시간(시간당 2.5~3.3%)과 리뷰 실측 30~35시간에서 역산 — 20시간 미만(5%/h 이상)은 비정상"
    const val SOURCE_NIGHT = "밤은 대기 구간이라 Android vitals 기준을 그대로 — 리뷰 실측(수면 측정 포함 밤사이 15~25% ≈ 2~3.2%/h)과도 맞음"
    const val SOURCE_EXPERIMENT = "비행기·절전·시계만 모드 기대치는 공개 측정이 없어 경험칙(1~2%/h)"

    const val IDLE_NORMAL = 3.2
    const val IDLE_ABNORMAL = 4.44
    const val MIXED_NORMAL = 3.3
    const val MIXED_ABNORMAL = 5.0
    const val NIGHT_NORMAL = 3.2
    const val NIGHT_ABNORMAL = 4.44
    const val EXPERIMENT_NORMAL = 1.0
    const val EXPERIMENT_ABNORMAL = 2.0

    fun idle(pph: Double?) = grade(pph, IDLE_NORMAL, IDLE_ABNORMAL, abnormalInclusive = false)
    fun mixed24h(pph: Double?) = grade(pph, MIXED_NORMAL, MIXED_ABNORMAL, abnormalInclusive = true)
    fun overnight(pph: Double?) = grade(pph, NIGHT_NORMAL, NIGHT_ABNORMAL, abnormalInclusive = false)
    fun experiment(pph: Double?) = grade(pph, EXPERIMENT_NORMAL, EXPERIMENT_ABNORMAL, abnormalInclusive = false)

    private fun grade(pph: Double?, normalMax: Double, abnormalFrom: Double, abnormalInclusive: Boolean): Grade = when {
        pph == null -> Grade.UNKNOWN
        pph <= normalMax -> Grade.NORMAL
        abnormalInclusive && pph >= abnormalFrom -> Grade.ABNORMAL
        !abnormalInclusive && pph > abnormalFrom -> Grade.ABNORMAL
        else -> Grade.CAUTION
    }
}
