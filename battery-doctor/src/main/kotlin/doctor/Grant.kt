package doctor

import doctor.fix.Journal
import doctor.fix.JournalEntry

/**
 * 워치 앱(갤럭시워치 전용앱)의 '배터리 지킴이'가 쓸 특수 권한을 adb 로 준다.
 * 일반 앱은 받을 수 없고 PC 의 adb(`pm grant` / `appops set`)로만 줄 수 있는 권한들이라 PC 에서 한 번 해 줘야 한다.
 * 단계마다 실패해도 계속 진행하고, 결과를 권한 → 성공/실패(사유) 표로 보여 준다.
 * 준 권한은 모두 [Journal] 에 `grant:<권한>` 으로 남겨 `undo --all` 이 회수(`pm revoke` / `appops set … default`)할 수 있다.
 * [doctor.fix.Catalog] 와는 무관하다.
 */
object Grant {
    const val PKG = "com.mywatch.kakaomap"
    const val MIN_APP_VERSION = "v2.33"

    /** 권한 한 개를 주는 단계. [key] 는 [verify] 가 돌려주는 맵의 키이자 기록 id 의 뒷부분 */
    data class Step(
        val key: String,
        /** 표에 보여 줄 짧은 이름 */
        val short: String,
        /** 이 권한으로 지킴이가 할 수 있게 되는 일 */
        val allows: String,
        /** `adb shell` 뒤에 붙는 인자 */
        val grant: List<String>,
        val revoke: List<String>,
    ) {
        val id: String get() = "grant:$key"
        val isAppOp: Boolean get() = !key.startsWith("android.permission.")
    }

    private fun pm(perm: String, allows: String) = Step(
        perm, perm.removePrefix("android.permission."), allows,
        listOf("pm", "grant", PKG, perm), listOf("pm", "revoke", PKG, perm),
    )

    private fun appop(op: String, allows: String) = Step(
        op, "$op(appops)", allows,
        listOf("appops", "set", PKG, op, "allow"), listOf("appops", "set", PKG, op, "default"),
    )

    /** 순서대로 실행한다 */
    val STEPS: List<Step> = listOf(
        pm("android.permission.BATTERY_STATS", "앱별 배터리 소모 보기"),
        pm("android.permission.WRITE_SECURE_SETTINGS", "절전·AOD·위치·동기화 자동 조절"),
        pm("android.permission.DUMP", "시스템 상태(웨이크락·알람) 읽기"),
        pm("android.permission.READ_LOGS", "시스템 로그 읽기"),
        appop("GET_USAGE_STATS", "앱 사용 통계 보기"),
        appop("WRITE_SETTINGS", "일반 설정(화면 밝기·꺼짐 시간) 조절"),
    )

    val APPOPS: List<String> = STEPS.filter { it.isAppOp }.map { it.key }

    /** 단계 하나의 결과 */
    data class Outcome(val step: Step, val ok: Boolean, val alreadyHad: Boolean, val reason: String?)

    /**
     * 실행 → 확인 → 표 출력. 전부 성공이면 0, 하나라도 실패면 1.
     * 실패한 단계도 기록(ok=false)은 남긴다 — 되돌릴 명령은 비워 둔다.
     */
    fun run(adb: AdbRunner, io: Console, journal: Journal, now: Long = System.currentTimeMillis()): Int {
        io.println()
        io.println("[권한 주기] $PKG 에 배터리 지킴이용 특수 권한을 줍니다 (${STEPS.size}개, 모두 기록되고 되돌릴 수 있음)")
        val before = probe(adb)
        val results = ArrayList<Pair<Step, Result>>()
        for (step in STEPS) {
            val r = try { adb.run(listOf("shell") + step.grant, 60) } catch (e: Exception) { Result(-3, "", e.toString()) }
            val failed = !r.ok || looksLikeError(r)
            results += step to r
            val had = before[step.key] == true
            // 이미 있던 권한은 되돌릴 것이 없다(우리가 준 것이 아님) — 모르면(조회 실패) 회수 명령을 남겨 둔다. 실패한 단계도 되돌릴 것 없음
            val undo: List<List<String>> = if (failed || had) emptyList() else listOf(listOf("shell") + step.revoke)
            journal.append(JournalEntry(
                now, step.id, before = before[step.key]?.let { if (it) "granted" else "denied" }, applied = listOf(listOf("shell") + step.grant),
                undo = undo, undone = false, ok = !failed, note = if (failed) errorText(r) else null,
            ))
        }
        val after = probe(adb)
        val outcomes = results.map { (step, r) ->
            val failed = !r.ok || looksLikeError(r)
            val verified = after[step.key]
            when {
                failed -> Outcome(step, false, false, explain(errorText(r)))
                verified == false -> Outcome(step, false, false, "명령은 통과했지만 확인(dumpsys package / appops get)에서 아직 허용되지 않음 — 이 펌웨어가 조용히 막은 것 같습니다. 그 기능은 지킴이에서 '권한 없음'으로 표시됩니다")
                else -> Outcome(step, true, before[step.key] == true, null)
            }
        }
        print(io, outcomes, after)
        io.println("기록: ${journal.file.absolutePath} — 메뉴 5(되돌리기) → all 로 준 권한을 모두 회수할 수 있습니다.")
        return if (outcomes.all { it.ok }) 0 else 1
    }

