package com.mywatch.kakaomap.tube

import android.content.ComponentCallbacks2

/**
 * 재생 화면 프로세스(:player)를 언제 끝낼지 (판단만 — 끝내는 건 MapApp).
 * 재생 엔진(GeckoView)은 화면을 닫은 뒤에도 150~300MB 를 쥐고 있어, 2GB 워치에서 메모리가 모자라면 대신 길 안내(본체)가 꺼질 수 있다.
 * 재생 화면이 하나도 남지 않으면 [GRACE_MS] 뒤에 끝낸다 (그 사이 다른 동영상을 고르면 데워진 엔진으로 빨리 뜨게).
 * 시스템이 메모리를 돌려 달라고 하면(뒤로 밀린 프로세스) 재생 화면이 없을 때 바로 끝낸다.
 */
class PlayerIdle {

    enum class Do { NOTHING, CANCEL, KILL_LATER, KILL_NOW }

    private var live = 0

    /** 재생 화면이 만들어짐 */
    fun created(): Do {
        live++
        return Do.CANCEL
    }

    /** 재생 화면이 없어짐 (화면 회전 등으로 다시 만드는 중이면 그대로) */
    fun destroyed(changingConfigurations: Boolean): Do {
        live = (live - 1).coerceAtLeast(0)
        return if (live == 0 && !changingConfigurations) Do.KILL_LATER else Do.NOTHING
    }

    /** 시스템이 메모리를 돌려 달라고 함 */
    fun trim(level: Int): Do =
        if (live == 0 && level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) Do.KILL_NOW else Do.NOTHING

    /** 끝내려는 순간에도 재생 화면이 없는지 */
    fun stillIdle(): Boolean = live == 0

    companion object {
        const val GRACE_MS = 2 * 60_000L
    }
}
