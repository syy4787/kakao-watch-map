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
import androidx.wear.protolayout.modifiers.LayoutModifier
import androidx.wear.protolayout.modifiers.clickable
import androidx.wear.protolayout.modifiers.contentDescription
import androidx.wear.protolayout.types.layoutString
import androidx.wear.tiles.Material3TileService
import androidx.wear.tiles.RequestBuilders.TileRequest
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.TileService
import androidx.wear.tiles.tile
import com.mywatch.battery.core.DrainAnalyzer
import com.mywatch.battery.core.Event
import com.mywatch.battery.core.Grade
import com.mywatch.battery.core.Projection
import com.mywatch.battery.core.WatchModels
import com.mywatch.kakaomap.R
import com.mywatch.kakaomap.battery.BatteryActivity
import com.mywatch.kakaomap.battery.BatteryLog
import com.mywatch.kakaomap.battery.BatteryProbe
import com.mywatch.kakaomap.battery.Guardian
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 타일 '배터리': 지금 잔량 · 최근 소모율과 판정 · 지금 페이스면 언제 15%·0% · 지킴이 상태(또는 알려진 문제).
 * 15분 기록마다 다시 그리라고 알리고, 시스템은 타일이 보일 때만 그린다 (배터리 거의 0).
 */
class BatteryTileService : Material3TileService() {

    override suspend fun MaterialScope.tileResponse(requestParams: TileRequest): Tile {
        // 기록 읽기·배터리 값·분석은 메인 스레드 밖에서 (지도·유튜브 화면이 떠 있을 때 버벅이지 않게) — 15분 기록마다 다시 그리라고
        // 알리므로 따로 새로 고침 주기(freshness)는 두지 않는다
        val c = this@BatteryTileService
        val s = withContext(Dispatchers.IO) {
            runCatching { noteDevice(c, requestParams.deviceConfiguration) }
            runCatching { snapshot(c) }
        }
        val layout = s.mapCatching { batteryTileLayout(c, it) }
            .onFailure { Log.w(TAG, "타일 그리기 실패", it) }
            .getOrElse { batteryTileFallback(c) }
        return tile(timeline = Timeline.fromLayoutElement(layout))
    }

    /** 타일에 쓸 글 네 줄 — [forecastSmall] 은 작은 워치용 한 줄 예상 (두 줄이면 아래 상태 줄이 칸 밖으로 밀린다) */
    data class Snapshot(val title: String, val rate: String, val forecast: String, val status: String, val forecastSmall: String = forecast) {
        /** [textWidthDp] = 글이 쓸 수 있는 폭 ([textWidthDp] 함수) */
        fun forecastFor(textWidthDp: Int): String = if (compact(textWidthDp)) forecastSmall else forecast
    }

