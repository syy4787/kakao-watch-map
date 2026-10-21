package com.mywatch.battery.core

import java.time.Instant
import java.time.ZoneId
import kotlin.math.ceil

/** 지킴이 설정 — 사용자가 배터리 화면에서 켜고 끈다 */
data class GuardianConfig(
    /** 밤에 화면이 꺼져 있으면 절전 모드 */
    val nightSaver: Boolean = true,
    /** 밤에 Always On Display 끄기 */
    val nightAodOff: Boolean = true,
    /** 밤에 위치 끄기 (수면 측정엔 필요 없음) */
    val nightLocationOff: Boolean = false,
    /** 밤에 계정 자동 동기화 끄기 */
    val nightSyncOff: Boolean = false,
    /** 밤에 손목 올림·화면 터치로 켜지는 것 끄기 (버튼으로는 켜진다 — 삼성 취침 모드와 같은 묶음) */
    val nightWakeOff: Boolean = true,
    /** 밤에 'Hey Google' 음성 호출 끄기 (고른 사람만) */
    val nightHotwordOff: Boolean = false,
    /** 화면 꺼진 채 시간당 5% 넘게 새면 바로 절전 모드 */
    val abnormalSaver: Boolean = true,
    /** 잠자기 전, 아침까지 못 버틸 것 같으면 충전 알림 */
    val chargeReminder: Boolean = true,
    /** 밤 시작·끝 (그날의 분, 00:00 = 0) */
    val nightStartMin: Int = 0,
    val nightEndMin: Int = 7 * 60,
    /** 충전 알림을 보는 시각 */
    val reminderMin: Int = 22 * 60 + 30,
) {
    val anyNight: Boolean get() = nightSaver || nightAodOff || nightLocationOff || nightSyncOff || nightWakeOff || nightHotwordOff
}

/** 지킴이가 지금 무엇을 해 두었는지 — 되돌릴 때 필요 */
data class GuardianState(
    val nightActive: Boolean = false,
    val abnormalActive: Boolean = false,
    /** 사용자가 손수 절전을 껐으면 이 시각까지는 다시 건드리지 않는다 */
    val userOverrideUntil: Long = 0,
    val lastAbnormalNotifyAt: Long = 0,
    /** 충전 알림을 보낸 날 (yyyy-MM-dd) */
    val lastReminderDay: String? = null,
    val abnormalSince: Long = 0,
    val nightSince: Long = 0,
    /** 우리가 절전을 켠 뒤 시스템이 '절전 중'이라고 한 번이라도 답했나 — 그래야 '꺼짐'을 사용자가 끈 것으로 본다 */
    val saverSeenOn: Boolean = false,
    /** 절전 설정을 써도 시스템이 따르지 않는 워치 — 절전 레버는 포기하고 나머지(AOD 등)만 */
    val saverBroken: Boolean = false,
)

sealed class GuardianAction {
    object NightOn : GuardianAction()
    object NightOff : GuardianAction()
    data class AbnormalOn(val pph: Double, val notify: Boolean) : GuardianAction()
    object AbnormalOff : GuardianAction()
    /** 사용자가 절전을 직접 껐다 — 우리가 켠 것들을 모두 되돌리고 한동안 쉰다 */
    object UserOverride : GuardianAction()
    /** 절전 설정을 써도 시스템이 켜 주지 않는다 — 앞으로 절전 레버는 건너뛴다 */
    object SaverNotWorking : GuardianAction()
    data class ChargeReminder(val predictedPercent: Double, val minutes: Int, val ratePph: Double) : GuardianAction()
}

/**
 * 15분마다 한 번 불려서 할 일을 정한다. 설정을 실제로 바꾸는 쪽(안드로이드)은 여기서 나온 행동만 수행한다.
 * 원칙: 사용자가 쓰고 있을 땐 건드리지 않고(화면이 30분 넘게 꺼져 있어야 밤 모드), 사용자가 손수 끄면 12시간 물러난다.
 */
