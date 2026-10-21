package com.mywatch.kakaomap

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.kakao.vectormap.KakaoMapSdk
import com.mywatch.kakaomap.battery.BatterySampleWorker
import com.mywatch.kakaomap.data.ErrorLog
import com.mywatch.kakaomap.data.RouteRepository
import com.mywatch.kakaomap.nav.NavigationService
import com.mywatch.kakaomap.tube.PlayerGuard
import com.mywatch.kakaomap.tube.PlayerIdle
import com.mywatch.kakaomap.tube.PlayerLifecycleShim
import com.mywatch.kakaomap.ui.TubeThumbs
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
                // 엔진이 쓰는 프로세스 생명 주기(앞·뒤) — androidx.startup 은 본체에서만 돌아 여기선 직접 켠다
                runCatching { PlayerLifecycleShim.init(this) }
                    .onFailure { ErrorLog.record("유튜브·앱 안 재생", "프로세스 생명 주기를 켜지 못함 (${it.javaClass.simpleName})") }
                // 재생 화면을 다 닫으면 잠시 뒤 이 프로세스를 끝내 엔진이 쥔 메모리를 돌려준다 (길 안내가 대신 꺼지지 않게)
                releaseWhenIdle()
                return
            }
            ProcessRole.MAIN -> Unit
        }
        // 화면이 가려지면 미리보기 그림을 반으로(길 안내 중이면 다), 시스템이 뒤로 돌리면 다 돌려준다 (2GB 워치 — 길 안내가 대신 정리되지 않게)
        registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) = TubeThumbs.trim(level, guiding = NavigationService.running)
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
            @Deprecated("시스템 콜백")
            override fun onLowMemory() = TubeThumbs.trim(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        })
        // 오류 기록 (앱 정보 화면의 '최근 오류 기록') — 앱이 멈춘 원인도 남긴다
        ErrorLog.init(this)
        // 안내를 시작한 목적지를 기기에 적어 둔다 (앱이 꺼져 안내가 끊기면 다음에 다시 찾아 주려고)
        RouteRepository.init(this)
        ErrorLog.installCrashHandler()
        // 지도 엔진(16MB 네이티브)은 여기서 읽지 않는다 — 이 프로세스는 15분 배터리 기록·타일·업데이트 확인으로도 뒤에서 자주 뜬다.
        // 지도 화면이 열릴 때 [ensureMapSdk] 가 한 번 읽는다
        // 업데이트 저장소가 정해져 있으면 주기적으로 새 버전 확인 (이미 예약돼 있으면 그대로)
        if (UpdateManager.repo(this) != null) runCatching { UpdateWorker.schedule(this) }
        // 배터리 표본 15분마다 (배터리 화면·PC 툴킷의 재료) — 이미 예약돼 있으면 그대로
        BatterySampleWorker.schedule(this)
        // 화면이 하나라도 떠 있는지 센다 — 쓰는 도중에 업데이트 설치로 앱이 꺼지지 않게,
        // 그리고 화면을 다 닫는 순간 미뤄 둔 업데이트를 바로 설치하려고
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                // 지난번에 앱이 자바 예외 밖의 이유로 끝났던 기록 — 화면을 처음 열 때 한 번만 (뒤에서 뜰 때마다 읽지 않게)
                if (!exitReasonsRead) { exitReasonsRead = true; Thread { ErrorLog.recordExitReasons(this@MapApp) }.start() }
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

    /** 재생 화면 프로세스: 재생 화면이 하나도 없으면 [PlayerIdle] 이 정한 때 끝낸다 */
    private fun releaseWhenIdle() {
        val idle = PlayerIdle()
        val main = Handler(Looper.getMainLooper())
        val end = Runnable { if (idle.stillIdle()) Process.killProcess(Process.myPid()) }
        fun act(d: PlayerIdle.Do) {
            when (d) {
                PlayerIdle.Do.CANCEL -> main.removeCallbacks(end)
                PlayerIdle.Do.KILL_LATER -> {
                    main.removeCallbacks(end)
                    main.postDelayed(end, PlayerIdle.GRACE_MS)
                }
                PlayerIdle.Do.KILL_NOW -> end.run()
                PlayerIdle.Do.NOTHING -> Unit
            }
        }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = act(idle.created())
            override fun onActivityDestroyed(activity: Activity) = act(idle.destroyed(activity.isChangingConfigurations))
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        })
        registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) = act(idle.trim(level))
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
            @Deprecated("시스템 콜백")
            override fun onLowMemory() = act(idle.trim(ComponentCallbacks2.TRIM_MEMORY_COMPLETE))
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
            @VisibleForTesting internal set

        /** 지도 엔진을 읽어 보았나 (이 프로세스에서 한 번만) */
        @Volatile
        var mapSdkTried = false
            private set

        @Volatile
        private var exitReasonsRead = false

        /**
         * 지도 엔진(카카오맵 SDK) 초기화 — 지도 화면이 처음 열릴 때 한 번. 실패 원인(정상이면 null)을 돌려주고 [initError] 에도 둔다.
         * (카카오는 Application 에서 초기화하라고 권하지만, 뒤에서만 뜨는 프로세스가 엔진을 읽지 않게 화면을 만들기 직전으로 미룸)
         */
        @Synchronized
        fun ensureMapSdk(context: Context): Throwable? {
            if (mapSdkTried) return initError
            mapSdkTried = true
            try {
                KakaoMapSdk.init(context.applicationContext, AppConfig.KAKAO_NATIVE_APP_KEY)
                // SDK가 인증에 실제로 쓰는 키 해시 → Logcat 에서 'KakaoWatchMap' 으로 검색
                Log.i(TAG, "패키지명=${context.packageName}  키 해시=${KakaoMapSdk.INSTANCE.hashKey}")
            } catch (t: Throwable) {
                // 카카오맵 SDK는 ARM 전용이라 x86 에뮬레이터에선 여기서 실패한다
                initError = t
                Log.e(TAG, "KakaoMapSdk.init 실패", t)
                ErrorLog.record("지도 엔진", t)
            }
            return initError
        }
    }
}
