package doctor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConnectTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val addr = "192.168.0.23:5555"
    private val none = "List of devices attached\n\n"
    private val connected = "List of devices attached\n$addr\tdevice\n\n"

    private fun cfg() = Config(tmp.newFolder("data"))

    private fun adbWithProps() = FakeAdb().apply {
        on("connect $addr", "connected to $addr\n")
        on("shell getprop ro.product.model", "SM-L350\n")
        on("shell getprop ro.build.display.id", "AZH4.260818.001\n")
        on("shell getprop ro.build.version.incremental", "L350XXU1AZH4\n")
    }

    @Test
    fun connectsWithoutPairingWhenUserSaysNo() {
        val adb = adbWithProps().apply { onSequence("devices", none, connected) }
        // devices 결과에 주소가 없으므로 connect 가 불려야 하고, 페어링은 'n' 이라 pair 는 안 불린다
        val io = FakeConsole(listOf("n", addr))
        val cfg = cfg()
        val ok = Connect.run(adb, cfg, io)
        assertTrue(io.text(), ok)
        val j = adb.joined()
        assertTrue(j.any { it == "connect $addr" })
        assertFalse(j.any { it.startsWith("pair") })
        assertEquals(addr, cfg.lastAddress)
        assertTrue(io.text().contains("SM-L350"))
        assertTrue(io.text().contains("L350XXU1AZH4"))
        // 5번 탭 안내가 먼저 나온다
        assertTrue(io.text().contains("소프트웨어 버전"))
        assertTrue(io.text().contains("5번"))
        assertTrue(io.text().contains("자동 Wi-Fi 끄기"))
    }

    @Test
    fun pairsOnlyWhenUserEntersCode() {
        val adb = adbWithProps().apply {
            onSequence("devices", none, connected)
            on("pair 192.168.0.23:41231 123456", "Successfully paired to 192.168.0.23:41231 [guid=adb-xyz]\n")
        }
        val io = FakeConsole(listOf("y", "192.168.0.23:41231", "123456", addr))
        val ok = Connect.run(adb, cfg(), io)
        assertTrue(io.text(), ok)
        val j = adb.joined()
        assertEquals(listOf("pair 192.168.0.23:41231 123456"), j.filter { it.startsWith("pair") })
        assertTrue(j.indexOf("pair 192.168.0.23:41231 123456") < j.indexOf("connect $addr"))
        assertTrue(io.text().contains("페어링 성공"))
    }

    @Test
    fun alreadyConnectedSkipsConnect() {
        val adb = adbWithProps().apply { on("devices", connected) }
        val cfg = cfg().apply { lastAddress = addr }
        val io = FakeConsole(listOf("n", ""))   // 주소는 기본값(엔터)
        assertTrue(io.text(), Connect.run(adb, cfg, io))
        assertTrue(io.text().contains("이미 연결"))
        assertFalse(adb.joined().any { it.startsWith("connect") })
    }

    @Test
    fun explicitAddressArgumentSkipsPrompts() {
        val adb = adbWithProps().apply { onSequence("devices", none, connected) }
        val io = FakeConsole(emptyList())
        assertTrue(io.text(), Connect.run(adb, cfg(), io, address = addr))
        assertTrue(adb.joined().contains("connect $addr"))
        assertTrue(io.prompts.isEmpty())
    }

    @Test
    fun failedConnectExplainsWifiAndPorts() {
        val adb = FakeAdb().apply {
            on("devices", none)
            on("connect $addr", "failed to connect to '$addr': Connection refused\n", code = 1)
        }
        val io = FakeConsole(listOf("n", addr))
        assertFalse(Connect.run(adb, cfg(), io))
        val t = io.text()
        assertTrue(t.contains("같은 Wi-Fi"))
        assertTrue(t.contains("AP 격리"))
        assertTrue(t.contains("자동 Wi-Fi 끄기"))
        assertTrue(t.contains("포트"))
    }

    @Test
    fun unauthenticatedConnectAsksForPairing() {
        val adb = FakeAdb().apply {
            on("devices", none)
            on("connect $addr", "failed to authenticate to $addr\n", code = 1)
        }
        val io = FakeConsole(listOf("n", addr))
        assertFalse(Connect.run(adb, cfg(), io))
        assertTrue(io.text().contains("페어링"))
    }

    @Test
    fun offlineDeviceIsReported() {
        val adb = FakeAdb().apply {
            on("devices", "List of devices attached\n$addr\toffline\n\n")
            on("connect $addr", "connected to $addr\n")
        }
        val io = FakeConsole(listOf("n", addr))
        assertFalse(Connect.run(adb, cfg(), io))
        assertTrue(io.text().contains("오프라인"))
    }

    @Test
    fun badPairingCodeStopsBeforeConnect() {
        val adb = FakeAdb().apply {
            on("devices", none)
            on("pair 192.168.0.23:41231 000000", "Failed: Wrong password or connection was dropped.\n", code = 1)
        }
        val io = FakeConsole(listOf("y", "192.168.0.23:41231", "000000"))
        assertFalse(Connect.run(adb, cfg(), io))
        assertTrue(io.text().contains("페어링 실패"))
        assertFalse(adb.joined().any { it.startsWith("connect") })
    }

    @Test
    fun configRoundTrip() {
        val dir = tmp.newFolder("cfg")
        val c = Config(dir)
        assertNull(c.lastAddress)
        c.lastAddress = addr
        c.purchaseDate = "2026-08-07"
        c.save()
        val again = Config(dir)
        assertEquals(addr, again.lastAddress)
        assertEquals("2026-08-07", again.purchaseDate)
        assertNull(again.adbPath)
    }
}
