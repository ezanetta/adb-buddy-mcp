package io.github.ezanetta.adbbuddy.adb

import io.github.ezanetta.adbbuddy.testutil.FakeProcessRunner
import io.github.ezanetta.adbbuddy.testutil.ONE_EMULATOR
import io.github.ezanetta.adbbuddy.testutil.fixture
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class AdbClientTest {
    private val runner = FakeProcessRunner()
    private val client = AdbClient("/sdk/adb", runner)

    @Test
    fun `picks the only device when serial is omitted`() = runTest {
        runner.onDevices(ONE_EMULATOR)
        client.resolveSerial(null) shouldBe "emulator-5554"
    }

    @Test
    fun `multiple devices without serial lists them`() = runTest {
        runner.onDevices(fixture("devices_mixed.txt"))
        val error = shouldThrow<AdbException.MultipleDevices> { client.resolveSerial(null) }
        error.message shouldContain "emulator-5554 (device)"
        error.message shouldContain "R5CR10ABCDE (unauthorized)"
        error.message shouldContain "Pass `serial`"
    }

    @Test
    fun `explicit serial must exist and be ready`() = runTest {
        runner.onDevices(fixture("devices_mixed.txt"))
        client.resolveSerial("2B171FDH3002VX") shouldBe "2B171FDH3002VX"
        shouldThrow<AdbException.DeviceNotFound> { client.resolveSerial("nope") }
            .message shouldContain "emulator-5554"
        shouldThrow<AdbException.DeviceNotReady> { client.resolveSerial("R5CR10ABCDE") }
            .message shouldContain "Accept the USB debugging prompt"
    }

    @Test
    fun `no devices`() = runTest {
        runner.onDevices(fixture("devices_empty.txt"))
        shouldThrow<AdbException.NoDevices> { client.resolveSerial(null) }
    }

    @Test
    fun `shell arguments are single quoted for the device shell`() = runTest {
        runner.on({ true }) { ProcessResult(0, ByteArray(0), "") }
        client.shell("emulator-5554", listOf("input", "text", "a'b; rm -rf /\$(id)"))

        runner.calls.single() shouldBe listOf(
            "-s",
            "emulator-5554",
            "shell",
            "'input'",
            "'text'",
            "'a'\\''b; rm -rf /\$(id)'",
        )
    }

    @Test
    fun `non zero exit becomes CommandFailed with stderr`() = runTest {
        runner.on({ true }) { ProcessResult(1, ByteArray(0), "Unknown package: com.x") }
        shouldThrow<AdbException.CommandFailed> { client.shell("s", listOf("pm", "clear", "com.x")) }
            .message shouldBe "`adb shell pm clear com.x` failed (exit 1): Unknown package: com.x"
    }

    @Test
    fun `timeouts are reported`() = runTest {
        runner.on({ true }) { ProcessResult(-1, ByteArray(0), "", timedOut = true) }
        shouldThrow<AdbException.Timeout> { client.shell("s", listOf("uiautomator", "dump")) }
            .message shouldContain "timed out"
    }

    @Test
    fun `exec-out passes plain tokens unquoted and rejects anything else`() = runTest {
        runner.on({ true }) { ProcessResult(0, ByteArray(0), "") }
        client.execOut("emulator-5554", listOf("screencap", "-p"))
        runner.calls.single() shouldBe listOf("-s", "emulator-5554", "exec-out", "screencap", "-p")

        shouldThrow<IllegalArgumentException> { client.execOut("emulator-5554", listOf("cat", "/sdcard/a b")) }
        shouldThrow<IllegalArgumentException> { client.execOut("emulator-5554", listOf("cat", "x;reboot")) }
    }

    @Test
    fun `truncated structured output is an error, not partial data`() = runTest {
        runner.on({ true }) { ProcessResult(0, "<hierarchy".encodeToByteArray(), "", stdoutTruncated = true) }
        shouldThrow<AdbException.OutputTooLarge> { client.shell("s", listOf("dumpsys", "activity", "activities")) }
            .message shouldContain "stopped instead of returning partial data"
        runner.caps.last() shouldBe OutputCap.KEEP_HEAD
    }

    @Test
    fun `logcat keeps the newest output and reports truncation`() = runTest {
        runner.on({ true }) { ProcessResult(0, "newest lines".encodeToByteArray(), "", stdoutTruncated = true) }
        val dump = client.logcat("s")
        dump.text shouldBe "newest lines"
        dump.truncated shouldBe true
        runner.caps.last() shouldBe OutputCap.KEEP_TAIL
    }
}
