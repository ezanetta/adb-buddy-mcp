package io.github.ezanetta.adbbuddy.tools

import io.github.ezanetta.adbbuddy.adb.ProcessResult
import io.github.ezanetta.adbbuddy.testutil.FakeProcessRunner
import io.github.ezanetta.adbbuddy.testutil.ONE_EMULATOR
import io.github.ezanetta.adbbuddy.testutil.fixture
import io.github.ezanetta.adbbuddy.testutil.ok
import io.github.ezanetta.adbbuddy.testutil.text
import io.github.ezanetta.adbbuddy.testutil.withMcpClient
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

class DeviceAndLogToolsTest {
    private val runner = FakeProcessRunner()

    @Test
    fun `list_devices shows model, version and kind, and unready devices`() = withMcpClient(runner) { client ->
        runner.onDevices(
            ONE_EMULATOR + "R5CR10ABCDE\tunauthorized usb:1-1 transport_id:2\n",
        )
        runner.onArgs("'getprop'", stdout = fixture("getprop_emulator.txt"))

        val result = client.callTool("list_devices", emptyMap())

        result.isError shouldBe null
        result.text shouldContain "2 device(s):"
        result.text shouldContain "- emulator-5554 | Google sdk_gphone16k_arm64 | Android 17 (API 37) | emulator"
        result.text shouldContain "- R5CR10ABCDE | unknown model | physical | state: unauthorized"
    }

    @Test
    fun `list_devices takes no serial and is read only`() = withMcpClient(runner) { client ->
        val tool = client.listTools().tools.single { it.name == "list_devices" }
        tool.inputSchema.properties?.keys.orEmpty() shouldNotContain "serial"
        tool.annotations?.readOnlyHint shouldBe true
    }

    @Test
    fun `get_logcat filters host side over a wide window`() = withMcpClient(runner) { client ->
        runner.onDevices(ONE_EMULATOR)
        runner.on({ "'logcat'" in it }) { ok(fixture("logcat_main.txt")) }

        val result = client.callTool("get_logcat", mapOf("tag" to "OkHttp", "level" to "W"))

        result.text shouldStartWith "logcat on emulator-5554, tag=OkHttp, level>=W: 1 line(s)"
        result.text shouldContain "<-- 500 Internal Server Error"
        result.text shouldNotContain "Finsky"
        runner.calls.last() shouldContain "'20000'"
    }

    @Test
    fun `get_logcat by package uses the app pid`() = withMcpClient(runner) { client ->
        runner.onDevices(ONE_EMULATOR)
        runner.onArgs("'pidof'", "'com.example.app'", stdout = "6123\n")
        runner.on({ "'logcat'" in it }) { ok(fixture("logcat_main.txt")) }

        client.callTool("get_logcat", mapOf("package" to "com.example.app", "lines" to 50))

        runner.calls.last() shouldContain "'--pid=6123'"
        runner.calls.last() shouldContain "'50'"
    }

    @Test
    fun `get_logcat reports an app that is not running`() = withMcpClient(runner) { client ->
        runner.onDevices(ONE_EMULATOR)
        runner.onArgs("'pidof'", "'com.example.app'", exitCode = 1)

        val result = client.callTool("get_logcat", mapOf("package" to "com.example.app"))

        result.isError shouldBe true
        result.text shouldContain "com.example.app is not running"
    }

    @Test
    fun `invalid arguments become error results`() = withMcpClient(runner) { client ->
        runner.onDevices(ONE_EMULATOR)
        client.callTool("get_logcat", mapOf("lines" to 0)).text shouldContain "`lines` must be between 1 and 2000"
        client.callTool("get_logcat", mapOf("level" to "LOUD")).text shouldContain "`level` must be one of"
        client.callTool("get_crash_log", mapOf("package" to "com.x; reboot")).text shouldContain "Invalid package name"
        client.callTool("get_crash_log", emptyMap()).text shouldContain "Missing required argument `package`"
    }

    @Test
    fun `multiple devices without serial is an error that lists them`() = withMcpClient(runner) { client ->
        runner.onDevices(fixture("devices_mixed.txt"))
        val result = client.callTool("get_logcat", emptyMap())
        result.isError shouldBe true
        result.text shouldContain "emulator-5554 (device)"
    }

    @Test
    fun `get_crash_log returns newest problems first`() = withMcpClient(runner) { client ->
        runner.onDevices(ONE_EMULATOR)
        runner.on({ it.containsAll(listOf("'-b'", "'crash'")) }) { ok(fixture("logcat_crash_buffer.txt")) }
        runner.on({ it.containsAll(listOf("'-b'", "'main'")) }) { ok(fixture("logcat_system_anr.txt")) }

        val result = client.callTool("get_crash_log", mapOf("package" to "com.example.app"))
        val sections = result.text.lines().filter { it.startsWith("## ") }

        sections shouldBe listOf(
            "## ANR at 10-19 12:05:10.250",
            "## Java crash (FATAL EXCEPTION) at 10-19 11:31:02.000",
        )
    }

    @Test
    fun `get_crash_log without crashes explains why`() = withMcpClient(runner) { client ->
        runner.onDevices(ONE_EMULATOR)
        runner.on({ "'logcat'" in it }) { ok("--------- beginning of crash\n") }

        val result = client.callTool("get_crash_log", mapOf("package" to "com.example.app"))

        result.isError shouldBe null
        result.text shouldContain "No crash or ANR found"
    }

    @Test
    fun `every tool advertises the optional serial except list_devices`() = withMcpClient(runner) { client ->
        client.listTools().tools.filter { it.name != "list_devices" }.forEach { tool ->
            tool.inputSchema.properties?.keys.orEmpty() shouldContain "serial"
            tool.inputSchema.required.orEmpty() shouldNotContain "serial"
        }
    }

    @Test
    fun `log tools say when logcat output was cut`() = withMcpClient(runner) { client ->
        runner.onDevices(ONE_EMULATOR)
        runner.on({ "'logcat'" in it }) {
            ProcessResult(0, fixture("logcat_main.txt").encodeToByteArray(), "", stdoutTruncated = true)
        }

        client.callTool("get_logcat", mapOf("level" to "E")).text shouldContain "only its newest part was searched"
        client.callTool("get_crash_log", mapOf("package" to "com.example.app")).text shouldContain
            "only its newest part was searched"
    }
}
