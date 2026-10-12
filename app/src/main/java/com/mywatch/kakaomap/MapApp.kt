package com.mywatch.kakaomap

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import com.kakao.vectormap.KakaoMapSdk
import com.mywatch.kakaomap.update.UpdateInstallWorker
import com.mywatch.kakaomap.update.UpdateManager
import com.mywatch.kakaomap.update.UpdateWorker

class MapApp : Application() {

    override fun onCreate() {
        super.onCreate()
        try {
            // 카카오 권장: Application 에서 한 번만 초기화
            KakaoMapSdk.init(this, AppConfig.KAKAO_NATIVE_APP_KEY)
            // SDK가 인증에 실제로 쓰는 키 해시 → Logcat 에서 'KakaoWatchMap' 으로 검색
            Log.i(TAG, "패키지명=$packageName  키 해시=${KakaoMapSdk.INSTANCE.hashKey}")
        } catch (t: Throwable) {
            // 카카오맵 SDK는 ARM 전용이라 x86 에뮬레이터에선 여기서 실패한다
            initError = t
            Log.e(TAG, "KakaoMapSdk.init 실패", t)
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
                if (startedActivities == 0 && !activity.isChangingConfigurations) UpdateInstallWorker.enqueueSoon(this@MapApp)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    companion object {
        const val TAG = "KakaoWatchMap"

        /** 지금 떠 있는(시작된) 화면 수 — 0 이면 앱을 안 보고 있음 (메인 스레드에서 쓰고 업데이트 작업이 읽음) */
        @Volatile
        var startedActivities = 0
            private set

        val inUse: Boolean get() = startedActivities > 0

        /** SDK 초기화 실패 원인 (정상이면 null) */
        @Volatile
        var initError: Throwable? = null
            private set
    }
}
