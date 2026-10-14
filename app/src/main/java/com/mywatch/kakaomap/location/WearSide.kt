package com.mywatch.kakaomap.location

import kotlin.math.hypot

/**
 * 워치를 어느 쪽으로 찼는지 — 화면의 위쪽이 실제로 앞(내가 보는 쪽)을 향하는지, 몸 쪽을 향하는지를 센서로 알아낸다.
 *
 * 갤럭시워치는 '손목 및 버튼 위치'에서 버튼을 반대로 두면 화면을 180° 돌려 그리지만, 앱에는 화면 회전을 알려 주지 않는다
 * (오른손목 사용자: "정면을 보면 뒤를 본다고 뜸"). 그래서 앱이 아는 '화면 위쪽'(12시)이 실제로는 몸 쪽일 수 있다.
 *
 * 화면을 보는 동안 화면은 늘 얼굴 쪽으로 기운다(눈은 워치보다 위·뒤). 그러니 화면이 바라보는 쪽의 반대(−Z)의 수평 방향은
 * 차는 방법과 상관없이 '앞'이다. 화면이 얼굴 쪽으로 적당히 기운 표본에서 '화면 위쪽'의 수평 방향이 그 앞과 같은 쪽인지
 * 반대쪽인지를 모아(지수 평균) 판단한다. 화면을 옆으로 굴렸거나(앞뒤를 알 수 없음) 수평·수직·땅을 보는 자세는 쓰지 않는다.
 * 바뀐 판단은 [HeadingSensor] 가 저장해 다음에도 처음부터 맞게 쓴다.
 */
class WearSide(flipped: Boolean = false) {

    /** 화면의 위쪽이 실제로는 몸 쪽을 향한다 */
    var flipped: Boolean = flipped
        private set

    /** +1 = 화면 위쪽이 앞(그대로 찼음), −1 = 몸 쪽(뒤집어 찼음) */
    private var evidence = if (flipped) -1.0 else 1.0

    /**
     * 센서 표본 하나([r] = 회전 행렬, [displayRotation] = 시스템이 알려 준 화면 회전)를 본다.
     * 판단이 바뀌었으면 true
     */
    fun observe(r: FloatArray, displayRotation: Int): Boolean {
        val zUp = r[8].toDouble()
        if (zUp < MIN_Z_UP || zUp > MAX_Z_UP) return false            // 화면이 하늘을 보며 얼굴 쪽으로 기운 자세만
        val ze = r[2].toDouble()
        val zn = r[5].toDouble()
        val zh = hypot(ze, zn)
        if (zh < MIN_TILT_H) return false
        val (ue, un) = HeadingMath.screenUp(r, displayRotation)
        val uh = hypot(ue, un)
        if (uh < MIN_UP_H) return false
        // 화면 위쪽(수평) 과 앞(−Z, 수평) 이 같은 쪽이면 +1, 반대쪽이면 −1. 옆으로 굴린 자세(직각에 가까움)는 버린다
        val agree = (ue * -ze + un * -zn) / (uh * zh)
        if (agree > -MIN_AGREE && agree < MIN_AGREE) return false
        evidence += RATE * ((if (agree > 0) 1.0 else -1.0) - evidence)
        val next = when {
            evidence < -SWITCH -> true
            evidence > SWITCH -> false
            else -> flipped
        }
        if (next == flipped) return false
        flipped = next
        return true
    }

    private companion object {
        /** 화면이 하늘에서 70° 안쪽으로 기움 (cos 70°) */
        const val MIN_Z_UP = 0.34
        /** 10° 넘게 기움 (cos 10°) — 수평에 가까우면 앞뒤를 가리지 못한다 */
        const val MAX_Z_UP = 0.985
        const val MIN_TILT_H = 0.17
        const val MIN_UP_H = 0.3
        /** 두 방향이 이루는 각이 53° 안쪽(같은 쪽) 또는 127° 바깥(반대쪽)일 때만 */
        const val MIN_AGREE = 0.6
        /** 표본 하나의 무게 — 화면을 3~4초(초당 16번) 보면 판단이 바뀐다 (남에게 잠깐 보여 준 것으로는 안 바뀌게) */
        const val RATE = 0.025
        /** 판단을 바꾸는 문턱 (가운데 ±0.5 는 그대로 — 잠깐 이상한 자세로는 안 바뀜) */
        const val SWITCH = 0.5
    }
}