object GuardianRules {
    const val SCREEN_OFF_BEFORE_NIGHT_MS = 30L * 60_000L
    /** 화면이 꺼진 지 이보다 짧으면 '방금 봤다' (표본 간격) */
    const val WAKE_SEEN_MS = 15L * 60_000L
    const val USER_OVERRIDE_MS = 12L * 3_600_000L
    /** 절전을 켠 뒤 이만큼 지나도 시스템이 '절전 중'이 아니면 이 워치에선 안 되는 것 */
    const val SAVER_CONFIRM_MS = 35L * 60_000L
    const val ABNORMAL_NOTIFY_COOLDOWN_MS = 6L * 3_600_000L
    /** 비정상 절전은 길어야 이만큼 — 그 뒤엔 풀고 다시 잰다 */
    const val ABNORMAL_MAX_MS = 6L * 3_600_000L
    /** 아침까지 남을 잔량이 이보다 적으면 충전 알림 */
    const val REMINDER_FLOOR = 15.0
    /** 알림이 권하는 목표 잔량(아침에 이만큼은 남게) */
    const val REMINDER_TARGET = 25.0
    /** 완전 방전에서 꽂으면 처음엔 분당 약 1%p (Android Authority 실측) */
    const val CHARGE_PERCENT_PER_MIN = 1.0

