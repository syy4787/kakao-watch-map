package doctor

/**
 * 워치와 무선 디버깅으로 연결하는 안내·대화.
 * 안내 → (필요하면) `adb pair` → `adb connect` → `adb devices` 확인 → 모델·펌웨어 출력 → 주소 저장.
 */
object Connect {

    val GUIDE: List<String> = listOf(
        "[워치에서 무선 디버깅 켜기]",
        " 1) 워치: 설정 > 워치 정보 > 소프트웨어 > '소프트웨어 버전'을 5번 연속 탭 → 개발자 모드가 켜집니다",
        " 2) 워치: 설정 > 개발자 옵션 > 'ADB 디버깅' 켜기, '무선 디버깅' 켜기 (\"이 네트워크에서 항상 허용\" 선택)",
        "    연결 작업 중에는 개발자 옵션의 '자동 Wi-Fi 끄기'도 켜 두면 끊김이 줄어듭니다 (측정을 시작하면 다시 끄세요)",
        " 3) 워치와 이 PC가 같은 Wi-Fi에 있어야 합니다. 손님용 Wi-Fi나 AP 격리(기기 간 통신 차단)가 켜진 공유기는 안 됩니다",
        "    (안 되면 휴대폰 핫스팟에 PC와 워치를 함께 붙이세요)",
        " 4) 처음 한 번: 무선 디버깅 > '새 기기 페어링' → 화면의 IP:페어링포트 와 6자리 코드가 필요합니다",
        " 5) 그다음: 무선 디버깅 화면 첫 줄에 보이는 IP:연결포트 를 입력합니다 (페어링 포트와 '다른' 번호입니다)",
        "    무선 디버깅을 껐다 켜거나 Wi-Fi가 바뀌면 포트가 바뀌니 그때마다 다시 확인하세요",
    )

    fun run(adb: AdbRunner, cfg: Config, io: Console, address: String? = null): Boolean {
        GUIDE.forEach { io.println(it) }
        io.println()

        val before = Adb.parseDevices(adb.run(listOf("devices"), 30).out)
        val target: String
        if (address != null) {
            target = address.trim()
        } else {
            val needPair = ask(io, "페어링이 필요합니까? (처음 연결하거나 '새 기기 페어링'을 다시 했을 때만) [y/N]: ")
            if (needPair.lowercase().startsWith("y")) {
                if (!pair(adb, io)) return false
            }
            val default = cfg.lastAddress
            val prompt = if (default != null) "연결 주소 (IP:연결포트) [엔터 = $default]: " else "연결 주소 (IP:연결포트, 예 192.168.0.23:5555): "
            val typed = ask(io, prompt)
            target = if (typed.isBlank()) (default ?: "") else typed
        }
        if (target.isBlank() || !target.contains(':')) {
            io.println("주소를 IP:포트 형태로 입력해야 합니다 (예 192.168.0.23:5555)")
            return false
        }

        if (before[target] == "device") {
            io.println("이미 연결되어 있습니다: $target")
        } else {
            val r = adb.run(listOf("connect", target), 60)
            val text = (r.out + "\n" + r.err).trim()
            io.println("adb connect → $text")
            when {
                text.contains("already connected") -> io.println("이미 연결되어 있던 주소입니다")
                text.contains("connected to") -> io.println("연결되었습니다")
                text.contains("failed to authenticate") || text.contains("authenticat") -> {
                    io.println("인증 실패 — 이 PC와 워치가 페어링되어 있지 않습니다.")
                    io.println("  워치의 무선 디버깅 > '새 기기 페어링'을 열고, 이 메뉴를 다시 실행해 '페어링이 필요합니까?'에 y 를 입력하세요.")
                    return false
                }
                else -> {
                    explainFailure(io)
                    return false
                }
            }
        }

        val after = Adb.parseDevices(adb.run(listOf("devices"), 30).out)
        when (after[target]) {
            "device" -> {}
            "offline" -> {
                io.println("기기가 '오프라인' 상태입니다 — 워치에서 무선 디버깅을 껐다가 다시 켜고(또는 워치 Wi-Fi를 껐다 켜고) 이 메뉴를 다시 실행하세요.")
                io.println("  그래도 안 되면 명령 프롬프트에서 adb kill-server 를 실행한 뒤 다시 시도하세요.")
                return false
            }
            "unauthorized" -> {
                io.println("워치 화면에 뜬 '디버깅을 허용하시겠습니까?'에서 '이 컴퓨터에서 항상 허용'을 누른 뒤 다시 실행하세요.")
                return false
            }
            null -> {
                io.println("adb devices 목록에 $target 이(가) 보이지 않습니다.")
                explainFailure(io)
                return false
            }
            else -> {
                io.println("기기 상태: ${after[target]} — 잠시 뒤 다시 시도하세요.")
                return false
            }
        }

        val dev = adb.withSerial(target)
        val model = prop(dev, "ro.product.model")
        val display = prop(dev, "ro.build.display.id")
        val incremental = prop(dev, "ro.build.version.incremental")
        io.println("워치 확인: 모델 ${model ?: "?"} / 펌웨어 ${display ?: "?"} (${incremental ?: "?"})")
        cfg.lastAddress = target
        cfg.save()
        io.println("연결 주소를 저장했습니다. 이제 메뉴 2(수집·분석) 또는 3(측정 시작)을 쓸 수 있습니다.")
        return true
    }

