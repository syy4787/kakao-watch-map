package com.mywatch.kakaomap.update

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import java.io.Closeable
import java.net.URL
import java.net.URLConnection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 큰 내려받기(새 버전 APK — 재생 엔진이 들어 있어 약 110MB)를 위한 워치 Wi-Fi.
 * 워치는 폰과 블루투스로 이어져 있으면 인터넷을 폰을 거쳐(느리게) 쓰는데, 저장된 Wi-Fi 가 가까이 있으면 잠깐 켜 달라고 해서
 * 그쪽으로 받는다 — 수십 배 빠르고 폰 데이터를 쓰지 않는다. 다 받으면 바로 놓아준다(배터리).
 * Wear OS 안내: https://developer.android.com/training/wearables/data/network-access
 */
object UpdateNet {

    /** 받은 Wi-Fi — [open] 으로 그 망에서 연결을 연다. 다 쓰면 [close] (Wi-Fi 부탁을 거둔다) */
    interface Lease : Closeable {
        fun open(url: URL): URLConnection
    }

    /** 시험에서 Wi-Fi 대신 */
    @VisibleForTesting
    @Volatile
    internal var override: ((Context, Long) -> Lease?)? = null

    /**
     * 워치 Wi-Fi 를 부탁하고 [waitMs] 까지 기다린다. 오면 그 망, 안 오면(저장된 Wi-Fi 가 없거나 멀다) null — 그땐 부탁을 거둔다.
     * 기다리는 동안 멈추므로 작업 스레드에서만 부른다
     */
    @WorkerThread
    fun wifi(context: Context, waitMs: Long): Lease? {
        override?.let { return it(context, waitMs) }
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val found = AtomicReference<Network?>()
        val ready = CountDownLatch(1)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                found.compareAndSet(null, network)
                ready.countDown()
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            cm.requestNetwork(request, callback)
        } catch (e: RuntimeException) {
            return null   // 권한·시스템 사정으로 못 부탁함 → 늘 쓰던 망으로
        }
        val network = try {
            if (ready.await(waitMs, TimeUnit.MILLISECONDS)) found.get() else null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
        if (network == null) {
            release(cm, callback)
            return null
        }
        return object : Lease {
            override fun open(url: URL): URLConnection = network.openConnection(url)
            override fun close() = release(cm, callback)
        }
    }

    /** 충전 중인지 — 자동 업데이트가 Wi-Fi 를 다시 켜 보는 때 (보통 집) */
    fun charging(context: Context): Boolean =
        runCatching { context.getSystemService(android.os.BatteryManager::class.java)?.isCharging == true }.getOrDefault(false)

    /** 지금 쓰는 망이 이미 Wi-Fi(또는 유선)인지 — 그럼 따로 부탁할 것 없이 그대로 받아도 된다 */
    fun onWifiNow(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }.getOrDefault(false)

    private fun release(cm: ConnectivityManager, callback: ConnectivityManager.NetworkCallback) {
        runCatching { cm.unregisterNetworkCallback(callback) }
    }
}
