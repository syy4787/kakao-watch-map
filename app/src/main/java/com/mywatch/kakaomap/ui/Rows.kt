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
import androidx.recyclerview.widget.DiffUtil
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
import com.mywatch.kakaomap.tube.Queue
import com.mywatch.kakaomap.tube.TubeChannel
import com.mywatch.kakaomap.tube.TubeLinks
import com.mywatch.kakaomap.tube.TubePlaylist
import com.mywatch.kakaomap.tube.TubeText
import com.mywatch.kakaomap.tube.TubeVideo

/** 목록 화면에 들어가는 줄 종류 */
sealed class Row {
    /**
     * 제목. 유튜브 화면에서만: [art] = 영상 번호 — 제목 위에 그 동영상의 16:9 그림, [avatar] = 채널 사진 주소 — 동그랗게
     */
    data class Header(val title: String, val subtitle: String? = null, val art: String? = null, val avatar: String? = null) : Row()
    /**
     * 버튼. [accent] = 눈에 띄는 버튼(지도: 파랑, 유튜브: 흰색). 유튜브 화면에서만: [sub] = 아래 작은 줄,
     * [art] = 아이콘 대신 그 곡(영상 번호)의 그림, [selected] = 고르는 화면에서 지금 고른 것 (읽어 주기에 '선택됨')
     */
    data class Action(
        val id: String,
        @param:DrawableRes val icon: Int,
        val text: String,
        val accent: Boolean = false,
        val sub: String? = null,
        val art: String? = null,
        val selected: Boolean = false,
        /** 아이콘 대신 동그란 사진(채널·계정)의 주소 */
        val avatar: String? = null,
    ) : Row()
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
    /**
     * 유튜브 동영상 큰 카드 (16:9 미리보기 · 길이 · 굵은 제목 두 줄 · [meta] = '채널 · 조회수 · 언제').
     * 누르면 이 동영상부터 [queue] 를 이어서, 길게 누르면 메뉴
     */
    data class VideoCard(val video: TubeVideo, val meta: String, val queue: Queue) : Row()
    /** 채널 한 줄 (동그란 사진 · 이름 · [sub] = 구독자) */
    data class ChannelItem(val channel: TubeChannel, val sub: String?) : Row()
    /** 재생목록 한 줄 (미리보기 · 제목 · [sub] = '채널 · N개'). [own] = 내 재생목록 */
    data class PlaylistItem(val playlist: TubePlaylist, val sub: String?, val own: Boolean = false) : Row()
    /** 불러오는 동안 동영상 카드 자리 */
    data class Skeleton(val n: Int) : Row()
    /** 로그인 코드 (크게) + 폰에서 열 주소 */
    data class Code(val code: String, val url: String) : Row()
    /** 나란히 놓인 작은 버튼들 (누르면 같은 id 의 [Action] 처럼) */
    data class Chips(val items: List<Chip>) : Row()
    data class Chip(val id: String, @param:DrawableRes val icon: Int, val text: String)
}

/** 목록 모양: 지도(기본) · 유튜브(큰 글씨·흰 주 버튼·어두운 회색 알약·16:9 동영상 카드) */
enum class Look { MAP, TUBE }

/** 줄이 무엇인지 (내용이 바뀌어도 같은 것이면 같은 값) — 목록을 바꿀 때 달라진 줄만 고치려고 */
internal fun keyOf(row: Row): String = when (row) {
    is Row.Header -> "h:" + row.title
    is Row.Action -> "a:" + row.id
    is Row.PlaceItem -> "p:" + row.place.id + ":" + row.place.name
    is Row.FavoriteItem -> "f:" + row.slot.kind.name
    is Row.ModeTabs -> "tabs"
    is Row.TransitCard -> "t:" + row.index
    is Row.OptionCard -> "o:" + row.index
    is Row.TransitChoice -> "choice"
    is Row.Step -> "s:" + row.key + ":" + row.title
    is Row.Note -> "n:" + row.text
    is Row.VideoCard -> "v:" + row.video.id
    is Row.ChannelItem -> "c:" + row.channel.id
    is Row.PlaylistItem -> "l:" + row.playlist.id
    is Row.Skeleton -> "sk"
    is Row.Code -> "code"
    is Row.Chips -> "chips:" + row.items.joinToString(",") { it.id }
}