    private fun pair(adb: AdbRunner, io: Console): Boolean {
        val addr = ask(io, "페어링 주소 (워치 '새 기기 페어링' 화면의 IP:페어링포트): ")
        val code = ask(io, "6자리 페어링 코드: ")
        if (!addr.contains(':') || code.isBlank()) {
            io.println("페어링 주소와 코드를 모두 입력해야 합니다.")
            return false
        }
        val r = adb.run(listOf("pair", addr, code), 60)
        val text = (r.out + "\n" + r.err).trim()
        io.println("adb pair → $text")
        return if (r.ok && text.contains("Successfully paired")) {
            io.println("페어링 성공. 워치에 '이 컴퓨터에서 항상 허용' 안내가 뜨면 허용하세요.")
            true
        } else {
            io.println("페어링 실패 — 코드는 몇 분이면 만료되니 '새 기기 페어링' 화면을 닫았다 다시 열어 새 코드로 시도하세요.")
            io.println("  워치와 PC가 같은 Wi-Fi인지, 페어링 '포트'(연결 포트와 다름)를 넣었는지 확인하세요.")
            false
        }
    }

    private fun explainFailure(io: Console) {
        io.println("연결 실패 — 다음을 확인하세요:")
        io.println("  ① 워치와 PC가 같은 Wi-Fi에 있는지 (휴대폰 핫스팟이 가장 확실합니다)")
        io.println("  ② 무선 디버깅 화면 첫 줄의 IP:연결포트 가 맞는지 — 무선 디버깅을 껐다 켜면 포트가 바뀝니다 (페어링 포트가 아닙니다)")
        io.println("  ③ 공유기의 AP 격리(무선 단말 간 통신 차단, '게스트 네트워크')가 켜져 있지 않은지")
        io.println("  ④ 개발자 옵션의 '자동 Wi-Fi 끄기'를 켰는지 — 끄면 워치가 휴대폰과 연결될 때 Wi-Fi를 꺼 버립니다")
        io.println("  ⑤ 워치 화면을 켠 채로(손목 올림) 다시 시도 — 화면이 꺼지면 Wi-Fi가 잠시 끊길 수 있습니다")
    }

    private fun prop(dev: AdbRunner, key: String): String? =
        dev.run(listOf("shell", "getprop", key), 30).takeIf { it.ok }?.out?.trim()?.takeIf { it.isNotEmpty() }

    private fun ask(io: Console, prompt: String): String = io.readLine(prompt)?.trim() ?: ""
}
