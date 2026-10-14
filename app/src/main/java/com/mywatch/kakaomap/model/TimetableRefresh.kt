package com.mywatch.kakaomap.model

/**
 * 기차·고속·시외버스 시간표 경로가 지나갈 때 언제 다시 찾을지 (길찾기 화면이 1분마다, 돌아올 때마다 부름).
 *  · 지난 편은 빼고, 어떤 종류(열차·고속·시외)가 다 지나가면 다음 편을 다시 찾는다
 *  · 다시 찾기는 마지막으로 찾은 지 [gapMs] 가 지나서 — 그 전이면 [due] 로 기억했다가 지나는 대로 (남은 경로가 없으면 바로)
 *
 * 예전엔 5분 안에 다 지나간 종류를 빼 두기만 했다. 빼 둔 목록에는 그 종류가 없어 '다 지나감'이 다시 보이지 않았고,
 * 시간표 경로가 하나도 안 남으면 확인 자체를 그만둬서 목적지를 다시 고를 때까지 열차·버스가 다시 나오지 않았다.
 */
class TimetableRefresh(private val gapMs: Long = GAP_MS) {

    sealed interface Do {
        /** 바뀐 것 없음 (남은 시간만 다시 그리면 됨) */
        data object Nothing : Do
        /** 지난 편만 뺀 목록으로 바꿈 */
        data class Keep(val routes: List<TransitRoute>) : Do
        /** 다음 편을 다시 찾음 */
        data object Refetch : Do
    }

    /** 다 지나간 종류가 있어 다시 찾아야 함 (마지막으로 찾은 지 얼마 안 돼 기다리는 중) */
    var due: Boolean = false
        private set

    /** 경로를 새로 찾기 시작함 — 기억해 둔 다시 찾기는 지운다 */
    fun reset() {
        due = false
    }

    /** 다시 찾을 일을 이어서 기억함 (화면을 다시 만들 때) */
    fun restore(due: Boolean) {
        this.due = due
    }

    /** 지금 [routes] 를 어떻게 할지 ([fetchedAtMs] = 마지막으로 찾기 시작한 시각) */
    fun tick(routes: List<TransitRoute>, nowMs: Long, fetchedAtMs: Long): Do {
        val (kept, ranOut) = TransitOptions.dropExpired(routes, nowMs)
        if (ranOut) due = true
        if (due && (kept.isEmpty() || nowMs - fetchedAtMs >= gapMs)) {
            due = false
            return Do.Refetch
        }
        return if (kept.size != routes.size) Do.Keep(kept) else Do.Nothing
    }

    companion object {
        /** 지난 편 때문에 다시 찾는 것은 5분에 한 번까지 (한 편 지날 때마다 찾으면 API 를 수십 번 부른다) */
        const val GAP_MS = 5 * 60_000L
    }
}
