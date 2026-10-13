package com.mywatch.kakaomap.ui

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
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
import com.mywatch.kakaomap.model.RouteChoice
import com.mywatch.kakaomap.model.TransitFilter
import com.mywatch.kakaomap.model.TransitGuide
import com.mywatch.kakaomap.model.TransitRoute
import com.mywatch.kakaomap.model.TransitSort
import com.mywatch.kakaomap.model.TravelMode

/** 목록 화면에 들어가는 줄 종류 */
sealed class Row {
    data class Header(val title: String, val subtitle: String? = null) : Row()
    data class Action(val id: String, @param:DrawableRes val icon: Int, val text: String, val accent: Boolean = false) : Row()
    data class PlaceItem(val place: Place, val recent: Boolean = false) : Row()
    data class FavoriteItem(val slot: FavoriteSlot) : Row()
    /**
     * 이동수단 탭. [seconds] = 탭마다 그 탭을 누르면 고르게 될 길의 시간(아직 모르면 없음),
     * [fastest] = 가장 빨리 도착하는 수단 (둘 이상 결과가 있을 때만). [tapped] = 누른 탭
     */
    data class ModeTabs(
        val selected: TravelMode,
        val seconds: Map<TravelMode, Int> = emptyMap(),
        val fastest: TravelMode? = null,
        val tapped: TravelMode = selected,
    ) : Row()
    data class TransitCard(val index: Int, val route: TransitRoute, val live: String? = null, val fastest: Boolean = false) : Row()
    /** 도보·자전거 경로 옵션 카드 (자전거도로 우선 · 최단 거리 · 편안한 길 …). [index] = 카드 순서 */
    data class OptionCard(val index: Int, val choice: RouteChoice, val selected: Boolean, val fastest: Boolean) : Row()
    /** 대중교통 거르기·정렬 칩. [tapped] = 누른 칩 (FILTER / SORT) */
    data class TransitChoice(
        val filter: TransitFilter,
        val filterCount: Int,
        val canFilter: Boolean,
        val sort: TransitSort,
        val tapped: Int = 0,
    ) : Row() {
        companion object {
            const val FILTER = 1
            const val SORT = 2
        }
    }
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
        is Row.TransitChoice -> R.layout.item_transit_options
        is Row.OptionCard -> R.layout.item_route_option
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
                val tabs = listOf(
                    Triple(R.id.tab_transit, R.id.tab_transit_label, TravelMode.TRANSIT),
                    Triple(R.id.tab_walk, R.id.tab_walk_label, TravelMode.WALK),
                    Triple(R.id.tab_bike, R.id.tab_bike_label, TravelMode.BIKE),
                )
                tabs.forEach { (id, labelId, mode) ->
                    val tab = v.findViewById<View>(id)
                    val name = modeName(v, mode)
                    val time = row.seconds[mode]?.let { Format.duration(it) }
                    val fastest = mode == row.fastest
                    // 카카오맵처럼 아이콘 아래에 걸리는 시간 (모를 땐 수단 이름), 가장 빠른 수단은 초록
                    v.text(labelId).apply {
                        text = time ?: name
                        setTextColor(color(v, if (fastest) R.color.fastest else R.color.text_secondary))
                        setTypeface(null, if (fastest) Typeface.BOLD else Typeface.NORMAL)
                    }
                    tab.contentDescription = listOfNotNull(name, time, if (fastest) "가장 빠름" else null).joinToString(", ")
                    tab.isSelected = mode == row.selected
                    tab.setOnClickListener { onClick(row.copy(tapped = mode)) }
                }
            }
            is Row.TransitCard -> bindTransit(v, row)
            is Row.TransitChoice -> {
                v.text(R.id.filter).apply {
                    visibility = if (row.canFilter) View.VISIBLE else View.GONE
                    text = "${row.filter.label} ${row.filterCount}"
                    isActivated = row.filter != TransitFilter.ALL
                    contentDescription = "경로 종류 ${row.filter.label} ${row.filterCount}개, 눌러서 바꾸기"
                    setOnClickListener { onClick(row.copy(tapped = Row.TransitChoice.FILTER)) }
                }
                v.text(R.id.sort).apply {
                    text = row.sort.label
                    isActivated = row.sort != TransitSort.DEFAULT
                    contentDescription = "정렬 ${row.sort.label}, 눌러서 바꾸기"
                    setOnClickListener { onClick(row.copy(tapped = Row.TransitChoice.SORT)) }
                }
                v.setOnClickListener(null)
                v.isClickable = false
            }
            is Row.OptionCard -> {
                val r = row.choice.route
                val time = Format.duration(r.totalTimeSec)
                val meta = "${Format.distance(r.totalDistanceM)} · ${Format.clockAfter(r.totalTimeSec)} 도착"
                v.text(R.id.label).apply {
                    text = row.choice.label
                    setTypeface(null, if (row.selected) Typeface.BOLD else Typeface.NORMAL)
                }
                v.text(R.id.badge).visibility = if (row.fastest) View.VISIBLE else View.GONE
                v.text(R.id.time).text = time
                v.text(R.id.meta).text = meta
                v.isSelected = row.selected
                v.contentDescription = listOfNotNull(
                    row.choice.label, time, meta,
                    if (row.fastest) "가장 빠름" else null,
                    if (row.selected) "선택됨" else "눌러서 이 길로",
                ).joinToString(", ")
                v.setOnClickListener { onClick(row) }
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
        v.text(R.id.time).text = timeAndArrival(v, r.totalTimeSec)
        v.text(R.id.meta).text = listOfNotNull(
            r.fare?.let { Format.fare(it) },
            Format.transfers(r.transfers),
            "도보 ${Format.duration(r.walkTimeSec)}",
        ).joinToString(" · ")
        v.findViewById<SegmentBarView>(R.id.bar).setRoute(r)
        // 기차·고속버스가 있으면 그 출발 시각("KTX 105 · 대전역 09:58 출발"), 없으면 첫 버스·지하철
        v.text(R.id.first).text = TransitGuide.headline(r) ?: "도보로 이동"
        v.text(R.id.live).show(row.live)
        v.text(R.id.badge).visibility = if (row.fastest) View.VISIBLE else View.GONE
        v.setOnClickListener { onClick(row) }
    }

    /**
     * "1시간 41분  오후 11:06 도착" — 총 시간은 굵고 크게(20sp), 도착 시각은 작게(12sp).
     * 도착 시각은 붙은 빈칸으로 묶어 한 덩어리로 줄바꿈된다 (한 줄에 안 들어가면 통째로 다음 줄)
     */
    private fun timeAndArrival(v: View, seconds: Int): CharSequence {
        val duration = Format.duration(seconds)
        val arrive = "${Format.clockAfter(seconds)} 도착".replace(' ', '\u00A0')
        return SpannableStringBuilder(duration).apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append("  ")
            val start = length
            append(arrive)
            setSpan(RelativeSizeSpan(12f / 20f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(color(v, R.color.text_secondary)), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun modeName(v: View, mode: TravelMode): String = v.context.getString(
        when (mode) {
            TravelMode.TRANSIT -> R.string.mode_transit
            TravelMode.WALK -> R.string.mode_walk
            TravelMode.BIKE -> R.string.mode_bike
        }
    )

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
