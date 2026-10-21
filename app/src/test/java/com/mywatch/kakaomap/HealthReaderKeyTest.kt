package com.mywatch.kakaomap

import com.mywatch.kakaomap.battery.HealthReader
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 공유 uid 묶음의 이름 — 알파벳 첫 패키지 이름을 쓰면, One UI Watch 홈이 다른 패키지와 uid 를 나눌 때 그 이름이 앞에 오지 않아
 * 홈 과소모를 못 짚는다 (리뷰에서 찾음). 알려진 문제를 보는 패키지(Play 서비스·홈)가 묶음에 있으면 그 이름으로. 시스템 uid('android'
 * 가 낀 묶음 — system_server 포함)는 그러지 않는다
 */
class HealthReaderKeyTest {
    private val home = "com.samsung.android.wearable.sysui"

    @Test
    fun aKnownPackageNamesItsSharedGroup() {
        assertEquals("$home+1", HealthReader.groupKey(listOf("com.android.systemui", home)))
        assertEquals("com.google.android.gms+2", HealthReader.groupKey(listOf("com.google.android.gsf", "com.google.android.gms", "com.google.android.gms.policy")))
        assertEquals("com.example.a+1", HealthReader.groupKey(listOf("com.example.b", "com.example.a")))
        assertEquals("com.example.solo", HealthReader.groupKey(listOf("com.example.solo")))
    }

    @Test
    fun theSystemUidKeepsItsName() {
        assertEquals("android+2", HealthReader.groupKey(listOf(home, "android", "com.android.settings")))
        // 알파벳 순서와 상관없이 'android' (PC 툴킷 packageOf 와 같은 규칙)
        assertEquals("android+2", HealthReader.groupKey(listOf("aaa.vendor.service", home, "android")))
    }
}