    private fun print(io: Console, outcomes: List<Outcome>, after: Map<String, Boolean>) {
        io.println(String.format(" %-24s %-14s %s", "권한", "결과", "지킴이가 할 수 있는 일"))
        for (o in outcomes) {
            val result = when { !o.ok -> "실패"; o.alreadyHad -> "성공(이미 있음)"; else -> "성공" }
            io.println(String.format(" %-24s %-14s %s%s", o.step.short, result, o.step.allows, o.reason?.let { " — $it" } ?: ""))
        }
        val ok = outcomes.count { it.ok }
        val checks = listOf("android.permission.BATTERY_STATS", "android.permission.WRITE_SECURE_SETTINGS", "GET_USAGE_STATS").map { k ->
            val v = after[k]
            val shown = when { v == null -> "확인 못 함"; k.startsWith("android.permission.") -> "granted=$v"; v -> "allow"; else -> "미허용" }
            "${k.removePrefix("android.permission.")} $shown"
        }
        io.println("확인(dumpsys package / appops get): ${checks.joinToString(", ")}")
        io.println(if (ok == outcomes.size) "권한 ${outcomes.size}개를 모두 주었습니다. 워치 앱의 '배터리 지킴이' 화면을 다시 열면 바로 반영됩니다."
            else "권한 ${outcomes.size}개 중 ${ok}개 성공, ${outcomes.size - ok}개 실패 — 실패한 기능만 지킴이에서 '권한 없음'으로 표시되고 나머지는 그대로 됩니다.")
    }

    /** 지금 상태: 키 → 허용 여부. 조회 자체가 실패한 키는 빠진다 */
    fun probe(adb: AdbRunner): Map<String, Boolean> {
        val dump = try { adb.run(listOf("shell", "dumpsys", "package", PKG), 60) } catch (e: Exception) { Result(-3, "", e.toString()) }
        val ops = APPOPS.associateWith { op -> try { adb.run(listOf("shell", "appops", "get", PKG, op), 60) } catch (e: Exception) { Result(-3, "", e.toString()) } }
        val parsed = verify(if (dump.ok) dump.out else "", ops.values.filter { it.ok }.joinToString("\n") { it.out })
        return parsed.filterKeys { k -> if (k.startsWith("android.permission.")) dump.ok else ops[k]?.ok == true }
    }

    /**
     * `dumpsys package com.mywatch.kakaomap` 와 `appops get …` 출력에서 [STEPS] 의 키마다 허용 여부를 읽는다.
     * 권한은 `<이름>: granted=true` 줄(install/runtime permissions 절), appops 는 `<OP>: allow` 줄. 없으면 false.
     */
    fun verify(dumpsysPackage: String, appopsOut: String): Map<String, Boolean> {
        val granted = HashMap<String, Boolean>()
        for (m in Regex("(?m)^\\s*(android\\.permission\\.[A-Z0-9_]+): granted=(true|false)").findAll(dumpsysPackage)) {
            granted[m.groupValues[1]] = m.groupValues[2] == "true"   // 뒤(runtime permissions)가 앞(install permissions)을 덮는다
        }
        val modes = HashMap<String, String>()
        for (m in Regex("(?m)^\\s*(?:Uid mode: )?([A-Z][A-Z0-9_]+):\\s*([a-z]+)").findAll(appopsOut)) modes[m.groupValues[1]] = m.groupValues[2]
        val out = LinkedHashMap<String, Boolean>()
        for (s in STEPS) out[s.key] = if (s.isAppOp) modes[s.key].let { it == "allow" || it == "foreground" } else granted[s.key] == true
        return out
    }

    /** pm/appops 는 exit 0 으로 끝나면서 오류를 찍기도 한다 */
    private fun looksLikeError(r: Result): Boolean {
        val t = (r.err + "\n" + r.out)
        return t.contains("Exception") || t.contains("Error:") || t.contains("Unknown package") || t.contains("Unknown permission") || t.contains("Unknown operation") || t.contains("not allowed")
    }

    private fun errorText(r: Result): String =
        (r.err + "\n" + r.out).lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" | ").ifEmpty { "exit ${r.code}" }

    /** 자주 보는 실패 문구를 우리말로. 모르는 문구는 그대로 */
    fun explain(error: String): String = when {
        error.contains("Unknown package", true) || error.contains("No package found", true) || error.contains("Unable to find package", true) || error.contains("No UID for", true) ->
            "앱이 아직 설치되지 않았습니다 → 워치 앱을 $MIN_APP_VERSION 이상으로 업데이트(설치)한 뒤 다시 실행하세요"
        error.contains("not requested by package", true) || error.contains("has not requested permission", true) ->
            "앱 버전이 낮아 이 권한을 요청하지 않습니다 → 워치 앱을 $MIN_APP_VERSION 이상으로 업데이트한 뒤 다시 실행하세요"
        error.contains("Operation not allowed", true) || error.contains("SecurityException", true) || error.contains("Permission Denial", true) || error.contains("not allowed", true) ->
            "이 펌웨어가 막았습니다 — 그 기능은 지킴이에서 '권한 없음'으로 표시됩니다 (다른 기능은 그대로 됩니다)"
        error.contains("Unknown permission", true) || error.contains("Unknown operation", true) ->
            "이 펌웨어에는 없는 권한입니다 — 그 기능은 지킴이에서 '권한 없음'으로 표시됩니다"
        error.contains("시간 초과") || error.contains("offline") || error.contains("no devices", true) || error.contains("device", true) && error.contains("not found", true) ->
            "워치와 통신이 끊겼습니다 — 워치 화면을 켜고(손목 올림) 메뉴 1(연결) 뒤 다시 실행하세요"
        else -> "adb 오류: ${error.take(160)}"
    }
}
