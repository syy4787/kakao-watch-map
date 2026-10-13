package com.mywatch.kakaomap.ui

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.wear.ambient.AmbientLifecycleObserver
import com.mywatch.kakaomap.R

/**
 * 목록 화면(길찾기·경로 상세)의 저전력(손목 내림) 화면.
 * Wear OS 6 부터는 손목을 내려도 마지막 화면이 '켜진 채(어둡게)' 남고 화면도 멈추지 않으며, 안내 중이면
 * 워치페이스로 돌아가지도 않는다. 밝은 목록을 몇십 분씩 켜 두지 않게 검은 화면에 짧은 글만 두고,
 * 그동안 할 일(실시간 버스 새로 고침 등)은 [onAmbientChanged] 로 멈춘다. 번인 방지 이동은 시스템이 한다.
 */
class AmbientCover(
    private val activity: ComponentActivity,
    private val summary: () -> String,
    private val onAmbientChanged: (ambient: Boolean) -> Unit = {},
) {
    var inAmbient = false
        private set

    private var cover: TextView? = null

    private val callback = object : AmbientLifecycleObserver.AmbientLifecycleCallback {
        override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
            inAmbient = true
            show()
            onAmbientChanged(true)
        }

        /** 저전력 화면에서 1분쯤마다 — 글(남은 시간 등)만 고친다 */
        override fun onUpdateAmbient() {
            cover?.text = summary()
        }

        override fun onExitAmbient() {
            inAmbient = false
            cover?.visibility = View.GONE
            onAmbientChanged(false)
        }
    }

    init {
        activity.lifecycle.addObserver(AmbientLifecycleObserver(activity, callback))
    }

    private fun show() {
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val v = cover ?: TextView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.BLACK)
            setTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
            textSize = 14f
            gravity = Gravity.CENTER
            val side = (activity.resources.displayMetrics.widthPixels * 0.15f).toInt()
            setPadding(side, 0, side, 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isClickable = true   // 혹시 저전력 화면에서 눌려도 아래 목록이 눌리지 않게
            cover = this
            root.addView(this)
        }
        v.text = summary()
        v.visibility = View.VISIBLE
        v.bringToFront()
    }
}
