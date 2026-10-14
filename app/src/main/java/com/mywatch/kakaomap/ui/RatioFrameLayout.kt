package com.mywatch.kakaomap.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

/** 폭에 맞춰 16:9 높이가 되는 칸 (유튜브 미리보기 자리) */
class RatioFrameLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(w * 9 / 16, View.MeasureSpec.EXACTLY))
    }
}
