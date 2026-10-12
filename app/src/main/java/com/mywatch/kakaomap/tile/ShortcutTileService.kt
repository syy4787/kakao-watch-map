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
import com.mywatch.kakaomap.data.Favorite
import com.mywatch.kakaomap.data.FavoriteSlot
import com.mywatch.kakaomap.data.Favorites
import com.mywatch.kakaomap.ui.SearchActivity

/**
 * 타일(워치 화면을 옆으로 넘기면 나오는 카드): 집으로 · 회사로 · 검색.
 * 누르면 앱의 그 화면이 바로 열린다. 등록 안 된 곳은 '등록하기' → 누르면 등록 검색.
 * 내용은 집·회사를 바꿀 때만 다시 그린다(주기적으로 깨우지 않음 → 배터리 0).
 */
class ShortcutTileService : Material3TileService() {

    override suspend fun MaterialScope.tileResponse(requestParams: TileRequest): Tile {
        // 타일은 길안내 서비스와 같은 프로세스에서 돈다 → 여기서 죽으면 안내까지 꺼지므로 무슨 일이 있어도 예외를 밖으로 내지 않는다
        val layout = runCatching { shortcuts(Favorites.slots(this@ShortcutTileService)) }
            .onFailure { Log.w(TAG, "타일 그리기 실패", it) }
            .getOrElse { fallback() }
        return tile(timeline = Timeline.fromLayoutElement(layout))
    }

    private fun MaterialScope.shortcuts(slots: List<FavoriteSlot>): LayoutElement = primaryLayout(
        titleSlot = { text(getString(R.string.tile_title).layoutString) },
        mainSlot = {
            column(
                *slots.flatMapIndexed { i, slot ->
                    listOfNotNull(if (i > 0) spacer(height = dp(4f)) else null, favoriteButton(slot))
                }.toTypedArray(),
                width = expand(),
            )
        },
        bottomSlot = {
            textEdgeButton(onClick = clickable(searchAction(), id = "search")) {
                text(getString(R.string.tile_search).layoutString)
            }
        },
    )

    private fun MaterialScope.favoriteButton(slot: FavoriteSlot): LayoutElement = button(
        onClick = clickable(favoriteAction(slot.kind), id = slot.kind.name),
        labelContent = { text(slot.title.layoutString) },
        secondaryLabelContent = { text(slot.subtitle.layoutString) },
        iconContent = {
            icon(imageResource(androidImage = androidImageResource(
                if (slot.kind == Favorite.HOME) R.drawable.ic_home else R.drawable.ic_work
            )))
        },
        width = expand(),
    )

    /** 무엇이 잘못돼도 최소한 '검색'은 열 수 있게 */
    private fun MaterialScope.fallback(): LayoutElement = primaryLayout(
        mainSlot = { text(getString(R.string.tile_title).layoutString) },
        bottomSlot = {
            textEdgeButton(onClick = clickable(searchAction(), id = "search")) {
                text(getString(R.string.tile_search).layoutString)
            }
        },
    )

    private fun searchAction() = ActionBuilders.launchAction(
        ComponentName(this, SearchActivity::class.java),
        mapOf(SearchActivity.EXTRA_SEARCH to ActionBuilders.booleanExtra(true)),
    )

    private fun favoriteAction(kind: Favorite) = ActionBuilders.launchAction(
        ComponentName(this, SearchActivity::class.java),
        mapOf(SearchActivity.EXTRA_FAVORITE to ActionBuilders.stringExtra(kind.name)),
    )

    companion object {
        private const val TAG = "ShortcutTile"

        /** 집·회사를 바꿨을 때 타일을 다시 그리게 한다 */
        fun refresh(context: Context) {
            // 화면(Activity) Context 로 묶으면 화면이 닫힌 뒤 풀 때 오류가 날 수 있어 앱 Context 로
            runCatching { TileService.getUpdater(context.applicationContext).requestUpdate(ShortcutTileService::class.java) }
        }
    }
}