    companion object {
        private const val TAG = "BatteryTile"

        /** 글이 쓸 수 있는 폭이 이보다 좁으면(작은 워치·큰 글꼴) 예상은 한 줄, 소모율은 작은 글씨로 */
        const val SMALL_DP = 200

        fun compact(textWidthDp: Int): Boolean = textWidthDp < SMALL_DP

        /** 충전 중 '하루 평균'을 말하려면 최근 24시간 방전이 이만큼은 쌓여야 (짧으면 들쭉날쭉) */
        const val DAY_AVERAGE_MIN_HOURS = 3.0

        // 고정 문구 — 맞춤 시험(BatteryTileFitTest)이 작은 워치 타일에 그대로 그려 본다
        /** 제목이 이미 '충전 중' — 큰 글꼴에서 두 줄이 되어 아래 줄을 밀어내지 않게 짧게 */
        internal const val RATE_CHARGING = "소모율 측정 쉼"
        internal const val RATE_COLLECTING = "기록 모으는 중"
        internal const val RATE_UNKNOWN = "소모율 아직 모름"
        /** 예상 줄의 대신 글 — 작은 워치·큰 글꼴에서도 한 줄 (두 줄이면 아래 상태 줄이 잘렸다) */
        internal const val FORECAST_CHARGING = "빼면 다시 잽니다"
        internal const val FORECAST_LATER = "예상은 2시간 뒤부터"
        internal const val STATUS_NIGHT = "지킴이: 밤 모드 중 (아침·충전 때 원래대로)"
        internal const val STATUS_ABNORMAL_SAVER = "지킴이: 비정상 소모 — 절전 켬"
        internal const val STATUS_ABNORMAL = "지킴이: 비정상 소모 감지"
        internal const val STATUS_IDLE = "지킴이: 대기 중"

        /** 기록(최근 2일)과 지금 값으로 타일 글을 만든다 — 시험에서 바로 부를 수 있게 */
        fun snapshot(context: Context, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Snapshot {
            val c = context.applicationContext
            val samples = BatteryLog(c).readRecent(2, now).let { logged ->
                // 지금 값(기록하지 않음)을 끝에 붙여 15분 사이의 변화도 반영
                val live = runCatching { BatteryProbe.read(c, Event.APP_OPEN) }.getOrNull()?.takeIf { s -> logged.lastOrNull()?.let { s.t > it.t } ?: true }
                if (live != null) logged + live.copy(t = now) else logged
            }
            val last = samples.maxByOrNull { it.t }
            // 제목 칸은 한 줄·좁다 — 글꼴을 키우면 '배터리 100% · 충전 중'이 잘려 충전 중엔 '충전 중 100%'로 (맞춤 시험에서 찾음)
            val title = last?.let { if (it.plugged) "충전 중 ${it.level}%" else "배터리 ${it.level}%" } ?: "배터리"
            val a = runCatching { DrainAnalyzer(WatchModels.nominalMah(Build.MODEL), zone).analyze(samples, now) }.getOrNull()
            val rate = when {
                a == null || last == null -> RATE_COLLECTING
                // 충전 중엔 지금 소모율 대신 하루 평균(방전 구간만, 3시간 넘게 쌓였을 때) — 밤에 충전하며 오늘 하루가 어땠는지
                last.plugged -> a.w24.takeIf { it.hours >= DAY_AVERAGE_MIN_HOURS }?.percentPerHour
                    ?.let { "하루 평균 시간당 ${DrainAnalyzer.fmt(it)}%" } ?: RATE_CHARGING
                else -> {
                    // 판정은 숫자와 함께만 (숫자 없이 '비정상'만 붙으면 무엇이 비정상인지 모르고, 큰 글꼴에서 두 줄이 된다)
                    val w = a.w1.percentPerHour ?: a.w8.percentPerHour
                    val g = a.verdict.grade
                    w?.let { "시간당 ${DrainAnalyzer.fmt(it)}%" + (if (g == Grade.UNKNOWN) "" else " · ${g.label}") } ?: RATE_UNKNOWN
                }
            }
            // 소모율은 위 줄에 있으니 언제 15%·0% 인지만 (작은 글씨 두 줄에 0% 시각이 잘리지 않게) — 작은 워치는 0% 때만
            val f = Projection.forecast(samples, now)
            val fallback = if (last?.plugged == true) FORECAST_CHARGING else FORECAST_LATER
            val forecast = f?.short(zone, now) ?: fallback
            val forecastSmall = f?.compact(zone, now) ?: fallback
            // 알려진 문제는 15분 기록(지킴이)이 찾아 적어 둔 그 문제의 할 일 한 줄 — 타일이 앱별 통계 파일을 읽지 않게
            val issue = runCatching { Guardian.tileIssueGraded(c, now) }.getOrNull()
            val st = Guardian.state(c)
            val trendLine = runCatching { com.mywatch.kakaomap.battery.BatteryAlerts.trendLine(c, now) }.getOrNull()
            // 절전이 실제로 켜졌을 때만 '절전 켬' (설정으로 안 켜지는 워치이거나 못 켰으면 아니다)
            val saverOn = !st.saverBroken && runCatching { com.mywatch.kakaomap.battery.Levers(c).saverOn() }.getOrDefault(false)
            val status = when {
                issue != null && issue.second == Grade.ABNORMAL -> issue.first
                // 지금 바꿔 둔 것이 먼저 — 무엇이 꺼져 있는지 알아야 한다
                st.nightActive -> STATUS_NIGHT
                st.abnormalActive -> if (saverOn) STATUS_ABNORMAL_SAVER else STATUS_ABNORMAL
                issue != null -> issue.first
                // 최근 며칠 대기 소모가 늘었으면 그 무렵 바뀐 것 (하루 요약이 정해 둔 줄)
                trendLine != null -> trendLine
                else -> a?.drift?.takeIf { it.suspicious }?.tile ?: STATUS_IDLE
            }
            return Snapshot(title, rate, forecast, status, forecastSmall)
        }

        /** 워치가 보낸 기기 값을 하루 요약용으로 적어 둔다 ([com.mywatch.kakaomap.battery.TileDeviceNote]) */
        fun noteDevice(context: Context, d: androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters) {
            val v = d.rendererSchemaVersion
            com.mywatch.kakaomap.battery.TileDeviceNote.note(
                context.applicationContext,
                com.mywatch.kakaomap.battery.TileDeviceNote.line(d.screenWidthDp, d.fontScale, v.major, v.minor),
            )
        }

        /** 새 기록마다 다시 그리라고 알린다 (타일이 보일 때만 실제로 그린다) */
        fun refresh(context: Context) {
            runCatching { TileService.getUpdater(context.applicationContext).requestUpdate(BatteryTileService::class.java) }
        }
    }
}

/**
 * 타일 화면 — 서비스와 시험(실제로 그려 글 잘림을 보는 맞춤 시험·렌더)이 같이 쓴다.
 * 글은 띄어쓰기에서만 줄이 바뀌게 표시해 넣고([TileText]), 읽어 주기(TalkBack)에는 원문을 준다(가운뎃점·줄표는 쉼표로 —
 * 이 줄들은 전엔 읽어 주지 않았다). 예상은 글이 쓸 수 있는 폭([textWidthDp])이 좁으면 한 줄로
 */
internal fun MaterialScope.batteryTileLayout(c: Context, s: BatteryTileService.Snapshot): LayoutElement = primaryLayout(
    titleSlot = { text(TileText.keepWords(s.title).layoutString, LayoutModifier.contentDescription(TileText.spoken(s.title))) },
    mainSlot = {
        // 글이 쓸 수 있는 폭이 좁으면(작은 워치·큰 글꼴) 예상은 한 줄, 소모율도 작은 글씨로 — 줄이 넘쳐 아래 상태 줄이 잘리지 않게
        val width = textWidthDp(deviceConfiguration)
        val compact = BatteryTileService.compact(width)
        val forecast = s.forecastFor(width)
        column(
            text(TileText.keepParts(s.rate).layoutString, LayoutModifier.contentDescription(TileText.spoken(s.rate)),
                typography = if (compact) Typography.BODY_SMALL else Typography.BODY_MEDIUM, maxLines = 2),
            text(TileText.keepParts(forecast).layoutString, LayoutModifier.contentDescription(TileText.spoken(forecast)), typography = Typography.BODY_SMALL, maxLines = 2),
            text(TileText.keepWords(s.status).layoutString, LayoutModifier.contentDescription(TileText.spoken(s.status)), typography = Typography.BODY_SMALL, maxLines = 2),
            width = expand(),
        )
    },
    bottomSlot = {
        textEdgeButton(onClick = clickable(openBattery(c), id = "open")) { text(c.getString(R.string.battery_tile_open).layoutString) }
    },
)

/**
 * 글이 쓸 수 있는 폭 — 글꼴 크기를 키우면(설정 › 디스플레이 › 글꼴 크기) 같은 화면에도 글이 덜 들어가므로 그만큼 좁은 화면으로 본다.
 * 시스템이 글꼴 배율을 안 주면(0) 1배로
 */
internal fun textWidthDp(d: androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters): Int =
    (d.screenWidthDp / d.fontScale.coerceAtLeast(1f)).toInt()

/** 무엇이 잘못돼도 최소한 배터리 화면은 열 수 있게 */
internal fun MaterialScope.batteryTileFallback(c: Context): LayoutElement = primaryLayout(
    mainSlot = { text(c.getString(R.string.battery_tile_label).layoutString) },
    bottomSlot = { textEdgeButton(onClick = clickable(openBattery(c), id = "open")) { text(c.getString(R.string.battery_tile_open).layoutString) } },
)

private fun openBattery(c: Context) = ActionBuilders.launchAction(ComponentName(c, BatteryActivity::class.java))
