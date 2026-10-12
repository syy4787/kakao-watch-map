package com.mywatch.kakaomap

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * 지도를 감싸는 레이아웃.
 *
 * Wear OS는 화면을 왼쪽→오른쪽으로 쓸면 앱이 닫히는데, 그러면 지도를 오른쪽으로 끌 때마다 앱이 꺼집니다.
 * "가로로 스크롤할 수 있다"고 알려 주면 SwipeDismissFrameLayout 이
 * 화면 왼쪽 가장자리(폭의 10%)에서 시작한 스와이프만 '닫기'로 처리하고,
 * 나머지 드래그는 전부 지도 이동으로 넘겨 줍니다. (Android 공식 가이드의 지도 처리 방식)
 */
class PannableFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    override fun canScrollHorizontally(direction: Int): Boolean = true
}
