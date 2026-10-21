package com.mywatch.kakaomap.tile

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.TimelineBuilders.Timeline
import androidx.wear.protolayout.layout.column
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.Typography
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.material3.textEdgeButton
import androidx.wear.protolayout.modifiers.clickable
import androidx.wear.protolayout.types.layoutString
import androidx.wear.tiles.Material3TileService
import androidx.wear.tiles.RequestBuilders.TileRequest
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.TileService
import androidx.wear.tiles.tile
import com.mywatch.battery.core.DrainAnalyzer
import com.mywatch.battery.core.Event
import com.mywatch.battery.core.Grade
import com.mywatch.battery.core.KnownIssues
import com.mywatch.battery.core.Projection
import com.mywatch.battery.core.WatchModels
import com.mywatch.kakaomap.R
import com.mywatch.kakaomap.battery.BatteryActivity
import com.mywatch.kakaomap.battery.BatteryLog
import com.mywatch.kakaomap.battery.BatteryProbe
import com.mywatch.kakaomap.battery.Guardian
import java.time.ZoneId
import kotlin.time.Duration.Companion.minutes

/**
 * 타일 '배터리': 지금 잔량 · 최근 소모율과 판정 · 지금 페이스면 언제 15%·0% · 지킴이 상태(또는 알려진 문제).
 * 15분 기록마다 다시 그리라고 알리고, 시스템은 타일이 보일 때만 그린다 (배터리 거의 0).
 */
class BatteryTileService : Material3TileService() {

    override suspend fun MaterialScope.tileResponse(requestParams: TileRequest): Tile {
        val layout = runCatching { main(snapshot(this@BatteryTileService)) }
            .onFailure { Log.w(TAG, "타일 그리기 실패", it) }
            .getOrElse { fallback() }
        return tile(timeline = Timeline.fromLayoutElement(layout), freshness = 15.minutes)
    }

    private fun MaterialScope.main(s: Snapshot): LayoutElement = primaryLayout(
        titleSlot = { text(s.title.layoutString) },
        mainSlot = {
            column(
                text(s.rate.layoutString, typography = Typography.BODY_MEDIUM, maxLines = 2),
                text(s.forecast.layoutString, typography = Typography.BODY_SMALL, maxLines = 2),
                text(s.status.layoutString, typography = Typography.BODY_SMALL, maxLines = 2),
                width = expand(),
            )
        },
        bottomSlot = {
            textEdgeButton(onClick = clickable(open(), id = "open")) { text(getString(R.string.battery_tile_open).layoutString) }
        },
    )

    /** 무엇이 잘못돼도 최소한 배터리 화면은 열 수 있게 */
    private fun MaterialScope.fallback(): LayoutElement = primaryLayout(
        mainSlot = { text(getString(R.string.battery_tile_label).layoutString) },
        bottomSlot = { textEdgeButton(onClick = clickable(open(), id = "open")) { text(getString(R.string.battery_tile_open).layoutString) } },
    )

    private fun open() = ActionBuilders.launchAction(ComponentName(this, BatteryActivity::class.java))

    /** 타일에 쓸 글 네 줄 */
    data class Snapshot(val title: String, val rate: String, val forecast: String, val status: String)

    companion object {
        private const val TAG = "BatteryTile"

        /** 기록(최근 2일)과 지금 값으로 타일 글을 만든다 — 시험에서 바로 부를 수 있게 */
        fun snapshot(context: Context, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Snapshot {
            val c = context.applicationContext
            val samples = BatteryLog(c).readRecent(2, now).let { logged ->
                // 지금 값(기록하지 않음)을 끝에 붙여 15분 사이의 변화도 반영
                val live = runCatching { BatteryProbe.read(c, Event.APP_OPEN) }.getOrNull()?.takeIf { s -> logged.lastOrNull()?.let { s.t > it.t } ?: true }
                if (live != null) logged + live.copy(t = now) else logged
            }
            val last = samples.maxByOrNull { it.t }
            val title = last?.let { "배터리 ${it.level}%" + if (it.plugged) " · 충전 중" else "" } ?: "배터리"
            val a = runCatching { DrainAnalyzer(WatchModels.nominalMah(Build.MODEL), zone).analyze(samples, now) }.getOrNull()
            val rate = when {
                a == null || last == null -> "기록 모으는 중 — 15분마다 쌓입니다"
                last.plugged -> "충전 중에는 소모율을 재지 않습니다"
                else -> {
                    val w = a.w1.percentPerHour ?: a.w8.percentPerHour
                    val g = a.verdict.grade
                    (w?.let { "시간당 ${DrainAnalyzer.fmt(it)}%" } ?: "소모율 아직 모름") +
                        (if (g == Grade.UNKNOWN) "" else " · ${g.label}")
                }
            }
            val forecast = Projection.forecast(samples, now)?.label(zone)?.removePrefix("지금 페이스")?.let { "이 페이스$it" }
                ?: if (last?.plugged == true) "충전기에서 빼면 다시 잽니다" else "앞날은 2시간 기록 뒤부터"
            val issue = runCatching { KnownIssues.detect(Guardian.culprits(c, now)).firstOrNull() }.getOrNull()
            val st = Guardian.state(c)
            val status = when {
                issue != null -> issue.title.substringBefore(" —") + " — 캐시 지우기"
                st.nightActive -> "지킴이: 밤 모드 중 (아침·충전 때 원래대로)"
                st.abnormalActive -> "지킴이: 비정상 소모 — 절전 켬"
                else -> a?.drift?.takeIf { it.suspicious }?.headline?.substringAfter("— ")?.let { "잔량 표시: $it" } ?: "지킴이: 대기 중"
            }
            return Snapshot(title, rate, forecast, status)
        }

        /** 새 기록마다 다시 그리라고 알린다 (타일이 보일 때만 실제로 그린다) */
        fun refresh(context: Context) {
            runCatching { TileService.getUpdater(context.applicationContext).requestUpdate(BatteryTileService::class.java) }
        }
    }
}
