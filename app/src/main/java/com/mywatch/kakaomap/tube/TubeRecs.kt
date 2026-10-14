package com.mywatch.kakaomap.tube

/**
 * 재생 화면이 알려 준 유튜브 믹스 하나: [seed] = 처음 동영상(본 것), [ids] = 유튜브가 이어 준 추천, [at] = 받은 때(ms).
 * 동영상 번호만 — 제목·그림은 보여 줄 때 새로 받는다
 */
data class Harvest(val seed: String, val ids: List<String>, val at: Long)

/** 받아 둔 믹스 추천 (워치 안에만) */
object MixHarvests {
    /** 믹스 하나에서 받아 두는 추천 수 (재생 화면이 한 번에 받는 50개 중 처음 것을 뺀 수) */
    const val MAX_IDS = 49

    /** 추천 번호 다듬기: 모양이 맞는 것만 · 같은 것은 한 번 · 씨앗은 빼고 · [MAX_IDS] 개까지 (순서 그대로) */
    internal fun clean(seed: String, ids: List<String>): List<String> =
        ids.asSequence().filter { TubeLinks.isId(it) && it != seed }.distinct().take(MAX_IDS).toList()
}