    fun decide(
        now: Long,
        zone: ZoneId,
        cfg: GuardianConfig,
        st: GuardianState,
        samples: List<BatterySample>,
        analysis: Analysis?,
        /** 지금 절전 모드가 켜져 있나 (모르면 null) */
        saverOnNow: Boolean?,
        /** 화면이 지금까지 꺼져 있던 시간(사용 통계로 정확히 알 때) — 없으면 15분 표본으로 어림 */
        screenOffMs: Long? = null,
        /** 최근 2시간 중 화면이 켜져 있던 시간(사용 통계) — 비정상 소모 판단에 */
        screenOnMs2h: Long? = null,
        /**
         * 이 앱의 길 안내 중이거나 최근 2시간 안에 안내했다 — 그동안은 밤·비정상 모드를 켜지 않고 켜 둔 것도 푼다
         * (안내의 GPS·LTE 소모를 '비정상'으로 읽어 절전을 켜거나, 밤 안내 중 손목 올려 켜기를 끄지 않게)
         */
        navigating: Boolean = false,
        /**
         * 기록을 새로 재기 시작한 때 — 지킴이가 본 마지막 재시작(부팅 표본이 늦게 남아도)이나 PC 자동 치료 (모르면 null). 그 뒤에 켠 비정상
         * 모드가 아니면 풀고 다시 재고, 비정상 판단은 그 뒤 표본으로만
         */
        freshFrom: Long? = null,
    ): Pair<List<GuardianAction>, GuardianState> {
        val actions = ArrayList<GuardianAction>()
        var s = st
        val latest = samples.lastOrNull()
        val charging = latest?.plugged == true
        val local = Instant.ofEpochMilli(now).atZone(zone)
        val minuteOfDay = local.hour * 60 + local.minute
        val today = local.toLocalDate().toString()

        // 1. 우리가 절전을 켜 둔 상태인가 — 시스템이 따라왔는지, 사용자가 손수 껐는지
        val saverWanted = (s.nightActive && cfg.nightSaver || s.abnormalActive) && !s.saverBroken
        if (saverWanted && saverOnNow == true && !s.saverSeenOn) s = s.copy(saverSeenOn = true)
        if (saverWanted && saverOnNow == false && !charging) {
            if (s.saverSeenOn) {
                // 켜졌던 것이 꺼졌다 → 사용자가 끈 것. 모두 되돌리고 물러난다
                actions += GuardianAction.UserOverride
                s = s.copy(nightActive = false, abnormalActive = false, userOverrideUntil = now + USER_OVERRIDE_MS, saverSeenOn = false)
            } else {
                val since = if (s.abnormalActive) s.abnormalSince else s.nightSince
                if (since > 0 && now - since >= SAVER_CONFIRM_MS) {
                    // 켠 지 한참인데 한 번도 켜지지 않았다 → 이 워치는 설정으로 절전을 못 켠다
                    actions += GuardianAction.SaverNotWorking
                    s = s.copy(saverBroken = true)
                }
            }
        }
        val overridden = now < s.userOverrideUntil
        val screenOff = screenOffMs ?: screenOffFor(samples, now)
        val inNight = inWindow(minuteOfDay, cfg.nightStartMin, cfg.nightEndMin)

        // 2. 밤 모드
        if (s.nightActive) {
            // 아침 2시간 안에 화면을 봤다 = 일어났다. 15분 표본이 놓친 짧은 켜짐도 사용 통계(화면 꺼진 시간)로 안다
            val lookedJustNow = latest?.interactive == true || (screenOffMs != null && screenOffMs < WAKE_SEEN_MS)
            val wakingUp = lookedJustNow && minutesUntil(minuteOfDay, cfg.nightEndMin) <= 120
            if (!inNight || charging || wakingUp || navigating) {
                actions += GuardianAction.NightOff
                s = s.copy(nightActive = false, saverSeenOn = false)
            }
        } else if (cfg.anyNight && inNight && !charging && !overridden && !navigating && screenOff >= SCREEN_OFF_BEFORE_NIGHT_MS && !s.abnormalActive) {
            actions += GuardianAction.NightOn
            s = s.copy(nightActive = true, nightSince = now, saverSeenOn = false)
        }

        // 3. 비정상 소모 — 밤 모드가 아닐 때만 (밤엔 이미 절전). 켠 뒤에 재시작했으면(알림대로 강제 재시작) 풀고 재시작 뒤로 다시 잰다.
        // 켠 때가 미래면(시계가 뒤로 감) 얼마나 됐는지 모르니 푼다
        if (s.abnormalActive) {
            val restarted = samples.any { it.event == Event.BOOT && it.t > s.abnormalSince && it.t <= now } ||
                (freshFrom != null && freshFrom >= s.abnormalSince && freshFrom <= now)
            if (charging || navigating || restarted || now - s.abnormalSince !in 0 until ABNORMAL_MAX_MS) {
                actions += GuardianAction.AbnormalOff
                s = s.copy(abnormalActive = false, saverSeenOn = false)
            }
        } else if (cfg.abnormalSaver && !s.nightActive && !charging && !overridden && !navigating) {
            val a = AlertRule.check(samples, now, lastAlertAt = null, screenOnMs = screenOnMs2h, freshFrom = freshFrom)
            if (a != null) {
                val notify = now - s.lastAbnormalNotifyAt !in 0 until ABNORMAL_NOTIFY_COOLDOWN_MS
                actions += GuardianAction.AbnormalOn(a.pph, notify)
                s = s.copy(abnormalActive = true, abnormalSince = now, saverSeenOn = false, lastAbnormalNotifyAt = if (notify) now else s.lastAbnormalNotifyAt)
            }
        }

        // 4. 잠자기 전 충전 알림 — 하루 한 번, 충전 중이 아닐 때
        if (cfg.chargeReminder && latest != null && !charging && s.lastReminderDay != today &&
            inWindow(minuteOfDay, cfg.reminderMin, cfg.nightStartMin.takeIf { it > cfg.reminderMin } ?: (cfg.reminderMin + 90) % 1440)
        ) {
            val rate = analysis?.overnight?.percentPerHour ?: analysis?.idle24?.percentPerHour ?: analysis?.w24?.percentPerHour ?: Thresholds.IDLE_NORMAL
            val hours = minutesUntil(minuteOfDay, cfg.nightEndMin) / 60.0
            val predicted = latest.level - rate * hours
            s = s.copy(lastReminderDay = today)
            if (predicted < REMINDER_FLOOR) {
                val minutes = ceil((REMINDER_TARGET - predicted) / CHARGE_PERCENT_PER_MIN).toInt().coerceIn(5, 90)
                actions += GuardianAction.ChargeReminder(predicted, minutes, rate)
            }
        }
        return actions to s
    }

    /** 마지막으로 화면이 켜져 있던 표본부터 지금까지(ms). 표본이 없으면 0 */
    fun screenOffFor(samples: List<BatterySample>, now: Long): Long {
        val last = samples.lastOrNull() ?: return 0
        if (last.interactive) return 0
        val lastOn = samples.lastOrNull { it.interactive }
        val since = lastOn?.t ?: samples.first().t
        return (now - since).coerceAtLeast(0)
    }

    /** [start, end) — 자정을 넘는 창도 된다 */
    fun inWindow(minute: Int, start: Int, end: Int): Boolean =
        if (start <= end) minute in start until end else minute >= start || minute < end

    /** 지금부터 다음 [target] 분까지 남은 분 */
    fun minutesUntil(minute: Int, target: Int): Int = ((target - minute) % 1440 + 1440) % 1440
}
