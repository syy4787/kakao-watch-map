package com.mywatch.kakaomap

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Looper
import com.mywatch.kakaomap.tube.PlayerAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 앱 안 재생의 소리 예절: 다른 음악 앱과 같이 나오지 않기, 전화·다른 음악이 오면 멈추기,
 * 이어폰(버즈)이 끊기면 멈추기(워치 스피커로 갑자기 크게 나오지 않게)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlayerAudioTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val am = context.getSystemService(AudioManager::class.java)
    private var paused = 0
    private val audio = PlayerAudio(context) { paused++ }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun playingTakesMediaAudioFocusSoOtherMusicStops() {
        audio.playing()
        val req = shadowOf(am).lastAudioFocusRequest
        assertNotNull(req)
        assertEquals(AudioManager.AUDIOFOCUS_GAIN, req.durationHint)
        assertEquals(AudioAttributes.USAGE_MEDIA, req.audioFocusRequest.audioAttributes.usage)
    }

    @Test
    fun losingFocusToACallOrAnotherPlayerPauses() {
        audio.playing()
        shadowOf(am).lastAudioFocusRequest.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        idle()
        assertEquals(1, paused)
        shadowOf(am).lastAudioFocusRequest.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        idle()
        assertEquals(2, paused)
        // 알림음 때문에 잠깐 줄이는 건 멈추지 않는다
        shadowOf(am).lastAudioFocusRequest.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        idle()
        assertEquals(2, paused)
    }

    @Test
    fun earbudsDisconnectingPausesInsteadOfBlastingFromTheWatch() {
        audio.playing()
        context.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        idle()
        assertEquals(1, paused)
    }

    @Test
    fun afterStoppingFocusIsReturnedAndEarbudsAreNoLongerWatched() {
        audio.playing()
        val req = shadowOf(am).lastAudioFocusRequest.audioFocusRequest
        audio.stopped()
        assertSame(req, shadowOf(am).lastAbandonedAudioFocusRequest)
        context.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        idle()
        assertEquals(0, paused)
    }

    @Test
    fun noFocusDuringACallMeansDoNotPlay() {
        shadowOf(am).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        audio.playing()
        idle()
        assertEquals(1, paused)
    }

    @Test
    fun playingTwiceAsksForFocusOnce() {
        audio.playing()
        val first = shadowOf(am).lastAudioFocusRequest
        audio.playing()
        assertSame(first, shadowOf(am).lastAudioFocusRequest)
        audio.stopped()
        audio.stopped()   // 두 번 멈춰도 괜찮다
    }
}
