package com.mywatch.kakaomap.ui

import android.app.Activity
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.wear.widget.SwipeDismissFrameLayout
import androidx.wear.widget.WearableLinearLayoutManager
import androidx.wear.widget.WearableRecyclerView
import com.mywatch.kakaomap.R
import kotlin.math.abs

/**
 * 둥근 화면용 목록 화면 공통 설정.
 * [look] = 지도(가운데부터, 좌우 여백 넉넉히) · 유튜브(유튜브 뮤직처럼 제목은 위에, 줄은 넓고 크게 —
 * 대신 둥근 화면 위·아래 가장자리로 갈수록 줄을 조금 작게·흐리게 해서 원 밖으로 덜 나가게. Wear OS 목록처럼)
 */
class WearList(activity: Activity, onClick: (Row) -> Unit, onLongClick: ((Row) -> Unit)? = null, look: Look = Look.MAP) {

    val list: WearableRecyclerView = activity.findViewById(R.id.list)
    private val status: TextView = activity.findViewById(R.id.status)
    private val swipe: SwipeDismissFrameLayout = activity.findViewById(R.id.swipe)
    val adapter = RowAdapter(onClick, onLongClick, look)

    /**
     * 오른쪽으로 쓸었을 때 화면을 닫지 않고 할 일 (화면 안에서 한 단계 뒤로 — 옆 버튼의 '뒤로'와 같게).
     * null 이면 쓸기를 받지 않아 시스템이 화면을 닫는다 (Wear OS 는 쓸기로 액티비티를 닫을 뿐 '뒤로' 처리를 부르지 않는다 —
     * 예전엔 검색 결과·유튜브 채널에서 쓸면 앱 화면이 통째로 닫혔다)
     */
    var swipeBack: (() -> Unit)? = null
        set(value) {
            field = value
            if (swipe.isSwipeable != (value != null)) swipe.isSwipeable = value != null
        }

    init {
        swipe.isSwipeable = false
        swipe.addCallback(object : SwipeDismissFrameLayout.Callback() {
            // 쓸려 나간 목록은 제자리로 돌아온 뒤 불린다 — 바로 앞 화면을 그린다
            override fun onDismissed(layout: SwipeDismissFrameLayout) {
                swipeBack?.invoke()
            }
        })
        val dm = activity.resources.displayMetrics
        if (look == Look.TUBE) {
            val side = (dm.widthPixels * TUBE_SIDE).toInt()
            // 제목이 화면 위쪽에 오게(가운데부터 시작하지 않음), 마지막 줄은 가운데 근처까지 올라올 수 있게
            list.isEdgeItemsCenteringEnabled = false
            list.setPadding(side, (dm.heightPixels * TUBE_TOP).toInt(), side, (dm.heightPixels * TUBE_BOTTOM).toInt())
            list.layoutManager = WearableLinearLayoutManager(activity, EdgeScale())
            status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            status.setTextColor(activity.getColor(R.color.text_primary))
        } else {
            val side = (dm.widthPixels * 0.11f).toInt()
            list.setPadding(side, list.paddingTop, side, list.paddingBottom)
            // 첫 줄·마지막 줄도 화면 가운데까지 올라오게 (둥근 화면에서 잘림 방지)
            list.isEdgeItemsCenteringEnabled = true
            list.layoutManager = WearableLinearLayoutManager(activity, null)
        }
        list.adapter = adapter
        // 줄이 바뀌면 그 자리에서 바로 (실시간 도착처럼 자주 바뀌는 줄이 깜빡이지 않게 — 움직임 효과 없음)
        list.itemAnimator = null
        list.requestFocus()   // 베젤·크라운으로 스크롤
    }

    var rows: List<Row>
        get() = adapter.rows
        set(value) {
            adapter.rows = value
            status.visibility = View.GONE
            list.visibility = View.VISIBLE
            // 숨겼다 다시 보이면 포커스가 풀려 베젤·크라운 스크롤이 안 먹으므로 다시 잡는다
            if (!list.hasFocus()) list.requestFocus()
        }

    fun showStatus(text: String) {
        status.text = text
        status.visibility = View.VISIBLE
        list.visibility = View.INVISIBLE
    }

    /**
     * 화면 가운데에서 멀어질수록 줄을 조금 작게(가장자리에서 82%), 아주 끝은 흐리게.
     * 줄은 가운데 쪽 모서리를 기준으로 줄어 이웃 줄과 붙어 보인다
     */
    internal class EdgeScale : WearableLinearLayoutManager.LayoutCallback() {
        override fun onLayoutFinished(child: View, parent: RecyclerView) {
            val h = parent.height
            if (h <= 0 || child.height <= 0) return
            val center = h / 2f
            val mid = child.top + child.height / 2f
            val d = abs(mid - center) / center
            val s = 1f - (1f - EDGE_SCALE) * smooth(0.45f, 1.05f, d)
            child.pivotX = child.width / 2f
            child.pivotY = if (mid > center) 0f else child.height.toFloat()
            child.scaleX = s
            child.scaleY = s
            child.alpha = 1f - (1f - EDGE_ALPHA) * smooth(0.7f, 1.15f, d)
        }

        private fun smooth(from: Float, to: Float, x: Float): Float {
            val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
            return t * t * (3 - 2 * t)
        }
    }

    companion object {
        /** 유튜브 화면 좌우 여백 (화면 폭에 대한 비율) — 지도(11%)보다 좁게, 줄을 넓게 */
        const val TUBE_SIDE = 0.065f
        /** 유튜브 화면 위 여백 — 제목이 둥근 화면 위쪽 안에 */
        const val TUBE_TOP = 0.11f
        /** 유튜브 화면 아래 여백 — 마지막 줄을 가운데 근처까지 올려 볼 수 있게 */
        const val TUBE_BOTTOM = 0.30f
        const val EDGE_SCALE = 0.82f
        const val EDGE_ALPHA = 0.55f
    }
}
