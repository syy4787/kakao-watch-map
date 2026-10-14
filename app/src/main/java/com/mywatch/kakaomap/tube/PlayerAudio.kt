package com.mywatch.kakaomap.tube

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * 앱 안 재생의 소리 예절 — 재생 엔진(GeckoView 158)은 오디오 포커스를 앱에 맡긴다.
 *  · 재생이 시작되면 오디오 포커스를 받는다 → 다른 음악 앱(유튜브 뮤직 등)은 멈춘다 (둘이 같이 나오지 않게)
 *  · 다른 앱이 포커스를 가져가면(전화·알람·다른 음악) 멈춘다
 *  · 이어폰(버즈)이 끊기면 멈춘다 — 워치 스피커로 갑자기 크게 나오지 않게
 * 멈추면([stopped]) 포커스를 돌려주고 이어폰 감시도 끝낸다. [pause] 는 메인 스레드에서 불린다
 */
class PlayerAudio(private val context: Context, private val pause: () -> Unit) {

    private val am = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var request: AudioFocusRequest? = null
    private var watchingNoisy = false

    private val focus = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
            // 잠깐 줄이기(알림음)는 시스템이 알아서 소리를 줄였다 되돌린다
        }
    }

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    /** 재생이 시작됨 */
    fun playing() {
        if (request == null && am != null) {
            val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setOnAudioFocusChangeListener(focus, main)
                .build()
            when (am.requestAudioFocus(r)) {
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> request = r
                // 통화 중 등 지금은 소리를 낼 수 없음 → 틀지 않는다
                AudioManager.AUDIOFOCUS_REQUEST_FAILED -> main.post { pause() }
            }
        }
        if (!watchingNoisy) {
            ContextCompat.registerReceiver(context, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
            watchingNoisy = true
        }
    }

    /** 멈춤·끝·화면을 떠남 */
    fun stopped() {
        request?.let { am?.abandonAudioFocusRequest(it) }
        request = null
        if (watchingNoisy) {
            runCatching { context.unregisterReceiver(noisy) }
            watchingNoisy = false
        }
    }
}
