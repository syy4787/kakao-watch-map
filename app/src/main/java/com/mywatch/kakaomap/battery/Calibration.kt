package com.mywatch.kakaomap.battery

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.mywatch.battery.core.BatterySample
import com.mywatch.battery.core.CalibrationAction
import com.mywatch.battery.core.CalibrationRule
import com.mywatch.battery.core.CalibrationState
import com.mywatch.kakaomap.R

/**
 * 게이지 보정 루틴의 워치 쪽: 상태를 설정 파일에 두고, 15분 표본마다 [CalibrationRule] 이 정한 대로 단계를 넘기며 알린다.
 * (완충 + 1시간 → 10% 아래까지 사용 → 다시 완충 + 1시간). 배터리 화면의 '게이지 보정 시작/그만두기'로 켜고 끈다.
 */
object Calibration {
    private const val K_STEP = "cal_step"
    private const val K_STARTED = "cal_started"
    private const val K_FULL_SINCE = "cal_full_since"
    private const val K_HAS_BEFORE = "cal_has_before"
    private const val K_BEFORE = "cal_before"
    private const val K_FINISHED = "cal_finished"

    private fun prefs(c: Context) = c.getSharedPreferences(BatteryLog.PREFS, Context.MODE_PRIVATE)

    fun state(c: Context): CalibrationState = prefs(c).let { p ->
        CalibrationState(
            step = p.getInt(K_STEP, 0), startedAt = p.getLong(K_STARTED, 0L), fullSince = p.getLong(K_FULL_SINCE, 0L),
            driftBefore = if (p.getBoolean(K_HAS_BEFORE, false)) p.getInt(K_BEFORE, 0) else null, finishedAt = p.getLong(K_FINISHED, 0L),
        )
    }

    fun save(c: Context, s: CalibrationState) = prefs(c).edit()
        .putInt(K_STEP, s.step).putLong(K_STARTED, s.startedAt).putLong(K_FULL_SINCE, s.fullSince)
        .putBoolean(K_HAS_BEFORE, s.driftBefore != null).putInt(K_BEFORE, s.driftBefore ?: 0).putLong(K_FINISHED, s.finishedAt).apply()

    fun start(c: Context, now: Long = System.currentTimeMillis(), driftPoints: Int?) = save(c, CalibrationRule.start(now, driftPoints))

    fun stop(c: Context) {
        save(c, CalibrationState())
        runCatching { c.getSystemService(NotificationManager::class.java)?.cancel(Guardian.N_CAL) }
    }

    /** 15분 표본 뒤에 [Guardian.tick] 이 부른다 */
    fun tick(c: Context, samples: List<BatterySample>, now: Long) {
        val st = state(c)
        if (!st.active) return
        val (actions, next) = CalibrationRule.decide(now, st, samples.filter { it.t <= now }.maxByOrNull { it.t })
        for (a in actions) runCatching { notify(c, a) }
        if (next != st) save(c, next)
    }

    private fun notify(c: Context, a: CalibrationAction) {
        val nm = Guardian.channel(c) ?: return
        val (title, text) = when (a) {
            is CalibrationAction.HoldFull -> "완충 — 1시간 더 꽂아 두세요 (보정 ${a.step}단계)" to
                "잔량 게이지는 완충 상태에서 용량을 다시 배웁니다. 1시간 뒤 다음 할 일을 알려 드립니다."
            is CalibrationAction.StepDone -> if (a.step == 1) "보정 1단계 끝 — 충전기에서 빼세요" to
                "이제 평소처럼 쓰세요. ${CalibrationRule.DRAIN_TO}% 아래로 내려가면 다음 단계를 알려 드립니다. 중간에 충전해도 되지만 보정은 그만큼 늦어집니다."
            else "보정 2단계 끝 — 지금 충전기에 올리세요" to
                "끊지 말고 100%까지 충전한 뒤 1시간 더 두세요. 그러면 보정이 끝납니다."
            is CalibrationAction.Done -> "게이지 보정 끝" to
                ((a.driftBefore?.let { "시작할 때 잔량 표시 오차 ${it}포인트. " } ?: "") +
                    "몇 시간 쓰고 나면 배터리 화면 '잔량 표시 점검'에서 지금 오차를 확인하세요. 그래도 크면 강제 재시작(홈+뒤로 7초) 뒤 서비스센터 상담이 맞습니다.")
            CalibrationAction.Expired -> "게이지 보정 기한 지남" to "14일 안에 끝나지 않아 멈췄습니다. 배터리 화면에서 다시 시작할 수 있습니다."
        }
        val n = NotificationCompat.Builder(c, Guardian.CHANNEL)
            .setSmallIcon(R.drawable.ic_battery)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(Guardian.openScreen(c))
            .setAutoCancel(true)
            .build()
        nm.notify(Guardian.N_CAL, n)
    }
}
