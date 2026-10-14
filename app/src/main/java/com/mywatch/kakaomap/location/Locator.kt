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

    /** 저전력 위치만 있을 때 GPS 를 기다리는 시간 (실내에서 오래 기다리지 않게) */
    private const val COARSE_WAIT_MS = 8_000L

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** '정확한 위치' 권한 — 길안내(갈림길·정류장 판단)에 필요. '대략적 위치'만 있으면 경로 보기만 된다 */
    fun hasPrecise(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** 위치가 몇 분 전 것인지 (화면에 'n분 전 위치'로 알릴 때) */
    fun ageMinutes(fix: Fix): Long = (SystemClock.elapsedRealtime() - fix.elapsedMs) / 60_000L

    /**
     * 최근(2분 이내)의 정확한 위치가 있으면 그것을, 없으면 새로 잡는다.
     * 최근 위치가 Wi-Fi·기지국 위치(안내 서비스가 도착 뒤·지하철에서 쓰는 저전력 위치)뿐이면 GPS 를 잠깐(8초) 기다려 보고,
     * 못 잡으면(실내) 그 위치라도 쓴다. 새로 못 잡으면 10분 이내의 마지막 위치까지만 허용 — 몇 시간 전 위치로 길을 찾지 않게.
     * 권한이 없거나 아무것도 없으면 null.
     */
    @SuppressLint("MissingPermission")
    suspend fun current(context: Context, timeoutMs: Long = 20_000): Fix? {
        val cached = RouteRepository.freshFix(FRESH_MS)
        if (cached != null && cached.goodForStart) return cached
        if (!hasPermission(context)) return cached
        val client = LocationServices.getFusedLocationProviderClient(context)
        val cts = CancellationTokenSource()
        val wait = if (cached != null) minOf(timeoutMs, COARSE_WAIT_MS) else timeoutMs
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            // 30초 넘은 캐시 위치는 받지 않음. 저전력 위치만 있으면 캐시를 아예 받지 않음(같은 저전력 위치가 되돌아오지 않게)
            .setMaxUpdateAgeMillis(if (cached != null) 0 else 30_000)
            .setDurationMillis(wait)
            .build()
        val fresh: Location? = withTimeoutOrNull(wait + 1_000) {
            suspendCancellableCoroutine { cont ->
                client.getCurrentLocation(request, cts.token)
                    .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                    .addOnFailureListener { if (cont.isActive) cont.resume(null) }
                cont.invokeOnCancellation { cts.cancel() }
            }
        }
        cts.cancel()
        fresh?.toFix()?.let { fix ->
            RouteRepository.updateFix(fix)
            return fix
        }
        if (cached != null) return cached
        val loc = withTimeoutOrNull(3_000) {
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

    /**
     * 검색 중심으로 쓸 대략적 위치 (몇 백 m 틀려도 됨 — GPS 를 켜지 않는다).
     * 저전력(Wi-Fi·기지국) 새 위치를 [waitMs] 까지 기다려 보고, 못 잡으면 [lastMaxAgeMs] 안의 마지막 위치.
     * 권한이 없거나 아무것도 없으면 null (그러면 전국에서 찾는다)
     */
    @SuppressLint("MissingPermission")
    suspend fun rough(context: Context, waitMs: Long = ROUGH_WAIT_MS, lastMaxAgeMs: Long = ROUGH_LAST_MAX_AGE_MS): Coord? {
        if (!hasPermission(context)) return null
        val client = LocationServices.getFusedLocationProviderClient(context)
        val cts = CancellationTokenSource()
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            .setMaxUpdateAgeMillis(ROUGH_FRESH_MS)
            .setDurationMillis(waitMs)
            .build()
        val fresh: Location? = withTimeoutOrNull(waitMs + 500) {
            suspendCancellableCoroutine { cont ->
                client.getCurrentLocation(request, cts.token)
                    .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                    .addOnFailureListener { if (cont.isActive) cont.resume(null) }
                cont.invokeOnCancellation { cts.cancel() }
            }
        }
        cts.cancel()
        val loc = fresh ?: withTimeoutOrNull(1_000) {
            suspendCancellableCoroutine { cont ->
                client.lastLocation
                    .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                    .addOnFailureListener { if (cont.isActive) cont.resume(null) }
            }
        }?.takeIf { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos <= lastMaxAgeMs * 1_000_000 }
        val fix = loc?.toFix() ?: return null
        RouteRepository.updateFix(fix)
        return fix.coord
    }

    /** 검색 중심을 잡을 때 기다리는 시간 */
    private const val ROUGH_WAIT_MS = 3_000L
    /** 이만큼 안에 다른 앱·시스템이 잡아 둔 위치면 바로 받는다 */
    private const val ROUGH_FRESH_MS = 10 * 60_000L
    /** 새로 못 잡을 때 쓸 마지막 위치의 최대 나이 (전국에서 아무 데나 찾는 것보다는 낫다) */
    private const val ROUGH_LAST_MAX_AGE_MS = 6 * 60 * 60_000L
}
