package com.mywatch.kakaomap

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import com.kakao.vectormap.KakaoMapSdk
import com.mywatch.kakaomap.data.ErrorLog
import com.mywatch.kakaomap.tube.PlayerGuard
import com.mywatch.kakaomap.update.UpdateInstallWorker
import com.mywatch.kakaomap.update.UpdateManager
import com.mywatch.kakaomap.update.UpdateWorker

class MapApp : Application() {

    override fun onCreate() {
        super.onCreate()
        when (roleOf(getProcessName(), packageName)) {
            // 브라우저 엔진(GeckoView)이 띄운 프로세스 — 우리 일은 하지 않는다 (일부는 권한 없는 격리 프로세스)
            ProcessRole.ENGINE -> return
            // 유튜브 재생 화면 — 오류 기록만 (지도 엔진·업데이트 예약은 본체에서)
            ProcessRole.PLAYER -> {
                ErrorLog.init(this)
                ErrorLog.installCrashHandler()
                // 엔진이 죽으면 바로 기억 → 다음 재생은 인터넷 앱으로
                PlayerGuard.of(this).watchCrashes()
                return
            }
            ProcessRole.MAIN -> Unit
        }
        // 오류 기록 (앱 정보 화면의 '최근 오류 기록') — 앱이 멈춘 원인도 남긴다
        ErrorLog.init(this)
        ErrorLog.installCrashHandler()
        Thread { ErrorLog.recordExitReasons(this) }.start()
        try {
            // 카카오 권장: Application 에서 한 번만 초기화
            KakaoMapSdk.init(this, AppConfig.KAKAO_NATIVE_APP_KEY)
            // SDK가 인증에 실제로 쓰는 키 해시 → Logcat 에서 'KakaoWatchMap' 으로 검색
            Log.i(TAG, "패키지명=$packageName  키 해시=${KakaoMapSdk.INSTANCE.hashKey}")
        } catch (t: Throwable) {
            // 카카오맵 SDK는 ARM 전용이라 x86 에뮬레이터에선 여기서 실패한다
            initError = t
            Log.e(TAG, "KakaoMapSdk.init 실패", t)
            ErrorLog.record("지도 엔진", t)
        }
        // 업데이트 저장소가 정해져 있으면 주기적으로 새 버전 확인
        if (UpdateManager.repo(this) != null) UpdateWorker.schedule(this)
        // 화면이 하나라도 떠 있는지 센다 — 쓰는 도중에 업데이트 설치로 앱이 꺼지지 않게,
        // 그리고 화면을 다 닫는 순간 미뤄 둔 업데이트를 바로 설치하려고
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities == 0 && !activity.isChangingConfigurations) {
                    lastUiStoppedAt = SystemClock.elapsedRealtime()
                    UpdateInstallWorker.enqueueSoon(this@MapApp)
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /** 이 앱의 프로세스 종류 */
    enum class ProcessRole { MAIN, PLAYER, ENGINE }

    companion object {
        const val TAG = "KakaoWatchMap"

        /** 프로세스 이름으로: 본체(패키지 이름) / 유튜브 재생 화면(:player) / 그 밖은 브라우저 엔진(GeckoView)의 것 */
        fun roleOf(process: String?, pkg: String): ProcessRole = when (process) {
            null, pkg -> ProcessRole.MAIN
            "$pkg:player" -> ProcessRole.PLAYER
            else -> ProcessRole.ENGINE
        }

        /** 지금 떠 있는(시작된) 화면 수 — 0 이면 앱을 안 보고 있음 (메인 스레드에서 쓰고 업데이트 작업이 읽음) */
        @Volatile
        var startedActivities = 0
            private set

        /**
         * 마지막으로 화면이 모두 가려진 시각 (부팅 후 경과 ms, 없으면 0).
         * 음성 입력·권한 창처럼 다른 앱 화면이 잠깐 덮어도 '화면 0개'가 되므로, 업데이트 설치는 이때부터 한동안 미룬다
         */
        @Volatile
        var lastUiStoppedAt = 0L
            private set

        val inUse: Boolean get() = startedActivities > 0

        /** SDK 초기화 실패 원인 (정상이면 null) */
        @Volatile
        var initError: Throwable? = null
            private set
    }
}