/** [keyOf] 를 목록 전체에 — 같은 열쇠가 또 나오면 '#2', '#3' … (같은 동영상이 두 구역에 있을 때 등) */
internal fun keysOf(rows: List<Row>): List<String> {
    val seen = HashMap<String, Int>()
    return rows.map { r ->
        val k = keyOf(r)
        val n = (seen[k] ?: 0) + 1
        seen[k] = n
        if (n == 1) k else "$k#$n"
    }
}

/**
 * 여러 줄 종류를 한 목록에 그리는 어댑터. [onLongClick] 이 있으면 장소·집·회사 줄을 길게 눌러 메뉴를 연다.
 * [look] = 제목·버튼·안내 줄의 모양 (곡 줄은 유튜브 화면에만 있다)
 */
class RowAdapter(
    private val onClick: (Row) -> Unit,
    private val onLongClick: ((Row) -> Unit)? = null,
    private val look: Look = Look.MAP,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /**
     * 줄들. 바꾸면 달라진 줄만 고친다 (같은 줄 = 같은 [keyOf]) — 위쪽 구역이 받아져 늘어나도 보고 있던 줄은 제자리
     * (예전엔 통째로 다시 그려 아래 목록이 밀리고, 누르려던 동영상이 손가락 아래에서 바뀌었다)
     */
    var rows: List<Row> = emptyList()
        set(value) {
            val old = field
            val oldKeys = keys
            val newKeys = keysOf(value)
            field = value
            keys = newKeys
            DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = old.size
                override fun getNewListSize() = value.size
                override fun areItemsTheSame(o: Int, n: Int) = oldKeys[o] == newKeys[n]
                override fun areContentsTheSame(o: Int, n: Int) = old[o] == value[n] && !showsClock(value[n])
            }, false).dispatchUpdatesTo(this)
        }

    /**
     * 그릴 때 시계로 'HH:MM 도착'을 정하는 카드 — 내용이 같아도 목록을 다시 넣을 때마다(1분마다 등) 다시 그린다
     * (안 그러면 10:00 에 연 목록이 10:20 에도 '10:25 도착')
     */
    private fun showsClock(row: Row) = row is Row.TransitCard || row is Row.OptionCard

    /** 줄마다 '같은 줄' 열쇠 (같은 것이 여러 번 나오면 몇 번째인지 붙임) */
    private var keys: List<String> = emptyList()

    fun replace(index: Int, row: Row) {
        if (index !in rows.indices) return
        rows = rows.toMutableList().also { it[index] = row }
    }

    override fun getItemCount() = rows.size

    override fun getItemViewType(position: Int) = when (rows[position]) {
        is Row.Header -> if (look == Look.TUBE) R.layout.item_tube_header else R.layout.item_header
        is Row.Action -> if (look == Look.TUBE) R.layout.item_tube_action else R.layout.item_action
        is Row.PlaceItem -> R.layout.item_place
        is Row.FavoriteItem -> R.layout.item_favorite
        is Row.ModeTabs -> R.layout.item_mode_tabs
        is Row.TransitCard -> R.layout.item_transit_card
        is Row.TransitChoice -> R.layout.item_transit_options
        is Row.OptionCard -> R.layout.item_route_option
        is Row.Step -> R.layout.item_step
        is Row.Note -> if (look == Look.TUBE) R.layout.item_tube_note else R.layout.item_note
        is Row.VideoCard -> R.layout.item_tube_card
        is Row.ChannelItem -> R.layout.item_tube_channel
        is Row.PlaylistItem -> R.layout.item_tube_playlist
        is Row.Skeleton -> R.layout.item_tube_skeleton
        is Row.Code -> R.layout.item_tube_code
        is Row.Chips -> R.layout.item_tube_chips
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        object : RecyclerView.ViewHolder(LayoutInflater.from(parent.context).inflate(viewType, parent, false)) {}

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val v = holder.itemView
        when (val row = rows[position]) {
            is Row.Header -> {
                v.text(R.id.title).text = row.title
                v.text(R.id.subtitle).show(row.subtitle)
                v.findViewById<ImageView>(R.id.art)?.art(row.art)
                v.findViewById<ImageView>(R.id.avatar)?.picture(row.avatar)
                v.setOnClickListener(null)
                v.isClickable = false
            }
            is Row.Action -> if (look == Look.TUBE) bindTubeAction(v, row) else {
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
                v.text(R.id.live).show(row.live?.let(Format::glued))
                v.setOnClickListener(null)
                v.isClickable = false
            }
            is Row.Note -> {
                (v as TextView).text = row.text
            }
            is Row.VideoCard -> bindCard(v, row)
            is Row.ChannelItem -> {
                v.findViewById<ImageView>(R.id.avatar).picture(row.channel.thumb, round = true, keepSpace = true)
                v.text(R.id.title).text = row.channel.title
                v.text(R.id.sub).show(row.sub)
                v.contentDescription = listOfNotNull(row.channel.title, row.sub).joinToString(", ")
                v.setOnClickListener { onClick(row) }
            }
            is Row.PlaylistItem -> {
                v.findViewById<ImageView>(R.id.thumb).picture(row.playlist.thumb, round = false, keepSpace = true)
                v.text(R.id.title).text = row.playlist.title
                v.text(R.id.sub).show(row.sub)
                v.contentDescription = listOfNotNull(v.context.getString(R.string.tube_playlist), row.playlist.title, row.sub).joinToString(", ")
                v.setOnClickListener { onClick(row) }
            }
            is Row.Skeleton -> {
                v.setOnClickListener(null)
                v.isClickable = false
            }
            is Row.Code -> {
                v.text(R.id.code).text = row.code
                v.text(R.id.url).text = row.url
                // 읽어 주기는 한 글자씩 (대소문자 그대로 넣어야 하는 코드)
                v.contentDescription = row.url + ", " + row.code.toList().joinToString(" ")
            }
            is Row.Chips -> bindChips(v as ViewGroup, row)
        }
    }

    /**
     * 동영상 카드 — 16:9 미리보기(480 크기, 위아래 검은 띠는 잘라 냄) + 오른쪽 아래 길이(생방송이면 빨간 LIVE)
     * + 굵은 제목 + '채널 · 조회수 · 언제'
     */
    private fun bindCard(v: View, row: Row.VideoCard) {
        val video = row.video
        v.findViewById<ImageView>(R.id.thumb).apply {
            clipToOutline = true
            TubeThumbs.into(this, TubeLinks.thumbnail(video.id, big = true))
        }
        val time = video.durationSec?.let { TubeText.clock(it) }
        val live = if (video.live) v.context.getString(R.string.tube_live) else null
        v.text(R.id.badge).apply {
            when {
                live != null -> {
                    visibility = View.VISIBLE
                    text = live
                    setBackgroundResource(R.drawable.bg_tube_badge_live)
                }
                time != null -> {
                    visibility = View.VISIBLE
                    text = time
                    setBackgroundResource(R.drawable.bg_tube_badge)
                }
                else -> visibility = View.GONE
            }
        }
        v.text(R.id.title).text = video.title
        v.text(R.id.meta).show(row.meta)
        v.contentDescription = listOfNotNull(video.title, row.meta.ifBlank { null }, time, live).joinToString(", ")
        v.setOnClickListener { onClick(row) }
        v.longClick(row)
    }

    /** 나란히 놓인 작은 버튼들 — 누르면 같은 id 의 버튼처럼 */
    private fun bindChips(group: ViewGroup, row: Row.Chips) {
        group.removeAllViews()
        val inflater = LayoutInflater.from(group.context)
        row.items.forEach { chip ->
            val item = inflater.inflate(R.layout.item_tube_chip, group, false)
            item.findViewById<ImageView>(R.id.icon).setImageResource(chip.icon)
            item.text(R.id.text).text = chip.text
            item.contentDescription = chip.text
            item.setOnClickListener { onClick(Row.Action(chip.id, chip.icon, chip.text)) }
            group.addView(item)
        }
    }

    /** 유튜브 화면 버튼 — 주 버튼은 흰 알약·검은 글자, 나머지는 어두운 회색 알약·흰 글자. 그림(동영상·채널 사진)이 있으면 아이콘 대신 */
    private fun bindTubeAction(v: View, row: Row.Action) {
        v.setBackgroundResource(if (row.accent) R.drawable.bg_tube_primary else R.drawable.bg_tube_button)
        val fg = color(v, if (row.accent) R.color.tube_on_primary else R.color.text_primary)
        val icon = v.findViewById<ImageView>(R.id.icon)
        val art = v.findViewById<ImageView>(R.id.art)
        if (row.avatar != null) art.picture(row.avatar, round = true) else {
            art.setBackgroundResource(R.drawable.bg_tube_art)
            art.art(row.art)
        }
        if (row.art != null || row.avatar != null) {
            icon.visibility = View.GONE
        } else {
            icon.visibility = View.VISIBLE
            icon.setImageResource(row.icon)
            icon.imageTintList = ColorStateList.valueOf(fg)
        }
        v.text(R.id.text).apply {
            text = row.text
            setTextColor(fg)
        }
        v.text(R.id.sub).apply {
            show(row.sub)
            setTextColor(color(v, if (row.accent) R.color.tube_on_primary_dim else R.color.tube_dim))
        }
        v.isSelected = row.selected
        v.contentDescription = listOfNotNull(
            row.text, row.sub, if (row.selected) v.context.getString(R.string.tube_selected) else null,
        ).joinToString(", ")
        v.setOnClickListener { onClick(row) }
    }

    /** 동영상 그림 칸: [id] 영상의 미리보기를 칸 모양대로(가운데) 넣는다. 없으면 숨기고, 늦게 오는 예전 그림도 안 붙게 */
    private fun ImageView.art(id: String?) {
        if (id == null) {
            visibility = View.GONE
            setTag(R.id.thumb, null)
            setImageDrawable(null)
            return
        }
        visibility = View.VISIBLE
        clipToOutline = true   // 둥근 모서리 (배경 모양대로 자르기)
        TubeThumbs.into(this, TubeLinks.thumbnail(id, big = true))
    }

    /**
     * 주소로 받는 그림 (채널 사진·재생목록 미리보기). [round] = 동그랗게. 주소가 없으면 숨기거나([keepSpace] 면 회색 자리만)
     */
    private fun ImageView.picture(url: String?, round: Boolean = true, keepSpace: Boolean = false) {
        if (round) setBackgroundResource(R.drawable.bg_tube_avatar)
        clipToOutline = true
        if (url == null) {
            visibility = if (keepSpace) View.VISIBLE else View.GONE
            setTag(R.id.thumb, null)
            setImageDrawable(null)
            return
        }
        visibility = View.VISIBLE
        TubeThumbs.into(this, url, circle = round)
    }

    private fun bindTransit(v: View, row: Row.TransitCard) {
        val r = row.route
        v.text(R.id.time).text = timeAndArrival(v, r.totalTimeSec)
        // 줄이 넘치면 '·' 에서만 갈라지게 ('도보 / 12분' 처럼 갈라지지 않게)
        v.text(R.id.meta).text = Format.glued(
            listOfNotNull(Format.fare(r), Format.transfers(r.transfers), "도보 ${Format.duration(r.walkTimeSec)}").joinToString(" · "),
        )
        v.findViewById<SegmentBarView>(R.id.bar).setRoute(r)
        // 기차·고속버스가 있으면 그 출발 시각("KTX 105 · 대전역 09:58 출발"), 없으면 첫 버스·지하철
        v.text(R.id.first).text = TransitGuide.headline(r) ?: "도보로 이동"
        v.text(R.id.live).show(row.live?.let(Format::glued))
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
