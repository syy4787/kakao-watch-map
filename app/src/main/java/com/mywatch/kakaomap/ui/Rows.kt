package com.mywatch.kakaomap.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.mywatch.kakaomap.R
import com.mywatch.kakaomap.data.Favorite
import com.mywatch.kakaomap.data.FavoriteSlot
import com.mywatch.kakaomap.model.Format
import com.mywatch.kakaomap.model.Place
import com.mywatch.kakaomap.model.SimpleRoute
import com.mywatch.kakaomap.model.TransitGuide
import com.mywatch.kakaomap.model.TransitRoute
import com.mywatch.kakaomap.model.TravelMode

/** 목록 화면에 들어가는 줄 종류 */
sealed class Row {
    data class Header(val title: String, val subtitle: String? = null) : Row()
    data class Action(val id: String, @param:DrawableRes val icon: Int, val text: String, val accent: Boolean = false) : Row()
    data class PlaceItem(val place: Place, val recent: Boolean = false) : Row()
    data class FavoriteItem(val slot: FavoriteSlot) : Row()
    data class ModeTabs(val selected: TravelMode) : Row()
    data class TransitCard(val index: Int, val route: TransitRoute, val live: String? = null) : Row()
    data class SimpleCard(val route: SimpleRoute) : Row()
    data class Step(
        val color: Int,
        @param:DrawableRes val icon: Int,
        val chip: String?,
        val title: String,
        val sub: String?,
        val live: String? = null,
        val key: String = "",
    ) : Row()
    data class Note(val text: String) : Row()
}

/** 여러 줄 종류를 한 목록에 그리는 어댑터. [onLongClick] 이 있으면 장소·집·회사 줄을 길게 눌러 메뉴를 연다 */
class RowAdapter(
    private val onClick: (Row) -> Unit,
    private val onLongClick: ((Row) -> Unit)? = null,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var rows: List<Row> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    fun replace(index: Int, row: Row) {
        if (index !in rows.indices) return
        rows = rows.toMutableList().also { it[index] = row }
    }

    override fun getItemCount() = rows.size

    override fun getItemViewType(position: Int) = when (rows[position]) {
        is Row.Header -> R.layout.item_header
        is Row.Action -> R.layout.item_action
        is Row.PlaceItem -> R.layout.item_place
        is Row.FavoriteItem -> R.layout.item_favorite
        is Row.ModeTabs -> R.layout.item_mode_tabs
        is Row.TransitCard -> R.layout.item_transit_card
        is Row.SimpleCard -> R.layout.item_simple_card
        is Row.Step -> R.layout.item_step
        is Row.Note -> R.layout.item_note
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        object : RecyclerView.ViewHolder(LayoutInflater.from(parent.context).inflate(viewType, parent, false)) {}

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val v = holder.itemView
        when (val row = rows[position]) {
            is Row.Header -> {
                v.text(R.id.title).text = row.title
                v.text(R.id.subtitle).show(row.subtitle)
                v.setOnClickListener(null)
                v.isClickable = false
            }
            is Row.Action -> {
                v.setBackgroundResource(if (row.accent) R.drawable.bg_action_accent else R.drawable.bg_action)
                v.findViewById<ImageView>(R.id.icon).setImageResource(row.icon)
                v.text(R.id.text).text = row.text
                v.contentDescription = row.text
                v.setOnClickListener { onClick(row) }
            }
            is Row.PlaceItem -> {
                val p = row.place
                v.text(R.id.name).text = p.name
                val meta = listOfNotNull(
                    p.category.ifBlank { null },
                    p.distanceM?.let { Format.distance(it) },
                ).joinToString(" · ")
                v.text(R.id.meta).show(meta.ifBlank { null })
                v.text(R.id.address).show(p.displayAddress.ifBlank { null })
                v.setOnClickListener { onClick(row) }
                v.longClick(row)
            }
            is Row.FavoriteItem -> {
                val slot = row.slot
                v.findViewById<ImageView>(R.id.icon).setImageResource(
                    if (slot.kind == Favorite.HOME) R.drawable.ic_home else R.drawable.ic_work
                )
                v.text(R.id.title).text = slot.title
                v.text(R.id.subtitle).apply {
                    text = slot.subtitle
                    setTextColor(color(v, if (slot.place == null) R.color.accent_light else R.color.text_secondary))
                }
                v.contentDescription = "${slot.title}, ${slot.subtitle}"
                v.setOnClickListener { onClick(row) }
                if (slot.place != null) v.longClick(row) else v.longClick(null)
            }
            is Row.ModeTabs -> {
                val tabs = mapOf(
                    R.id.tab_transit to TravelMode.TRANSIT,
                    R.id.tab_walk to TravelMode.WALK,
                    R.id.tab_bike to TravelMode.BIKE,
                )
                tabs.forEach { (id, mode) ->
                    val tab = v.findViewById<View>(id)
                    tab.isSelected = mode == row.selected
                    tab.setOnClickListener { onClick(Row.ModeTabs(mode)) }
                }
            }
            is Row.TransitCard -> bindTransit(v, row)
            is Row.SimpleCard -> {
                val r = row.route
                v.text(R.id.time).text = Format.duration(r.totalTimeSec)
                v.text(R.id.meta).text = "${Format.distance(r.totalDistanceM)} · ${Format.clockAfter(r.totalTimeSec)} 도착"
                v.setOnClickListener(null)
                v.isClickable = false
            }
            is Row.Step -> {
                v.findViewById<View>(R.id.line).setBackgroundColor(row.color)
                v.findViewById<ImageView>(R.id.icon).apply {
                    setImageResource(row.icon)
                    imageTintList = ColorStateList.valueOf(row.color)
                }
                v.text(R.id.chip).apply {
                    if (row.chip.isNullOrBlank()) visibility = View.GONE else {
                        visibility = View.VISIBLE
                        text = row.chip
                        backgroundTintList = ColorStateList.valueOf(row.color)
                    }
                }
                v.text(R.id.title).text = row.title
                v.text(R.id.sub).show(row.sub)
                v.text(R.id.live).show(row.live)
                v.setOnClickListener(null)
                v.isClickable = false
            }
            is Row.Note -> {
                (v as TextView).text = row.text
            }
        }
    }

    private fun bindTransit(v: View, row: Row.TransitCard) {
        val r = row.route
        v.text(R.id.time).text = Format.duration(r.totalTimeSec)
        v.text(R.id.arrive).text = "${Format.clockAfter(r.totalTimeSec)} 도착"
        v.text(R.id.meta).text = listOfNotNull(
            r.fare?.let { Format.fare(it) },
            Format.transfers(r.transfers),
            "도보 ${Format.duration(r.walkTimeSec)}",
        ).joinToString(" · ")
        v.findViewById<SegmentBarView>(R.id.bar).setRoute(r)
        val first = r.firstRide
        v.text(R.id.first).text = if (first == null) "도보로 이동" else TransitGuide.boardText(first)
        v.text(R.id.live).show(row.live)
        v.setOnClickListener { onClick(row) }
    }

    private fun View.text(id: Int): TextView = findViewById(id)

    private fun View.longClick(row: Row?) {
        val handler = onLongClick
        if (row == null || handler == null) {
            setOnLongClickListener(null)
            isLongClickable = false
        } else {
            setOnLongClickListener { handler(row); true }
        }
    }

    private fun TextView.show(value: String?) {
        if (value.isNullOrBlank()) {
            visibility = View.GONE
        } else {
            visibility = View.VISIBLE
            text = value
        }
    }

    companion object {
        fun color(view: View, res: Int) = ContextCompat.getColor(view.context, res)
    }
}
