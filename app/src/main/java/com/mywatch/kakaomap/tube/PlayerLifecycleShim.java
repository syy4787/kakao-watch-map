package com.mywatch.kakaomap.tube;

import android.content.Context;

import androidx.lifecycle.ProcessLifecycleOwner;

/**
 * 재생 화면 프로세스(:player)에도 ProcessLifecycleOwner 를 켠다.
 * androidx.startup(InitializationProvider)은 본체 프로세스에서만 돌아, :player 에선 프로세스 생명 주기가 처음 상태에 멈춰 있었다 —
 * 재생 엔진(GeckoView)은 그것으로 네트워크 감시를 켜고(끊겼다 다시 이어지면 재생 화면이 알게) 화면을 떠나면 엔진을 쉬게 한다.
 * 늦게 켜는 공개 방법(ProcessLifecycleInitializer)은 막혀 있어 코틀린 internal 함수를 자바에서 부른다 —
 * lifecycle 버전을 올려 이름이 바뀌면 빌드가 실패해 바로 알 수 있다 (PlayerIdleTest 도 확인).
 */
public final class PlayerLifecycleShim {
    private PlayerLifecycleShim() {
    }

    public static void init(Context context) {
        ProcessLifecycleOwner.init$lifecycle_process(context);
    }
}
