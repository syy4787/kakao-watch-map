package com.mywatch.kakaomap.location

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.edit

/**
 * 위치 권한 묻기 도우미. 안드로이드는 두 번 거절하면 권한 창을 더는 띄우지 않고 요청에 곧바로 '거절'을 돌려주므로,
 * 그렇게 돌아왔을 때만 이 앱의 설정(권한) 화면을 연다 — 앱 안에서 막다른 길이 되지 않게.
 * (첫 창을 쓸어 닫았거나 '이번만 허용'이 끝난 것은 다시 물을 수 있으므로 평소처럼 창을 띄운다)
 */
object PermissionHelp {
    // 지도 화면이 쓰던 기록과 같은 곳 (이미 물어봤는지)
    private const val PREFS = "main"
    private const val KEY_ASKED = "asked_location"

    fun askedLocation(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ASKED, false)

    fun markAskedLocation(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_ASKED, true) }

    /** 권한 요청([askedAtMs])에 [answeredAtMs] 에 거절이 왔을 때, 사람이 누를 수 없을 만큼 빨랐으면 시스템이 창을 안 띄운 것 */
    fun deniedWithoutDialog(askedAtMs: Long, answeredAtMs: Long): Boolean =
        askedAtMs > 0 && answeredAtMs - askedAtMs in 0 until INSTANT_DENIAL_MS

    /** 창이 뜨고 사람이 거절을 누르기까지는 이보다 오래 걸린다 (창이 나타나는 데만 0.3초 남짓) */
    private const val INSTANT_DENIAL_MS = 700L

    /** 이 앱의 설정 화면(권한 → 위치). 열지 못하면 false */
    fun openAppSettings(activity: Activity): Boolean = try {
        activity.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null))
        )
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}
