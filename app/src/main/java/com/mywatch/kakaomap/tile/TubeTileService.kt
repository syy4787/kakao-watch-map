package com.mywatch.kakaomap.tile

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.TimelineBuilders.Timeline
import androidx.wear.protolayout.layout.androidImageResource
import androidx.wear.protolayout.layout.column
import androidx.wear.protolayout.layout.imageResource
import androidx.wear.protolayout.layout.spacer
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.button
import androidx.wear.protolayout.material3.icon
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
import com.mywatch.kakaomap.R
import com.mywatch.kakaomap.tube.TubeState
import com.mywatch.kakaomap.tube.TubeVideo
import com.mywatch.kakaomap.ui.TubeActivity
import kotlin.time.Duration.Companion.hours

/**
 * 타일 '유튜브': 검색(말로 바로) · 믹스 이어 듣기(최근 동영상으로 믹스 — 앱 안 재생 화면) · 열기.
 * 동영상을 틀 때와 하루에 한 번만 다시 그린다 (배터리 거의 0).
 */
class TubeTileService : Material3TileService() {

    override suspend fun MaterialScope.tileResponse(requestParams: TileRequest): Tile {
        // 길안내 서비스와 같은 프로세스에서 돌므로 무슨 일이 있어도 예외를 밖으로 내지 않는다
        val layout = runCatching { main(TubeState(this@TubeTileService).lastPlayed()) }
            .onFailure { Log.w(TAG, "타일 그리기 실패", it) }
            .getOrElse { fallback() }
        // 하루에 한 번은 다시 그린다 — '믹스 이어 듣기' 아래 곡이 30일(보관 기한)이 지나면 사라지게
        return tile(timeline = Timeline.fromLayoutElement(layout), freshness = 24.hours)
    }

    private fun MaterialScope.main(last: TubeVideo?): LayoutElement = primaryLayout(
        titleSlot = { text(getString(R.string.tube_tile_label).layoutString) },
        mainSlot = {
            column(
                button(
                    onClick = clickable(launch(TubeActivity.EXTRA_VOICE), id = "voice"),
                    labelContent = { text(getString(R.string.tube_search).layoutString) },
                    secondaryLabelContent = { text(getString(R.string.tube_tile_search_sub).layoutString) },
                    iconContent = { icon(imageResource(androidImage = androidImageResource(R.drawable.ic_mic))) },
                    width = expand(),
                ),
                spacer(height = dp(4f)),
                button(
                    onClick = clickable(launch(TubeActivity.EXTRA_MIX), id = "mix"),
                    labelContent = { text(getString(R.string.tube_tile_mix).layoutString) },
                    secondaryLabelContent = {
                        text((last?.title ?: getString(R.string.tube_tile_no_recent)).layoutString)
                    },
                    iconContent = { icon(imageResource(androidImage = androidImageResource(R.drawable.ic_queue_music))) },
                    width = expand(),
                ),
                width = expand(),
            )
        },
        bottomSlot = {
            textEdgeButton(onClick = clickable(launch(null), id = "open")) {
                text(getString(R.string.tube_tile_open).layoutString)
            }
        },
    )

    /** 무엇이 잘못돼도 최소한 앱은 열 수 있게 */
    private fun MaterialScope.fallback(): LayoutElement = primaryLayout(
        mainSlot = { text(getString(R.string.tube_tile_label).layoutString) },
        bottomSlot = {
            textEdgeButton(onClick = clickable(launch(TubeActivity.EXTRA_VOICE), id = "voice")) {
                text(getString(R.string.tube_search).layoutString)
            }
        },
    )

    /** [flag] = 켤 바로가기 (없으면 그냥 열기) */
    private fun launch(flag: String?) = ActionBuilders.launchAction(
        ComponentName(this, TubeActivity::class.java),
        if (flag == null) emptyMap() else mapOf(flag to ActionBuilders.booleanExtra(true)),
    )

    companion object {
        private const val TAG = "TubeTile"

        /** 곡을 틀거나 기록을 지웠을 때 '믹스 이어 듣기' 아래 곡 이름을 바꾼다 */
        fun refresh(context: Context) {
            runCatching { TileService.getUpdater(context.applicationContext).requestUpdate(TubeTileService::class.java) }
        }
    }
}
