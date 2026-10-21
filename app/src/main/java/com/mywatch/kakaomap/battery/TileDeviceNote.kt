package com.mywatch.kakaomap.battery

import android.content.Context
import java.util.Locale

/**
 * 워치가 타일에 보내는 기기 값(화면 폭·글꼴 배율·타일 렌더러 버전) 한 줄 — 하루 요약의 '[타일]' 줄로 PC 툴킷 보고서에 실린다.
 * 타일 맞춤 시험은 렌더러 1.500·글꼴 1배를 어림으로 쓰므로 실제 워치 값을 알아 두려고. 같은 값이면 다시 쓰지 않는다
 */
object TileDeviceNote {
    const val KEY = "tile_device"

    fun line(widthDp: Int, fontScale: Float, rendererMajor: Int, rendererMinor: Int): String =
        "${widthDp}dp · 글꼴 ${String.format(Locale.US, "%.2f", fontScale).trimEnd('0').trimEnd('.')}배 · 렌더러 $rendererMajor.$rendererMinor"

    fun note(context: Context, line: String) {
        val p = context.getSharedPreferences(BatteryLog.PREFS, Context.MODE_PRIVATE)
        if (p.getString(KEY, null) != line) p.edit().putString(KEY, line).apply()
    }

    fun read(context: Context): String? =
        context.getSharedPreferences(BatteryLog.PREFS, Context.MODE_PRIVATE).getString(KEY, null)
}
