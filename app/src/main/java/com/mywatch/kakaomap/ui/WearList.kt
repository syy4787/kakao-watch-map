package com.mywatch.kakaomap.ui

import android.app.Activity
import android.view.View
import android.widget.TextView
import androidx.wear.widget.WearableLinearLayoutManager
import androidx.wear.widget.WearableRecyclerView
import com.mywatch.kakaomap.R

/** 둥근 화면용 목록 화면 공통 설정 */
class WearList(activity: Activity, onClick: (Row) -> Unit, onLongClick: ((Row) -> Unit)? = null) {

    val list: WearableRecyclerView = activity.findViewById(R.id.list)
    private val status: TextView = activity.findViewById(R.id.status)
    val adapter = RowAdapter(onClick, onLongClick)

    init {
        val dm = activity.resources.displayMetrics
        val side = (dm.widthPixels * 0.11f).toInt()
        list.setPadding(side, list.paddingTop, side, list.paddingBottom)
        // 첫 줄·마지막 줄도 화면 가운데까지 올라오게 (둥근 화면에서 잘림 방지)
        list.isEdgeItemsCenteringEnabled = true
        list.layoutManager = WearableLinearLayoutManager(activity, null)
        list.adapter = adapter
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
}
