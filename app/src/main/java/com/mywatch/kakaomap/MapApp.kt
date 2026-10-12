package com.mywatch.kakaomap

import android.app.Application
import android.util.Log
import com.kakao.vectormap.KakaoMapSdk
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
        // 업데이트 저장소가 정해져 있으면 충전 중 자동 업데이트 예약
        if (UpdateManager.repo(this) != null) UpdateWorker.schedule(this)
    }

    companion object {
        const val TAG = "KakaoWatchMap"

        /** SDK 초기화 실패 원인 (정상이면 null) */
        @Volatile
        var initError: Throwable? = null
            private set
    }
}
