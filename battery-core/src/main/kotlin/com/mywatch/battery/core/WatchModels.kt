package com.mywatch.battery.core

/** 갤럭시 워치9 모델명 → 공칭 배터리 용량. 모르는 모델은 null(그러면 mAh 계산은 생략) */
object WatchModels {
    private val table = listOf(
        // (접두, 용량 mAh, 크기)
        Triple("SM-L35", 445, "44mm"),   // SM-L350 블루투스, SM-L355 LTE (갤럭시 워치9 44mm, 445 mAh typical)
        Triple("SM-L34", 390, "40mm"),   // SM-L340 블루투스, SM-L345 LTE (갤럭시 워치9 40mm, 390 mAh typical)
    )

    private fun entry(model: String?) = model?.trim()?.uppercase()?.let { m -> table.firstOrNull { m.startsWith(it.first) } }

    fun nominalMah(model: String?): Int? = entry(model)?.second

    fun sizeLabel(model: String?): String? = entry(model)?.third
}
