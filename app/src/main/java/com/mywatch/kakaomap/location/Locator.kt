package com.mywatch.kakaomap.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.mywatch.kakaomap.data.RouteRepository
import com.mywatch.kakaomap.model.Coord
import com.mywatch.kakaomap.model.Fix
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** 안드로이드 위치 → 측정 시각·정확도가 붙은 [Fix] */
fun Location.toFix(): Fix = Fix(
    coord = Coord(latitude, longitude),
    accuracyM = if (hasAccuracy()) accuracy else Float.NaN,
    elapsedMs = elapsedRealtimeNanos / 1_000_000,
)

/** 길찾기 출발지(현재 위치) 구하기 */
object Locator {

    /** 2분 안에 잡힌 위치면 그대로 출발지로 쓴다 */
    const val FRESH_MS = 2 * 60_000L

    /** 새 위치를 못 잡을 때 대신 쓸 수 있는 '마지막 위치'의 최대 나이 (실내 등) */
    private const val FALLBACK_MAX_AGE_MS = 10 * 60_000L

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** 위치가 몇 분 전 것인지 (화면에 'n분 전 위치'로 알릴 때) */
    fun ageMinutes(fix: Fix): Long = (SystemClock.elapsedRealtime() - fix.elapsedMs) / 60_000L

    /**
     * 최근(2분 이내) 위치가 있으면 그것을, 없으면 새로 잡는다.
     * 새로 못 잡으면 10분 이내의 마지막 위치까지만 허용 — 몇 시간 전 위치로 길을 찾는 일이 없게.
     * 권한이 없거나 아무것도 없으면 null.
     */
    @SuppressLint("MissingPermission")
    suspend fun current(context: Context, timeoutMs: Long = 20_000): Fix? {
        RouteRepository.freshFix(FRESH_MS)?.let { return it }
        if (!hasPermission(context)) return null
        val client = LocationServices.getFusedLocationProviderClient(context)
        val cts = CancellationTokenSource()
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(30_000)       // 30초 넘은 캐시 위치는 받지 않음
            .setDurationMillis(timeoutMs)
            .build()
        val fresh: Location? = withTimeoutOrNull(timeoutMs + 1_000) {
            suspendCancellableCoroutine { cont ->
                client.getCurrentLocation(request, cts.token)
                    .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                    .addOnFailureListener { if (cont.isActive) cont.resume(null) }
                cont.invokeOnCancellation { cts.cancel() }
            }
        }
        cts.cancel()
        val loc = fresh ?: withTimeoutOrNull(3_000) {
            suspendCancellableCoroutine { cont ->
                client.lastLocation
                    .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                    .addOnFailureListener { if (cont.isActive) cont.resume(null) }
            }
        }?.takeIf { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos <= FALLBACK_MAX_AGE_MS * 1_000_000 }
        val fix = loc?.toFix() ?: RouteRepository.freshFix(FALLBACK_MAX_AGE_MS) ?: return null
        RouteRepository.updateFix(fix)
        return fix
    }
}
