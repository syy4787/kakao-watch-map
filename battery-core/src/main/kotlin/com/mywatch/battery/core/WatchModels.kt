package com.mywatch.battery.core

/** 갤럭시 워치9 모델명 → 공칭 배터리 용량·크기·공식 충전 시간. 모르는 모델은 null(그러면 mAh 계산·충전 속도 판단은 생략) */
object WatchModels {
    private class Entry(val prefix: String, val mah: Int, val size: String, val fullChargeMin: Int)

    private val table = listOf(
        // 공식 0→100% 충전 시간: 40mm 80분 · 44mm 90분 (삼성 발표 — Android Authority 2026-08-12, Gadget Hacks 2026-10-13)
        Entry("SM-L35", 445, "44mm", 90),   // SM-L350 블루투스, SM-L355 LTE (갤럭시 워치9 44mm, 445 mAh typical)
        Entry("SM-L34", 390, "40mm", 80),   // SM-L340 블루투스, SM-L345 LTE (갤럭시 워치9 40mm, 390 mAh typical)
    )

    private fun entry(model: String?) = model?.trim()?.uppercase()?.let { m -> table.firstOrNull { m.startsWith(it.prefix) } }

    fun nominalMah(model: String?): Int? = entry(model)?.mah

    fun sizeLabel(model: String?): String? = entry(model)?.size

    /** 삼성이 밝힌 0→100% 충전 시간(분) */
    fun fullChargeMinutes(model: String?): Int? = entry(model)?.fullChargeMin
}
