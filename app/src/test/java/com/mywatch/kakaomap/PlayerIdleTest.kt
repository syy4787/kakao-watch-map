package com.mywatch.kakaomap

import android.app.Application
import android.content.ComponentCallbacks2
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.mywatch.kakaomap.tube.PlayerIdle
import com.mywatch.kakaomap.tube.PlayerLifecycleShim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 재생 화면 프로세스(:player)를 언제 끝낼지. 재생 엔진(GeckoView)은 화면을 닫은 뒤에도 150~300MB 를 쥐고 있어
 * 2GB 워치에서 대신 길 안내(본체)가 꺼질 수 있다 — 재생 화면이 하나도 없으면 잠시 뒤에 끝낸다
 * (곧바로 다른 동영상을 고르면 데워진 엔진으로 빨리 뜨게). 메모리가 모자라다고 하면 바로.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlayerIdleTest {

    @Test
    fun closingTheLastPlayerScreenEndsTheProcessAfterAShortWhile() {
        val idle = PlayerIdle()
        assertEquals(PlayerIdle.Do.CANCEL, idle.created())
        assertEquals(PlayerIdle.Do.KILL_LATER, idle.destroyed(changingConfigurations = false))
        assertTrue(idle.stillIdle())
    }

    @Test
    fun pickingAnotherVideoInTimeKeepsTheWarmEngine() {
        val idle = PlayerIdle()
        idle.created()
        idle.destroyed(changingConfigurations = false)
        assertEquals(PlayerIdle.Do.CANCEL, idle.created())
        assertEquals(false, idle.stillIdle())
    }

    @Test
    fun aPlayerScreenStillOpenBehindAnotherKeepsTheProcess() {
        val idle = PlayerIdle()
        idle.created()
        idle.created()
        assertEquals(PlayerIdle.Do.NOTHING, idle.destroyed(changingConfigurations = false))
        assertEquals(PlayerIdle.Do.NOTHING, idle.trim(ComponentCallbacks2.TRIM_MEMORY_COMPLETE))
    }

    @Test
    fun recreatingTheScreenIsNotClosingIt() {
        val idle = PlayerIdle()
        idle.created()
        assertEquals(PlayerIdle.Do.NOTHING, idle.destroyed(changingConfigurations = true))
    }

    @Test
    fun lowMemoryWithNoPlayerScreenEndsItRightAway() {
        val idle = PlayerIdle()
        assertEquals(PlayerIdle.Do.KILL_NOW, idle.trim(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND))
        assertEquals("화면을 숨긴 정도로는 아직", PlayerIdle.Do.NOTHING, idle.trim(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
    }

    @Test
    fun theProcessLifecycleCanBeStartedOutsideTheMainProcess() {
        // androidx.startup 은 본체 프로세스에서만 돌아 :player 에선 ProcessLifecycleOwner 가 멈춰 있었다
        // (GeckoView 의 네트워크 감시·엔진 쉬기가 안 돎). 내부 함수 이름이 바뀌면 여기서(그리고 빌드에서) 알 수 있다
        PlayerLifecycleShim.init(RuntimeEnvironment.getApplication())
        assertTrue(ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED))
    }
}
